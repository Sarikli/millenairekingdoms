package com.millenairekingdoms.shipping;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemStack;

/** Pure ordinary-chest slot planning. No world access, authority, fees, reservations, escrow or delivery guarantee.
 * Exact native item components and noncargo goods are preserved. Callers must establish real surplus and persistence. */
public final class RoyalGoodsPlan {
    private static final int MAX_SLOTS=4096,MAX_ITEMS=1_000_000;
    private RoyalGoodsPlan() {}

    public static final class Plan {
        private final List<ItemStack> before,after,cargo;
        private final int quantity;
        private Plan(List<ItemStack> before,List<ItemStack> after,List<ItemStack> cargo) {
            this.before=copy(before);this.after=copy(after);this.cargo=copy(cargo);
            quantity=RoyalGoodsPlan.quantity(cargo);
        }
        public List<ItemStack> before(){return copy(before);}
        public List<ItemStack> after(){return copy(after);}
        public List<ItemStack> cargo(){return copy(cargo);}
        public int quantity(){return quantity;}
        public boolean matchesBefore(List<ItemStack> current) {
            if(current==null || current.size()!=before.size())return false;
            for(int i=0;i<before.size();i++) if(current.get(i)==null || !ItemStack.matches(before.get(i),current.get(i)))return false;
            return true;
        }
    }

    /** Remove exactly the requested native item/components, in original slot order. No caller stack is mutated. */
    public static Plan remove(List<ItemStack> source,ItemStack product,int amount) {
        if(product==null || product.isEmpty() || amount<=0 || amount>MAX_ITEMS)throw new IllegalArgumentException("Invalid royal cargo quantity or product");
        var before=slots(source);var after=new ArrayList<>(copy(before));var cargo=new ArrayList<ItemStack>();int remaining=amount;
        for(int i=0;i<after.size() && remaining>0;i++) {
            var stack=after.get(i);if(stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack,product))continue;
            int moved=Math.min(remaining,stack.getCount());cargo.add(stack.copyWithCount(moved));
            after.set(i,moved==stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount()-moved));remaining-=moved;
        }
        if(remaining!=0)throw new IllegalArgumentException("Insufficient exact real cargo stock; no partial removal plan");
        return new Plan(before,after,cargo);
    }

    /** Insert every exact cargo stack or fail before any live mutation. Merge only native component-equal stacks. */
    public static Plan insert(List<ItemStack> destination,List<ItemStack> cargo) {
        var before=slots(destination);var goods=slots(cargo);quantity(goods);var after=new ArrayList<>(copy(before));
        for(var incoming:goods) {
            if(incoming.isEmpty())throw new IllegalArgumentException("Cargo may not contain empty stacks");
            int remaining=incoming.getCount();
            for(int i=0;i<after.size() && remaining>0;i++) {
                var stack=after.get(i);
                if(stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack,incoming))continue;
                int moved=Math.min(remaining,capacity(stack)-stack.getCount());
                if(moved>0){after.set(i,stack.copyWithCount(stack.getCount()+moved));remaining-=moved;}
            }
            for(int i=0;i<after.size() && remaining>0;i++) if(after.get(i).isEmpty()) {
                int moved=Math.min(remaining,capacity(incoming));after.set(i,incoming.copyWithCount(moved));remaining-=moved;
            }
            if(remaining!=0)throw new IllegalArgumentException("Destination has insufficient exact cargo capacity; no partial insertion plan");
        }
        return new Plan(before,after,goods);
    }

    private static List<ItemStack> slots(List<ItemStack> input) {
        if(input==null || input.size()>MAX_SLOTS)throw new IllegalArgumentException("Unsupported royal cargo slot count");
        for(var stack:input) {
            if(stack==null || !stack.isEmpty() && (stack.getCount()<=0 || stack.getCount()>capacity(stack)))
                throw new IllegalArgumentException("Invalid ordinary chest cargo stack; preserve inventory");
        }
        return copy(input);
    }
    private static int capacity(ItemStack stack) {return Math.min(64,stack.getMaxStackSize());}
    private static int quantity(List<ItemStack> stacks) {
        long amount=0;for(var stack:stacks)amount=Math.addExact(amount,stack.getCount());
        if(amount<=0 || amount>MAX_ITEMS)throw new IllegalArgumentException("Invalid total royal cargo quantity");return Math.toIntExact(amount);
    }
    private static List<ItemStack> copy(List<ItemStack> stacks) {return stacks.stream().map(ItemStack::copy).toList();}
}
