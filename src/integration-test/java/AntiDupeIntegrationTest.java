package integration;

import me.saif.betterenderchests.VariableEnderChests;
import me.saif.betterenderchests.data.DataManager;
import me.saif.betterenderchests.enderchest.*;
import me.saif.betterenderchests.utils.Callback;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Synthetic regressions only; never install this test helper on a real server. */
public final class AntiDupeIntegrationTest extends JavaPlugin {
    private VariableEnderChests vec;
    private EnderChestManager manager;
    private DataManager original;
    private Field dataField;
    private Map<UUID, EnderChest> cache;
    private Method save;
    private AtomicBoolean saving;
    private int checks;
    private final UUID saveId = new UUID(0, 2000);
    private final Map<UUID, EnderChestSnapshot> stored = new ConcurrentHashMap<>();
    private volatile CountDownLatch saveStarted = new CountDownLatch(1), saveGate = new CountDownLatch(1);
    private volatile CountDownLatch readStarted = new CountDownLatch(1), readGate = new CountDownLatch(0);
    private volatile UUID delayedId;
    private volatile boolean failSave;
    private EnderChest live;
    private final List<org.bukkit.entity.HumanEntity> chestViewers = new ArrayList<>(), retrievalViewers = new ArrayList<>();

    public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                require(Files.exists(Path.of("ALLOW_SYNTHETIC_VEC_TESTS"))
                        && Bukkit.getWorlds().get(0).getName().equals("test-world") && Bukkit.getOnlinePlayers().isEmpty(), "isolated server marker");
                vec = (VariableEnderChests) Bukkit.getPluginManager().getPlugin("VariableEnderChests");
                manager = vec.getEnderChestManager();
                original = vec.getDataManager();
                dataField = field(EnderChestManager.class, "dataManager");
                cache = (Map<UUID, EnderChest>) field(EnderChestManager.class, "uuidEnderChestMap").get(manager);
                saving = (AtomicBoolean) field(EnderChestManager.class, "savingChests").get(manager);
                save = EnderChestManager.class.getDeclaredMethod("saveCachedChests");
                save.setAccessible(true);
                DataManager controlled = (DataManager) Proxy.newProxyInstance(getClassLoader(), new Class<?>[]{DataManager.class}, (p, m, a) -> {
                    if (m.getName().equals("saveEnderChestMultiple")) {
                        saveStarted.countDown();
                        if (!saveGate.await(8, TimeUnit.SECONDS)) throw new AssertionError("save gate timeout");
                        if (failSave) throw new IllegalStateException("deliberately injected save failure");
                        stored.putAll((Map<UUID, EnderChestSnapshot>) a[0]);
                        return null;
                    }
                    if (m.getName().equals("loadEnderChest")) {
                        UUID id = a[0] instanceof UUID ? (UUID) a[0] : delayedId;
                        EnderChestSnapshot snapshot = stored.get(id);
                        if (id != null && id.equals(delayedId)) {
                            readStarted.countDown();
                            if (!readGate.await(8, TimeUnit.SECONDS)) throw new AssertionError("read gate timeout");
                        }
                        return snapshot;
                    }
                    if (m.getName().equals("saveNameAndUUID")) return null;
                    try { return m.invoke(original, a); } catch (InvocationTargetException e) { throw e.getCause(); }
                });
                dataField.set(manager, controlled);
                live = chest(saveId, 64);
                Inventory main = live.getInventory(), retrieval = live.getRetriever().getInventory();
                field(EnderChest.class, "inventory").set(live, viewers(main, chestViewers));
                field(EnderChest.class, "retrievalInventory").set(live, viewers(retrieval, retrievalViewers));
                Player viewer = player(saveId);
                require(!live.hasViewers(), "both empty inventories have no viewers");
                chestViewers.add(viewer);
                require(live.hasViewers(), "chest viewer keeps chest alive");
                retrievalViewers.add(viewer);
                require(live.hasViewers(), "both inventories viewed keeps chest alive");
                chestViewers.clear();
                require(live.hasViewers(), "retrieval viewer keeps chest alive");
                retrievalViewers.clear();
                cache.put(saveId, live);
                save.invoke(manager);
                until(() -> saveStarted.getCount() == 0, () -> {
                    require(cache.get(saveId) == live, "cache stays authoritative during a blocked write");
                    require(manager.getEnderChest(saveId).getResult() == live, "reopen during save shares original inventory");
                    live.getInventory().getItem(0).setAmount(32);
                    saveGate.countDown();
                    until(() -> !saving.get(), this::afterDirtySave);
                });
            } catch (Throwable e) { fail(e); }
        }, 30L);
    }

    private void afterDirtySave() throws Exception {
        require(cache.get(saveId) == live, "edited chest is retained after older snapshot commits");
        require(stored.get(saveId).get(0).getAmount() == 64, "snapshot does not change with live inventory");
        ItemStack[] copy = stored.get(saveId).getContents();
        copy[0].setAmount(1);
        require(stored.get(saveId).get(0).getAmount() == 64, "snapshot contents are defensive copies");
        Player viewer = player(saveId);
        chestViewers.add(viewer); retrievalViewers.add(viewer);
        save.invoke(manager);
        until(() -> !saving.get(), () -> {
            require(cache.get(saveId) == live, "both viewed inventories survive periodic save");
            chestViewers.clear(); retrievalViewers.clear();
            failSave = true;
            save.invoke(manager);
            until(() -> !saving.get(), () -> {
                require(cache.get(saveId) == live, "failed database write cannot evict chest");
                failSave = false;
                save.invoke(manager);
                until(() -> !saving.get(), () -> {
                    require(!cache.containsKey(saveId), "unchanged idle chest is evicted only after commit");
                    require(stored.get(saveId).get(0).getAmount() == 32, "committed database snapshot is current");
                    Callback<EnderChest> reopened = manager.getEnderChest(saveId);
                    until(reopened::hasResults, () -> {
                        require(reopened.getResult().getInventory().getItem(0).getAmount() == 32, "reopen after eviction cannot resurrect older 64-stack");
                        cache.remove(saveId);
                        race(0);
                    });
                });
            });
        });
    }

    private void race(int variant) throws Exception {
        UUID id = new UUID(0, 2010 + variant);
        delayedId = id;
        ItemStack[] seed = new ItemStack[54]; seed[0] = new ItemStack(Material.DIAMOND, 64);
        stored.put(id, new EnderChestSnapshot(id, "RaceTest", seed, 3));
        readStarted = new CountDownLatch(1); readGate = new CountDownLatch(1);
        Callback<EnderChest> callback;
        if (variant == 2) {
            Method join = EnderChestManager.class.getDeclaredMethod("onJoin", PlayerJoinEvent.class);
            join.setAccessible(true);
            join.invoke(manager, new PlayerJoinEvent(player(id), (String) null));
            callback = null;
        } else callback = variant == 0 ? manager.getEnderChest(id) : manager.getEnderChest("RaceTest");
        until(() -> readStarted.getCount() == 0, () -> {
            require(manager.getEnderChest(player(id)) == null, "uncached server-thread access waits for background data");
            CompletableFuture<EnderChest> asyncAccess = CompletableFuture.supplyAsync(() -> manager.getEnderChest(player(id)));
            until(asyncAccess::isDone, () -> {
            require(asyncAccess.join() == null, "async API caller shares the pending load instead of reading synchronously");
            EnderChest authoritative = chest(id, 64);
            cache.put(id, authoritative);
            int withdrawn = authoritative.getInventory().getItem(0).getAmount();
            authoritative.getInventory().clear();
            readGate.countDown();
            until(() -> (callback == null || callback.hasResults()) && !((Set<?>) getField("loadingChests")).contains(id), () -> {
                require(cache.get(id) == authoritative, "delayed load never replaces used chest, variant " + variant);
                if (callback != null) require(callback.getResult() == authoritative, "callback returns authoritative chest");
                require(withdrawn == 64 && authoritative.getInventory().isEmpty(), "64 items remain 64 after delayed read, variant " + variant);
                cache.remove(id);
                if (variant < 2) race(variant + 1);
                else shutdownSaveTest();
            });
            });
        });
    }

    private void shutdownSaveTest() throws Exception {
        delayedId = null;
        EnderChest current = chest(saveId, 64);
        cache.put(saveId, current);
        saveStarted = new CountDownLatch(1);
        saveGate = new CountDownLatch(1);
        save.invoke(manager);
        until(() -> saveStarted.getCount() == 0, () -> {
            current.getInventory().getItem(0).setAmount(16);
            CompletableFuture<Void> releaseOnShutdown = CompletableFuture.runAsync(() -> {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!(Boolean) getField("shuttingDown") && System.nanoTime() < deadline)
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                saveGate.countDown();
            });
            manager.finishUp();
            releaseOnShutdown.join();
            require(stored.get(saveId).get(0).getAmount() == 16, "shutdown save wins over an older in-flight autosave");
            dataField.set(manager, original);
            getLogger().info("ANTI-DUPE PASS: " + checks + " assertions; slow reads/writes, viewed caches, failed writes, immutable snapshots, shutdown save order");
            Bukkit.shutdown();
        });
    }

    private Object getField(String name) { try { return field(EnderChestManager.class, name).get(manager); } catch (Exception e) { throw new RuntimeException(e); } }
    private static Field field(Class<?> type, String name) throws Exception { Field f = type.getDeclaredField(name); f.setAccessible(true); return f; }
    private EnderChest chest(UUID id, int amount) throws Exception {
        Constructor<EnderChest> c = EnderChest.class.getDeclaredConstructor(UUID.class, String.class, ItemStack[].class, int.class);
        c.setAccessible(true);
        ItemStack[] contents = new ItemStack[54]; contents[0] = new ItemStack(Material.DIAMOND, amount);
        return c.newInstance(id, "RaceTest", contents, 3);
    }
    private Inventory viewers(Inventory delegate, List<org.bukkit.entity.HumanEntity> list) {
        return (Inventory) Proxy.newProxyInstance(getClassLoader(), new Class<?>[]{Inventory.class}, (p, m, a) -> {
            if (m.getName().equals("getViewers")) return list;
            try { return m.invoke(delegate, a); } catch (InvocationTargetException e) { throw e.getCause(); }
        });
    }
    private Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(getClassLoader(), new Class<?>[]{Player.class}, (p, m, a) -> {
            switch (m.getName()) {
                case "getUniqueId": return id;
                case "getName": return "RaceTest";
                case "getWorld": return Bukkit.getWorlds().get(0);
                case "hashCode": return System.identityHashCode(p);
                case "equals": return p == a[0];
                case "isOnline": return true;
                default: if (m.getReturnType() == boolean.class) return false; if (m.getReturnType() == int.class) return 0; return null;
            }
        });
    }
    private interface Step { void run() throws Exception; }
    private void until(BooleanSupplier ready, Step step) {
        new BukkitRunnable() { int ticks; public void run() {
            try { if (++ticks > 120) throw new AssertionError("async step timeout"); if (!ready.getAsBoolean()) return; cancel(); step.run(); }
            catch (Throwable e) { cancel(); fail(e); }
        }}.runTaskTimer(this, 1L, 1L);
    }
    private void require(boolean condition, String label) { if (!condition) throw new AssertionError(label); checks++; }
    private void fail(Throwable e) {
        saveGate.countDown(); readGate.countDown();
        getLogger().log(java.util.logging.Level.SEVERE, "ANTI-DUPE FAILED after " + checks + " assertions", e);
        try { if (dataField != null) dataField.set(manager, original); } catch (Exception ignored) { }
        Bukkit.getScheduler().runTask(this, Bukkit::shutdown);
    }
}
