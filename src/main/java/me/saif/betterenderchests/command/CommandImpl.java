package me.saif.betterenderchests.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import me.saif.betterenderchests.VariableEnderChests;

import java.util.List;

public class CommandImpl extends Command {

    private final PluginCommand command;
    private final VariableEnderChests plugin;

    protected CommandImpl(VariableEnderChests plugin, PluginCommand command) {
        super(command.getName());
        this.plugin = plugin;
        this.setAliases(command.getAliases());

        if (command.requiresPermission()) {
            this.setPermission(command.getPermission());
        }

        this.command = command;
    }

    @Override
    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
        if (plugin.getConverterManager().isConverting()) {
            sender.sendMessage("Enderchest conversion in progress or server restart required.");
            return true;
        }
        this.command.onCommand(sender, commandLabel, args);
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) throws IllegalArgumentException {
        List<String> completions = this.command.onTabComplete(sender, alias, args);
        if (completions == null)
            return super.tabComplete(sender, alias, args);
        else return completions;
    }
}
