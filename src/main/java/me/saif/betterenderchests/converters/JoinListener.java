package me.saif.betterenderchests.converters;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;

public class JoinListener implements Listener {

    private ConverterManager converterManager;

    public JoinListener(ConverterManager converterManager) {
        this.converterManager = converterManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onJoin(AsyncPlayerPreLoginEvent event) {
        if (!this.converterManager.isConverting())
            return;

        event.setLoginResult(AsyncPlayerPreLoginEvent.Result.KICK_OTHER);
        event.setKickMessage("Server not currently join able.");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onLogin(PlayerLoginEvent event) {
        if (this.converterManager.isConverting())
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, "Enderchest migration in progress. Please wait for the server to restart.");
    }

}
