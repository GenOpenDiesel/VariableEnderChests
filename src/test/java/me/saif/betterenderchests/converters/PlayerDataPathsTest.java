package me.saif.betterenderchests.converters;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PlayerDataPathsTest {
    @TempDir Path world;

    @Test void readsMinecraft26PlayerDirectory() throws IOException {
        Path modern = Files.createDirectories(world.resolve("players/data"));
        assertEquals(modern, PlayerDataPaths.resolve(world));
    }

    @Test void retainsSupportForLegacyPlayerDirectory() throws IOException {
        Path legacy = Files.createDirectories(world.resolve("playerdata"));
        assertEquals(legacy, PlayerDataPaths.resolve(world));
    }

    @Test void readsSharedPlayerDataWhenBukkitReturnsDimensionFolder() throws IOException {
        Path modern = Files.createDirectories(world.resolve("players/data"));
        Path dimension = Files.createDirectories(world.resolve("dimensions/minecraft/overworld"));
        assertEquals(modern, PlayerDataPaths.resolve(dimension));
    }

    @Test void sharedPlayerDataWinsOverStaleFilesInsideDimension() throws IOException {
        Path modern = Files.createDirectories(world.resolve("players/data"));
        Path dimension = Files.createDirectories(world.resolve("dimensions/minecraft/overworld"));
        Files.createDirectories(dimension.resolve("playerdata"));
        Files.createDirectories(dimension.resolve("players/data"));
        assertEquals(modern, PlayerDataPaths.resolve(dimension));
    }

    @Test void modernDirectoryWinsOverStaleLegacyData() throws IOException {
        Path modern = Files.createDirectories(world.resolve("players/data"));
        Path legacy = Files.createDirectories(world.resolve("playerdata"));
        Files.writeString(legacy.resolve("old-player.dat"), "stale backup must not be migrated");
        assertEquals(modern, PlayerDataPaths.resolve(world));
    }

    @Test void refusesToInventAnEmptySourceDirectory() {
        assertThrows(IOException.class, () -> PlayerDataPaths.resolve(world));
        assertFalse(Files.exists(world.resolve("playerdata")));
        assertFalse(Files.exists(world.resolve("players/data")));
    }
}
