package me.saif.betterenderchests.data;

import me.saif.betterenderchests.data.database.SQLDatabase;
import me.saif.betterenderchests.data.database.SQLiteDatabase;
import me.saif.betterenderchests.enderchest.EnderChestSnapshot;
import me.saif.betterenderchests.utils.TimeUtils;
import org.bukkit.Bukkit;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SQLiteDataManager extends SQLDataManager {

    private final ExecutorService executorService = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "VariableEnderChests-SQLite-Worker");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean warnedAboutServerThread = false;

    public SQLiteDataManager(SQLiteDatabase database) {
        super(database);
    }

    @Override
    public boolean createBackup(Logger logger, File backupsFolder) {
        if (!backupsFolder.isDirectory()) {
            backupsFolder.mkdirs();
        }
        try (Connection connection = this.database.getConnection();
             Statement statement = connection.createStatement()) {
            logger.info("Creating backup of data.");
            File backupFile = new File(backupsFolder, "sqlitebackup-" + TimeUtils.getCurrentFormattedTime() + ".db");
            statement.executeUpdate("backup to " + backupFile);
            logger.info("Finished backing up to " + backupFile.getName());
            return true;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public void init() {
        this.executeOnSingleThread(super::init);
    }

    @Override
    public String getName(UUID uuid) {
        return this.executeOnSingleThread(() -> super.getName(uuid));
    }

    @Override
    public UUID getUUID(String name) {
        return this.executeOnSingleThread(() -> super.getUUID(name));
    }

    @Override
    public void saveNameAndUUIDs(Map<String, UUID> map) {
        this.executeOnSingleThread(() -> super.saveNameAndUUIDs(map));
    }

    @Override
    public void saveNameAndUUID(String name, UUID uuid) {
        this.executeOnSingleThread(() -> super.saveNameAndUUID(name, uuid));
    }

    @Override
    public void saveEnderChestMultiple(Map<UUID, EnderChestSnapshot> snapshotMap) {
        this.executeOnSingleThread(() -> super.saveEnderChestMultiple(snapshotMap));
    }

    @Override
    public void purge(char... confirm) {
        this.executeOnSingleThread(() -> super.purge(confirm));
    }

    @Override
    public void deleteEnderChest(String name) {
        this.executeOnSingleThread(() -> super.deleteEnderChest(name));
    }

    @Override
    public void deleteEnderChest(UUID uuid) {
        this.executeOnSingleThread(() -> super.deleteEnderChest(uuid));
    }

    @Override
    public void saveEnderChest(UUID uuid, EnderChestSnapshot snapshot) {
        this.executeOnSingleThread(() -> super.saveEnderChest(uuid, snapshot));
    }

    private void executeOnSingleThread(Runnable runnable) {
        warnIfServerThread();
        try {
            executorService.submit(runnable).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private <T> T executeOnSingleThread(Callable<T> callable) {
        warnIfServerThread();
        try {
            return executorService.submit(callable).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    //every call here waits for the single sqlite worker to be free. doing that from a server thread
    //stalls the whole server until the worker is done, which is what the watchdog kills the server for.
    //nothing in the plugin should hit this anymore, so shout about it if something still does.
    private void warnIfServerThread() {
        if (warnedAboutServerThread || !Bukkit.isPrimaryThread())
            return;

        warnedAboutServerThread = true;
        Bukkit.getLogger().log(Level.WARNING, "[VariableEnderChests] A database call was made from a server thread. "
                + "This can freeze the server if the database is busy. Please report the stacktrace below.",
                new IllegalStateException("Database access on server thread"));
    }

    @Override
    public Set<UUID> getAllEnderChests() {
        return this.executeOnSingleThread(super::getAllEnderChests);
    }

    @Override
    public Map<String, EnderChestSnapshot> loadEnderChestsByName(Set<String> names) {
        return this.executeOnSingleThread(() ->  super.loadEnderChestsByName(names));
    }

    @Override
    public Map<UUID, EnderChestSnapshot> loadEnderChestsByUUID(Set<UUID> uuids) {
        return this.executeOnSingleThread(() ->  super.loadEnderChestsByUUID(uuids));
    }

    @Override
    public void finishUp() {
        this.executorService.shutdown();
        try {
            //this used to pass System.currentTimeMillis() as the timeout, which is decades long,
            //so a stuck query would hang the shutdown forever instead of letting the server stop.
            if (!this.executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                Bukkit.getLogger().warning("[VariableEnderChests] Database tasks did not finish within 30 seconds. Forcing shutdown.");
                this.executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            this.executorService.shutdownNow();
        }
        super.finishUp();
    }
}
