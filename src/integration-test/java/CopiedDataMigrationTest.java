package integration;

import me.saif.betterenderchests.VariableEnderChests;
import me.saif.betterenderchests.converters.VanillaMergeConverter;
import me.saif.betterenderchests.enderchest.EnderChestSnapshot;
import me.saif.betterenderchests.nbtapi.*;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;

/** Runs ONLY against disposable, explicitly marked copies; no fixtures are bundled. */
public final class CopiedDataMigrationTest extends JavaPlugin {
    private VariableEnderChests vec;
    private int checks;

    public void onEnable() {
        vec = (VariableEnderChests) Bukkit.getPluginManager().getPlugin("VariableEnderChests");
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!Files.exists(Path.of("ALLOW_COPIED_VEC_DATA_TEST"))
                    || !Bukkit.getWorlds().get(0).getName().equals("copy-world")
                    || !Bukkit.getOnlinePlayers().isEmpty() || !Bukkit.getIp().equals("127.0.0.1")) {
                getLogger().severe("Refusing to run without disposable copy-world, local-only bind and explicit marker.");
                Bukkit.shutdown();
                return;
            }
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try { verifyCopies(); }
                catch (Throwable e) { getLogger().log(java.util.logging.Level.SEVERE, "COPIED-DATA FAILED after " + checks + " checks", e); }
                finally { Bukkit.getScheduler().runTask(this, Bukkit::shutdown); }
            });
        }, 30L);
    }

    private void verifyCopies() throws Exception {
        Path folder = Path.of("copy-world", "players", "data");
        require(Files.isDirectory(folder), "Minecraft 26 player folder exists");
        Set<UUID> beforeIds = vec.getDataManager().getAllEnderChests();
        Map<UUID, EnderChestSnapshot> before = vec.getDataManager().loadEnderChestsByUUID(beforeIds);
        Map<UUID, List<ItemStack>> incoming = new HashMap<>();
        Map<UUID, String> unrelated = new HashMap<>();
        Map<Path, byte[]> previousFiles = new HashMap<>();
        List<Path> currentFiles = new ArrayList<>();
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String name = file.getFileName().toString();
                if (name.endsWith(".dat_old")) {
                    previousFiles.put(file, sha(file));
                    continue;
                }
                if (!name.endsWith(".dat")) continue;
                UUID uuid = UUID.fromString(name.substring(0, name.length() - 4));
                currentFiles.add(file);
                NBTFile nbt = new NBTFile(file.toFile());
                List<ItemStack> items = new ArrayList<>();
                NBTCompoundList entries = nbt.getCompoundList("EnderItems");
                for (int index = 0; index < entries.size(); index++) {
                    NBTListCompound entry = entries.get(index);
                    ItemStack item = NBT.itemStackFromNBT(entry);
                    require(item != null && !item.getType().isAir(), "source item decodes");
                    items.add(item);
                }
                incoming.put(uuid, items);
                nbt.removeKey("EnderItems");
                unrelated.put(uuid, nbt.toString());
            }
        }
        int oldStacks = before.values().stream().mapToInt(s -> stacks(Arrays.asList(s.getContents()))).sum();
        int incomingStacks = incoming.values().stream().mapToInt(CopiedDataMigrationTest::stacks).sum();
        int nonemptySources = (int) incoming.values().stream().filter(x -> !x.isEmpty()).count();
        require(new VanillaMergeConverter(vec).convert(), "copied dataset migration succeeds");
        Set<UUID> afterIds = vec.getDataManager().getAllEnderChests();
        Map<UUID, EnderChestSnapshot> after = vec.getDataManager().loadEnderChestsByUUID(afterIds);
        Set<UUID> all = new HashSet<>(beforeIds);
        all.addAll(incoming.keySet());
        for (UUID uuid : all) {
            List<ItemStack> expected = new ArrayList<>();
            EnderChestSnapshot prior = before.get(uuid), result = after.get(uuid);
            if (prior != null) expected.addAll(Arrays.asList(prior.getContents()));
            expected.addAll(incoming.getOrDefault(uuid, List.of()));
            List<ItemStack> actual = result == null ? List.of() : Arrays.asList(result.getContents());
            require(multiset(expected).equals(multiset(actual)), "all item quantities and metadata survive for " + uuid);
            require(stacks(expected) == stacks(actual), "all stacks survive for " + uuid);
            if (prior != null) require(result != null && result.getRows() == prior.getRows(), "existing row count survives");
        }
        for (Path file : currentFiles) {
            UUID uuid = UUID.fromString(file.getFileName().toString().substring(0, 36));
            NBTFile nbt = new NBTFile(file.toFile());
            require(nbt.getCompoundList("EnderItems").isEmpty(), "vanilla chest is empty after successful move");
            nbt.removeKey("EnderItems");
            require(nbt.toString().equals(unrelated.get(uuid)), "all unrelated player NBT is unchanged");
        }
        for (Map.Entry<Path, byte[]> old : previousFiles.entrySet())
            require(Arrays.equals(old.getValue(), sha(old.getKey())), "dat_old backup remains untouched");
        require(new VanillaMergeConverter(vec).convert(), "repeat migration succeeds without source items");
        Map<UUID, EnderChestSnapshot> repeated = vec.getDataManager().loadEnderChestsByUUID(vec.getDataManager().getAllEnderChests());
        require(repeated.keySet().equals(after.keySet()), "repeat does not add inventories");
        for (UUID uuid : after.keySet())
            require(multiset(Arrays.asList(after.get(uuid).getContents())).equals(multiset(Arrays.asList(repeated.get(uuid).getContents()))),
                    "repeat cannot duplicate items");
        int finalStacks = after.values().stream().mapToInt(s -> stacks(Arrays.asList(s.getContents()))).sum();
        require(finalStacks == oldStacks + incomingStacks, "global stack count is conserved");
        getLogger().info("COPIED-DATA PASS: " + checks + " checks; player files=" + currentFiles.size()
                + ", previous files=" + previousFiles.size() + ", nonempty vanilla=" + nonemptySources
                + ", old plugin stacks=" + oldStacks + ", vanilla stacks=" + incomingStacks + ", merged stacks=" + finalStacks);
    }

    private static Map<ItemStack, Long> multiset(List<ItemStack> items) {
        Map<ItemStack, Long> result = new HashMap<>();
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir() || item.getAmount() <= 0) continue;
            ItemStack identity = item.clone();
            identity.setAmount(1);
            result.merge(identity, (long) item.getAmount(), Long::sum);
        }
        return result;
    }
    private static int stacks(List<ItemStack> items) {
        return (int) items.stream().filter(i -> i != null && !i.getType().isAir() && i.getAmount() > 0).count();
    }
    private static byte[] sha(Path path) throws Exception { return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)); }
    private void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}
