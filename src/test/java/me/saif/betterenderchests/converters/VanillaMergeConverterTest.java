package me.saif.betterenderchests.converters;

import me.saif.betterenderchests.enderchest.EnderChestSnapshot;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class VanillaMergeConverterTest {
    @Test
    void keepsBothFullChestsIncludingIdenticalStacks() {
        ItemStack[] existing = new ItemStack[54];
        Arrays.setAll(existing, i -> new ItemStack(Material.DIAMOND, 64));
        List<ItemStack> incoming = new ArrayList<>();
        for (int i = 0; i < 27; i++)
            incoming.add(new ItemStack(Material.DIAMOND, 64));
        ItemStack[] merged = VanillaMergeConverter.mergeContents(existing, incoming);
        assertEquals(81, merged.length);
        assertEquals(81 * 64, Arrays.stream(merged).mapToInt(ItemStack::getAmount).sum());
        for (int i = 0; i < 54; i++)
            assertSame(existing[i], merged[i]);
    }

    @Test
    void fillsGapsWithoutReplacingExistingItemsAndClonesTheSource() {
        ItemStack[] existing = new ItemStack[54];
        existing[0] = new ItemStack(Material.EMERALD, 12);
        existing[2] = new ItemStack(Material.NETHER_STAR, 3);
        ItemStack source = new ItemStack(Material.DIAMOND, 16);
        ItemStack[] merged = VanillaMergeConverter.mergeContents(existing, List.of(source));
        assertEquals(54, merged.length);
        assertSame(existing[0], merged[0]);
        assertSame(existing[2], merged[2]);
        assertEquals(Material.DIAMOND, merged[1].getType());
        merged[1].setAmount(1);
        assertEquals(16, source.getAmount());
        assertNull(existing[1]);
    }

    @Test
    void preservesExistingOverflowThroughMergeAndSnapshot() {
        ItemStack[] existing = new ItemStack[81];
        Arrays.setAll(existing, i -> new ItemStack(Material.STONE, i % 64 + 1));
        ItemStack[] merged = VanillaMergeConverter.mergeContents(existing, List.of(new ItemStack(Material.DIAMOND, 7)));
        EnderChestSnapshot snapshot = new EnderChestSnapshot(UUID.randomUUID(), "TestPlayer", merged, 1);
        assertEquals(82, snapshot.getContents().length);
        assertEquals(7, snapshot.get(81).getAmount());
        assertEquals(1, snapshot.getRows());
        for (int i = 0; i < 81; i++)
            assertEquals(existing[i].getAmount(), snapshot.get(i).getAmount());
    }

    @Test
    void emptySourceDoesNotChangeAnyExistingSlot() {
        ItemStack[] existing = new ItemStack[54];
        existing[53] = new ItemStack(Material.DIAMOND, 3);
        ItemStack[] merged = VanillaMergeConverter.mergeContents(existing, List.of());
        assertEquals(54, merged.length);
        assertSame(existing[53], merged[53]);
        for (int i = 0; i < 53; i++)
            assertNull(merged[i]);
    }
}
