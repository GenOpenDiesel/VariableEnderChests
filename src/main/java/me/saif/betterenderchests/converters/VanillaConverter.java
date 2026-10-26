package me.saif.betterenderchests.converters;

import me.saif.betterenderchests.VariableEnderChests;

/** The legacy command name now performs the same crash-recoverable MOVE, never a copy/overwrite. */
public final class VanillaConverter extends VanillaMergeConverter {
    public VanillaConverter(VariableEnderChests plugin) {
        super(plugin, "VanillaConverter");
    }
}