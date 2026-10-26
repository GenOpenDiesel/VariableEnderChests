package me.saif.betterenderchests.converters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Vanilla moved playerdata/ to players/data/ in Minecraft 26.1. */
final class PlayerDataPaths {
    private PlayerDataPaths() { }

    static Path resolve(Path worldFolder) throws IOException {
        //On 26.x Bukkit may return <save>/dimensions/minecraft/overworld,
        //but shared player files live at <save>/players/data.
        Path absolute = worldFolder.toAbsolutePath().normalize();
        for (Path ancestor = absolute.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
            if (ancestor.getFileName() != null && ancestor.getFileName().toString().equals("dimensions")
                    && ancestor.relativize(absolute).getNameCount() >= 2) {
                Path shared = ancestor.getParent().resolve("players").resolve("data");
                if (Files.isDirectory(shared))
                    return shared;
            }
        }
        Path modern = worldFolder.resolve("players").resolve("data");
        if (Files.isDirectory(modern))
            return modern;
        Path legacy = worldFolder.resolve("playerdata");
        if (Files.isDirectory(legacy))
            return legacy;
        throw new IOException("Player data folder not found. Expected " + modern + " or " + legacy);
    }
}
