package me.saif.betterenderchests.enderchest;

import com.google.common.collect.Sets;
import de.tr7zw.changeme.nbtapi.utils.MinecraftVersion;
import me.saif.betterenderchests.VariableEnderChests;
import me.saif.betterenderchests.data.DataManager;
import me.saif.betterenderchests.lang.MessageKey;
import me.saif.betterenderchests.utils.Callback;
import me.saif.betterenderchests.utils.CaselessString;
import me.saif.betterenderchests.utils.FoliaScheduler;
import me.saif.betterenderchests.utils.Manager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class EnderChestManager extends Manager<VariableEnderChests> implements Listener {

    private final DataManager dataManager;

    private final Map<UUID, EnderChest> uuidEnderChestMap = new ConcurrentHashMap<>();
    private final Map<CaselessString, UUID> nameUUIDMap = new ConcurrentHashMap<>();

    private final Map<UUID, Callback<EnderChest>> uuidCallbackMap = new ConcurrentHashMap<>();
    private final Map<CaselessString, Callback<EnderChest>> nameCallbackMap = new ConcurrentHashMap<>();
    private final Set<EnderChestSnapshot> snapshotsToSave = Sets.newConcurrentHashSet();
    private final AtomicBoolean savingChests = new AtomicBoolean();
    private final Object saveLock = new Object();
    private volatile boolean shuttingDown;

    //data fetched on the login thread, so that PlayerJoinEvent never has to touch the database
    private final Map<UUID, EnderChestSnapshot> preloadedSnapshots = new ConcurrentHashMap<>();
    private final Set<UUID> preloadedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> preloadingPlayers = ConcurrentHashMap.newKeySet();
    //uuids that currently have a load running, so we never queue the same load twice
    private final Set<UUID> loadingChests = ConcurrentHashMap.newKeySet();

    private final Map<UUID, Block> openFromBlocks = new HashMap<>();
    private int defaultRows;
    private final boolean convert;
    private volatile boolean playerDataUsed;

    private final Sound OPEN_SOUND;
    private final Sound CLOSE_SOUND;

    private final Set<Material> blacklist = new HashSet<>();

    public EnderChestManager(VariableEnderChests plugin) {
        super(plugin);
        this.dataManager = getPlugin().getDataManager();


        //load the correct sound depending on verison
        if (MinecraftVersion.getVersion() == MinecraftVersion.MC1_8_R3) {
            OPEN_SOUND = Sound.valueOf("CHEST_OPEN");
            CLOSE_SOUND = Sound.valueOf("CHEST_CLOSE");
        } else if (!MinecraftVersion.isNewerThan(MinecraftVersion.MC1_12_R1)) {
            OPEN_SOUND = Sound.valueOf("BLOCK_ENDERCHEST_OPEN");
            CLOSE_SOUND = Sound.valueOf("BLOCK_ENDERCHEST_CLOSE");
        } else {
            OPEN_SOUND = Sound.valueOf("BLOCK_ENDER_CHEST_OPEN");
            CLOSE_SOUND = Sound.valueOf("BLOCK_ENDER_CHEST_CLOSE");
        }

        //getting config values
        this.convert = this.getPlugin().getConfig().getBoolean("convert-current-ender-chest", true);
        this.defaultRows = this.getPlugin().getConfig().getInt("default-rows", 3);
        if (this.defaultRows > 6)
            this.defaultRows = 6;
        else if (this.defaultRows < 0)
            this.defaultRows = 0;

        for (String s : this.getPlugin().getConfig().getStringList("blacklisted-items")) {
            Material material = Material.matchMaterial(s);
            if (material == null) {
                plugin.getLogger().warning("Blacklisted item by the name of \"" + s + "\" found. This is not a valid minecraft material. Ignoring...");
                continue;
            }

            this.blacklist.add(material);
        }

        //load data for already online players eg. if plugin is reloaded.
        //the query itself runs off the server thread, otherwise a reload with a busy database
        //freezes the server for as long as the query takes.
        FoliaScheduler.runGlobal(plugin, () -> {
            Map<UUID, String> onlinePlayers = new HashMap<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                onlinePlayers.put(player.getUniqueId(), player.getName());
            }

            if (onlinePlayers.isEmpty())
                return;

            FoliaScheduler.runAsync(plugin, () -> {
                Map<UUID, EnderChestSnapshot> data;
                try {
                    Map<String, UUID> namesToSave = new HashMap<>();
                    onlinePlayers.forEach((uuid, name) -> namesToSave.put(name, uuid));
                    this.dataManager.saveNameAndUUIDs(namesToSave);

                    data = this.dataManager.loadEnderChestsByUUID(new HashSet<>(onlinePlayers.keySet()));
                } catch (Exception e) {
                    this.getPlugin().getLogger().log(Level.SEVERE, "Could not load the enderchests of the players that were already online.", e);
                    return;
                }

                if (data == null) {
                    //never fall back to empty chests here, that would overwrite real data on the next save
                    this.getPlugin().getLogger().severe("Could not load the enderchests of the players that were already online.");
                    return;
                }

                FoliaScheduler.runGlobal(plugin, () -> applyStartupData(data));
            });
        });

        FoliaScheduler.runGlobalTimer(this.getPlugin(), () -> {
            saveCachedChests();

            //Pending name callbacks are removed by their completion handlers. Removing an offline
            //player's callback here would hide an in-flight read from cache eviction checks.

            //drop preloaded data of players that never made it in game
            this.preloadedPlayers.removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
            this.preloadedSnapshots.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
        }, 6000L, 6000L);
    }

    //Keep the authoritative inventory available until its exact snapshot has committed.
    //Removing it before the async write lets a reopen load an older database copy.
    private void saveCachedChests() {
        if (shuttingDown || !savingChests.compareAndSet(false, true))
            return;
        Map<UUID, EnderChest> savedChests = new HashMap<>(uuidEnderChestMap);
        Map<UUID, EnderChestSnapshot> snapshots = new HashMap<>();
        savedChests.forEach((uuid, chest) -> snapshots.put(uuid, chest.snapshot()));
        FoliaScheduler.runAsync(getPlugin(), () -> {
            try {
                synchronized (saveLock) {
                    if (shuttingDown)
                        return;
                    dataManager.saveEnderChestMultiple(snapshots);
                }
            } catch (Exception e) {
                getPlugin().getLogger().log(Level.SEVERE, "Enderchest save failed. Keeping all cached inventories.", e);
                savingChests.set(false);
                return;
            }
            if (shuttingDown)
                return;
            FoliaScheduler.runGlobal(getPlugin(), () -> {
                try {
                    savedChests.forEach((uuid, chest) -> {
                        EnderChestSnapshot saved = snapshots.get(uuid);
                        //Pending reads might still hold an older database snapshot.
                        if (Bukkit.getPlayer(uuid) == null && !chest.hasViewers()
                                && loadingChests.isEmpty() && uuidCallbackMap.isEmpty() && nameCallbackMap.isEmpty()
                                && preloadingPlayers.isEmpty() && preloadedPlayers.isEmpty() && preloadedSnapshots.isEmpty()
                                && chest.getLastNumRows() == saved.getRows()
                                && Arrays.equals(chest.getContents(), saved.getContents()))
                            uuidEnderChestMap.remove(uuid, chest);
                    });
                } finally {
                    savingChests.set(false);
                }
            });
        });
    }

    private void applyStartupData(Map<UUID, EnderChestSnapshot> data) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.playerDataUsed = true;
            if (this.uuidEnderChestMap.containsKey(player.getUniqueId()))
                continue;

            EnderChestSnapshot snapshot = data.get(player.getUniqueId());
            if (snapshot == null) {
                this.getPlugin().getLogger().info("Creating new enderchest for " + player.getName());
                this.uuidEnderChestMap.putIfAbsent(player.getUniqueId(), createNew(player));
            } else {
                this.getPlugin().getLogger().info("Loaded enderchest for " + player.getName());
                this.uuidEnderChestMap.putIfAbsent(player.getUniqueId(),
                        new EnderChest(player.getUniqueId(), player.getName(), snapshot.getContents(), snapshot.getRows()));
            }

            this.nameUUIDMap.put(new CaselessString(player.getName()), player.getUniqueId());

            //if player has vanilla chest open, close it and open their VEC
            //if in disabled world, no need to do this

            if (this.getPlugin().getDisabledWorlds().contains(player.getWorld().getName()))
                continue;

            final Player p = player;
            FoliaScheduler.runEntity(this.getPlugin(), p, () -> {
                if (p.getOpenInventory().getTopInventory().equals(p.getEnderChest())) {
                    p.closeInventory();
                    int rows = this.getNumRows(p);
                    if (rows == 0) {
                        this.getPlugin().getMessenger().sendMessage(p, MessageKey.NO_ENDERCHEST_SELF);
                    }

                    EnderChest enderChest = this.getEnderChest(p);
                    if (enderChest != null)
                        this.openEnderChest(enderChest, p, this.getNumRows(p));
                }
            }, null);
        }
    }

    private EnderChest loadData(String name, UUID uuid) {
        EnderChestSnapshot enderChestSnapshot = this.dataManager.loadEnderChest(uuid);

        if (enderChestSnapshot == null) {
            return null;
        }

        return new EnderChest(uuid, name, enderChestSnapshot.getContents(), enderChestSnapshot.getRows());
    }

    //the login thread is already off the server thread and the player is not in game yet, so this is
    //the one place where we can safely wait for the database. doing it here means PlayerJoinEvent
    //always finds the data in memory and nothing on a server thread ever has to wait for sqlite.
    @EventHandler(priority = EventPriority.MONITOR)
    private void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED)
            return;

        UUID uuid = event.getUniqueId();
        String name = event.getName();

        this.preloadedPlayers.remove(uuid);
        this.preloadedSnapshots.remove(uuid);

        this.preloadingPlayers.add(uuid);
        try {
            this.dataManager.saveNameAndUUID(name, uuid);
            EnderChestSnapshot snapshot = this.dataManager.loadEnderChest(uuid);

            if (snapshot != null)
                this.preloadedSnapshots.put(uuid, snapshot);

            //marks the preload as done, even when the player simply has no chest yet
            this.preloadedPlayers.add(uuid);
        } catch (Exception e) {
            //do not mark it as preloaded, the join will retry the load in the background
            this.preloadedSnapshots.remove(uuid);
            this.getPlugin().getLogger().log(Level.WARNING, "Could not preload the enderchest of " + name
                    + " while they were logging in. It will be loaded in the background instead.", e);
        } finally {
            this.preloadingPlayers.remove(uuid);
        }
    }

    //a denied login never fires PlayerJoinEvent, so clean up what the preload left behind
    @EventHandler(priority = EventPriority.MONITOR)
    private void onLogin(PlayerLoginEvent event) {
        if (event.getResult() == PlayerLoginEvent.Result.ALLOWED)
            return;

        UUID uuid = event.getPlayer().getUniqueId();
        this.preloadedPlayers.remove(uuid);
        this.preloadedSnapshots.remove(uuid);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onJoin(PlayerJoinEvent event) {
        this.playerDataUsed = true;
        Player player = event.getPlayer();
        String name = player.getName();
        UUID uuid = player.getUniqueId();

        this.nameUUIDMap.put(new CaselessString(name), uuid);

        boolean preloaded = this.preloadedPlayers.remove(uuid);
        EnderChestSnapshot snapshot = this.preloadedSnapshots.remove(uuid);

        if (this.uuidEnderChestMap.containsKey(uuid)) {
            return;
        }

        //normal case: the data is already in memory, so the chest is usable the moment the player is in game
        if (preloaded) {
            if (snapshot == null) {
                this.getPlugin().getLogger().info("Creating new enderchest for " + name);
                this.uuidEnderChestMap.putIfAbsent(uuid, createNew(player));
            } else {
                this.getPlugin().getLogger().info("Loaded enderchest for " + name);
                this.uuidEnderChestMap.putIfAbsent(uuid, new EnderChest(uuid, name, snapshot.getContents(), snapshot.getRows()));
            }
            return;
        }

        //the preload did not run or failed, fall back to loading in the background
        loadInBackground(name, uuid, true);
    }

    //loads a chest off the server thread and puts it in the cache once it is done
    private void loadInBackground(String name, UUID uuid, boolean saveName) {
        if (!this.loadingChests.add(uuid))
            return;

        FoliaScheduler.runAsync(this.getPlugin(), () -> {
            EnderChest enderChest;
            try {
                if (saveName)
                    this.dataManager.saveNameAndUUID(name, uuid);

                enderChest = loadData(name, uuid);
            } catch (Exception e) {
                this.loadingChests.remove(uuid);
                //a failed load must not turn into an empty chest, that would wipe the player's items
                this.getPlugin().getLogger().log(Level.SEVERE, "Could not load the enderchest of " + name, e);
                return;
            }

            if (enderChest == null) {
                this.getPlugin().getLogger().info("Creating new enderchest for " + name);
            } else {
                this.getPlugin().getLogger().info("Loaded enderchest for " + name);
            }

            FoliaScheduler.runGlobal(getPlugin(), () -> {
                try {
                    if (this.uuidEnderChestMap.containsKey(uuid))
                        return;

                    if (enderChest != null) {
                        this.uuidEnderChestMap.putIfAbsent(uuid, enderChest);
                        this.nameUUIDMap.put(new CaselessString(name), uuid);
                        return;
                    }

                    //a brand new chest can only be created while the player is online
                    Player online = Bukkit.getPlayer(uuid);
                    if (online != null) {
                        this.uuidEnderChestMap.putIfAbsent(uuid, createNew(online));
                        this.nameUUIDMap.put(new CaselessString(name), uuid);
                    }
                } finally {
                    this.loadingChests.remove(uuid);
                }
            });
        });
    }

    @EventHandler(ignoreCancelled = true)
    private void onInventoryOpen(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK)
            return;

        Player player = event.getPlayer();

        //if world disabled, use vanilla
        if (this.getPlugin().getDisabledWorlds().contains(player.getWorld().getName()))
            return;


        if (event.getClickedBlock().getType() == Material.ENDER_CHEST) {
            event.setCancelled(true);

            int rows = this.getNumRows(player);
            if (rows == 0) {
                this.getPlugin().getMessenger().sendMessage(player, MessageKey.NO_ENDERCHEST_SELF);
            } else {
                EnderChest enderChest = this.getEnderChest(player);

                if (enderChest == null) {
                    //still loading in the background, opening the vanilla chest here would let people dupe
                    this.getPlugin().getMessenger().sendMessage(player, MessageKey.ENDERCHEST_LOADING_SELF);
                    return;
                }

                if (MinecraftVersion.isAtLeastVersion(MinecraftVersion.MC1_19_R1)) {
                    org.bukkit.block.EnderChest chestBlock = ((org.bukkit.block.EnderChest) event.getClickedBlock().getState());
                    chestBlock.open();
                    chestBlock.update();
                }

                event.getPlayer().playSound(event.getClickedBlock().getLocation(), OPEN_SOUND, 1, 1F);
                this.openEnderChest(enderChest, player, rows);
                this.openFromBlocks.put(player.getUniqueId(), event.getClickedBlock());
            }
        }
    }

    //Also catches vanilla chests opened by another plugin, or after a cancelled interact event.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    private void onVanillaOpen(InventoryOpenEvent event) {
        if (!this.getPlugin().getConfig().getBoolean("single-enderchest", true)
                || event.getInventory().getType() != InventoryType.ENDER_CHEST
                || !(event.getPlayer() instanceof Player))
            return;
        event.setCancelled(true);
        Player player = (Player) event.getPlayer();
        FoliaScheduler.runEntity(this.getPlugin(), player, () -> {
            if (!player.isOnline())
                return;
            int rows = getNumRows(player);
            if (rows == 0) {
                this.getPlugin().getMessenger().sendMessage(player, MessageKey.NO_ENDERCHEST_SELF);
                return;
            }
            openEnderChest(getEnderChest(player), player, rows);
        }, null);
    }

    public boolean canMergeVanilla() {
        return !playerDataUsed && uuidEnderChestMap.isEmpty() && loadingChests.isEmpty()
                && preloadingPlayers.isEmpty() && preloadedPlayers.isEmpty() && preloadedSnapshots.isEmpty()
                && uuidCallbackMap.isEmpty() && nameCallbackMap.isEmpty() && snapshotsToSave.isEmpty();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    private void onInventoryClose(InventoryCloseEvent event) {
        if (this.openFromBlocks.containsKey(event.getPlayer().getUniqueId())) {
            event.getViewers().remove(event.getPlayer());

            Block block = this.openFromBlocks.remove(event.getPlayer().getUniqueId());


            if (MinecraftVersion.isAtLeastVersion(MinecraftVersion.MC1_19_R1) && block.getState() instanceof org.bukkit.block.EnderChest && event.getViewers().isEmpty()) {
                org.bukkit.block.EnderChest chestBlock = ((org.bukkit.block.EnderChest) block.getState());
                chestBlock.close();
                chestBlock.update();
            }
            ((Player) event.getPlayer()).playSound(block.getLocation(), CLOSE_SOUND, 1, 1F);
        }
    }

    @EventHandler
    private void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        this.openFromBlocks.remove(uuid);
        this.preloadedPlayers.remove(uuid);
        this.preloadedSnapshots.remove(uuid);

        //this used to call getEnderChest(), which loads from the database when the chest is not cached
        //yet - for example when a player disconnects right after joining. that load runs on the server
        //thread and waits for the sqlite worker, which froze the server until the watchdog killed it.
        //a chest that was never loaded also cannot have this player as a viewer, so there is nothing to do.
        EnderChest enderChest = this.uuidEnderChestMap.get(uuid);

        //remove the player as a viewer
        if (enderChest != null)
            enderChest.getInventory().getViewers().remove(event.getPlayer());

        event.getPlayer().closeInventory();


        //keep the enderchest loaded so people can't dupe. Will reassess this bug later
        /*if (!enderChest.hasViewers()) {
            this.getPlugin().getLogger().info("Saving data for " + enderChest.getName());
            EnderChestSnapshot snapshot = enderChest.snapshot();
            this.uuidEnderChestMap.remove(snapshot.getUuid());
            this.nameUUIDMap.remove(new CaselessString(snapshot.getName()));

            //adds snapshot
            this.snapshotsToSave.add(snapshot);

            Bukkit.getScheduler().runTaskAsynchronously(this.getPlugin(), () -> {
                this.dataManager.saveEnderChest(snapshot.getUuid(), snapshot);
                //removes snapshot from savelist
                this.snapshotsToSave.remove(snapshot);
            });
        }*/

        //someone is watching the enderchest so keep it loaded
        //we will deal with this later when clearing cache periodically.
    }

    /**
     * Returns the cached enderchest of a player.
     * <p>
     * May return null while the chest is loading. All callers share the same background load;
     * a parallel synchronous read must not create a second inventory for the same player.
     */
    public EnderChest getEnderChest(Player player) {
        if (this.getPlugin().getConverterManager().isConverting())
            return null;
        this.playerDataUsed = true;
        EnderChest enderChest = this.uuidEnderChestMap.get(player.getUniqueId());

        if (enderChest != null)
            return enderChest;

        loadInBackground(player.getName(), player.getUniqueId(), false);
        return null;
    }

    //opens ender chest and reopens updated enderchest for players
    public void openEnderChest(EnderChest chest, Player player, int rows) {
        if (chest == null) {
            this.getPlugin().getMessenger().sendMessage(player, MessageKey.ENDERCHEST_LOADING_SELF);
            return;
        }
        chest.setRows(rows);
        this.openEnderChest(chest, player);
    }

    public boolean hasMergeOverflow(Player player) {
        EnderChest chest = this.uuidEnderChestMap.get(player.getUniqueId());
        return chest != null && chest.getContents().length > 54;
    }

    public void openEnderChest(EnderChest chest, Player player) {
        chest.openInventory(player);
    }

    public void openEnderChest(Player player, int rows) {
        EnderChest chest = this.getEnderChest(player);

        if (chest == null) {
            this.getPlugin().getMessenger().sendMessage(player, MessageKey.ENDERCHEST_LOADING_SELF);
            return;
        }

        chest.setRows(rows);
        this.openEnderChest(chest, player);
    }

    //returns whether there are items and if the inventory was opened.
    public boolean openRetriever(EnderChest chest, Player player, int rows) {
        chest.setRows(rows);
        chest.prepareRetrieval();
        Inventory inventory = chest.getRetriever().getInventory();

        if (!inventory.isEmpty()) {
            player.openInventory(inventory);
            return true;
        }

        return false;
    }

    public void clearEnderChest(EnderChest enderChest) {
        enderChest.clearContents();
    }

    public int getNumRows(Player player) {
        for (int i = 6; i > 0; i--) {
            if (player.hasPermission("enderchest.size." + i)) {
                return i;
            }
        }
        return defaultRows;
    }

    public void updateRows(Player player) {
        EnderChest enderChest = getEnderChest(player);

        if (enderChest == null)
            return;

        enderChest.setRows(getNumRows(player));
    }

    public Callback<EnderChest> getEnderChest(String name) {
        if (this.getPlugin().getConverterManager().isConverting())
            return Callback.withResult(null);
        this.playerDataUsed = true;
        CaselessString mcName = new CaselessString(name);
        if (this.nameUUIDMap.containsKey(mcName) && this.uuidEnderChestMap.get(this.nameUUIDMap.get(mcName)) != null)
            return Callback.withResult(this.uuidEnderChestMap.get(this.nameUUIDMap.get(mcName)));

        if (this.nameCallbackMap.containsKey(mcName))
            return nameCallbackMap.get(mcName);

        Callback<EnderChest> callback = new Callback<>();
        this.nameCallbackMap.put(mcName, callback);

        FoliaScheduler.runAsync(this.getPlugin(), () -> {
            EnderChestSnapshot snapshot = this.dataManager.loadEnderChest(name);

            EnderChest loaded = snapshot == null ? null : new EnderChest(snapshot.getUuid(), snapshot.getName(), snapshot.getContents(), snapshot.getRows());
            FoliaScheduler.runGlobal(this.getPlugin(), () -> {
                this.nameCallbackMap.remove(mcName);

                if (loaded == null) {
                    callback.setResult(null);
                    return;
                }

                //the player may have logged in while this query was running. handing out this copy
                //next to the one they are already using would mean the same items exist twice, so
                //always keep the cached chest and throw the copy away.
                EnderChest existing = this.uuidEnderChestMap.putIfAbsent(snapshot.getUuid(), loaded);
                this.nameUUIDMap.put(mcName, snapshot.getUuid());

                callback.setResult(existing == null ? loaded : existing);
            });
        });

        return callback;
    }

    public Callback<EnderChest> getEnderChest(UUID uuid) {
        if (this.getPlugin().getConverterManager().isConverting())
            return Callback.withResult(null);
        this.playerDataUsed = true;
        if (this.uuidEnderChestMap.get(uuid) != null)
            return Callback.withResult(this.uuidEnderChestMap.get(uuid));

        if (this.uuidCallbackMap.containsKey(uuid))
            return uuidCallbackMap.get(uuid);

        Callback<EnderChest> callback = new Callback<>();
        this.uuidCallbackMap.put(uuid, callback);

        FoliaScheduler.runAsync(this.getPlugin(), () -> {
            EnderChestSnapshot snapshot = this.dataManager.loadEnderChest(uuid);

            EnderChest loaded = snapshot == null ? null : new EnderChest(snapshot.getUuid(), snapshot.getName(), snapshot.getContents(), snapshot.getRows());
            FoliaScheduler.runGlobal(this.getPlugin(), () -> {
                this.uuidCallbackMap.remove(uuid);

                //uuidEnderChestMap is a ConcurrentHashMap, putting a null here threw a NullPointerException
                if (loaded == null) {
                    callback.setResult(null);
                    return;
                }

                //same as above: never hand out a second copy of a chest that is already in use
                EnderChest existing = this.uuidEnderChestMap.putIfAbsent(uuid, loaded);

                callback.setResult(existing == null ? loaded : existing);
            });
        });

        return callback;
    }

    public void deleteEnderChest(UUID uuid) {
        EnderChest enderChest = this.uuidEnderChestMap.get(uuid);
        Player player = Bukkit.getPlayer(uuid);
        if (enderChest != null) {
            enderChest.clearContents();

            if (player != null) {
                if (this.convert)
                    enderChest.setContents(player.getEnderChest().getContents());
                return;
            }

            //unload enderchest without saving
            this.uuidEnderChestMap.remove(enderChest.getUUID());
        }
        this.dataManager.deleteEnderChest(uuid);
    }

    public void deleteEnderChest(String name) {
        CaselessString caselessString = new CaselessString(name);
        EnderChest enderChest = this.uuidEnderChestMap.get(this.nameUUIDMap.get(caselessString));
        Player player = Bukkit.getPlayerExact(name);
        if (enderChest != null) {
            enderChest.clearContents();

            if (player != null) {
                if (this.convert)
                    enderChest.setContents(player.getEnderChest().getContents());
                return;
            }

            //unload enderchest without saving
            this.uuidEnderChestMap.remove(enderChest.getUUID());
        }
        this.dataManager.deleteEnderChest(name);
    }

    //saves all data
    public void finishUp() {
        this.shuttingDown = true;
        for (EnderChest value : this.uuidEnderChestMap.values()) {
            List<HumanEntity> list = new ArrayList<>(value.getInventory().getViewers());
            for (HumanEntity humanEntity : list) {
                humanEntity.closeInventory();
            }
        }
        Map<UUID, EnderChestSnapshot> enderChestSnapshotMap = new HashMap<>();
        for (EnderChest value : this.uuidEnderChestMap.values()) {
            enderChestSnapshotMap.put(value.getUUID(), value.snapshot());

            this.getPlugin().getLogger().info("Saving data for " + value.getName());
        }
        synchronized (saveLock) {
            this.dataManager.saveEnderChestMultiple(enderChestSnapshotMap);
        }

        //saves the snapshots that have not been saved
        for (EnderChestSnapshot enderChestSnapshot : this.snapshotsToSave) {
            this.dataManager.saveEnderChest(enderChestSnapshot.getUuid(), enderChestSnapshot);
        }

        this.snapshotsToSave.clear();

        this.uuidCallbackMap.clear();
        this.nameCallbackMap.clear();
        this.uuidEnderChestMap.clear();
        this.nameUUIDMap.clear();

        enderChestSnapshotMap.clear();
        nameUUIDMap.clear();
    }

    private EnderChest createNew(Player player) {
        return new EnderChest(player.getUniqueId(), player.getName(), this.convert ? player.getEnderChest().getContents() : new ItemStack[]{});
    }

    public Set<Material> getBlacklist() {
        return blacklist;
    }
}
