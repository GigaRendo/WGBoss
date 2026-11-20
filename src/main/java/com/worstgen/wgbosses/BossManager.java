package com.worstgen.wgbosses;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.WaterBoundPathNavigation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public final class BossManager {
    public static final String BOSS_TAG = "wgbosses_boss";
    public static final String MINION_TAG = "wgbosses_minion";
    private static final List<String> PERSONALITIES = List.of("ego", "light", "coward", "hype", "genz");

    public static final class Spec {
        public int tier;
        public boolean calamity, rainbow, blood;
        public String abnormal = "";
        public String personality;
        public List<String> affixes = new ArrayList<>();
        public String arena;
    }

    private final Set<String> persisted = new HashSet<>();

    private record Task(long at, Runnable run) {}
    private final List<Task> tasks = new ArrayList<>();

    /** Run something N ticks from now (used to space out the boss-death messages). */
    public void later(int ticks, Runnable r) { tasks.add(new Task(tick + ticks, r)); }

    public static EntityType<?> byId(String id) {
        var i = net.minecraft.resources.Identifier.tryParse(id);
        if (i == null) return null;
        return BuiltInRegistries.ENTITY_TYPE.getOptional(i).orElse(null);
    }

    private java.nio.file.Path persistFile() { return Config.dir().resolve("active_uuids.txt"); }

    public void loadPersisted() {
        try {
            java.nio.file.Path f = persistFile();
            if (java.nio.file.Files.exists(f)) {
                List<String> lines = java.nio.file.Files.readAllLines(f);
                if (lines.size() > 3000) lines = lines.subList(lines.size() - 1000, lines.size());
                persisted.addAll(lines);
            }
        } catch (Exception ignored) { }
    }

    /** Remember boss/minion UUIDs so leftovers from crashes or chunk unloads get removed when they load again. */
    public void remember(Entity e) {
        String u = e.getUUID().toString();
        if (!persisted.add(u)) return;
        try {
            java.nio.file.Files.writeString(persistFile(), u + "\n",
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) { }
    }

    public MinecraftServer server;
    public long tick;
    public final List<Boss> bosses = new ArrayList<>();
    public final Map<UUID, Boss> byMob = new HashMap<>();
    public final Map<UUID, Boss> minionOwner = new HashMap<>();

    private final List<Entity> toDiscard = new ArrayList<>();
    private final List<Entity> orphanCheck = new ArrayList<>();
    private final Set<Integer> warned = new HashSet<>();
    private List<EntityType<?>> pool;
    private long nextSpawn = -1;
    private boolean nextBlood;
    private int endedTier = -1, lowStreak;
    private Config.Arena nextArena;
    private boolean paused, testSpawn, itemsChecked;
    private java.time.ZonedDateTime lastCheck;
    private final Deque<Config.Event> pendingEvents = new ArrayDeque<>();
    private long pendingSince;
    private final Map<UUID, List<String>> noteGot = new HashMap<>();
    private final Map<UUID, List<String>> uniqueGot = new HashMap<>();
    private int nextId = 1;

    private Config cfg() { return WGBosses.config; }

    // ------------------------------------------------------------------ lifecycle
    public void init(MinecraftServer s) {
        server = s;
        tick = 0;
        nextSpawn = -1;
        pool = null;
        bosses.clear(); byMob.clear(); minionOwner.clear(); tasks.clear();
    }

    public void shutdown() {
        killAllSilently();
    }

    public void killAllSilently() {
        for (Boss b : new ArrayList<>(bosses)) b.discardAll();
        bosses.clear(); byMob.clear(); minionOwner.clear();
    }

    public void scheduleNext() {
        Config c = cfg();
        int mn = c.spawnMinMinutes, mx = c.spawnMaxMinutes;
        if (c.tierCooldowns && endedTier >= 0 && endedTier < c.tiers.size() && c.tiers.get(endedTier).cooldownMaxMinutes > 0) {
            mn = c.tiers.get(endedTier).cooldownMinMinutes;
            mx = Math.max(mn, c.tiers.get(endedTier).cooldownMaxMinutes);
        }
        endedTier = -1;
        nextArena = pickArena();
        nextSpawn = tick + Util.rint(mn * 1200, mx * 1200);
        nextBlood = Util.chance(c.bloodMoonChance);
        warned.clear();
    }

    public net.minecraft.world.InteractionResult onUseBlock(net.minecraft.world.entity.player.Player pl, net.minecraft.world.InteractionHand hand) {
        Config c = cfg();
        if (!c.blockPlacingInArenaDuringFight || bosses.isEmpty() || !(pl instanceof ServerPlayer sp) || sp.isCreative()) {
            return net.minecraft.world.InteractionResult.PASS;
        }
        if (!(sp.getItemInHand(hand).getItem() instanceof net.minecraft.world.item.BlockItem)) return net.minecraft.world.InteractionResult.PASS;
        for (Boss b : bosses) {
            if (b.arena == null || b.level != sp.level()) continue;
            double dx = sp.getX() - b.arena.x, dz = sp.getZ() - b.arena.z;
            if (dx * dx + dz * dz <= b.arena.radius * b.arena.radius) {
                sp.sendSystemMessage(Component.literal("You can't place blocks during a boss fight.").withStyle(ChatFormatting.RED));
                return net.minecraft.world.InteractionResult.FAIL;
            }
        }
        return net.minecraft.world.InteractionResult.PASS;
    }

    private void chk(List<String> out, String id) {
        if (id == null || id.isBlank()) return;
        if (Rewards.itemOrNull(id) == null && !out.contains(id)) out.add(id);
    }

    /** Logs reward items / arenas / events that point at something that does not exist. */
    public void checkItems() {
        Config c = cfg();
        List<String> missing = new ArrayList<>();
        chk(missing, c.shardItem);
        if (c.vitalEmberEnabled) chk(missing, c.vitalEmberItem);
        for (Map<String, List<Config.RewardEntry>> map : List.of(c.rewards, c.perPlayerRewards, c.topDamageBonusRewards)) {
            for (List<Config.RewardEntry> l : map.values()) if (l != null) for (Config.RewardEntry e : l) if (e != null) chk(missing, e.item);
        }
        if (missing.isEmpty()) WGBosses.LOG.info("WGBosses: all reward items were found.");
        for (String s : missing) WGBosses.LOG.warn("WGBosses: reward item not found (it will be skipped): {}", s);
        for (Config.Arena a : c.arenas) {
            if (arenaLevel(a) == null) WGBosses.LOG.warn("WGBosses: arena '{}' uses dimension '{}' which was not found", a.name, a.dimension);
        }
        for (Config.Event e : c.events) {
            if (c.tierIndex(e.tier) < 0) WGBosses.LOG.warn("WGBosses: event '{}' has unknown tier '{}'", e.name, e.tier);
            if (e.arena != null && !e.arena.isBlank() && c.arenaByName(e.arena) == null) WGBosses.LOG.warn("WGBosses: event '{}' uses unknown arena '{}'", e.name, e.arena);
        }
    }

    private java.time.ZoneId zone() {
        try {
            String z = cfg().eventTimezone;
            return z == null || z.isBlank() ? java.time.ZoneId.systemDefault() : java.time.ZoneId.of(z.trim());
        } catch (Throwable t) {
            return java.time.ZoneId.systemDefault();
        }
    }

    private static boolean dayMatches(String day, java.time.DayOfWeek dow) {
        if (day == null) return true;
        String d = day.trim().toUpperCase(Locale.ROOT);
        if (d.equals("DAILY") || d.equals("EVERYDAY") || d.isEmpty()) return true;
        return d.length() >= 3 && dow.name().startsWith(d);
    }

    private void tickEvents() {
        Config c = cfg();
        if (!c.eventsEnabled || c.events.isEmpty()) { lastCheck = null; pendingEvents.clear(); return; }
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(zone());
        if (lastCheck == null) { lastCheck = now; return; }
        for (Config.Event e : new ArrayList<>(c.events)) {
            if (e == null || !e.enabled) continue;
            java.time.ZonedDateTime cand = now.toLocalDate().atTime(Math.floorMod(e.hour, 24), Math.floorMod(e.minute, 60)).atZone(now.getZone());
            if (!dayMatches(e.day, cand.getDayOfWeek())) continue;
            if (e.warnMinutes > 0) {
                java.time.ZonedDateTime w = cand.minusMinutes(e.warnMinutes);
                if (w.isAfter(lastCheck) && !w.isAfter(now)) {
                    Util.broadcast(server, prefix().append(Component.literal("EVENT: a guaranteed " + e.tier + " boss (" + e.name + ") spawns in "
                            + e.warnMinutes + " minutes" + (e.arena == null || e.arena.isBlank() ? "" : " in arena " + e.arena) + "!")
                            .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
                }
            }
            if (cand.isAfter(lastCheck) && !cand.isAfter(now)) { pendingEvents.add(e); pendingSince = tick; }
        }
        lastCheck = now;
        if (!pendingEvents.isEmpty()) {
            if (bosses.isEmpty()) runEvent(pendingEvents.poll());
            else if (tick - pendingSince > 12000) pendingEvents.clear();
        }
    }

    /** Spawns the event's guaranteed boss right now. */
    public boolean runEvent(Config.Event e) {
        Config c = cfg();
        int tier = c.tierIndex(e.tier);
        if (tier < 0) { WGBosses.LOG.warn("Event '{}' has unknown tier '{}'", e.name, e.tier); return false; }
        EntityType<?> type = null;
        if (e.mob != null && !e.mob.isBlank()) {
            net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.tryParse(e.mob.trim());
            if (id != null) {
                Optional<EntityType<?>> o = BuiltInRegistries.ENTITY_TYPE.getOptional(id);
                if (o.isPresent()) type = o.get();
            }
        }
        Config.Arena a = e.arena == null || e.arena.isBlank() ? null : c.arenaByName(e.arena);
        ServerLevel lvl = a == null ? null : arenaLevel(a);
        Vec3 pos;
        if (lvl != null) {
            pos = arenaSpawn(lvl, a);
        } else {
            a = null;
            List<ServerPlayer> cands = new ArrayList<>();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (p.level() instanceof ServerLevel sl && c.allowedDimensions.contains(sl.dimension().identifier().toString())) cands.add(p);
            }
            if (cands.isEmpty()) return false;
            ServerPlayer p = Util.pick(cands);
            lvl = (ServerLevel) p.level();
            pos = spawnPoint(p, lvl, c.spawnMinDistance, c.spawnMaxDistance);
        }
        boolean ok = spawnOne(lvl, type, tier, pos, a);
        if (ok) nextSpawn = Long.MAX_VALUE;
        return ok;
    }

    public boolean paused() { return paused; }
    public void pause(boolean p) { paused = p; }
    public void setNext(int minutes) { nextSpawn = tick + minutes * 1200L; warned.clear(); nextArena = pickArena(); }
    public void skip() { nextBlood = Util.chance(cfg().bloodMoonChance); nextArena = pickArena(); }
    public String nextArenaName() { return nextArena != null ? nextArena.name : "-"; }
    public List<Boss> all() { return new ArrayList<>(bosses); }

    public long ticksUntilNext() { return nextSpawn == Long.MAX_VALUE ? -1 : nextSpawn - tick; }

    // ------------------------------------------------------------------ tick
    public void tick(MinecraftServer s) {
        if (server == null) server = s;
        tick++;
        Config c = cfg();

        for (Entity e : orphanCheck) {
            if (!e.isRemoved() && !byMob.containsKey(e.getUUID()) && !minionOwner.containsKey(e.getUUID())) e.discard();
        }
        orphanCheck.clear();
        for (Entity e : toDiscard) {
            if (!e.isRemoved() && e.level() instanceof ServerLevel sl) {
                sl.sendParticles(ParticleTypes.POOF, e.getX(), e.getY() + 0.5, e.getZ(), 10, 0.3, 0.3, 0.3, 0.05);
            }
            e.discard();
        }
        toDiscard.clear();

        if (!tasks.isEmpty()) {
            for (Task t : new ArrayList<>(tasks)) {
                if (tick >= t.at()) {
                    tasks.remove(t);
                    try { t.run().run(); } catch (Throwable ex) { WGBosses.LOG.error("Delayed task failed", ex); }
                }
            }
        }

        if (nextSpawn < 0) scheduleNext();
        if (paused && nextSpawn != Long.MAX_VALUE) nextSpawn++;
        if (!itemsChecked && tick > 100) { itemsChecked = true; checkItems(); }
        if (tick % 20 == 0) {
            try { tickEvents(); } catch (Throwable t) { WGBosses.LOG.error("Event check failed", t); }
        }

        for (Boss b : new ArrayList<>(bosses)) {
            if (b.dying) continue;
            try { b.tick(tick); }
            catch (Throwable t) { WGBosses.LOG.error("Boss tick failed, removing boss", t); b.gone = true; }
            if (b.gone && !b.dying) {
                Util.broadcast(server, prefix().append(Component.literal("The boss vanished.").withStyle(ChatFormatting.GRAY)));
                b.discardAll();
                removeBoss(b);
            }
        }

        if (bosses.isEmpty() && tick % 20 == 0 && c.autoSpawn) {
            int online = server.getPlayerList().getPlayerCount();
            long remaining = nextSpawn - tick;
            if (online >= c.minPlayers) {
                for (int m : c.warningMinutesBefore) {
                    if (!warned.contains(m) && remaining <= m * 1200L && remaining > 0) { warned.add(m); warn(m); }
                }
                if (remaining <= 0) spawnAuto();
            } else if (remaining <= 0) {
                nextSpawn = tick + 1200;
            }
        }
    }

    private MutableComponent prefix() {
        return Component.literal("[WGBoss] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
    }

    private void warn(int minutes) {
        boolean blood = nextBlood;
        Component title = Component.literal(blood ? "THE BLOOD MOON APPROACHES" : "!! BOSS INCOMING !!")
                .withStyle(blood ? ChatFormatting.DARK_RED : ChatFormatting.GOLD, ChatFormatting.BOLD);
        String when = (minutes == 1 ? "1 minute" : minutes + " minutes") + (nextArena != null ? " in arena " + nextArena.name : "");
        Component sub = Component.literal((blood ? "Something terrible spawns in " : "A boss spawns in ") + when + " - gear up!")
                .withStyle(blood ? ChatFormatting.RED : ChatFormatting.YELLOW);
        Util.titleAll(server, title, sub, 10, 80, 20);
        Util.soundAll(server, SoundEvents.WITHER_SPAWN, 0.5f, 1.5f);
        Util.broadcast(server, prefix().append(Component.literal(
                (blood ? "The blood moon rises in " : "A boss will appear in ") + when + "!").withStyle(blood ? ChatFormatting.RED : ChatFormatting.YELLOW)));
    }

    // ------------------------------------------------------------------ mob pool
    private boolean blacklisted(String id) {
        for (String b : cfg().mobBlacklist) {
            if (b.equals(id)) return true;
            if (b.endsWith(":*") && id.startsWith(b.substring(0, b.length() - 1))) return true;
        }
        return false;
    }

    private boolean eligible(EntityType<?> t, ServerLevel lvl) {
        try {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(t).toString();
            if (blacklisted(id) || !t.canSummon()) return false;
            MobCategory cat = t.getCategory();
            if (cat == MobCategory.MISC || cat == MobCategory.WATER_CREATURE || cat == MobCategory.WATER_AMBIENT
                    || cat == MobCategory.UNDERGROUND_WATER_CREATURE || cat == MobCategory.AXOLOTLS) return false;
            Entity e = t.create(lvl, EntitySpawnReason.COMMAND);
            if (!(e instanceof Mob m)) return false;
            boolean ok = !(m.getNavigation() instanceof FlyingPathNavigation)
                    && !(m.getNavigation() instanceof WaterBoundPathNavigation)
                    && !(m.getMoveControl() instanceof FlyingMoveControl);
            e.discard();
            return ok;
        } catch (Throwable ex) {
            return false;
        }
    }

    private void ensurePool(ServerLevel lvl) {
        if (pool == null || pool.isEmpty()) {
            pool = new ArrayList<>();
            for (EntityType<?> t : BuiltInRegistries.ENTITY_TYPE) if (eligible(t, lvl)) pool.add(t);
            WGBosses.LOG.info("WGBosses mob pool: {} mob types", pool.size());
            WGBosses.LOG.info("WGBosses flying mobs in pool: {}", flyerIds(lvl));
        }
    }

    /** Pool mobs that will hover/fly as bosses (by id keyword/config). */
    public List<String> flyerIds(ServerLevel lvl) {
        ensurePool(lvl);
        List<String> l = new ArrayList<>();
        for (EntityType<?> t : pool) {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(t).toString();
            if (Boss.isFlyerId(id)) l.add(id);
        }
        return l;
    }

    public EntityType<?> randomMob(ServerLevel lvl) {
        ensurePool(lvl);
        return pool.isEmpty() ? byId("minecraft:zombie") : Util.pick(pool);
    }

    public void resetPool() { pool = null; }

    // ------------------------------------------------------------------ names
    private String fill(String tpl, String player, String mob) {
        return tpl.replace("{player}", player).replace("{mob}", mob);
    }

    private String pickName(EntityType<?> type) {
        String mobName = type.getDescription().getString();
        List<String> online = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) online.add(p.getName().getString());
        List<String> offline = new ArrayList<>();
        for (String n : WGBosses.stats.knownNames()) if (!online.contains(n)) offline.add(n);
        double r = Util.rdbl(0, 1);
        MessageBank bank = WGBosses.bank;
        if (r < 0.45 && !online.isEmpty()) return fill(Util.pick(bank.playerTemplates), Util.pick(online), mobName.toLowerCase(Locale.ROOT));
        if (r < 0.70 && !offline.isEmpty()) return fill(Util.pick(bank.playerTemplates), Util.pick(offline), mobName.toLowerCase(Locale.ROOT));
        return fill(Util.pick(bank.coolNames), "", mobName);
    }

    // ------------------------------------------------------------------ spawning
    public Spec rollSpec(int forced, boolean blood, int avoid) {
        Config c = cfg();
        Spec s = new Spec();
        int cal = c.calamityIndex();
        if (forced >= 0) s.tier = forced;
        else if (!blood && Util.chance(c.calamityChance)) s.tier = cal;
        else {
            int max = blood ? Math.min(c.bloodMoonMaxTierIndex, cal - 1) : cal - 1;
            int min = (forced < 0 && !blood && c.pityEnabled && lowStreak >= c.pityStreak) ? Math.min(c.pityMinTier, max) : 0;
            int total = 0;
            for (int i = min; i <= max; i++) if (i != avoid) total += c.tiers.get(i).weight;
            int roll = total <= 0 ? 0 : Util.rint(1, total);
            s.tier = min;
            for (int i = min; i <= max; i++) {
                if (i == avoid) continue;
                roll -= c.tiers.get(i).weight;
                if (roll <= 0) { s.tier = i; break; }
            }
        }
        s.calamity = s.tier == cal;
        s.blood = blood;
        s.rainbow = !s.calamity && Util.chance(c.rainbowChance);
        if (s.rainbow) s.abnormal = Util.pick(List.of("SPEED", "HEALTH", "DAMAGE", "SIZE"));
        s.personality = Util.pick(PERSONALITIES);
        if (c.affixesEnabled && s.tier >= c.affixMinTier && !c.affixPool.isEmpty() && Util.chance(c.affixChance)) {
            List<String> pl = new ArrayList<>(c.affixPool);
            s.affixes.add(pl.remove(Util.rint(0, pl.size() - 1)));
            if (s.tier >= c.affixSecondMinTier && !pl.isEmpty() && Util.chance(c.affixSecondChance)) s.affixes.add(pl.remove(Util.rint(0, pl.size() - 1)));
        }
        return s;
    }

    public Boss spawnBoss(ServerLevel lvl, EntityType<?> type, Vec3 pos, Spec spec) {
        try {
            Entity e = type.create(lvl, EntitySpawnReason.COMMAND);
            if (!(e instanceof Mob m)) return null;
            m.setPos(pos.x, pos.y, pos.z);
            try { m.finalizeSpawn(lvl, lvl.getCurrentDifficultyAt(m.blockPosition()), EntitySpawnReason.COMMAND, null); }
            catch (Throwable ignored) { }
            remember(m);
            m.setPersistenceRequired();
            Boss b = new Boss(this, nextId++, lvl, m, spec, pickName(type), server.getPlayerList().getPlayerCount());
            lvl.addFreshEntity(m);
            b.resetDespawn(tick);
            bosses.add(b);
            byMob.put(m.getUUID(), b);
            b.noRewards = testSpawn;
            testSpawn = false;
            return b;
        } catch (Throwable t) {
            WGBosses.LOG.error("Failed to spawn boss", t);
            return null;
        }
    }

    private Vec3 spawnPoint(ServerPlayer p, ServerLevel lvl, double min, double max) {
        Vec3 pos = Util.findSafe(lvl, p.getX(), p.getY(), p.getZ(), min, max);
        return pos != null ? pos : new Vec3(p.getX() + min, p.getY() + 1, p.getZ());
    }

    private Config.Arena pickArena() {
        Config c = cfg();
        if (!c.arenaEnabled || c.arenas.isEmpty()) return null;
        return Util.pick(c.arenas);
    }

    private ServerLevel arenaLevel(Config.Arena a) {
        for (ServerLevel l : server.getAllLevels()) {
            if (l.dimension().identifier().toString().equals(a.dimension)) return l;
        }
        return null;
    }

    private Vec3 arenaSpawn(ServerLevel lvl, Config.Arena a) {
        Config c = cfg();
        Vec3 p = Util.findSafe(lvl, a.x, a.y, a.z, 0, a.radius * 0.5);
        if (p == null || Math.abs(p.y - a.y) > c.arenaYRange + 1) p = new Vec3(a.x, a.y, a.z);
        return p;
    }

    private void spawnAuto() {
        Config c = cfg();
        if (c.arenaEnabled && !c.arenas.isEmpty()) {
            Config.Arena ar = (nextArena != null && c.arenas.contains(nextArena)) ? nextArena : pickArena();
            ServerLevel al = ar == null ? null : arenaLevel(ar);
            if (al != null) {
                Vec3 ap = arenaSpawn(al, ar);
                boolean aok = nextBlood ? spawnBloodMoon(al, ap, ar) : spawnOne(al, null, -1, ap, ar);
                nextSpawn = aok ? Long.MAX_VALUE : tick + 1200;
                return;
            }
        }
        List<ServerPlayer> cands = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level() instanceof ServerLevel sl && c.allowedDimensions.contains(sl.dimension().identifier().toString())) cands.add(p);
        }
        if (cands.isEmpty()) { nextSpawn = tick + 1200; return; }
        ServerLevel lvl = null;
        Vec3 pos = null;
        for (int i = 0; i < 8 && pos == null; i++) {
            ServerPlayer p = Util.pick(cands);
            ServerLevel pl = (ServerLevel) p.level();
            Vec3 q = spawnPoint(p, pl, c.spawnMinDistance, c.spawnMaxDistance);
            if (!inZone(pl, q)) { pos = q; lvl = pl; }
        }
        if (pos == null) { nextSpawn = tick + 1200; return; }
        boolean ok = nextBlood ? spawnBloodMoon(lvl, pos) : spawnOne(lvl, null, -1, pos);
        if (ok) nextSpawn = Long.MAX_VALUE; else nextSpawn = tick + 1200;
    }

    private boolean inZone(ServerLevel lvl, Vec3 pos) {
        String dim = lvl.dimension().identifier().toString();
        for (Config.Zone z : cfg().noSpawnZones) {
            if (z.dimension != null && !z.dimension.isEmpty() && !z.dimension.equals(dim)) continue;
            double dx = pos.x - z.x, dz = pos.z - z.z;
            if (dx * dx + dz * dz <= z.radius * z.radius) return true;
        }
        return false;
    }

    private boolean spawnOne(ServerLevel lvl, EntityType<?> type, int tier, Vec3 pos) {
        return spawnOne(lvl, type, tier, pos, null);
    }

    private boolean spawnOne(ServerLevel lvl, EntityType<?> type, int tier, Vec3 pos, Config.Arena arena) {
        EntityType<?> t = type != null ? type : randomMob(lvl);
        Spec sp = rollSpec(tier, false, -1);
        if (arena != null) sp.arena = arena.name;
        Boss b = spawnBoss(lvl, t, pos, sp);
        if (b == null) return false;
        if (tier < 0) lowStreak = b.trueTier <= cfg().pityLowTier ? lowStreak + 1 : 0;
        announce(List.of(b), false);
        say(b, "spawn", null, true);
        return true;
    }

    public boolean spawnBloodMoon(ServerLevel lvl, Vec3 pos) {
        return spawnBloodMoon(lvl, pos, null);
    }

    public boolean spawnBloodMoon(ServerLevel lvl, Vec3 pos, Config.Arena arena) {
        Spec a = rollSpec(-1, true, -1);
        Spec b2 = rollSpec(-1, true, a.tier);
        if (arena != null) { a.arena = arena.name; b2.arena = arena.name; }
        Boss one = spawnBoss(lvl, randomMob(lvl), pos, a);
        if (one == null) return false;
        Vec3 p2 = Util.findSafe(lvl, pos.x, pos.y, pos.z, 5, 8);
        if (p2 == null) p2 = pos.add(6, 0, 0);
        Boss two = spawnBoss(lvl, randomMob(lvl), p2, b2);
        List<Boss> l = new ArrayList<>(List.of(one));
        if (two != null) l.add(two);
        announce(l, true);
        for (Boss b : l) say(b, "spawn", null, true);
        return true;
    }

    /** Admin spawn: kills any existing boss silently first. */
    public boolean adminSpawn(ServerLevel lvl, ServerPlayer near, EntityType<?> type, int tier) {
        return adminSpawn(lvl, near, type, tier, false);
    }

    public boolean adminSpawn(ServerLevel lvl, ServerPlayer near, EntityType<?> type, int tier, boolean test) {
        killAllSilently();
        testSpawn = test;
        Vec3 pos;
        Config.Arena aa = pickArena();
        ServerLevel al = aa != null ? arenaLevel(aa) : null;
        if (al != null) { lvl = al; pos = arenaSpawn(al, aa); } else { pos = spawnPoint(near, lvl, 8, 15); aa = null; }
        boolean ok = spawnOne(lvl, type, tier, pos, aa);
        nextSpawn = ok ? Long.MAX_VALUE : tick + 1200;
        return ok;
    }

    public boolean adminBloodMoon(ServerLevel lvl, ServerPlayer near) {
        killAllSilently();
        boolean ok = spawnBloodMoon(lvl, spawnPoint(near, lvl, 10, 18));
        nextSpawn = ok ? Long.MAX_VALUE : tick + 1200;
        return ok;
    }

    public boolean adminKill() {
        if (bosses.isEmpty()) return false;
        killAllSilently();
        scheduleNext();
        return true;
    }

    private void removeBoss(Boss b) {
        bosses.remove(b);
        byMob.remove(b.mob.getUUID());
        endedTier = Math.max(endedTier, b.trueTier);
        if (bosses.isEmpty()) scheduleNext();
    }

    // ------------------------------------------------------------------ announcements
    private Component tpLink(Boss b, String label) {
        return Component.literal(label).withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN).withBold(true)
                .withClickEvent(new ClickEvent.RunCommand("/wgboss tp " + b.id))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Teleport 10-20 blocks from the boss (one use)"))));
    }

    private void announce(List<Boss> bs, boolean blood) {
        Util.soundAll(server, SoundEvents.WITHER_SPAWN, 0.9f, 1.0f);
        if (blood) {
            Util.titleAll(server, Component.literal("THE BLOOD MOON RISES").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD),
                    Component.literal("Two bosses have appeared!!").withStyle(ChatFormatting.RED), 10, 100, 30);
            Util.broadcast(server, prefix().append(Component.literal("THE BLOOD MOON RISES!! Two bosses at once!").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)));
        } else {
            Boss b = bs.get(0);
            Util.titleAll(server, Component.literal("A BOSS HAS SPAWNED").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                    Component.literal("[" + b.tierName() + "] ").withStyle(b.chatColor()).append(Component.literal(b.baseName).withStyle(ChatFormatting.WHITE)),
                    10, 80, 20);
        }
        int n = 1;
        for (Boss b : bs) {
            String dim = b.level.dimension().identifier().getPath();
            long ax = Math.round(b.mob.getX() / 10.0) * 10, ay = Math.round(b.mob.getY() / 10.0) * 10, az = Math.round(b.mob.getZ() / 10.0) * 10;
            MutableComponent m = prefix();
            m.append(Component.literal("[" + b.tierName() + "] ").withStyle(b.chatColor()));
            m.append(b.displayName());
            m.append(Component.literal(" spawned in the " + dim + " near " + ax + ", " + ay + ", " + az + (b.arena != null ? " [arena " + b.arena.name + "]" : "") + "!  ").withStyle(ChatFormatting.YELLOW));
            if (b.rainbow) m.append(Component.literal("(RAINBOW: abnormal " + b.abnormal.toLowerCase(Locale.ROOT) + ")  ").withStyle(ChatFormatting.LIGHT_PURPLE));
            m.append(tpLink(b, bs.size() > 1 ? "[Click to Teleport #" + n + "]" : "[Click to Teleport]"));
            Util.broadcast(server, m);
            n++;
            Discord.spawn(b);
        }
    }

    public void phaseAnnounce(Boss b) {
        int p = b.phase;
        Component title; Component sub;
        if (b.calamity && p == b.phases) {
            title = Component.literal("THE TRUE FORM REVEALED").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD);
            sub = Component.literal("CALAMITY").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
            Util.broadcast(server, prefix().append(Component.literal("!!! THE TRUE FORM REVEALED: CALAMITY !!!").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)));
        } else if (b.calamity) {
            title = Component.literal("IT TRANSFORMS").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
            sub = b.displayName().copy().append(Component.literal(" is now " + b.tierName() + "!").withStyle(ChatFormatting.WHITE));
            Util.broadcast(server, prefix().append(b.displayName()).append(Component.literal(" transformed into a " + b.tierName() + "!!").withStyle(ChatFormatting.RED)));
        } else {
            title = Component.literal("PHASE " + p).withStyle(b.chatColor(), ChatFormatting.BOLD);
            sub = Component.literal(b.baseName + " grows stronger!").withStyle(ChatFormatting.WHITE);
            Util.broadcast(server, prefix().append(b.displayName()).append(Component.literal(" entered PHASE " + p + "/" + b.phases + "!!").withStyle(ChatFormatting.RED)));
        }
        Util.titleAll(server, title, sub, 5, 50, 15);
        Util.soundAll(server, SoundEvents.ENDER_DRAGON_GROWL, 0.8f, 0.9f);
        say(b, "phase", null, true);
    }

    // ------------------------------------------------------------------ chat
    public void say(Boss b, String event, ServerPlayer p, boolean force) {
        if (!force && tick < b.nextChat) return;
        b.nextChat = tick + cfg().chatCooldownSeconds * 20L;
        String line = WGBosses.bank.line(b.personality, event, cfg().taglishChance);
        if (line == null) return;
        String who;
        if (p != null) who = p.getName().getString();
        else {
            List<ServerPlayer> all = server.getPlayerList().getPlayers();
            who = all.isEmpty() ? "nobody" : all.get(Util.rint(0, all.size() - 1)).getName().getString();
        }
        line = line.replace("{player}", who).replace("{boss}", b.baseName);
        MutableComponent c = Component.literal("<").withStyle(ChatFormatting.GRAY);
        c.append(b.displayName());
        c.append(Component.literal("> ").withStyle(ChatFormatting.GRAY));
        c.append(Component.literal(line).withStyle(ChatFormatting.WHITE));
        Util.broadcast(server, c);
    }

    // ------------------------------------------------------------------ despawn / death
    public void despawn(Boss b) {
        if (b.dying) return;
        b.dying = true;
        say(b, "despawn", null, true);
        Util.broadcast(server, prefix().append(b.displayName()).append(Component.literal(" got bored and left.").withStyle(ChatFormatting.GRAY)));
        b.discardAll();
        removeBoss(b);
    }

    public void onBossKilled(Boss b, ServerPlayer killer) {
        if (b.dying) return;
        b.dying = true;
        ServerLevel lvl = b.level;
        Vec3 pos = b.mob.position();

        List<Map.Entry<UUID, Double>> sorted = new ArrayList<>(b.damage.entrySet());
        sorted.sort((x, y) -> Double.compare(y.getValue(), x.getValue()));
        UUID topId = sorted.isEmpty() ? null : sorted.get(0).getKey();
        ServerPlayer top = topId == null ? null : server.getPlayerList().getPlayer(topId);

        lvl.sendParticles(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y + b.mob.getBbHeight() * 0.5, pos.z, 3, 1, 1, 1, 0);
        lvl.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, pos.x, pos.y + b.mob.getBbHeight() * 0.5, pos.z, 80, 1.5, 1.5, 1.5, 0.3);
        Util.sound(lvl, pos.x, pos.y, pos.z, SoundEvents.TOTEM_USE, 2f, 0.8f);
        Util.soundAll(server, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1f);

        // rewards: split between everyone who fought, top damage dealer gets a bonus
        Rewards.Result rr = Rewards.roll(b);
        Map<UUID, Component> rewardMsgs = b.noRewards ? new LinkedHashMap<>() : distribute(b, sorted, topId, rr);

        // chat, spaced out: defeated -> last words -> top damage -> rankings -> rewards
        List<Component> rankLines = new ArrayList<>();
        int rank = 1;
        for (Map.Entry<UUID, Double> e : sorted) {
            if (rank > 3) break;
            rankLines.add(Component.literal("  #" + rank + " " + nameOf(e.getKey()) + " - " + Math.round(e.getValue()) + " damage").withStyle(ChatFormatting.YELLOW));
            rank++;
        }
        final String topName = topId == null ? null : nameOf(topId);

        Util.broadcast(server, prefix().append(b.displayName()).append(Component.literal(" has been defeated!").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)));
        later(30, () -> say(b, "death", killer, true));
        if (topName != null) {
            later(60, () -> Util.broadcast(server, prefix().append(Component.literal(topName + " dealt the most damage and earns bonus rewards!").withStyle(ChatFormatting.LIGHT_PURPLE))));
        }
        later(80, () -> { for (Component l : rankLines) Util.broadcast(server, l); });
        later(140, () -> {
            for (Map.Entry<UUID, Component> e : rewardMsgs.entrySet()) {
                ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
                if (p != null) p.sendSystemMessage(e.getValue());
            }
        });

        // stats
        Config c = cfg();
        if (!b.noRewards) for (Map.Entry<UUID, Double> e : sorted) {
            Stats.E s = WGBosses.stats.get(e.getKey(), null);
            s.damage += e.getValue();
            if (e.getValue() / b.maxHp * 100.0 >= c.participationPercentForStats) {
                s.kills++;
                s.highest = Math.max(s.highest, b.trueTier);
                if (b.calamity) s.calamity++;
            }
            if (e.getKey().equals(topId)) s.mvp++;
        }
        WGBosses.stats.save();

        if (!b.noRewards) {
            List<String> top3 = new ArrayList<>();
            int rk = 1;
            for (Map.Entry<UUID, Double> e : sorted) {
                if (rk > 3) break;
                top3.add("#" + rk + " " + nameOf(e.getKey()) + " - " + Math.round(e.getValue()) + " damage");
                rk++;
            }
            Discord.kill(b, top3);
            if (c.topKillersAfterKill && b.trueTier >= c.topKillersMinTier) {
                later(Util.rint(200, 400), () -> {
                    List<Map.Entry<String, Double>> tk = WGBosses.stats.top("kills", c.topKillersCount);
                    if (tk.isEmpty()) return;
                    Util.broadcast(server, prefix().append(Component.literal("Top boss killers on the server:").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
                    int r = 1;
                    for (Map.Entry<String, Double> e : tk) {
                        Util.broadcast(server, Component.literal("  #" + r + " " + e.getKey() + " - " + Math.round(e.getValue()) + " kills").withStyle(ChatFormatting.YELLOW));
                        r++;
                    }
                });
            }
        }

        b.discardAll();
        removeBoss(b);
    }

    private final Map<UUID, Map<net.minecraft.world.item.Item, Integer>> got = new HashMap<>();
    private final Map<UUID, Integer> gotXp = new HashMap<>();

    private void give(ServerPlayer p, net.minecraft.world.item.Item item, int count) {
        got.computeIfAbsent(p.getUUID(), k -> new LinkedHashMap<>()).merge(item, count, Integer::sum);
        int max = Math.max(1, new ItemStack(item, 1).getMaxStackSize());
        while (count > 0) {
            int n = Math.min(max, count);
            ItemStack st = new ItemStack(item, n);
            if (!p.getInventory().add(st) && !st.isEmpty()) Block.popResource((ServerLevel) p.level(), p.blockPosition(), st);
            count -= n;
        }
    }

    /** Per-player extras: shards, vital ember, unique rewards. */
    private void extras(Boss b, ServerPlayer p, boolean isTop) {
        Config c = cfg();
        Config.TierDef t = c.tiers.get(b.trueTier);
        double mult = b.rewardMultiplier();
        Stats.E st = WGBosses.stats.get(p.getUUID(), p.getName().getString());
        if (c.streakEnabled) {
            st.streak++;
            st.bestStreak = Math.max(st.bestStreak, st.streak);
            if (st.streak >= 2) {
                double bonus = Math.min(c.streakCapPercent, c.streakStepPercent * (st.streak - 1));
                mult += bonus / 100.0;
                noteGot.computeIfAbsent(p.getUUID(), k -> new ArrayList<>()).add("Streak x" + st.streak + " (+" + Math.round(bonus) + "% shards)");
            }
            if (st.streak >= c.streakAnnounceMin) {
                final String nm = p.getName().getString();
                final int sk = st.streak;
                later(300, () -> Util.broadcast(server, prefix().append(Component.literal(nm + " is on a " + sk + " boss kill streak!").withStyle(ChatFormatting.GOLD))));
            }
        }
        if (c.dailyBonusEnabled && c.dailyBonusShards > 0) {
            String today = java.time.LocalDate.now().toString();
            if (!today.equals(st.lastDaily)) {
                net.minecraft.world.item.Item ds = Rewards.itemOrNull(c.shardItem);
                if (ds != null) {
                    st.lastDaily = today;
                    give(p, ds, c.dailyBonusShards);
                    noteGot.computeIfAbsent(p.getUUID(), k -> new ArrayList<>()).add("Daily first-kill bonus +" + c.dailyBonusShards + " shards");
                }
            }
        }
        if (c.slayerBuffEnabled && b.trueTier >= c.slayerBuffMinTier) {
            int secs = c.slayerBuffSecondsPerTier * (b.trueTier + 1);
            for (String bn : c.slayerBuffs) {
                net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> ef = slayerEffect(bn);
                if (ef != null) p.addEffect(new net.minecraft.world.effect.MobEffectInstance(ef, secs * 20, 0));
            }
            noteGot.computeIfAbsent(p.getUUID(), k -> new ArrayList<>()).add("Slayer buff " + Math.max(1, secs / 60) + " min");
        }
        if (c.shardsPerPlayer) {
            net.minecraft.world.item.Item shard = Rewards.itemOrNull(c.shardItem);
            if (shard != null) {
                int n = (int) Math.round(Util.rint(t.shardPlayerMin, Math.max(t.shardPlayerMin, t.shardPlayerMax)) * mult);
                if (isTop) n += Util.rint(c.topBonusShardMin, Math.max(c.topBonusShardMin, c.topBonusShardMax));
                if (n > 0) give(p, shard, n);
            }
        }
        if (c.vitalEmberEnabled && t.emberChance > 0 && Util.chance(Math.min(1.0, t.emberChance * mult))) {
            net.minecraft.world.item.Item em = Rewards.itemOrNull(c.vitalEmberItem);
            if (em != null) give(p, em, 1);
        }
        for (Config.UniqueReward u : c.uniqueRewards) {
            if (u == null || u.command == null || u.command.isBlank()) continue;
            if (b.trueTier < u.minTier || b.trueTier > u.maxTier || (u.topOnly && !isTop)) continue;
            if (!Util.chance(u.chance)) continue;
            runCommand(u.command.replace("%player%", p.getName().getString()));
            uniqueGot.computeIfAbsent(p.getUUID(), k -> new ArrayList<>()).add(u.name);
        }
    }

    private static net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> slayerEffect(String n) {
        return switch (n == null ? "" : n.trim().toLowerCase(Locale.ROOT)) {
            case "strength" -> net.minecraft.world.effect.MobEffects.STRENGTH;
            case "luck" -> net.minecraft.world.effect.MobEffects.LUCK;
            case "regeneration" -> net.minecraft.world.effect.MobEffects.REGENERATION;
            case "speed" -> net.minecraft.world.effect.MobEffects.SPEED;
            case "haste" -> net.minecraft.world.effect.MobEffects.HASTE;
            case "resistance" -> net.minecraft.world.effect.MobEffects.RESISTANCE;
            case "absorption" -> net.minecraft.world.effect.MobEffects.ABSORPTION;
            default -> null;
        };
    }

    private void runCommand(String cmd) {
        try {
            String line = cmd.startsWith("/") ? cmd.substring(1) : cmd;
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), line);
        } catch (Throwable t) {
            WGBosses.LOG.error("Unique reward command failed: {}", cmd, t);
        }
    }

    private Map<UUID, Component> distribute(Boss b, List<Map.Entry<UUID, Double>> sorted, UUID topId, Rewards.Result rr) {
        got.clear();
        gotXp.clear();
        uniqueGot.clear();
        noteGot.clear();
        double minDmg = b.maxHp * cfg().lootMinDamagePercent / 100.0;
        List<ServerPlayer> part = new ArrayList<>();
        List<Double> dmg = new ArrayList<>();
        double total = 0;
        for (Map.Entry<UUID, Double> e : sorted) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null || !p.isAlive() || e.getValue() < minDmg) continue;
            if (p.level() != b.level || p.distanceTo(b.mob) > cfg().lootMaxDistance) continue;
            part.add(p); dmg.add(e.getValue()); total += e.getValue();
        }
        Map<UUID, Component> out = new LinkedHashMap<>();
        if (part.isEmpty() || total <= 0) return out;
        ServerPlayer top = part.get(0); // sorted by damage, so the first online participant is the best one

        Map<net.minecraft.world.item.Item, Integer> pool = new LinkedHashMap<>();
        for (ItemStack st : rr.chest()) pool.merge(st.getItem(), st.getCount(), Integer::sum);
        for (Map.Entry<net.minecraft.world.item.Item, Integer> e : pool.entrySet()) {
            int totalCount = e.getValue(), given = 0;
            int[] share = new int[part.size()];
            for (int i = 0; i < part.size(); i++) { share[i] = (int) Math.floor(totalCount * dmg.get(i) / total); given += share[i]; }
            share[0] += totalCount - given; // remainder to the top damage dealer
            for (int i = 0; i < part.size(); i++) if (share[i] > 0) give(part.get(i), e.getKey(), share[i]);
        }
        for (ItemStack st : rr.bonus()) give(top, st.getItem(), st.getCount());
        for (ServerPlayer p : part) {
            for (ItemStack st : Rewards.rollPerPlayer(b)) give(p, st.getItem(), st.getCount());
            extras(b, p, p == top);
        }
        for (int i = 0; i < part.size(); i++) {
            int x = rr.xp() > 0 ? (int) (rr.xp() * dmg.get(i) / total) : 0;
            if (x > 0) {
                gotXp.put(part.get(i).getUUID(), x);
                ExperienceOrb.award((ServerLevel) part.get(i).level(), part.get(i).position(), x);
            }
        }
        for (ServerPlayer p : part) {
            MutableComponent msg = prefix().append(Component.literal("Your rewards: ").withStyle(ChatFormatting.GREEN));
            Map<net.minecraft.world.item.Item, Integer> m = got.get(p.getUUID());
            boolean first = true;
            if (m != null) {
                for (Map.Entry<net.minecraft.world.item.Item, Integer> e : m.entrySet()) {
                    if (!first) msg.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
                    first = false;
                    msg.append(Component.literal(e.getValue() + "x ").withStyle(ChatFormatting.YELLOW));
                    msg.append(new ItemStack(e.getKey()).getHoverName());
                }
            }
            Integer xp = gotXp.get(p.getUUID());
            if (xp != null) {
                if (!first) msg.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
                first = false;
                msg.append(Component.literal(xp + " XP").withStyle(ChatFormatting.GREEN));
            }
            List<String> ul = uniqueGot.get(p.getUUID());
            if (ul != null) {
                for (String un : ul) {
                    if (!first) msg.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
                    first = false;
                    msg.append(Component.literal("UNIQUE: " + un).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
                }
            }
            List<String> nl = noteGot.get(p.getUUID());
            if (nl != null) {
                for (String nn : nl) {
                    if (!first) msg.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
                    first = false;
                    msg.append(Component.literal(nn).withStyle(ChatFormatting.AQUA));
                }
            }
            if (first) msg.append(Component.literal("nothing this time").withStyle(ChatFormatting.GRAY));
            if (p == top) msg.append(Component.literal("  (includes top damage bonus!)").withStyle(ChatFormatting.LIGHT_PURPLE));
            out.put(p.getUUID(), msg);
        }
        return out;
    }

    private String nameOf(UUID id) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        return p != null ? p.getName().getString() : WGBosses.stats.get(id, null).name;
    }

    // ------------------------------------------------------------------ teleport
    public String teleport(ServerPlayer p, int id) {
        Boss b = null;
        for (Boss x : bosses) if (x.id == id && !x.dying) b = x;
        if (b == null) return "That boss is already gone.";
        if (!b.tpUsed.add(p.getUUID())) return "You already used the teleport for this boss.";
        Config c = cfg();
        Vec3 d = Util.findSafe(b.level, b.mob.getX(), b.mob.getY(), b.mob.getZ(), c.teleportMinDistance, c.teleportMaxDistance);
        if (d == null) d = b.mob.position().add(c.teleportMinDistance, 0, 0);
        float yaw = (float) (Math.toDegrees(Math.atan2(b.mob.getZ() - d.z, b.mob.getX() - d.x)) - 90.0);
        p.teleportTo(b.level, d.x, d.y, d.z, Set.of(), yaw, 0f, true);
        return null;
    }

    // ------------------------------------------------------------------ events
    public boolean allowDamage(LivingEntity e, DamageSource src, float amount) {
        Boss b = byMob.get(e.getUUID());
        if (b != null) return b.onDamage(src, amount);
        if (e instanceof ServerPlayer sp && !Boss.applying) {
            Entity at = src.getEntity();
            Boss ob = at == null ? null : byMob.get(at.getUUID());
            if (ob != null && ob.nativeAi && ob.convertHit(sp, src)) return false;
        }
        if (minionOwner.containsKey(e.getUUID()) && e.getHealth() - amount <= 0) {
            toDiscard.add(e); // minions never drop loot
            return false;
        }
        return true;
    }

    public void afterDeath(LivingEntity e, DamageSource src) {
        if (!(e instanceof ServerPlayer p) || bosses.isEmpty()) return;
        WGBosses.stats.get(p.getUUID(), null).streak = 0;
        Entity a = src.getEntity();
        if (a == null) return;
        Boss b = byMob.get(a.getUUID());
        if (b == null) b = minionOwner.get(a.getUUID());
        if (b != null) say(b, "kill", p, true);
    }

    public void entityLoaded(Entity e) {
        if (persisted.contains(e.getUUID().toString())) orphanCheck.add(e);
    }

    public Boss first() { return bosses.isEmpty() ? null : bosses.get(0); }
}
