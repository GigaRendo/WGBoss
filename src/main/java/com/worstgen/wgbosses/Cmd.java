package com.worstgen.wgbosses;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Cmd {
    private Cmd() {}

    private static final SuggestionProvider<CommandSourceStack> TIERS = (ctx, b) -> {
        List<String> l = new ArrayList<>(List.of("random"));
        for (Config.TierDef t : WGBosses.config.tiers) l.add(t.name.toLowerCase(Locale.ROOT));
        return SharedSuggestionProvider.suggest(l, b);
    };
    private static final SuggestionProvider<CommandSourceStack> STATS = (ctx, b) ->
            SharedSuggestionProvider.suggest(List.of("kills", "damage", "calamity", "highest", "mvp"), b);

    private static Component T(String key, Object... kv) { return WGBosses.bank.msg(key, kv); }

    private static boolean admin(CommandSourceStack s) { return s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER); }

    public static void register(CommandDispatcher<CommandSourceStack> d, CommandBuildContext ctx) {
        d.register(Commands.literal("wgboss")
                .then(Commands.literal("spawn").requires(Cmd::admin)
                        .executes(c -> spawn(c, null, "random"))
                        .then(Commands.argument("mob", ResourceArgument.resource(ctx, Registries.ENTITY_TYPE))
                                .executes(c -> spawn(c, mob(c), "random"))
                                .then(Commands.argument("tier", StringArgumentType.word()).suggests(TIERS)
                                        .executes(c -> spawn(c, mob(c), StringArgumentType.getString(c, "tier"))))))
                .then(Commands.literal("kill").requires(Cmd::admin).executes(c -> {
                    boolean ok = WGBosses.MANAGER.adminKill();
                    if (ok) c.getSource().sendSuccess(() -> T("cmd.kill.ok"), true);
                    else c.getSource().sendFailure(T("cmd.kill.none"));
                    return ok ? 1 : 0;
                }))
                .then(Commands.literal("toggle").requires(Cmd::admin).executes(c -> {
                    Config cfg = WGBosses.config;
                    cfg.autoSpawn = !cfg.autoSpawn;
                    cfg.save();
                    if (cfg.autoSpawn) WGBosses.MANAGER.scheduleNext();
                    Component s = T(cfg.autoSpawn ? "cmd.toggle.on" : "cmd.toggle.off");
                    c.getSource().sendSuccess(() -> s, true);
                    return 1;
                }))
                .then(Commands.literal("bloodmoon").requires(Cmd::admin).executes(Cmd::bloodMoon))
                .then(Commands.literal("reload").requires(Cmd::admin).executes(c -> {
                    WGBosses.reloadAll();
                    c.getSource().sendSuccess(() -> T("cmd.reload"), true);
                    return 1;
                }))
                .then(Commands.literal("tp")
                        .then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(c -> {
                            ServerPlayer p = c.getSource().getPlayer();
                            if (p == null) return 0;
                            String err = WGBosses.MANAGER.teleport(p, IntegerArgumentType.getInteger(c, "id"));
                            if (err != null) { c.getSource().sendFailure(T(err)); return 0; }
                            return 1;
                        })))
                .then(Commands.literal("top")
                        .executes(c -> top(c, "kills"))
                        .then(Commands.argument("stat", StringArgumentType.word()).suggests(STATS)
                                .executes(c -> top(c, StringArgumentType.getString(c, "stat").toLowerCase(Locale.ROOT)))))
                .then(Commands.literal("info").executes(c -> {
                    Boss b = WGBosses.MANAGER.first();
                    if (b == null) {
                        long t = WGBosses.MANAGER.ticksUntilNext();
                        Component s = !WGBosses.config.autoSpawn ? T("info.off") : t < 0 ? T("info.unscheduled") : T("info.next", "min", t / 1200 + 1);
                        c.getSource().sendSuccess(() -> s, false);
                    } else {
                        Component s = T("info.boss", "tier", b.tierName(), "boss", b.baseName, "phase", b.phase, "phases", b.phases,
                                "hp", Math.round(100 * b.hp / b.maxHp));
                        c.getSource().sendSuccess(() -> s, false);
                    }
                    return 1;
                })));
    }

    private static EntityType<?> mob(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return ResourceArgument.getSummonableEntityType(c, "mob").value();
    }

    private static ServerPlayer anchor(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = c.getSource().getPlayer();
        if (p != null) return p;
        List<ServerPlayer> all = c.getSource().getServer().getPlayerList().getPlayers();
        return all.isEmpty() ? null : Util.pick(all);
    }

    private static int spawn(CommandContext<CommandSourceStack> c, EntityType<?> type, String tierName) {
        ServerPlayer p = anchor(c);
        if (p == null) { c.getSource().sendFailure(T("cmd.noplayer")); return 0; }
        int tier = -1;
        if (!tierName.equalsIgnoreCase("random")) {
            tier = WGBosses.config.tierIndex(tierName);
            if (tier < 0) { c.getSource().sendFailure(T("cmd.badtier", "tier", tierName)); return 0; }
        }
        ServerLevel lvl = (ServerLevel) p.level();
        boolean ok = WGBosses.MANAGER.adminSpawn(lvl, p, type, tier);
        if (!ok) { c.getSource().sendFailure(T("cmd.spawnfail")); return 0; }
        return 1;
    }

    private static int bloodMoon(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = anchor(c);
        if (p == null) { c.getSource().sendFailure(T("cmd.noplayer")); return 0; }
        return WGBosses.MANAGER.adminBloodMoon((ServerLevel) p.level(), p) ? 1 : 0;
    }

    private static int top(CommandContext<CommandSourceStack> c, String stat) {
        List<Map.Entry<String, Double>> l = WGBosses.stats.top(stat, 5);
        String tk = switch (stat) {
            case "damage", "calamity", "highest", "mvp" -> stat;
            default -> "kills";
        };
        c.getSource().sendSuccess(() -> T("top.title." + tk), false);
        if (l.isEmpty()) {
            c.getSource().sendSuccess(() -> T("top.empty"), false);
            return 1;
        }
        int i = 1;
        for (Map.Entry<String, Double> e : l) {
            String val;
            if (stat.equals("highest")) val = WGBosses.config.tiers.get(Math.max(0, (int) Math.round(e.getValue()) - 1)).name;
            else if (stat.equals("damage")) val = String.valueOf(Math.round(e.getValue()));
            else val = String.valueOf(Math.round(e.getValue()));
            Component line = T("top.line", "rank", i, "player", e.getKey(), "value", val);
            c.getSource().sendSuccess(() -> line, false);
            i++;
        }
        return 1;
    }
}
