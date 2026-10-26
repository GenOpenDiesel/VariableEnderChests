package me.saif.betterenderchests.converters;

import de.tr7zw.changeme.nbtapi.*;
import de.tr7zw.changeme.nbtapi.iface.ReadWriteNBT;
import de.tr7zw.changeme.nbtapi.utils.DataFixerUtil;
import de.tr7zw.changeme.nbtapi.utils.MinecraftVersion;
import me.saif.betterenderchests.VariableEnderChests;
import me.saif.betterenderchests.utils.ItemStackSerializer;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Stream;

/** Offline move: merge into plugin storage, commit, and only then clear EnderItems. */
public final class VanillaMergeConverter extends Converter {
    private static final String JOURNAL = "vec_vanilla_moves";
    private final Path playerdata;

    public VanillaMergeConverter(VariableEnderChests plugin) {
        super(plugin, "VanillaMergeConverter");
        this.playerdata = Bukkit.getWorlds().get(0).getWorldFolder().toPath().resolve("playerdata");
    }

    @Override
    public boolean convert(String... args) {
        try {
            if (args.length != 0)
                throw new IllegalArgumentException("VanillaMergeConverter takes no flags. It always preserves both inventories.");
            if (!Bukkit.getOnlinePlayers().isEmpty() || !plugin.getEnderChestManager().canMergeVanilla())
                throw new IllegalStateException("Restart with logins blocked and run the merge before any player joins.");
            if (!Files.isDirectory(playerdata))
                throw new IOException("Player data folder not found: " + playerdata);

            Path backup = plugin.getDataFolder().toPath().resolve("backups")
                    .resolve("vanilla-move-" + System.currentTimeMillis() + "-" + UUID.randomUUID());
            Files.createDirectories(backup);
            plugin.getLogger().info("Vanilla move backups: " + backup.toAbsolutePath());
            try (Connection connection = plugin.getSQLDatabase().getConnection();
                 Statement statement = connection.createStatement()) {
                //This journal is committed in the SAME transaction as the merged chest. A retry
                //after a crash between DB commit and .dat replacement must not add the items twice.
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + JOURNAL
                        + " (UUID VARCHAR(36) NOT NULL PRIMARY KEY, SOURCE LONGTEXT NOT NULL)");
                int moved = 0;
                try (Stream<Path> files = Files.list(playerdata)) {
                    for (Path file : (Iterable<Path>) files.sorted()::iterator) {
                        String name = file.getFileName().toString();
                        if (!name.endsWith(".dat"))
                            continue;
                        UUID uuid;
                        try {
                            uuid = UUID.fromString(name.substring(0, name.length() - 4));
                        } catch (IllegalArgumentException ignored) {
                            continue;
                        }
                        if (movePlayer(connection, uuid, file, backup))
                            moved++;
                    }
                }
                plugin.getLogger().info("Migration Complete! Moved vanilla enderchests for " + moved + " players.");
                plugin.getLogger().info("Restart the server before allowing players to join. Overflow is available through /retrieveender.");
            }
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Vanilla move stopped. Do not restore only one side of a migration. "
                    + "Restart with logins blocked and rerun VanillaMergeConverter to resume safely.", e);
            return false;
        }
    }

    private boolean movePlayer(Connection connection, UUID uuid, Path file, Path backup) throws Exception {
        NBTFile player = new NBTFile(file.toFile());
        NBTCompoundList source = player.getCompoundList("EnderItems");
        String sourceText = source.toString();
        String pending = getPending(connection, uuid);
        if (source.isEmpty()) {
            if (pending != null)
                clearPending(connection, uuid);
            return false;
        }
        if (pending != null && !pending.equals(sourceText))
            throw new IOException("Vanilla contents changed during an unfinished move for " + uuid + ". Restore/check backups first.");

        Files.copy(file, backup.resolve(uuid + ".dat"));
        if (pending == null) {
            List<ItemStack> incoming = new ArrayList<>();
            int dataVersion = player.getInteger("DataVersion");
            for (int i = 0; i < source.size(); i++) {
                ReadWriteNBT itemData = source.get(i);
                if (MinecraftVersion.isAtLeastVersion(MinecraftVersion.MC1_20_R4))
                    itemData = DataFixerUtil.fixUpItemData(itemData, dataVersion, DataFixerUtil.getCurrentVersion());
                ItemStack item = NBT.itemStackFromNBT(itemData);
                if (item == null || item.getType().isAir() || item.getAmount() <= 0)
                    throw new IOException("Cannot decode vanilla item " + i + " for " + uuid + "; source has not been cleared.");
                incoming.add(item);
            }
            mergeAndCommit(connection, uuid, incoming, sourceText, backup);
        }

        //Never write a stale copy over playerdata if another process/plugin changed it meanwhile.
        if (!Arrays.equals(Files.readAllBytes(file), Files.readAllBytes(backup.resolve(uuid + ".dat"))))
            throw new IOException("Player data changed during migration: " + uuid);
        clearVanilla(file);
        clearPending(connection, uuid);
        plugin.getLogger().info("Moved and cleared vanilla enderchest: " + uuid);
        return true;
    }

    private void mergeAndCommit(Connection connection, UUID uuid, List<ItemStack> incoming,
                                String sourceText, Path backup) throws Exception {
        connection.setAutoCommit(false);
        try {
            ItemStack[] existing = new ItemStack[54];
            int rows = Math.max(1, Math.min(6, plugin.getConfig().getInt("default-rows", 3)));
            try (PreparedStatement read = connection.prepareStatement("SELECT `ROWS`, CONTENTS FROM enderchests WHERE UUID=?")) {
                read.setString(1, uuid.toString());
                try (ResultSet result = read.executeQuery()) {
                    if (result.next()) {
                        rows = result.getInt("ROWS");
                        String encoded = result.getString("CONTENTS");
                        Files.writeString(backup.resolve(uuid + "-plugin.txt"), rows + "\n" + encoded, StandardCharsets.UTF_8);
                        existing = ItemStackSerializer.deserialize(encoded);
                        if (existing == null || existing.length == 0)
                            throw new IOException("Cannot decode plugin enderchest for " + uuid + "; no items were moved.");
                    } else {
                        Files.writeString(backup.resolve(uuid + "-plugin.txt"), "NO_EXISTING_CHEST", StandardCharsets.UTF_8);
                    }
                }
            }
            ItemStack[] merged = mergeContents(existing, incoming);
            String encoded = ItemStackSerializer.serialize(merged);
            if (!sameContents(merged, ItemStackSerializer.deserialize(encoded)))
                throw new IOException("Item serialization verification failed for " + uuid);
            try (PreparedStatement save = connection.prepareStatement("REPLACE INTO enderchests (UUID, `ROWS`, CONTENTS) VALUES (?,?,?)")) {
                save.setString(1, uuid.toString());
                save.setInt(2, rows);
                save.setString(3, encoded);
                save.executeUpdate();
            }
            try (PreparedStatement journal = connection.prepareStatement("INSERT INTO " + JOURNAL + " (UUID,SOURCE) VALUES (?,?)")) {
                journal.setString(1, uuid.toString());
                journal.setString(2, sourceText);
                journal.executeUpdate();
            }
            connection.commit();
        } catch (Exception e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    static ItemStack[] mergeContents(ItemStack[] existing, List<ItemStack> incoming) {
        List<ItemStack> merged = new ArrayList<>(Arrays.asList(Arrays.copyOf(existing, Math.max(54, existing.length))));
        int next = 0;
        for (ItemStack item : incoming) {
            while (next < merged.size() && merged.get(next) != null
                    && !merged.get(next).getType().isAir() && merged.get(next).getAmount() > 0)
                next++;
            if (next < merged.size())
                merged.set(next++, item.clone());
            else
                merged.add(item.clone());
        }
        return merged.toArray(new ItemStack[0]);
    }

    private static boolean sameContents(ItemStack[] expected, ItemStack[] actual) {
        if (actual == null || expected.length != actual.length)
            return false;
        for (int i = 0; i < expected.length; i++) {
            ItemStack a = expected[i];
            ItemStack b = actual[i];
            boolean emptyA = a == null || a.getType().isAir() || a.getAmount() <= 0;
            boolean emptyB = b == null || b.getType().isAir() || b.getAmount() <= 0;
            if (emptyA != emptyB || (!emptyA && !a.equals(b)))
                return false;
        }
        return true;
    }

    private String getPending(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement read = connection.prepareStatement("SELECT SOURCE FROM " + JOURNAL + " WHERE UUID=?")) {
            read.setString(1, uuid.toString());
            try (ResultSet result = read.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private void clearPending(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + JOURNAL + " WHERE UUID=?")) {
            delete.setString(1, uuid.toString());
            delete.executeUpdate();
        }
    }

    private void clearVanilla(Path file) throws IOException {
        Path temporary = Files.createTempFile(playerdata, "vec-move-", ".tmp");
        try {
            Files.copy(file, temporary, StandardCopyOption.REPLACE_EXISTING);
            NBTFile moved = new NBTFile(temporary.toFile());
            moved.removeKey("EnderItems");
            moved.save();
            if (!new NBTFile(temporary.toFile()).getCompoundList("EnderItems").isEmpty())
                throw new IOException("Could not clear vanilla enderchest in " + file);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
