package integration;

import me.saif.betterenderchests.VariableEnderChests;
import me.saif.betterenderchests.command.commands.EnderChestCommand;
import me.saif.betterenderchests.converters.VanillaMergeConverter;
import me.saif.betterenderchests.enderchest.*;
import me.saif.betterenderchests.nbtapi.*;
import me.saif.betterenderchests.nbtapi.utils.DataFixerUtil;
import me.saif.betterenderchests.utils.ItemStackSerializer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.*;
import java.net.InetAddress;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Run only on an isolated, disposable Paper server. It creates synthetic player data. */
public final class MoveIntegrationTest extends JavaPlugin {
    private VariableEnderChests vec;
    private Path playerdata;
    private final UUID full = id(1);
    private ItemStack[] expected;
    private int checks;

    public void onEnable() {
        vec = (VariableEnderChests) Bukkit.getPluginManager().getPlugin("VariableEnderChests");
        Bukkit.getScheduler().runTaskLater(this, () -> {
            playerdata = Bukkit.getWorlds().get(0).getWorldFolder().toPath().resolve("playerdata");
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    storageTests();
                    Bukkit.getScheduler().runTask(this, () -> {
                        try { inventoryTests(); } catch (Throwable e) { fail(e); }
                    });
                } catch (Throwable e) { fail(e); }
            });
        }, 20L);
    }

    private void storageTests() throws Exception {
        require(Bukkit.getWorlds().get(0).getName().equals("test-world")
                && Files.exists(Path.of("ALLOW_SYNTHETIC_VEC_TESTS")) && Bukkit.getOnlinePlayers().isEmpty(), "disposable test server marker is required");
        Files.createDirectories(playerdata);
        try (Connection c = vec.getSQLDatabase().getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DROP TRIGGER IF EXISTS reject_move");
            for (int i = 1; i <= 6; i++) {
                Files.deleteIfExists(file(id(i)));
                s.executeUpdate("DELETE FROM enderchests WHERE UUID='" + id(i) + "'");
                //The journal is created by the converter; it may not exist on the first run.
                try { s.executeUpdate("DELETE FROM vec_vanilla_moves WHERE UUID='" + id(i) + "'"); } catch (SQLException ignored) { }
            }
        }
        ItemStack[] old = new ItemStack[54];
        Arrays.setAll(old, i -> named(Material.DIAMOND, 64, "existing-" + i));
        ItemStack[] vanilla = new ItemStack[27];
        Arrays.setAll(vanilla, i -> named(Material.EMERALD, 64, "vanilla-" + i));
        ItemStack box = new ItemStack(Material.SHULKER_BOX);
        BlockStateMeta meta = (BlockStateMeta) box.getItemMeta();
        ShulkerBox state = (ShulkerBox) meta.getBlockState();
        state.getInventory().setItem(0, named(Material.NETHER_STAR, 13, "nested item"));
        meta.setBlockState(state);
        box.setItemMeta(meta);
        vanilla[26] = box;
        put(full, old);
        source(full, vanilla);
        source(id(2), new ItemStack[]{named(Material.DIAMOND, 17, "new player")});
        ItemStack[] sparse = new ItemStack[54];
        sparse[50] = named(Material.EMERALD, 11, "keep original slot");
        put(id(3), sparse);
        source(id(3), new ItemStack[]{named(Material.DIAMOND, 9, "incoming")});
        require(new VanillaMergeConverter(vec).convert(), "migration succeeds");
        expected = load(full);
        require(expected.length == 81, "81 stacks survive two full chests");
        for (int i = 0; i < 54; i++) require(old[i].equals(expected[i]), "existing metadata/slot " + i);
        for (int i = 0; i < 27; i++) require(vanilla[i].equals(expected[54 + i]), "incoming metadata/shulker " + i);
        require(new NBTFile(file(full).toFile()).getCompoundList("EnderItems").isEmpty(), "vanilla emptied");
        require(new NBTFile(file(full).toFile()).getString("UnrelatedSentinel").equals("keep me"), "unrelated NBT preserved");
        require(load(id(2))[0].getAmount() == 17, "player with no plugin chest moved");
        require(load(id(3))[50].getAmount() == 11 && load(id(3))[0].getAmount() == 9, "sparse merge preserves existing slots");
        require(new VanillaMergeConverter(vec).convert(), "repeat succeeds");
        require(equal(expected, load(full)), "repeat does not duplicate items");

        //Simulate a crash after committing the merged chest, before clearing playerdata.
        UUID interrupted = id(4);
        ItemStack[] alreadyMerged = new ItemStack[54];
        alreadyMerged[0] = named(Material.EMERALD, 5, "old");
        alreadyMerged[1] = named(Material.DIAMOND, 9, "moved");
        put(interrupted, alreadyMerged);
        source(interrupted, new ItemStack[]{alreadyMerged[1]});
        try (Connection c = vec.getSQLDatabase().getConnection(); PreparedStatement s = c.prepareStatement("INSERT INTO vec_vanilla_moves (UUID,SOURCE) VALUES (?,?)")) {
            s.setString(1, interrupted.toString());
            s.setString(2, new NBTFile(file(interrupted).toFile()).getCompoundList("EnderItems").toString());
            s.executeUpdate();
        }
        require(new VanillaMergeConverter(vec).convert(), "interrupted transaction resumes");
        require(equal(alreadyMerged, load(interrupted)), "resume does not re-add items");
        require(new NBTFile(file(interrupted).toFile()).getCompoundList("EnderItems").isEmpty(), "resume clears source");

        UUID rejected = id(5);
        put(rejected, alreadyMerged);
        source(rejected, new ItemStack[]{named(Material.NETHERITE_INGOT, 7, "must not disappear")});
        try (Connection c = vec.getSQLDatabase().getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TRIGGER reject_move BEFORE INSERT ON vec_vanilla_moves WHEN NEW.UUID='" + rejected + "' BEGIN SELECT RAISE(ABORT, 'injected failure'); END");
        }
        require(!new VanillaMergeConverter(vec).convert(), "DB failure is reported");
        require(equal(alreadyMerged, load(rejected)), "failed transaction rolls back plugin changes");
        require(new NBTFile(file(rejected).toFile()).getCompoundList("EnderItems").size() == 1, "failed transaction leaves source intact");
        try (Connection c = vec.getSQLDatabase().getConnection(); Statement s = c.createStatement()) { s.executeUpdate("DROP TRIGGER reject_move"); }
        require(new VanillaMergeConverter(vec).convert(), "retry after DB failure succeeds");
        require(load(rejected)[2].getAmount() == 7, "retry moves all remaining items");

        UUID corrupt = id(6);
        NBTFile bad = new NBTFile(file(corrupt).toFile());
        bad.setInteger("DataVersion", DataFixerUtil.getCurrentVersion());
        NBTListCompound invalid = bad.getCompoundList("EnderItems").addCompound();
        invalid.setString("id", "minecraft:no_such_item");
        invalid.setInteger("count", 1);
        bad.save();
        require(!new VanillaMergeConverter(vec).convert(), "invalid item aborts migration");
        require(new NBTFile(file(corrupt).toFile()).getCompoundList("EnderItems").size() == 1, "invalid source is not cleared");
        Files.delete(file(corrupt));
    }

    private void inventoryTests() throws Exception {
        Constructor<EnderChest> ctor = EnderChest.class.getDeclaredConstructor(UUID.class, String.class, ItemStack[].class, int.class);
        ctor.setAccessible(true);
        EnderChest chest = ctor.newInstance(full, "MoveTest", expected, 6);
        require(chest.getRetriever().getInventory().getSize() == 27, "six-row chest exposes overflow retrieval");
        require(equal(expected, chest.snapshot().getContents()), "81 slots survive in-memory load and snapshot");
        chest.setRows(1);
        require(chest.getRetriever().getInventory().getSize() == 54, "retrieval respects maximum GUI size");
        int retrieved = occupied(chest.getRetriever().getInventory().getContents());
        chest.getRetriever().getInventory().clear();
        chest.prepareRetrieval();
        retrieved += occupied(chest.getRetriever().getInventory().getContents());
        require(retrieved == 72, "all 72 hidden stacks can be retrieved over two opens");
        chest.getRetriever().getInventory().clear();
        chest.prepareRetrieval();
        require(chest.getRetriever().getInventory().isEmpty(), "retrieved stacks do not reappear");
        chest = ctor.newInstance(full, "MoveTest", expected, 6);

        Field cacheField = vec.getEnderChestManager().getClass().getDeclaredField("uuidEnderChestMap");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked") Map<UUID,EnderChest> cache = (Map<UUID,EnderChest>) cacheField.get(vec.getEnderChestManager());
        cache.put(full, chest);
        Inventory empty = Bukkit.createInventory(null, 9);
        Inventory[] opened = new Inventory[1];
        Player[] playerRef = new Player[1];
        InventoryView view = view(empty, playerRef);
        Player fake = (Player) Proxy.newProxyInstance(getClassLoader(), new Class<?>[]{Player.class}, (p,m,a) -> {
            switch (m.getName()) {
                case "getUniqueId": return full;
                case "getName": return "MoveTest";
                case "getWorld": return Bukkit.getWorlds().get(0);
                case "getOpenInventory": return view;
                case "hasPermission": case "isOnline": return true;
                case "openInventory": opened[0] = (Inventory) a[0]; return view;
                case "hashCode": return System.identityHashCode(p);
                case "equals": return p == a[0];
                case "toString": return "SyntheticMoveTestPlayer";
                default: return zero(m.getReturnType());
            }
        });
        playerRef[0] = fake;
        new EnderChestCommand(vec, "test", List.of()).onCommand(fake, "test", new String[0]);
        require(opened[0] == chest.getInventory(), "command opens plugin chest");
        opened[0] = null;
        Block block = Bukkit.getWorlds().get(0).getBlockAt(0, 80, 0);
        block.setType(Material.ENDER_CHEST);
        PlayerInteractEvent interact = new PlayerInteractEvent(fake, Action.RIGHT_CLICK_BLOCK, null, block, org.bukkit.block.BlockFace.UP, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(interact);
        require(interact.isCancelled() && opened[0] == chest.getInventory(), "block opens same plugin chest");
        Inventory nativeChest = Bukkit.createInventory(null, InventoryType.ENDER_CHEST);
        InventoryOpenEvent nativeOpen = new InventoryOpenEvent(view(nativeChest, playerRef));
        opened[0] = null;
        Bukkit.getPluginManager().callEvent(nativeOpen);
        require(nativeOpen.isCancelled(), "vanilla open from another plugin is intercepted");
        EnderChest finalChest = chest;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                require(opened[0] == finalChest.getInventory(), "intercepted vanilla open redirects to same plugin chest");
                vec.getConverterManager().requireRestart();
                PlayerLoginEvent login = new PlayerLoginEvent(fake, "localhost", InetAddress.getLoopbackAddress());
                Bukkit.getPluginManager().callEvent(login);
                require(login.getResult() != PlayerLoginEvent.Result.ALLOWED, "login blocked while restart is required");
                require(vec.getEnderChestManager().getEnderChest(fake) == null, "API cannot expose a chest during migration lock");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "clearenderchest MoveTest");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "clearenderchest MoveTest");
                require(equal(expected, finalChest.getContents()), "commands cannot modify data during migration lock");
                getLogger().info("INTEGRATION PASS: " + checks + " assertions");
                Bukkit.shutdown();
            } catch (Throwable e) { fail(e); }
        }, 3L);
    }

    private InventoryView view(Inventory top, Player[] player) {
        return (InventoryView) Proxy.newProxyInstance(getClassLoader(), new Class<?>[]{InventoryView.class}, (p,m,a) -> {
            switch (m.getName()) {
                case "getTopInventory": case "getBottomInventory": return top;
                case "getPlayer": return player[0];
                case "getType": return top.getType();
                case "getTitle": case "getOriginalTitle": return "test";
                case "hashCode": return System.identityHashCode(p);
                case "equals": return p == a[0];
                default: return zero(m.getReturnType());
            }
        });
    }

    private static Object zero(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }
    private static UUID id(int n) { return new UUID(0, n); }
    private Path file(UUID uuid) { return playerdata.resolve(uuid + ".dat"); }
    private static ItemStack named(Material type, int amount, String name) {
        ItemStack item = new ItemStack(type, amount);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(List.of("Custom lore", "preserve this"));
        item.setItemMeta(meta);
        return item;
    }
    private void source(UUID uuid, ItemStack[] items) throws Exception {
        NBTFile data = new NBTFile(file(uuid).toFile());
        data.setInteger("DataVersion", DataFixerUtil.getCurrentVersion());
        data.setString("UnrelatedSentinel", "keep me");
        NBTCompoundList list = data.getCompoundList("EnderItems");
        for (int i = 0; i < items.length; i++) {
            NBTListCompound entry = list.addCompound();
            entry.mergeCompound(NBT.itemStackToNBT(items[i]));
            entry.setByte("Slot", (byte) i);
        }
        data.save();
    }
    private void put(UUID uuid, ItemStack[] contents) throws Exception {
        try (Connection c = vec.getSQLDatabase().getConnection(); PreparedStatement s = c.prepareStatement("REPLACE INTO enderchests (UUID,`ROWS`,CONTENTS) VALUES (?,6,?)")) {
            s.setString(1, uuid.toString());
            s.setString(2, ItemStackSerializer.serialize(contents));
            s.executeUpdate();
        }
    }
    private ItemStack[] load(UUID uuid) { return vec.getDataManager().loadEnderChest(uuid).getContents(); }
    private static int occupied(ItemStack[] items) { return (int) Arrays.stream(items).filter(i -> i != null && !i.getType().isAir()).count(); }
    private static boolean equal(ItemStack[] a, ItemStack[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            boolean ea = a[i] == null || a[i].getType().isAir();
            boolean eb = b[i] == null || b[i].getType().isAir();
            if (ea != eb || (!ea && !a[i].equals(b[i]))) return false;
        }
        return true;
    }
    private void require(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
    private void fail(Throwable e) {
        getLogger().log(java.util.logging.Level.SEVERE, "INTEGRATION FAILED after " + checks + " assertions", e);
        Bukkit.getScheduler().runTask(this, Bukkit::shutdown);
    }
}
