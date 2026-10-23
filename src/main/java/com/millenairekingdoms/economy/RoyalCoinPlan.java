package com.millenairekingdoms.economy;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.millenaire.item.ModItems;
import org.millenaire.item.MoneyHelper;

/** Side-effect-free denomination/change planning for the forthcoming durable treasury service.
 * Only ordinary loose currency is spendable; goods and coins with custom components are preserved.
 * A plan is not a payment receipt: inventory access, authority, journaling and recovery belong to the caller. */
public final class RoyalCoinPlan {
    private RoyalCoinPlan() {}

    public static final class Plan {
        private final List<ItemStack> before, after;
        private final long beforeValue, afterValue;
        private Plan(List<ItemStack> before, List<ItemStack> after, long beforeValue, long afterValue) {
            this.before = copies(before); this.after = copies(after);
            this.beforeValue = beforeValue; this.afterValue = afterValue;
        }
        public List<ItemStack> before() { return copies(before); }
        public List<ItemStack> after() { return copies(after); }
        public long beforeValue() { return beforeValue; }
        public long afterValue() { return afterValue; }
    }

    public static Plan debit(List<ItemStack> slots, long amount) {
        if (amount <= 0) throw new IllegalArgumentException("A payment must be positive");
        return plan(slots, -amount);
    }

    /** A refund must still be authorized against a paid transaction; this method only plans its coins. */
    public static Plan credit(List<ItemStack> slots, long amount) {
        if (amount <= 0) throw new IllegalArgumentException("A refund must be positive");
        return plan(slots, amount);
    }

    public static long spendableValue(List<ItemStack> slots) {
        long value = 0;
        for (ItemStack stack : slots) value = Math.addExact(value, (long) denomination(stack) * stack.getCount());
        return value;
    }

    private static Plan plan(List<ItemStack> slots, long delta) {
        long before = spendableValue(slots);
        long after;
        try { after = Math.addExact(before, delta); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("Currency amount is too large", e); }
        if (after < 0) throw new IllegalArgumentException("Not enough physical treasury coins");
        List<ItemStack> result = copies(slots);
        List<Integer> available = new ArrayList<>();
        for (int slot = 0; slot < result.size(); slot++) {
            ItemStack stack = result.get(slot);
            if (stack.isEmpty() || denomination(stack) > 0) {
                available.add(slot); result.set(slot, ItemStack.EMPTY);
            }
        }
        Item[] coins = {ModItems.DENIER_OR.get(), ModItems.DENIER_ARGENT.get(), ModItems.DENIER.get()};
        int[] values = {MoneyHelper.OR_VALUE, MoneyHelper.ARGENT_VALUE, 1};
        int next = 0;
        long left = after;
        for (int kind = 0; kind < coins.length; kind++) {
            long count = left / values[kind];
            left %= values[kind];
            while (count > 0) {
                if (next == available.size()) throw new IllegalArgumentException("No room for exact coin change; nothing was taken");
                int size = (int) Math.min(count, coins[kind].getDefaultMaxStackSize());
                result.set(available.get(next++), new ItemStack(coins[kind], size));
                count -= size;
            }
        }
        return new Plan(slots, result, before, after);
    }

    private static int denomination(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        Item item = stack.getItem();
        int value = item == ModItems.DENIER_OR.get() ? MoneyHelper.OR_VALUE
                : item == ModItems.DENIER_ARGENT.get() ? MoneyHelper.ARGENT_VALUE
                : item == ModItems.DENIER.get() ? 1 : 0;
        return value > 0 && ItemStack.isSameItemSameComponents(stack, new ItemStack(item)) ? value : 0;
    }

    private static List<ItemStack> copies(List<ItemStack> slots) {
        return new ArrayList<>(slots.stream().map(ItemStack::copy).toList());
    }
}
