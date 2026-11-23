package com.worstgen.wgbosses;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
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
            SharedSuggestionProvider.suggest(List.of("kills", "damage", "calamity", "highest", "mvp", "streak"), b);
    private static final SuggestionProvider<CommandSourceStack> DAYS = (ctx, b) ->
            SharedSuggestionProvider.suggest(List.of("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "daily"), b);
    private static final SuggestionProvider<CommandSourceStack> ARENAS = (ctx, b) -> {
        List<String> l = new ArrayList<>();
        for (Config.Arena a : WGBosses.config.arenas) l.add(a.name);
        return SharedSuggestionProvider.suggest(l, b);
    };
    private static final SuggestionProvider<CommandSourceStack> EVENTS = (ctx, b) -> {
        List<String> l = new ArrayList<>();
        for (Config.Event e : WGBosses.config.events) l.add(e.name);
        return SharedSuggestionProvider.suggest(l, b);
    };

    private static boolean admin(CommandSourceStack s) { return s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER); }

    public static void register(CommandDispatcher<CommandSourceStack> d, CommandBuildContext ctx) {
        d.register(Commands.literal("wgboss")
                .then(Commands.literal("spawn").requires(Cmd::admin)
                        .executes(c -> spawn(c, null, "random"))
                        .then(Commands.argument("mob", ResourceArgument.resource(ctx, Registries.ENTITY_TYPE))
                                .executes(c -> spawn(c, mob(c), "random"))
                                .then(Commands.argument("tier", StringArgumentType.word()).suggests(TIERS)
                                        .executes(c -> spawn(c, mob(c), StringArgumentType.getString(c, "tier"))))))
                .then(Commands.literal("locate").executes(c -> {
                    Boss b = WGBosses.MANAGER.first();
                    ServerPlayer me = c.getSource().getPlayer();
                    if (b == null) { c.getSource().sendFailure(Component.literal("No boss is active.")); return 0; }
                    String where = b.arena != null ? " (arena " + b.arena.name + ")" : "";
                    String msg;
                    if (me == null || me.level() != b.level) {
                        msg = "The boss is in the " + b.level.dimension().identifier().getPath() + where + ".";
                    } else {
                        double dx = b.mob.getX() - me.getX(), dz = b.mob.getZ() - me.getZ();
                        double deg = Math.toDegrees(Math.atan2(dx, -dz));
                        if (deg < 0) deg += 360;
                        String[] dirs = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
                        String dir = dirs[(int) Math.round(deg / 45.0) % 8];
                        msg = "The boss is about " + Math.round(Math.sqrt(dx * dx + dz * dz) / 10.0) * 10 + " blocks to the " + dir + where + ".";
                    }
                    c.getSource().sendSuccess(() -> Component.literal(msg).withStyle(ChatFormatting.YELLOW), false);
                    return 1;
                }))
                .then(Commands.literal("event").requires(Cmd::admin)
                        .then(Commands.literal("add")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.argument("day", StringArgumentType.word()).suggests(DAYS)
                                                .then(Commands.argument("hour", IntegerArgumentType.integer(0, 23))
                                                        .then(Commands.argument("minute", IntegerArgumentType.integer(0, 59))
                                                                .then(Commands.argument("tier", StringArgumentType.word()).suggests(TIERS)
                                                                        .executes(c -> addEvent(c, ""))
                                                                        .then(Commands.argument("arena", StringArgumentType.word()).suggests(ARENAS)
                                                                                .executes(c -> addEvent(c, StringArgumentType.getString(c, "arena"))))))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("name", StringArgumentType.word()).suggests(EVENTS).executes(c -> {
                                    String name = StringArgumentType.getString(c, "name");
                                    boolean removed = WGBosses.config.events.removeIf(e -> e.name.equalsIgnoreCase(name));
                                    if (!removed) { c.getSource().sendFailure(Component.literal("No event named " + name)); return 0; }
                                    WGBosses.config.save();
                                    c.getSource().sendSuccess(() -> Component.literal("Event '" + name + "' removed."), true);
                                    return 1;
                                })))
                        .then(Commands.literal("run")
                                .then(Commands.argument("name", StringArgumentType.word()).suggests(EVENTS).executes(c -> {
                                    String name = StringArgumentType.getString(c, "name");
                                    for (Config.Event e : WGBosses.config.events) {
                                        if (e.name.equalsIgnoreCase(name)) {
                                            boolean ok = WGBosses.MANAGER.runEvent(e);
                                            if (!ok) { c.getSource().sendFailure(Component.literal("Could not run that event (see server log).")); return 0; }
                                            return 1;
                                        }
                                    }
                                    c.getSource().sendFailure(Component.literal("No event named " + name));
                                    return 0;
                                })))
                        .then(Commands.literal("on").executes(c -> {
                            WGBosses.config.eventsEnabled = true;
                            WGBosses.config.save();
                            c.getSource().sendSuccess(() -> Component.literal("Scheduled events ON."), true);
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(c -> {
                            WGBosses.config.eventsEnabled = false;
                            WGBosses.config.save();
                            c.getSource().sendSuccess(() -> Component.literal("Scheduled events OFF."), true);
                            return 1;
                        }))
                        .then(Commands.literal("list").executes(c -> {
                            Config cfg = WGBosses.config;
                            StringBuilder sb = new StringBuilder("Events " + (cfg.eventsEnabled ? "ON" : "OFF") + " (time zone "
                                    + (cfg.eventTimezone == null || cfg.eventTimezone.isBlank() ? java.time.ZoneId.systemDefault().toString() : cfg.eventTimezone) + "): ");
                            if (cfg.events.isEmpty()) sb.append("none");
                            for (Config.Event e : cfg.events) {
                                sb.append("\n- ").append(e.name).append(": ").append(e.day).append(" ")
                                        .append(String.format("%02d:%02d", e.hour, e.minute)).append(" ").append(e.tier)
                                        .append(e.arena == null || e.arena.isBlank() ? "" : " @" + e.arena).append(e.enabled ? "" : " (disabled)");
                            }
                            String out = sb.toString();
                            c.getSource().sendSuccess(() -> Component.literal(out), false);
                            return 1;
                        })))
                .then(Commands.literal("spawntest").requires(Cmd::admin)
                        .executes(c -> spawn(c, null, "random", true))
                        .then(Commands.argument("mob", ResourceArgument.resource(ctx, Registries.ENTITY_TYPE))
                                .executes(c -> spawn(c, mob(c), "random", true))
                                .then(Commands.argument("tier", StringArgumentType.word()).suggests(TIERS)
                                        .executes(c -> spawn(c, mob(c), StringArgumentType.getString(c, "tier"), true)))))
                .then(Commands.literal("debug").requires(Cmd::admin).executes(c -> {
                    List<Boss> l = WGBosses.MANAGER.all();
                    if (l.isEmpty()) { c.getSource().sendFailure(Component.literal("No boss is active.")); return 0; }
                    for (Boss b : l) {
                        for (String s : b.debugLines()) {
                            WGBosses.LOG.info("[debug] {}", s);
                            c.getSource().sendSuccess(() -> Component.literal(s), false);
                        }
                    }
                    return 1;
                }))
                .then(Commands.literal("next").requires(Cmd::admin)
                        .then(Commands.argument("minutes", IntegerArgumentType.integer(0, 1440)).executes(c -> {
                            int mins = IntegerArgumentType.getInteger(c, "minutes");
                            WGBosses.MANAGER.setNext(mins);
                            c.getSource().sendSuccess(() -> Component.literal("Next boss in " + mins + " min."), true);
                            return 1;
                        })))
                .then(Commands.literal("pause").requires(Cmd::admin).executes(c -> {
                    WGBosses.MANAGER.pause(true);
                    c.getSource().sendSuccess(() -> Component.literal("Spawn timer paused."), true);
                    return 1;
                }))
                .then(Commands.literal("resume").requires(Cmd::admin).executes(c -> {
                    WGBosses.MANAGER.pause(false);
                    c.getSource().sendSuccess(() -> Component.literal("Spawn timer resumed."), true);
                    return 1;
                }))
                .then(Commands.literal("skip").requires(Cmd::admin).executes(c -> {
                    WGBosses.MANAGER.skip();
                    c.getSource().sendSuccess(() -> Component.literal("Rerolled the next boss event and arena."), true);
                    return 1;
                }))
                .then(Commands.literal("kill").requires(Cmd::admin).executes(c -> {
                    boolean ok = WGBosses.MANAGER.adminKill();
                    if (ok) c.getSource().sendSuccess(() -> Component.literal("Boss removed (no drops)."), true);
                    else c.getSource().sendFailure(Component.literal("No boss is active."));
                    return ok ? 1 : 0;
                }))
                .then(Commands.literal("arena").requires(Cmd::admin)
                        .then(Commands.literal("set")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                                        .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                                .then(Commands.argument("radius", IntegerArgumentType.integer(5, 1000)).executes(c -> {
                                                                    Config cfg = WGBosses.config;
                                                                    String name = StringArgumentType.getString(c, "name");
                                                                    Config.Arena a = cfg.arenaByName(name);
                                                                    if (a == null) { a = new Config.Arena(); a.name = name; cfg.arenas.add(a); }
                                                                    a.x = DoubleArgumentType.getDouble(c, "x");
                                                                    a.y = DoubleArgumentType.getDouble(c, "y");
                                                                    a.z = DoubleArgumentType.getDouble(c, "z");
                                                                    a.radius = IntegerArgumentType.getInteger(c, "radius");
                                                                    a.dimension = c.getSource().getLevel().dimension().identifier().toString();
                                                                    cfg.arenaEnabled = true;
                                                                    cfg.save();
                                                                    String msg = "Arena '" + name + "' saved (" + Math.round(a.x) + " " + Math.round(a.y) + " "
                                                                            + Math.round(a.z) + ", radius " + Math.round(a.radius) + "). Arenas: " + cfg.arenas.size() + ", arena mode ON.";
                                                                    c.getSource().sendSuccess(() -> Component.literal(msg), true);
                                                                    return 1;
                                                                })))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
                                    Config cfg = WGBosses.config;
                                    String name = StringArgumentType.getString(c, "name");
                                    Config.Arena a = cfg.arenaByName(name);
                                    if (a == null) { c.getSource().sendFailure(Component.literal("No arena named " + name)); return 0; }
                                    cfg.arenas.remove(a);
                                    cfg.save();
                                    c.getSource().sendSuccess(() -> Component.literal("Arena '" + name + "' removed. Arenas left: " + cfg.arenas.size()), true);
                                    return 1;
                                })))
                        .then(Commands.literal("on").executes(c -> {
                            WGBosses.config.arenaEnabled = true;
                            WGBosses.config.save();
                            c.getSource().sendSuccess(() -> Component.literal("Arena mode ON (bosses spawn in a random arena)."), true);
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(c -> {
                            WGBosses.config.arenaEnabled = false;
                            WGBosses.config.save();
                            c.getSource().sendSuccess(() -> Component.literal("Arena mode OFF (bosses spawn anywhere)."), true);
                            return 1;
                        }))
                        .then(Commands.literal("list").executes(c -> {
                            Config cfg = WGBosses.config;
                            StringBuilder sb = new StringBuilder("Arena mode " + (cfg.arenaEnabled ? "ON" : "OFF") + ". Arenas: ");
                            if (cfg.arenas.isEmpty()) sb.append("none");
                            for (Config.Arena a : cfg.arenas) {
                                sb.append("\n- ").append(a.name).append(" ").append(a.dimension).append(" ").append(Math.round(a.x)).append(" ")
                                        .append(Math.round(a.y)).append(" ").append(Math.round(a.z)).append(" r").append(Math.round(a.radius));
                            }
                            String out = sb.toString();
                            c.getSource().sendSuccess(() -> Component.literal(out), false);
                            return 1;
                        })))
                .then(Commands.literal("toggle").requires(Cmd::admin).executes(c -> {
                    Config cfg = WGBosses.config;
                    cfg.autoSpawn = !cfg.autoSpawn;
                    cfg.save();
                    if (cfg.autoSpawn) WGBosses.MANAGER.scheduleNext();
                    String s = cfg.autoSpawn ? "Automatic boss spawns are now ON (new timer started)." : "Automatic boss spawns are now OFF.";
                    c.getSource().sendSuccess(() -> Component.literal(s), true);
                    return 1;
                }))
                .then(Commands.literal("bloodmoon").requires(Cmd::admin).executes(Cmd::bloodMoon))
                .then(Commands.literal("reload").requires(Cmd::admin).executes(c -> {
                    WGBosses.reloadAll();
                    WGBosses.MANAGER.checkItems();
                    c.getSource().sendSuccess(() -> Component.literal("WGBosses config reloaded."), true);
                    return 1;
                }))
                .then(Commands.literal("flyers").requires(Cmd::admin).executes(c -> {
                    ServerPlayer p = c.getSource().getPlayer();
                    ServerLevel lvl = p != null ? (ServerLevel) p.level() : c.getSource().getServer().overworld();
                    List<String> l = WGBosses.MANAGER.flyerIds(lvl);
                    String s = l.isEmpty() ? "No flying mobs detected in the boss pool."
                            : l.size() + " flying mobs: " + String.join(", ", l.subList(0, Math.min(40, l.size())));
                    c.getSource().sendSuccess(() -> Component.literal(s), false);
                    return 1;
                }))
                .then(Commands.literal("tp")
                        .then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(c -> {
                            ServerPlayer p = c.getSource().getPlayer();
                            if (p == null) return 0;
                            String err = WGBosses.MANAGER.teleport(p, IntegerArgumentType.getInteger(c, "id"));
                            if (err != null) { c.getSource().sendFailure(Component.literal(err)); return 0; }
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
                        String s = !WGBosses.config.autoSpawn ? "Automatic spawns are OFF (/wgboss toggle)." : t < 0 ? "A boss is not scheduled right now." : "No boss active. Next one in about " + (t / 1200 + 1) + " min.";
                        c.getSource().sendSuccess(() -> Component.literal(s), false);
                    } else {
                        String s = "[" + b.tierName() + "] " + b.baseName + " - " + Math.round(100 * b.hp / b.maxHp) + "% HP";
                        c.getSource().sendSuccess(() -> Component.literal(s), false);
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

    private static int addEvent(CommandContext<CommandSourceStack> c, String arenaName) {
        Config cfg = WGBosses.config;
        String name = StringArgumentType.getString(c, "name");
        String day = StringArgumentType.getString(c, "day").toUpperCase(Locale.ROOT);
        String tier = StringArgumentType.getString(c, "tier").toLowerCase(Locale.ROOT);
        List<String> days = List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY", "DAILY");
        if (!days.contains(day)) { c.getSource().sendFailure(Component.literal("Day must be monday..sunday or daily.")); return 0; }
        if (cfg.tierIndex(tier) < 0) { c.getSource().sendFailure(Component.literal("Unknown tier: " + tier)); return 0; }
        if (!arenaName.isEmpty() && cfg.arenaByName(arenaName) == null) { c.getSource().sendFailure(Component.literal("Unknown arena: " + arenaName + " (see /wgboss arena list)")); return 0; }
        cfg.events.removeIf(e -> e.name.equalsIgnoreCase(name));
        Config.Event e = new Config.Event();
        e.name = name;
        e.day = day;
        e.hour = IntegerArgumentType.getInteger(c, "hour");
        e.minute = IntegerArgumentType.getInteger(c, "minute");
        e.tier = tier;
        e.arena = arenaName;
        cfg.events.add(e);
        cfg.eventsEnabled = true;
        cfg.save();
        String msg = "Event '" + name + "' saved: " + day + " " + String.format("%02d:%02d", e.hour, e.minute) + " " + tier
                + (arenaName.isEmpty() ? "" : " in arena " + arenaName) + " (server time zone " + java.time.ZoneId.systemDefault() + ").";
        c.getSource().sendSuccess(() -> Component.literal(msg), true);
        return 1;
    }

    private static int spawn(CommandContext<CommandSourceStack> c, EntityType<?> type, String tierName) {
        return spawn(c, type, tierName, false);
    }

    private static int spawn(CommandContext<CommandSourceStack> c, EntityType<?> type, String tierName, boolean test) {
        ServerPlayer p = anchor(c);
        if (p == null) { c.getSource().sendFailure(Component.literal("No player online to spawn near.")); return 0; }
        int tier = -1;
        if (!tierName.equalsIgnoreCase("random")) {
            tier = WGBosses.config.tierIndex(tierName);
            if (tier < 0) { c.getSource().sendFailure(Component.literal("Unknown tier: " + tierName)); return 0; }
        }
        ServerLevel lvl = (ServerLevel) p.level();
        boolean ok = WGBosses.MANAGER.adminSpawn(lvl, p, type, tier, test);
        if (!ok) { c.getSource().sendFailure(Component.literal("Could not spawn that boss (see server log).")); return 0; }
        return 1;
    }

    private static int bloodMoon(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = anchor(c);
        if (p == null) { c.getSource().sendFailure(Component.literal("No player online.")); return 0; }
        return WGBosses.MANAGER.adminBloodMoon((ServerLevel) p.level(), p) ? 1 : 0;
    }

    private static int top(CommandContext<CommandSourceStack> c, String stat) {
        List<Map.Entry<String, Double>> l = WGBosses.stats.top(stat, 5);
        String label = switch (stat) {
            case "damage" -> "Top damage";
            case "calamity" -> "Calamity kills";
            case "highest" -> "Highest tier killed";
            case "mvp" -> "MVP (most damage) wins";
            case "streak" -> "Best boss kill streak";
            default -> "Top boss kills";
        };
        c.getSource().sendSuccess(() -> Component.literal("== " + label + " ==").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
        if (l.isEmpty()) {
            c.getSource().sendSuccess(() -> Component.literal("Nothing recorded yet.").withStyle(ChatFormatting.GRAY), false);
            return 1;
        }
        int i = 1;
        for (Map.Entry<String, Double> e : l) {
            String val;
            if (stat.equals("highest")) val = WGBosses.config.tiers.get(Math.max(0, (int) Math.round(e.getValue()) - 1)).name;
            else if (stat.equals("damage")) val = String.valueOf(Math.round(e.getValue()));
            else val = String.valueOf(Math.round(e.getValue()));
            String line = "#" + i + " " + e.getKey() + " - " + val;
            c.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.YELLOW), false);
            i++;
        }
        return 1;
    }
}
