package me.saif.betterenderchests.enderchest;

import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.UUID;

public class EnderChestSnapshot {

    private final int rows;
    private final ItemStack[] contents;
    private final String name;
    private final UUID uuid;

    public EnderChestSnapshot(UUID uuid, String name, ItemStack[] contents, int rows) {
        this.uuid = uuid;
        this.name = name;
        this.contents = copyItems(contents);
        this.rows = rows;
    }

    protected EnderChestSnapshot(EnderChest enderChest) {
        this.name = enderChest.getName();
        this.uuid = enderChest.getUUID();
        this.rows = enderChest.getLastNumRows();
        this.contents = copyItems(enderChest.getContents());
    }

    public String getName() {
        return name;
    }

    public UUID getUuid() {
        return uuid;
    }

    public int getRows() {
        return rows;
    }

    public int getNumContents() {
        return contents.length;
    }

    public ItemStack get(int i) {
        if (i >= contents.length || i < 0)
            return null;
        return contents[i] == null ? null : contents[i].clone();
    }

    public ItemStack[] getContents() {
        return copyItems(contents);
    }

    private static ItemStack[] copyItems(ItemStack[] source) {
        ItemStack[] copy = Arrays.copyOf(source, Math.max(54, source.length));
        for (int i = 0; i < copy.length; i++)
            if (copy[i] != null)
                copy[i] = copy[i].clone();
        return copy;
    }

    @Override
    public int hashCode() {
        return uuid.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EnderChestSnapshot)) return false;

        EnderChestSnapshot that = (EnderChestSnapshot) o;

        return getUuid().equals(that.getUuid());
    }
}
