package com.worstgen.wgbosses;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.*;

public final class Rewards {
    private Rewards() {}

    public record Result(List<ItemStack> chest, List<ItemStack> bonus, int xp, int shards, int bonusShards) {}

    private static Item item(String id) {
        Identifier i = Identifier.tryParse(id);
        if (i == null) return null;
        Optional<Item> o = BuiltInRegistries.ITEM.getOptional(i);
        if (o.isEmpty() || o.get() == Items.AIR) return null;
        return o.get();
    }

    private static void addStacks(List<ItemStack> out, Item item, int count) {
        int max = Math.max(1, new ItemStack(item, 1).getMaxStackSize());
        while (count > 0) {
            int n = Math.min(max, count);
            out.add(new ItemStack(item, n));
            count -= n;
        }
    }

    private static void fromEntries(List<ItemStack> out, List<Config.RewardEntry> entries) {
        if (entries == null) return;
        for (Config.RewardEntry e : entries) {
            if (e == null || e.item == null || !Util.chance(e.chance)) continue;
            Item it = item(e.item);
            if (it == null) continue; // item (or its mod) is not installed -> skipped
            int n = Util.rint(e.min, e.max);
            if (n > 0) addStacks(out, it, n);
        }
    }

    /** Items rolled individually for every participant (e.g. a small chance at a totem). */
    public static List<ItemStack> rollPerPlayer(Boss b) {
        List<ItemStack> out = new ArrayList<>();
        String key = WGBosses.config.tiers.get(b.trueTier).name.toLowerCase(Locale.ROOT);
        fromEntries(out, WGBosses.config.perPlayerRewards.get(key));
        return out;
    }

    public static Result roll(Boss b) {
        Config c = WGBosses.config;
        Config.TierDef t = c.tiers.get(b.trueTier);
        String key = t.name.toLowerCase(Locale.ROOT);
        List<ItemStack> chest = new ArrayList<>();
        List<ItemStack> bonus = new ArrayList<>();

        int shards = 0, bonusShards = 0;
        Item shard = item(c.shardItem);
        if (shard != null) {
            shards = Util.rint(t.shardMin, t.shardMax);
            bonusShards = Util.rint(c.topDamageShardMin, c.topDamageShardMax);
            addStacks(chest, shard, shards);
            addStacks(bonus, shard, bonusShards);
        }
        fromEntries(chest, c.rewards.get(key));
        fromEntries(bonus, c.topDamageBonusRewards.get(key));
        int xp = Util.rint(t.xpMin, t.xpMax);
        return new Result(chest, bonus, xp, shards, bonusShards);
    }

    /** "12x Diamond, 3x Iron Ingot" */
    public static Component describe(List<ItemStack> stacks) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Component> names = new HashMap<>();
        for (ItemStack s : stacks) {
            String k = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
            counts.merge(k, s.getCount(), Integer::sum);
            names.putIfAbsent(k, s.getHoverName());
        }
        net.minecraft.network.chat.MutableComponent out = Component.empty();
        boolean first = true;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (!first) out.append(Component.literal(", "));
            first = false;
            out.append(Component.literal(e.getValue() + "x ")).append(names.get(e.getKey()));
        }
        if (first) out.append(Component.literal("nothing"));
        return out;
    }
}
