package com.ma.languagelearning.game;

import com.ma.languagelearning.config.MllConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Watches the player's inventory for newly acquired items.
 *
 * <p>Holds a per-item total-count snapshot that is refreshed once every few ticks. A word
 * becomes a challenge when the count for its registry id has gone up since the previous
 * snapshot and the ledger does not already contain it.</p>
 *
 * <p>The very first snapshot is recorded silently: joining a world with a full inventory
 * must not queue twenty challenges. Detection is also suspended while a container screen is
 * open, so looting a chest does not fire mid-loot — the items are picked up when the screen
 * closes and the next snapshot runs.</p>
 */
public final class AcquisitionTracker {

    private static final Logger LOG = LoggerFactory.getLogger("malanguagelearning");

    /** Snapshot interval in client ticks (5 ticks = 4 Hz). */
    private static final int SNAPSHOT_INTERVAL_TICKS = 5;

    private final ItemNameResolver resolver;
    private final LearnedLedger ledger;

    /** item key -> total count across the inventory, as of the last snapshot. */
    private final Map<String, Integer> counts = new HashMap<>();
    /** last known stack per key, so the challenge can be handed a full ItemStack. */
    private final Map<String, ItemStack> stacks = new HashMap<>();
    /** keys detected as new and not yet polled away. */
    private final Set<String> pending = new LinkedHashSet<>();

    private boolean baselineTaken;
    private int tickCounter;

    public AcquisitionTracker(ItemNameResolver resolver, LearnedLedger ledger) {
        this.resolver = resolver;
        this.ledger = ledger;
    }

    /**
     * Advance the tracker. Safe to call every client tick; the expensive inventory scan is
     * throttled internally.
     */
    public void tick() {
        tickCounter++;
        if (tickCounter < SNAPSHOT_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        try {
            snapshot();
        } catch (Exception e) {
            LOG.error("[malanguagelearning] inventory snapshot failed", e);
        }
    }

    /**
     * Item keys acquired since the last call, in acquisition order.
     *
     * <p>Consuming: the returned keys are cleared from the tracker.</p>
     */
    public List<String> pollNewItemKeys() {
        if (pending.isEmpty()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>(pending);
        pending.clear();
        return keys;
    }

    /** The last seen stack for a registry id, or null. */
    public ItemStack stackForKey(String key) {
        if (key == null) {
            return null;
        }
        ItemStack stack = stacks.get(key);
        return stack == null || stack.isEmpty() ? null : stack;
    }

    /** Forget all state; the next snapshot becomes a baseline again. */
    public void reset() {
        counts.clear();
        stacks.clear();
        pending.clear();
        baselineTaken = false;
        tickCounter = 0;
    }

    // ------------------------------------------------------------------ internals

    private void snapshot() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            reset();
            return;
        }

        // Never detect while a container is open: the player is mid-transfer and the counts
        // would change for reasons unrelated to "acquiring" something.
        Screen screen = mc.gui.screen();
        if (screen instanceof AbstractContainerScreen<?>) {
            return;
        }

        Inventory inventory;
        try {
            inventory = mc.player.getInventory();
        } catch (Exception e) {
            return;
        }
        if (inventory == null) {
            return;
        }

        Map<String, Integer> fresh = new HashMap<>();
        Map<String, ItemStack> freshStacks = new HashMap<>();

        List<ItemStack> stacksToScan;
        try {
            stacksToScan = inventory.getNonEquipmentItems();
        } catch (Exception e) {
            return;
        }

        for (ItemStack stack : stacksToScan) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String key = resolver.keyFor(stack);
            if (key == null) {
                continue;
            }
            int count;
            try {
                count = stack.getCount();
            } catch (Exception e) {
                continue;
            }
            fresh.merge(key, count, Integer::sum);
            freshStacks.putIfAbsent(key, stack.copy());
            // Any sighting of a learned item restarts its cooldown, including picking up
            // more of something already known.
            try {
                ledger.markSeen(key, System.currentTimeMillis());
            } catch (Exception e) {
                // Never let bookkeeping break acquisition detection.
            }
        }

        if (!baselineTaken) {
            // First look at this world: record it and ask for nothing.
            counts.clear();
            counts.putAll(fresh);
            stacks.clear();
            stacks.putAll(freshStacks);
            baselineTaken = true;
            LOG.debug("[malanguagelearning] acquisition baseline: {} keys", counts.size());
            return;
        }

        MllConfig cfg = MllConfig.get();
        for (Map.Entry<String, Integer> entry : fresh.entrySet()) {
            String key = entry.getKey();
            int now = entry.getValue();
            int before = counts.getOrDefault(key, 0);
            if (now <= before) {
                continue;
            }
            if (cfg.ignoredItems != null && cfg.ignoredItems.contains(key)) {
                continue;
            }
            if (cfg.learnOncePerItem && ledger.isLearned(key)) {
                continue;
            }
            if (ledger.isLearned(key)) {
                continue;
            }
            ItemStack stack = freshStacks.get(key);
            if (stack == null) {
                continue;
            }
            if (resolver.wordFor(stack) == null) {
                // Too short, blank, or ignored — not a word worth asking for.
                continue;
            }
            pending.add(key);
            LOG.debug("[malanguagelearning] new item acquired: {} ({} -> {})", key, before, now);
        }

        counts.clear();
        counts.putAll(fresh);
        stacks.clear();
        stacks.putAll(freshStacks);
    }
}
