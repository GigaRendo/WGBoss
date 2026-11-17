package com.worstgen.wgbosses;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.*;

/** One live boss. Health is "virtual" so tiers above 1024 HP work; the real mob is healed every tick. */
public final class Boss {
    public enum Mode { NEAREST, MIDDLE, FARTHEST, TOP }

    private static final BossEvent.BossBarColor[] PALETTE = {
            BossEvent.BossBarColor.WHITE, BossEvent.BossBarColor.GREEN, BossEvent.BossBarColor.BLUE,
            BossEvent.BossBarColor.PURPLE, BossEvent.BossBarColor.YELLOW, BossEvent.BossBarColor.PINK,
            BossEvent.BossBarColor.RED};
    private static final ChatFormatting[] CHAT = {
            ChatFormatting.WHITE, ChatFormatting.GREEN, ChatFormatting.AQUA, ChatFormatting.LIGHT_PURPLE,
            ChatFormatting.GOLD, ChatFormatting.RED, ChatFormatting.DARK_RED};

    public final BossManager mgr;
    public final int id;
    public final ServerLevel level;
    public final Mob mob;
    public final EntityType<?> type;
    public final int trueTier;
    public final boolean calamity, rainbow, bloodMoon;
    public final String abnormal, personality;
    public final int phases;
    public final double maxHp;
    public final String baseName;
    public final Map<UUID, Double> damage = new HashMap<>();
    public final Set<UUID> tpUsed = new HashSet<>();
    public final List<Mob> minions = new ArrayList<>();
    public final ServerBossEvent bar;

    public int phase = 1;
    public double hp;
    public boolean gone, dying;
    public long nextChat;
    public long despawnAt;

    private final int[] calIdx;
    private final List<List<Skill>> phaseSkills = new ArrayList<>();
    private final Map<Skill, Long> cd = new EnumMap<>(Skill.class);
    private double dmgMult = 1, speedMult = 1, sizeBase, sizeEnd, baseMove = 0.25, curSpeed = 0.25;

    private ServerPlayer target, skillTarget;
    private long targetUntil, nextSkillTick, attackCd;
    private Skill cur;
    private int skillT, dashLeft, transition, stuck;
    private Vec3 lastPos = Vec3.ZERO;
    private final List<Vec3> strikes = new ArrayList<>();
    private boolean lowSaid, leapHit;
    private int airTicks;
    private long rangedCd;
    public final boolean flyer;
    private boolean hasTotem, hovering;
    private double hoverY, flyHeight;
    private int swoop, swoopCd = 60, fireT;
    private long invulnUntil;
    public double healed;
    private static final String DECOY_ID = "wgbosses:decoy_scale";
    private static final class Bolt { Vec3 pos; ServerPlayer tgt; int life = 140; }
    private final List<Bolt> bolts = new ArrayList<>();
    private final Map<UUID, Long> decoys = new HashMap<>();
    private final Set<Integer> retreatUsed = new HashSet<>();
    private long shieldUntil, reprieveUntil, bloomUntil, invisUntil, lastHit = -1000;
    private double reprieveRoom, hhDamage;
    private Vec3 awayDir = new Vec3(1, 0, 0);
    private long powerUntil, drainUntil, stunUntil, ruptureUntil;
    private double puDamage, ruptureAcc;
    private UUID ruptureId;
    private Vec3 ruptureLast, lagFrom = Vec3.ZERO, lagDir = Vec3.ZERO, wfAim = Vec3.ZERO;
    private int echoCount, echoRounds;
    private final Set<UUID> hitSet = new HashSet<>();
    private final List<ServerPlayer> wfTargets = new ArrayList<>();
    private final Map<UUID, double[]> dps = new HashMap<>();

    Boss(BossManager mgr, int id, ServerLevel level, Mob mob, BossManager.Spec s, String name, int playersOnline) {
        Config c = WGBosses.config;
        this.mgr = mgr; this.id = id; this.level = level; this.mob = mob; this.type = mob.getType();
        this.trueTier = s.tier; this.calamity = s.calamity; this.rainbow = s.rainbow; this.bloodMoon = s.blood;
        this.abnormal = s.abnormal; this.personality = s.personality; this.baseName = name;
        this.flyer = detectFlyer(mob);
        this.hasTotem = Util.chance(c.totemChance);
        this.flyHeight = Util.rdbl(c.flyerHeightMin, Math.max(c.flyerHeightMin, c.flyerHeightMax));
        Config.TierDef t = c.tiers.get(trueTier);
        this.phases = calamity ? 5 : Util.rint(t.phasesMin, t.phasesMax);
        if (calamity) { int a = Util.rint(0, 2); calIdx = new int[]{a, a + 1, 4, 5, 6}; } else calIdx = null;

        double hpMul = 1 + Math.min(c.healthScaleCapPercent, Math.max(0, playersOnline - c.minPlayers) * c.healthPerExtraPlayerPercent) / 100.0;
        if (bloodMoon) { hpMul *= c.bloodMoonHealthMult; dmgMult = c.bloodMoonDamageMult; }
        sizeBase = Util.rdbl(t.sizeMin, t.sizeMax);
        sizeEnd = t.sizeMax;
        if (calamity) { sizeBase = Util.rdbl(2.0, 3.5); sizeEnd = Util.rdbl(10, 12); }
        switch (abnormal) {
            case "SPEED" -> speedMult = Util.rdbl(2.0, 3.0);
            case "HEALTH" -> hpMul *= Util.rdbl(2.5, 4.0);
            case "DAMAGE" -> dmgMult *= Util.rdbl(2.0, 3.0);
            case "SIZE" -> sizeBase = Util.rdbl(9.0, 12.0);
            default -> { }
        }
        this.maxHp = Math.max(20, t.health * hpMul);
        this.hp = maxHp;

        AttributeInstance mi = mob.getAttribute(Attributes.MOVEMENT_SPEED);
        if (mi != null) baseMove = Math.max(0.2, mi.getBaseValue());
        clearGoals();
        setAttr(Attributes.MAX_HEALTH, 1024);
        mob.setHealth(mob.getMaxHealth());

        List<Skill> prev = new ArrayList<>();
        for (int p = 1; p <= phases; p++) {
            Config.TierDef pt = phaseTier(p);
            int pIdx = phaseTierIdx(p);
            int n = Math.min(6, Util.rint(pt.skillMin + (p - 1), pt.skillMax + (p - 1)));
            List<Skill> pool = new ArrayList<>();
            for (Skill sk : Skill.values()) {
                if (sk.minTier <= pIdx && !prev.contains(sk) && !(sk == Skill.SUMMON && p < 2)
                        && !(flyer && (sk == Skill.LEAP || sk == Skill.SMASH))) pool.add(sk);
            }
            Collections.shuffle(pool);
            List<Skill> now = new ArrayList<>(prev);
            while (now.size() < n && !pool.isEmpty()) now.add(pool.remove(0));
            phaseSkills.add(now);
            prev = now;
        }

        applyPhase(1);
        mob.setNoGravity(flyer);
        mob.setCustomName(displayName());
        mob.setCustomNameVisible(true);
        bar = new ServerBossEvent(UUID.randomUUID(), barTitle(), color(), BossEvent.BossBarOverlay.PROGRESS);
        refreshBar();
        resetDespawn(0);
        nextSkillTick = 100;
    }

    // ------------------------------------------------------------------ flyer / toughness helpers
    private static boolean detectFlyer(Mob m) {
        if (m.getNavigation() instanceof FlyingPathNavigation || m.getMoveControl() instanceof FlyingMoveControl) return true;
        var key = BuiltInRegistries.ENTITY_TYPE.getKey(m.getType());
        return key != null && isFlyerId(key.toString());
    }

    public static boolean isFlyerId(String id) {
        Config c = WGBosses.config;
        if (c.flyingMobs.contains(id)) return true;
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        for (String tok : path.split("_")) if (c.flyingMobKeywords.contains(tok)) return true;
        return false;
    }

    private static Holder<MobEffect> effectByName(String n) {
        String k = n.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        return switch (k) {
            case "slowness" -> MobEffects.SLOWNESS;
            case "weakness" -> MobEffects.WEAKNESS;
            case "mining_fatigue" -> MobEffects.MINING_FATIGUE;
            case "levitation" -> MobEffects.LEVITATION;
            case "wither" -> MobEffects.WITHER;
            case "poison" -> MobEffects.POISON;
            case "blindness" -> MobEffects.BLINDNESS;
            case "nausea" -> MobEffects.NAUSEA;
            case "glowing" -> MobEffects.GLOWING;
            default -> null;
        };
    }

    private void clearEffects(Mob m) {
        Config c = WGBosses.config;
        if (!c.effectImmunity || m.getActiveEffects().isEmpty()) return;
        for (String n : c.immuneEffects) {
            Holder<MobEffect> e = effectByName(n);
            if (e != null && m.hasEffect(e)) m.removeEffect(e);
        }
    }

    private void toughness(long now) {
        Config c = WGBosses.config;
        if (mob.isNoAi()) mob.setNoAi(false);
        clearEffects(mob);
        if (mob.isOnFire()) {
            fireT++;
            if (c.maxBurnSeconds <= 0 || fireT > c.maxBurnSeconds * 20) { mob.clearFire(); fireT = 0; }
        } else fireT = 0;
        if (c.removeCobwebs && now % 4 == 0) {
            AABB bb = mob.getBoundingBox().inflate(0.3);
            for (BlockPos p : BlockPos.betweenClosed(Mth.floor(bb.minX), Mth.floor(bb.minY), Mth.floor(bb.minZ),
                    Mth.floor(bb.maxX), Mth.floor(bb.maxY), Mth.floor(bb.maxZ))) {
                if (level.getBlockState(p).is(Blocks.COBWEB)) level.removeBlock(p, false);
            }
        }
    }

    /** Heals the boss, respecting the per-fight cap and the current phase's top. Returns how much was healed. */
    public double heal(double amount) {
        double room = maxHp * WGBosses.config.maxTotalHealPercent / 100.0 - healed;
        double top = maxHp * (phases - phase + 1) / phases;
        double a = Math.max(0, Math.min(amount, Math.min(room, top - hp)));
        hp += a;
        healed += a;
        return a;
    }

    public boolean invulnerable() { return mgr.tick < invulnUntil || mgr.tick < shieldUntil; }

    private double powerMul() { return mgr.tick < powerUntil ? WGBosses.config.powerUpDamageMult : 1.0; }

    private double speedBoost() { return mgr.tick < powerUntil ? 1.3 : 1.0; }

    private boolean band(ServerPlayer p, double x, double z, double r, double w) {
        double d = Util.flat(p.getX(), p.getZ(), x, z);
        return Math.abs(d - r) <= w && Math.abs(p.getY() - mob.getY()) < 6;
    }

    private static double segDist(Vec3 a, Vec3 b, Vec3 p) {
        Vec3 ab = b.subtract(a);
        double l2 = ab.lengthSqr();
        if (l2 < 1.0E-6) return p.distanceTo(a);
        double u = Math.max(0, Math.min(1, p.subtract(a).dot(ab) / l2));
        return p.distanceTo(a.add(ab.scale(u)));
    }

    private void tickRupture(long now) {
        if (ruptureId == null) return;
        ServerPlayer p = mgr.server.getPlayerList().getPlayer(ruptureId);
        if (now >= ruptureUntil || !valid(p)) { ruptureId = null; return; }
        Vec3 pos = p.position();
        if (ruptureLast != null) ruptureAcc += Math.min(8, Util.flat(pos.x, pos.z, ruptureLast.x, ruptureLast.z));
        ruptureLast = pos;
        if (now % 4 == 0) level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, p.getX(), p.getY() + 1, p.getZ(), 3, 0.3, 0.5, 0.3, 0);
        if (now % 10 == 0) {
            if (ruptureAcc > 0.6) hurt(p, curDamage() * 0.1 * Math.min(12, ruptureAcc));
            ruptureAcc = 0;
        }
    }

    private boolean healable() {
        double top = maxHp * (phases - phase + 1) / phases;
        return hp < top - maxHp * 0.05 && healed < maxHp * WGBosses.config.maxTotalHealPercent / 100.0 - 1;
    }

    private boolean movingSkill() {
        if (cur == Skill.LEAP) return true;
        if (cur == Skill.WATERFOWL) { int k = skillT - 21; return k >= 0 && k < 54 && k % 18 < 12; }
        if (cur == Skill.DECOY_SWAP) return skillT > 15 && skillT <= 40;
        if (cur == Skill.RETREAT_HEAL) return (skillT > 10 && skillT <= 22) || (skillT > 28 && skillT <= 40);
        return false;
    }

    private void announce(String text, ChatFormatting f) {
        Component c = Component.literal(text).withStyle(f);
        double r = WGBosses.config.targetRange;
        for (ServerPlayer p : players()) if (p.distanceToSqr(mob) <= r * r) p.sendSystemMessage(c);
    }

    private void hideBoss(int ticks) {
        mob.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, ticks, 0, false, false));
        mob.setCustomNameVisible(false);
        invisUntil = mgr.tick + ticks;
    }

    private void addDecoy(ServerPlayer p, double scale, long now) {
        AttributeInstance ai = p.getAttribute(Attributes.SCALE);
        var id = net.minecraft.resources.Identifier.tryParse(DECOY_ID);
        if (ai == null || id == null) return;
        ai.removeModifier(id);
        ai.addTransientModifier(new AttributeModifier(id, scale - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        decoys.put(p.getUUID(), now + WGBosses.config.decoySeconds * 20L);
    }

    private void removeDecoy(UUID u) {
        decoys.remove(u);
        ServerPlayer p = mgr.server.getPlayerList().getPlayer(u);
        var id = net.minecraft.resources.Identifier.tryParse(DECOY_ID);
        if (p == null || id == null) return;
        AttributeInstance ai = p.getAttribute(Attributes.SCALE);
        if (ai != null) ai.removeModifier(id);
    }

    private void clearDecoys() {
        for (UUID u : new ArrayList<>(decoys.keySet())) removeDecoy(u);
    }

    private void buffs(long now) {
        Config cfg = WGBosses.config;
        if (shieldUntil > 0) {
            if (now >= shieldUntil) {
                shieldUntil = 0;
                Util.sound(level, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.GLASS_BREAK, 2.5f, 0.7f);
                ring(ParticleTypes.ELECTRIC_SPARK, mob.getX(), mob.getY() + mob.getBbHeight() * 0.5, mob.getZ(), mob.getBbWidth(), 30);
            } else if (now % 2 == 0) {
                double ang = now * 0.25, r = mob.getBbWidth() * 0.6 + 0.8;
                for (int i = 0; i < 3; i++) {
                    double a = ang + i * 2.09;
                    level.sendParticles(ParticleTypes.ELECTRIC_SPARK, mob.getX() + Math.cos(a) * r,
                            mob.getY() + mob.getBbHeight() * (0.2 + 0.3 * i), mob.getZ() + Math.sin(a) * r, 1, 0, 0, 0, 0);
                }
                if (now % 20 == 0) Util.sound(level, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.BEACON_AMBIENT, 1.5f, 1.4f);
            }
        }
        if (now < bloomUntil && now % 10 == 0) {
            heal(maxHp * cfg.bloomPercent / 100.0 / 12.0);
            ring(ParticleTypes.HAPPY_VILLAGER, mob.getX(), mob.getY(), mob.getZ(), mob.getBbWidth() * 0.7 + 0.5, 10);
        }
        if (invisUntil > 0 && now >= invisUntil) {
            invisUntil = 0;
            mob.removeEffect(MobEffects.INVISIBILITY);
            mob.setCustomNameVisible(true);
        }
        if (!decoys.isEmpty()) {
            for (Map.Entry<UUID, Long> e : new ArrayList<>(decoys.entrySet())) if (now >= e.getValue()) removeDecoy(e.getKey());
        }
        tickBolts();
        tickRupture(now);
        if (now < powerUntil && now % 10 == 0) puff(ParticleTypes.ANGRY_VILLAGER, 3);
        if (now < drainUntil && now % 10 == 0) puff(ParticleTypes.DAMAGE_INDICATOR, 3);
        if (now < stunUntil && now % 5 == 0) puff(ParticleTypes.CRIT, 5);
    }

    private void tickBolts() {
        if (bolts.isEmpty()) return;
        Config cfg = WGBosses.config;
        Iterator<Bolt> it = bolts.iterator();
        while (it.hasNext()) {
            Bolt bt = it.next();
            bt.life--;
            if (bt.life <= 0 || !valid(bt.tgt)) { it.remove(); continue; }
            Vec3 aim = bt.tgt.position().add(0, bt.tgt.getBbHeight() * 0.5, 0);
            Vec3 diff = aim.subtract(bt.pos);
            double dist = diff.length();
            if (dist < 1.1) {
                hurt(bt.tgt, curDamage() * 0.4);
                int lo = cfg.shulkerLevitationMinSeconds, hi = Math.max(lo, cfg.shulkerLevitationMaxSeconds);
                bt.tgt.addEffect(new MobEffectInstance(MobEffects.LEVITATION, Util.rint(lo, hi) * 20, 0));
                level.sendParticles(ParticleTypes.END_ROD, aim.x, aim.y, aim.z, 20, 0.4, 0.4, 0.4, 0.05);
                Util.sound(level, aim.x, aim.y, aim.z, SoundEvents.SHULKER_BULLET_HIT, 1.5f, 1f);
                it.remove();
                continue;
            }
            bt.pos = bt.pos.add(diff.normalize().scale(0.45));
            level.sendParticles(ParticleTypes.END_ROD, bt.pos.x, bt.pos.y, bt.pos.z, 2, 0.05, 0.05, 0.05, 0);
        }
    }

    private void splashHit(Skill s, ServerPlayer p) {
        int secs = WGBosses.config.splashSeconds * 20;
        switch (s) {
            case SPLASH_POISON -> p.addEffect(new MobEffectInstance(MobEffects.POISON, secs + 20, 0));
            case SPLASH_WITHER -> p.addEffect(new MobEffectInstance(MobEffects.WITHER, 60, 0));
            case SPLASH_HARMING -> hurt(p, curDamage() * 0.7);
            case SPLASH_LEVITATION -> p.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 60, 0));
            case SPLASH_SLOWNESS -> p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, secs, 1));
            case SPLASH_BLINDNESS -> p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, secs, 0));
            default -> { }
        }
    }

    private boolean hoverable(Skill s) {
        return s == Skill.HOOK || s == Skill.BARRAGE || s == Skill.ROAR || s == Skill.SUMMON || s == Skill.LIGHTNING;
    }

    // ------------------------------------------------------------------ helpers
    private void clearGoals() {
        for (String f : new String[]{"goalSelector", "targetSelector"}) {
            try {
                Field fld = Mob.class.getDeclaredField(f);
                fld.setAccessible(true);
                ((GoalSelector) fld.get(mob)).removeAllGoals(g -> true);
            } catch (Throwable ignored) { }
        }
    }

    private void setAttr(Holder<Attribute> a, double v) {
        AttributeInstance i = mob.getAttribute(a);
        if (i != null) i.setBaseValue(v);
    }

    public int phaseTierIdx(int p) { return calamity ? calIdx[p - 1] : trueTier; }
    public Config.TierDef phaseTier(int p) { return WGBosses.config.tiers.get(phaseTierIdx(p)); }
    public int displayTierIdx() { return phaseTierIdx(phase); }

    private double lower(int p) { return maxHp * (phases - p) / phases; }

    public double curDamage() {
        return phaseTier(phase).damage * (calamity ? 1 : 1 + 0.2 * (phase - 1)) * dmgMult;
    }

    private void applyPhase(int p) {
        Config.TierDef pt = phaseTier(p);
        double size = calamity ? sizeBase + (sizeEnd - sizeBase) * (p - 1) / 4.0 : sizeBase * (1 + 0.05 * (p - 1));
        size = Math.max(0.5, Math.min(15, size));
        setAttr(Attributes.SCALE, size);
        setAttr(Attributes.KNOCKBACK_RESISTANCE, Math.min(0.9, 0.2 + size * 0.07));
        setAttr(Attributes.STEP_HEIGHT, 0.6 + size * 0.35);
        curSpeed = baseMove * pt.speedMult * (1 + 0.07 * (p - 1)) * speedMult;
        setAttr(Attributes.MOVEMENT_SPEED, curSpeed);
    }

    public ChatFormatting chatColor() { return CHAT[Math.min(CHAT.length - 1, displayTierIdx())]; }

    public Component displayName() { return Component.literal(baseName).withStyle(chatColor(), ChatFormatting.BOLD); }

    public String tierName() { return phaseTier(phase).name; }

    private Component barTitle() {
        String tn = tierName().toUpperCase(Locale.ROOT);
        return Component.literal("[" + tn + "] ").withStyle(chatColor())
                .append(Component.literal(baseName + " - Phase " + phase + "/" + phases).withStyle(ChatFormatting.WHITE));
    }

    private BossEvent.BossBarColor color() {
        if (rainbow) return PALETTE[(int) ((mgr.tick / 8) % 7)];
        if (bloodMoon) return phase % 2 == 1 ? BossEvent.BossBarColor.RED : BossEvent.BossBarColor.PINK;
        int idx = calamity ? displayTierIdx() : (trueTier + phase - 1);
        return PALETTE[idx % 7];
    }

    public void refreshBar() {
        double span = maxHp / phases;
        bar.setProgress((float) Math.max(0, Math.min(1, (hp - lower(phase)) / span)));
        bar.setName(barTitle());
        if (!rainbow) bar.setColor(color());
    }

    private void syncBar() {
        for (ServerPlayer p : mgr.server.getPlayerList().getPlayers()) bar.addPlayer(p);
    }

    public void resetDespawn(long now) {
        Config c = WGBosses.config;
        despawnAt = now + (long) Util.rint(c.despawnMinMinutes * 1200, c.despawnMaxMinutes * 1200);
    }

    private List<ServerPlayer> players() {
        List<ServerPlayer> l = new ArrayList<>();
        for (ServerPlayer p : level.players()) if (p.isAlive() && !p.isSpectator()) l.add(p);
        return l;
    }

    private boolean airborne(ServerPlayer p) {
        return p != null && !p.onGround() && (p.getY() - mob.getY() > 3.5 || p.isFallFlying());
    }

    private int newTargetTicks() {
        Config c = WGBosses.config;
        return Util.rint(c.targetMinSeconds * 20, Math.max(c.targetMinSeconds, c.targetMaxSeconds) * 20);
    }

    private ServerPlayer nearestGrounded(List<ServerPlayer> ps) {
        ServerPlayer best = null; double bd = Double.MAX_VALUE;
        for (ServerPlayer p : ps) {
            if (airborne(p)) continue;
            double d = p.distanceToSqr(mob);
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    private List<ServerPlayer> inRange(List<ServerPlayer> all) {
        double r = WGBosses.config.targetRange;
        List<ServerPlayer> l = new ArrayList<>();
        for (ServerPlayer p : all) if (p.distanceToSqr(mob) <= r * r) l.add(p);
        return l;
    }

    private boolean valid(ServerPlayer p) {
        return p != null && p.isAlive() && !p.isSpectator() && p.level() == level;
    }

    private void hurt(ServerPlayer p, double amount) {
        if (!p.isAlive() || amount <= 0) return;
        double ratio = amount / Math.max(0.01, curDamage());
        double pct = phaseTier(phase).hitPercent * (calamity ? 1 : 1 + 0.2 * (phase - 1)) * dmgMult * powerMul();
        double finalDmg = Math.max(1.0, p.getMaxHealth() * pct / 100.0 * ratio);
        if (WGBosses.config.neverOneShot && p.getHealth() > 1.0f) finalDmg = Math.min(finalDmg, p.getHealth() - 1.0f);
        DamageSource src = WGBosses.config.armorBypass ? level.damageSources().indirectMagic(mob, mob) : level.damageSources().mobAttack(mob);
        p.hurtServer(level, src, (float) finalDmg);
        if (mgr.tick < drainUntil) {
            heal(maxHp * WGBosses.config.lifeDrainHealPercent / 100.0);
            level.sendParticles(ParticleTypes.HEART, mob.getX(), mob.getY() + mob.getBbHeight() * 0.8, mob.getZ(), 2, 0.3, 0.3, 0.3, 0.02);
        }
    }

    private void puff(ParticleOptions p, int n) {
        double w = mob.getBbWidth();
        level.sendParticles(p, mob.getX(), mob.getY() + 0.1, mob.getZ(), n, w * 0.4, 0.1, w * 0.4, 0.02);
    }

    private void ring(ParticleOptions p, double cx, double cy, double cz, double r, int n) {
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            level.sendParticles(p, cx + Math.cos(a) * r, cy + 0.15, cz + Math.sin(a) * r, 1, 0, 0, 0, 0);
        }
    }

    private void face(Entity t) {
        double dx = t.getX() - mob.getX(), dz = t.getZ() - mob.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        mob.setYRot(yaw);
        mob.setYHeadRot(yaw);
        mob.setYBodyRot(yaw);
    }

    private ServerPlayer farthest(List<ServerPlayer> ps) {
        ServerPlayer best = null; double bd = -1;
        for (ServerPlayer p : ps) { double d = p.distanceToSqr(mob); if (d > bd) { bd = d; best = p; } }
        return best;
    }

    // ------------------------------------------------------------------ damage
    /** Returns true if vanilla damage should still be applied (for the hurt flash / knockback). */
    public boolean onDamage(DamageSource src, float amount) {
        if (dying || gone) return false;
        if (src.is(DamageTypes.IN_FIRE) || src.is(DamageTypes.ON_FIRE) || src.is(DamageTypes.LAVA)
                || src.is(DamageTypes.HOT_FLOOR) || src.is(DamageTypes.IN_WALL) || src.is(DamageTypes.CRAMMING)
                || src.is(DamageTypes.DROWN) || src.is(DamageTypes.STARVE) || src.is(DamageTypes.CACTUS)
                || src.is(DamageTypes.FALL) || src.is(DamageTypes.FLY_INTO_WALL) || src.is(DamageTypes.FELL_OUT_OF_WORLD)
                || src.is(DamageTypes.FREEZE) || src.is(DamageTypes.SWEET_BERRY_BUSH) || src.is(DamageTypes.LIGHTNING_BOLT)) {
            return false;
        }
        if (transition > 0 || invulnerable()) return false;
        lastHit = mgr.tick;
        Entity a = src.getEntity();
        ServerPlayer attacker = a instanceof ServerPlayer sp ? sp : null;
        double amt = amount;
        Config cfg = WGBosses.config;
        if (cfg.damageCapEnabled) {
            Config.TierDef pt = phaseTier(phase);
            if (pt.hitCapPercent > 0) amt = Math.min(amt, maxHp * pt.hitCapPercent / 100.0);
            if (attacker != null && pt.dpsCapPercent > 0) {
                double[] w = dps.computeIfAbsent(attacker.getUUID(), k -> new double[]{mgr.tick, 0});
                if (mgr.tick - w[0] >= 20) { w[0] = mgr.tick; w[1] = 0; }
                amt = Math.min(amt, Math.max(0, maxHp * pt.dpsCapPercent / 100.0 - w[1]));
                w[1] += amt;
            }
        }
        double floor = lower(phase);
        double eff = Math.min(amt, Math.max(0, hp - floor));
        hp -= amt;
        if (attacker != null) {
            damage.merge(attacker.getUUID(), eff, Double::sum);
            resetDespawn(mgr.tick);
            if (Util.chance(0.12)) mgr.say(this, "hit", attacker, false);
        }
        if (hp <= floor) {
            hp = floor;
            if (phase < phases) startTransition();
            else if (hasTotem) popTotem();
            else mgr.onBossKilled(this, attacker);
            return false;
        }
        if (mgr.tick < reprieveUntil && reprieveRoom > 0 && eff > 0) {
            double h = heal(Math.min(eff * 0.5, reprieveRoom));
            reprieveRoom -= h;
        }
        if (cur == Skill.POWER_UP) {
            puDamage += eff;
            if (puDamage >= maxHp * cfg.powerUpInterruptPercent / 100.0) {
                stunUntil = mgr.tick + 40;
                announce("The boss's charge was broken!", ChatFormatting.GOLD);
                endSkill(mgr.tick);
            }
        }
        if (cur == Skill.HEART_HOVER) {
            hhDamage += eff;
            if (hhDamage >= maxHp * cfg.heartHoverInterruptPercent / 100.0) {
                announce("The boss lost its focus!", ChatFormatting.YELLOW);
                endSkill(mgr.tick);
            }
        }
        mob.setHealth(mob.getMaxHealth());
        return amount < 600;
    }

    /** The boss would have died: the hidden totem brings it back one phase. */
    private void popTotem() {
        hasTotem = false;
        endSkillNow();
        if (phases > 1) {
            phase--;
            applyPhase(phase);
            hp = lower(phase) + maxHp / phases * WGBosses.config.totemRefillPercent / 100.0;
        } else {
            hp = maxHp * 0.5;
        }
        hp = Math.min(hp, maxHp);
        invulnUntil = mgr.tick + 60;
        lowSaid = false;
        nextSkillTick = mgr.tick + 80;
        mob.setCustomName(displayName());
        refreshBar();
        double cy = mob.getY() + mob.getBbHeight() * 0.5;
        level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, mob.getX(), cy, mob.getZ(), 120, 1.5, 1.5, 1.5, 0.4);
        Util.sound(level, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.TOTEM_USE, 3f, 0.8f);
        for (ServerPlayer p : players()) {
            if (p.distanceToSqr(mob) < 144) Util.push(p, mob.getX(), mob.getZ(), 1.0, 0.4);
        }
        Util.titleAll(mgr.server, Component.literal("THE BOSS CHEATED DEATH!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                displayName(), 10, 60, 20);
    }

    // ------------------------------------------------------------------ phase transition
    private void startTransition() {
        transition = 60;
        shieldUntil = 0;
        bolts.clear();
        endSkillNow();
        Util.sound(level, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.ENDER_DRAGON_GROWL, 3.0f, 0.8f);
    }

    private void tickTransition() {
        transition--;
        mob.setDeltaMovement(0, flyer ? 0 : mob.getDeltaMovement().y, 0);
        if (transition % 4 == 0) {
            double r = (60 - transition) * 0.25;
            ring(ParticleTypes.CLOUD, mob.getX(), mob.getY(), mob.getZ(), Math.min(r, 14), 24);
            puff(ParticleTypes.ANGRY_VILLAGER, 6);
        }
        if (transition == 30) {
            phase++;
            applyPhase(phase);
            mob.setCustomName(displayName());
            refreshBar();
            for (ServerPlayer p : players()) {
                if (p.distanceToSqr(mob) < 144) Util.push(p, mob.getX(), mob.getZ(), 1.2, 0.5);
            }
            Util.sound(level, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.WITHER_SPAWN, 3.0f, 1.0f);
            mgr.phaseAnnounce(this);
            if (phase >= 2) spawnMinions(2 + phase / 2);
            nextSkillTick = mgr.tick + 80;
        }
    }

    // ------------------------------------------------------------------ main tick
    public void tick(long now) {
        if (dying) return;
        if (mob.isRemoved() || !mob.isAlive()) { gone = true; return; }
        mob.setHealth(mob.getMaxHealth());
        toughness(now);
        buffs(now);
        mob.setAirSupply(mob.getMaxAirSupply());
        safety(now);
        if (now >= despawnAt) { mgr.despawn(this); return; }
        if (now % 5 == 0) refreshBar();
        if (now % 20 == 0) syncBar();
        if (rainbow && now % 8 == 0) bar.setColor(color());

        if (transition > 0) { tickTransition(); return; }

        List<ServerPlayer> ps = inRange(players());
        tickMinions(ps);
        if (ps.isEmpty()) { target = null; if (cur != null) endSkill(now); return; }
        validateTarget(ps, now);
        airTicks = (target != null && airborne(target)) ? airTicks + 1 : 0;
        if (cur == null && airTicks > 60) {
            ServerPlayer g = nearestGrounded(ps);
            if (g != null) { target = g; targetUntil = now + newTargetTicks(); airTicks = 0; }
        }
        if (cur != null) tickSkill(now, ps); else maybeStartSkill(now, ps);
        move();

        if (!lowSaid && hp / maxHp < 0.2) { lowSaid = true; mgr.say(this, "lowhp", target, true); }
    }

    private void safety(long now) {
        if (!level.noBlockCollision(mob, mob.getBoundingBox())) {
            for (int i = 0; i < 40 && !level.noBlockCollision(mob, mob.getBoundingBox()); i++) {
                mob.setPos(mob.getX(), mob.getY() + 1, mob.getZ());
            }
        }
        int minY = level.dimensionType().minY();
        if (mob.getY() < minY + 2) {
            List<ServerPlayer> ps = players();
            Vec3 to = ps.isEmpty() ? new Vec3(mob.getX(), minY + 80, mob.getZ())
                    : new Vec3(ps.get(0).getX(), ps.get(0).getY() + 2, ps.get(0).getZ());
            mob.setPos(to.x, to.y, to.z);
            mob.setDeltaMovement(Vec3.ZERO);
        }
        if (now % 20 == 0) {
            if (target != null && cur == null && transition <= 0) {
                double moved = mob.position().distanceTo(lastPos);
                double d = Util.flat(mob.getX(), mob.getZ(), target.getX(), target.getZ());
                if (moved < 0.4 && d > mob.getBbWidth() * 0.5 + 4) stuck++; else stuck = 0;
                if (stuck >= 2) {
                    // teleport higher (and a little forward) until we are free
                    double dx = target.getX() - mob.getX(), dz = target.getZ() - mob.getZ();
                    double len = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
                    mob.setPos(mob.getX() + dx / len * 2, mob.getY() + 2 + stuck, mob.getZ() + dz / len * 2);
                    for (int i = 0; i < 40 && !level.noBlockCollision(mob, mob.getBoundingBox()); i++) {
                        mob.setPos(mob.getX(), mob.getY() + 1, mob.getZ());
                    }
                    mob.setDeltaMovement(Vec3.ZERO);
                }
            }
            lastPos = mob.position();
        }
    }

    // ------------------------------------------------------------------ targeting
    private void validateTarget(List<ServerPlayer> ps, long now) {
        if (!valid(target) || target.distanceToSqr(mob) > Math.pow(WGBosses.config.targetRange * 1.25, 2)) {
            if (cur == null) retarget(ps, now);
            else target = ps.get(0);
            return;
        }
        if (cur == null && now >= targetUntil) retarget(ps, now);
    }

    private void retarget(List<ServerPlayer> ps, long now) {
        List<ServerPlayer> sorted = new ArrayList<>(ps);
        sorted.sort(Comparator.comparingDouble(p -> p.distanceToSqr(mob)));
        int n = sorted.size();
        double r = Util.rdbl(0, 1);
        Mode m;
        if (n == 1) m = Mode.NEAREST;
        else if (r < 0.28) m = Mode.NEAREST;
        else if (r < 0.45) m = n >= 3 ? Mode.MIDDLE : Mode.NEAREST;
        else if (r < 0.72) m = Mode.FARTHEST;
        else m = Mode.TOP;

        ServerPlayer pick = sorted.get(0);
        switch (m) {
            case MIDDLE -> pick = sorted.get(n / 2);
            case FARTHEST -> pick = sorted.get(n - 1);
            case TOP -> {
                double best = 0;
                for (ServerPlayer p : sorted) {
                    double d = damage.getOrDefault(p.getUUID(), 0.0);
                    if (d > best) { best = d; pick = p; }
                }
            }
            default -> { }
        }
        target = pick;
        targetUntil = now + newTargetTicks();
        if (m == Mode.FARTHEST && n > 1 && cur == null) dashLeft = Util.rint(40, 100);
    }

    // ------------------------------------------------------------------ movement
    private boolean frozen() { return transition > 0 || cur != null; }

    private void move() {
        if (target == null) return;
        face(target);
        Vec3 dm = mob.getDeltaMovement();
        if (mgr.tick < stunUntil) { mob.setDeltaMovement(0, flyer ? 0 : dm.y, 0); return; }
        if (frozen()) {
            if (!movingSkill()) mob.setDeltaMovement(0, flyer ? 0 : dm.y, 0);
            return;
        }
        if (flyer) { moveFlyer(); return; }
        double dx = target.getX() - mob.getX(), dz = target.getZ() - mob.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        double reach = mob.getBbWidth() * 0.5 + 1.8;
        double speed = Math.min(1.0, curSpeed * 0.85 * speedBoost() * (dashLeft > 0 ? 2.4 : 1.0));
        if (dashLeft > 0) dashLeft--;
        if (d > reach * 0.8) {
            double vy = dm.y;
            if (mob.horizontalCollision && mob.onGround()) vy = 0.5 + mob.getBbHeight() * 0.02;
            mob.setDeltaMovement(dx / d * speed, vy, dz / d * speed);
            mob.getNavigation().stop();
        } else {
            mob.setDeltaMovement(dm.x * 0.3, dm.y, dm.z * 0.3);
        }
        if (attackCd > 0) attackCd--;
        if (d <= reach && Math.abs(target.getY() - mob.getY()) < mob.getBbHeight() + 1.0 && attackCd <= 0) {
            hurt(target, curDamage());
            Util.push(target, mob.getX(), mob.getZ(), 0.6, 0.3);
            attackCd = dashLeft > 0 ? 12 : 20;
        }
        // anti-air: a flying target can't be ignored, the boss spits bolts at it
        if (rangedCd > 0) rangedCd--;
        if (airTicks >= 20 && rangedCd <= 0 && d > reach && target.distanceToSqr(mob) <= 40 * 40) {
            rangedCd = 50;
            Vec3 from = new Vec3(mob.getX(), mob.getY() + mob.getBbHeight() * 0.6, mob.getZ());
            line(from, target.position().add(0, target.getBbHeight() * 0.5, 0), ParticleTypes.SOUL_FIRE_FLAME);
            hurt(target, curDamage() * 0.5);
            Util.sound(level, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.ANVIL_LAND, 1.5f, 1.8f);
        }
    }

    private void moveFlyer() {
        double dx = target.getX() - mob.getX(), dz = target.getZ() - mob.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        double reach = mob.getBbWidth() * 0.5 + 1.8;
        double speed = Math.min(1.0, curSpeed * 0.85 * speedBoost() * (dashLeft > 0 ? 2.4 : 1.0));
        if (dashLeft > 0) dashLeft--;
        if (swoop > 0) {
            swoop--;
            Vec3 aim = target.position().add(0, target.getBbHeight() * 0.5, 0);
            Vec3 diff = aim.subtract(mob.position().add(0, mob.getBbHeight() * 0.5, 0));
            double dist = diff.length();
            if (dist <= reach + 0.8) {
                hurt(target, curDamage());
                Util.push(target, mob.getX(), mob.getZ(), 0.6, 0.3);
                swoop = 0;
                swoopCd = Util.rint(50, 100);
                mob.setDeltaMovement(0, 0.5, 0);
            } else {
                mob.setDeltaMovement(diff.normalize().scale(Math.min(1.3, speed * 1.8 + 0.3)));
                if (swoop == 0) swoopCd = Util.rint(50, 100);
            }
            return;
        }
        if (swoopCd > 0) swoopCd--;
        double tt = mgr.tick * 0.12 + id;
        double wantY = target.getY() + (airborne(target) ? 1.0 : flyHeight) + Math.sin(tt * 1.7) * 0.6;
        double vy = Math.max(-0.5, Math.min(0.5, (wantY - mob.getY()) * 0.15));
        double vx = 0, vz = 0;
        if (d > reach + 2.5) { vx = dx / d * speed; vz = dz / d * speed; }
        if (d > 0.01) {
            double side = Math.sin(tt) * 0.12;
            vx += -dz / d * side;
            vz += dx / d * side;
        }
        mob.setDeltaMovement(vx, vy, vz);
        if (swoopCd <= 0 && d <= 16) swoop = 30;
    }

    // ------------------------------------------------------------------ skills
    private boolean canUse(Skill s, long now, List<ServerPlayer> ps) {
        if (cd.getOrDefault(s, 0L) > now || target == null) return false;
        double d = Math.sqrt(target.distanceToSqr(mob));
        return switch (s) {
            case LEAP -> (d >= 6 && d <= 26) || (airborne(target) && d <= 22);
            case HOOK -> d >= (airborne(target) ? 4 : 8) && d <= 40;
            case BARRAGE -> d <= 34;
            case SMASH -> d <= 14;
            case ROAR -> d <= 24;
            case SUMMON -> minions.size() < WGBosses.config.maxMinions;
            case TELEPORT -> d > 4;
            case DASH -> d > 5;
            case POWER_SHIELD -> !invulnerable();
            case MEND, REPRIEVE, LIFEBLOOM, HEART_HOVER -> healable();
            case RETREAT_HEAL -> healable() && !retreatUsed.contains(phase);
            case INVIS_SPLASH -> invisUntil == 0;
            case SHULKER_BOLT -> d <= 40 && bolts.size() < 4;
            case SPLASH_POISON, SPLASH_WITHER, SPLASH_HARMING, SPLASH_LEVITATION, SPLASH_SLOWNESS, SPLASH_BLINDNESS -> d <= 40;
            case WATERFOWL -> d <= 30;
            case POWER_UP -> mgr.tick >= powerUntil;
            case LIFE_DRAIN -> mgr.tick >= drainUntil && healable();
            case EXECUTION_MARK -> d <= 48;
            case REVERSE_POLARITY -> d <= 32;
            case ECHO_SLAM -> d <= 16;
            case LAGUNA_BLADE -> d >= 6 && d <= 44;
            case RUPTURE -> ruptureId == null;
            default -> true;
        };
    }

    private void maybeStartSkill(long now, List<ServerPlayer> ps) {
        if (now < nextSkillTick) return;
        List<Skill> avail = new ArrayList<>();
        for (Skill s : phaseSkills.get(phase - 1)) if (canUse(s, now, ps)) avail.add(s);
        if (target != null && airborne(target)) {
            List<Skill> air = new ArrayList<>();
            for (Skill s : avail) {
                if (s == Skill.HOOK || s == Skill.BARRAGE || s == Skill.LIGHTNING || s == Skill.ROAR
                        || s == Skill.FLY_POTION || s == Skill.LEAP || s == Skill.SUMMON || s == Skill.SHULKER_BOLT
                        || s == Skill.SUN_STRIKE || s == Skill.LAGUNA_BLADE || s == Skill.EXECUTION_MARK
                        || s == Skill.RUPTURE || s == Skill.REVERSE_POLARITY || s == Skill.WATERFOWL) air.add(s);
            }
            if (!air.isEmpty()) avail = air;
        }
        if (invulnerable()) avail.removeIf(sk -> !(sk == Skill.DASH || sk == Skill.TELEPORT || sk == Skill.LEAP));
        if (avail.isEmpty()) { nextSkillTick = now + 40; return; }
        Skill s = Util.pick(avail);
        cur = s;
        skillT = 0;
        Config hc = WGBosses.config;
        if (!flyer && hoverable(s) && phaseTierIdx(phase) >= hc.hoverMinTier && Util.chance(hc.hoverCastChance)) {
            hovering = true;
            hoverY = mob.getY() + Util.rdbl(hc.hoverHeightMin, Math.max(hc.hoverHeightMin, hc.hoverHeightMax));
            mob.setNoGravity(true);
        }
        cd.put(s, now + (s == Skill.TELEPORT ? Util.rint(600, 1200) : (long) (s.cooldown * Util.rdbl(0.8, 1.3))));
        skillTarget = switch (s) {
            case HOOK -> farthest(ps);
            case TELEPORT, DECOY_SWAP -> Util.pick(ps);
            default -> target;
        };
        if (Util.chance(0.2)) mgr.say(this, "skill", skillTarget, false);
    }

    private void endSkillNow() {
        cur = null;
        skillT = 0;
        hovering = false;
        mob.setNoGravity(flyer);
    }

    private void endSkill(long now) {
        endSkillNow();
        int wait = Util.rint(70, 140 - Math.min(50, (phase - 1) * 12));
        if (target != null && airborne(target)) wait /= 2;
        nextSkillTick = now + wait;
    }

    private List<ServerPlayer> within(List<ServerPlayer> ps, double r) {
        List<ServerPlayer> l = new ArrayList<>();
        for (ServerPlayer p : ps) if (p.distanceToSqr(mob) <= r * r) l.add(p);
        return l;
    }

    private void line(Vec3 from, Vec3 to, ParticleOptions p) {
        Vec3 diff = to.subtract(from);
        double len = diff.length();
        if (len < 0.1) return;
        Vec3 step = diff.scale(1.0 / len);
        for (double s = 0; s <= len && s < 60; s += 1.0) {
            Vec3 q = from.add(step.scale(s));
            level.sendParticles(p, q.x, q.y, q.z, 1, 0, 0, 0, 0);
        }
    }

    private void tickSkill(long now, List<ServerPlayer> ps) {
        skillT++;
        ServerPlayer t = valid(skillTarget) ? skillTarget : ps.get(0);
        face(t);
        double x = mob.getX(), y = mob.getY(), z = mob.getZ();
        double dmg = curDamage();
        double size = mob.getBbWidth();
        Config cfg = WGBosses.config;
        if (hovering) {
            double hv = Math.max(-0.4, Math.min(0.5, (hoverY - y) * 0.2));
            mob.setDeltaMovement(0, hv, 0);
        }

        switch (cur) {
            case DASH -> {
                if (skillT == 1) Util.sound(level, x, y, z, SoundEvents.IRON_GOLEM_ATTACK, 2f, 0.6f);
                puff(ParticleTypes.CLOUD, 5);
                if (skillT >= 12) { dashLeft = Util.rint(40, 100); endSkill(now); }
            }
            case LEAP -> {
                if (skillT < 15) puff(ParticleTypes.LARGE_SMOKE, 4);
                else if (skillT == 15) {
                    double dx = t.getX() - x, dz = t.getZ() - z, d = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
                    double h = Math.min(1.5, 0.4 + d * 0.07);
                    double vy = Math.max(0.75, Math.min(1.6, 0.75 + (t.getY() - y) * 0.07));
                    leapHit = false;
                    mob.setDeltaMovement(dx / d * h, vy, dz / d * h);
                    Util.sound(level, x, y, z, SoundEvents.IRON_GOLEM_ATTACK, 2f, 0.5f);
                } else if (!leapHit && skillT > 15 && !within(ps, 3 + size * 0.5).isEmpty()) {
                    for (ServerPlayer p : within(ps, 3 + size * 0.5)) { hurt(p, dmg * 0.7); Util.push(p, x, z, 0.4, -0.8); }
                    leapHit = true;
                } else if ((skillT > 22 && mob.onGround()) || skillT > 80) {
                    double r = 3 + size * 0.4;
                    for (ServerPlayer p : within(ps, r)) { hurt(p, dmg * 0.7); Util.push(p, x, z, 0.9, 0.45); }
                    ring(ParticleTypes.EXPLOSION, x, y, z, Math.min(r, 8), 12);
                    Util.sound(level, x, y, z, SoundEvents.ANVIL_LAND, 2f, 0.6f);
                    endSkill(now);
                }
            }
            case SMASH -> {
                if (skillT == 1) mob.setDeltaMovement(0, 0.7, 0);
                if (skillT < 26) ring(ParticleTypes.CRIT, x, y, z, Math.min(1 + skillT * 0.3, 3 + size * 0.7), 14);
                else if (skillT == 26) {
                    double r = Math.min(14, 3 + size * 0.7);
                    for (ServerPlayer p : within(ps, r)) { hurt(p, dmg * 0.9); Util.push(p, x, z, 1.1, 0.6); }
                    ring(ParticleTypes.EXPLOSION, x, y, z, r, 20);
                    ring(ParticleTypes.CLOUD, x, y, z, r * 0.6, 20);
                    Util.sound(level, x, y, z, SoundEvents.ANVIL_LAND, 3f, 0.5f);
                    Util.sound(level, x, y, z, SoundEvents.IRON_GOLEM_ATTACK, 3f, 0.4f);
                } else if (skillT >= 30) endSkill(now);
            }
            case ROAR -> {
                if (skillT == 1) Util.sound(level, x, y, z, SoundEvents.ENDER_DRAGON_GROWL, 3f, 0.9f);
                if (skillT < 20) puff(ParticleTypes.ANGRY_VILLAGER, 3);
                else if (skillT == 20) {
                    for (ServerPlayer p : within(ps, 24)) { hurt(p, dmg * 0.25); Util.push(p, x, z, 1.6, 0.5); }
                    ring(ParticleTypes.CLOUD, x, y, z, 6, 30);
                    ring(ParticleTypes.CLOUD, x, y, z, 12, 40);
                    endSkill(now);
                }
            }
            case BARRAGE -> {
                if (skillT < 20) ring(ParticleTypes.END_ROD, x, y + mob.getBbHeight() * 0.5, z, 2 + size * 0.3, 10);
                else if (skillT <= 64 && skillT % 4 == 0) {
                    List<ServerPlayer> in = within(ps, 32);
                    if (!in.isEmpty()) {
                        ServerPlayer v = Util.chance(0.6) && in.contains(t) ? t : Util.pick(in);
                        Vec3 from = new Vec3(x, y + mob.getBbHeight() * 0.6, z);
                        line(from, v.position().add(0, v.getBbHeight() * 0.5, 0), ParticleTypes.SOUL_FIRE_FLAME);
                        hurt(v, dmg * 0.45);
                        Util.push(v, x, z, 0.3, 0.1);
                        Util.sound(level, v.getX(), v.getY(), v.getZ(), SoundEvents.ANVIL_LAND, 0.6f, 1.8f);
                    }
                } else if (skillT > 66) endSkill(now);
            }
            case LIGHTNING -> {
                if (skillT == 1) {
                    strikes.clear();
                    List<ServerPlayer> sh = new ArrayList<>(ps);
                    Collections.shuffle(sh);
                    int n = Math.min(sh.size(), 2 + phase / 2);
                    for (int i = 0; i < n; i++) strikes.add(sh.get(i).position());
                    Util.sound(level, x, y, z, SoundEvents.LIGHTNING_BOLT_THUNDER, 2f, 1.4f);
                }
                if (skillT < 25) {
                    if (skillT % 4 == 0) for (Vec3 s : strikes) ring(ParticleTypes.ELECTRIC_SPARK, s.x, s.y, s.z, 2.5, 16);
                } else if (skillT == 25) {
                    for (Vec3 s : strikes) {
                        EntityType<?> lt = BossManager.byId("minecraft:lightning_bolt");
                        Entity le = lt == null ? null : lt.create(level, EntitySpawnReason.EVENT);
                        if (le instanceof LightningBolt bolt) {
                            bolt.setPos(s.x, s.y, s.z);
                            bolt.setVisualOnly(true);
                            level.addFreshEntity(bolt);
                        }
                        for (ServerPlayer p : ps) {
                            if (p.position().distanceTo(s) <= 3.2) hurt(p, dmg);
                        }
                    }
                } else if (skillT >= 28) endSkill(now);
            }
            case SUMMON -> {
                if (skillT < 30) puff(ParticleTypes.PORTAL, 10);
                else { spawnMinions(2 + phase / 2); endSkill(now); }
            }
            case TELEPORT -> {
                if (skillT == 1) Util.sound(level, x, y, z, SoundEvents.ENDERMAN_TELEPORT, 2f, 0.7f);
                if (skillT < 20) puff(ParticleTypes.PORTAL, 12);
                else {
                    Vec3 look = t.getLookAngle();
                    double hl = Math.max(0.01, Math.sqrt(look.x * look.x + look.z * look.z));
                    double back = 2 + size * 0.5;
                    double bx = t.getX() - look.x / hl * back, bz = t.getZ() - look.z / hl * back;
                    Vec3 dest = new Vec3(bx, t.getY(), bz);
                    if (!Util.safe(level, net.minecraft.core.BlockPos.containing(dest))) {
                        Vec3 alt = Util.findSafe(level, t.getX(), t.getY(), t.getZ(), 2, 5);
                        if (alt != null) dest = alt; else dest = t.position().add(0, 0.5, 0);
                    }
                    mob.setPos(dest.x, dest.y, dest.z);
                    mob.setDeltaMovement(Vec3.ZERO);
                    puff(ParticleTypes.PORTAL, 30);
                    Util.sound(level, dest.x, dest.y, dest.z, SoundEvents.ENDERMAN_TELEPORT, 2f, 0.7f);
                    target = t;
                    targetUntil = now + newTargetTicks();
                    endSkill(now);
                }
            }
            case POWER_SHIELD -> {
                if (skillT < 15) ring(ParticleTypes.ELECTRIC_SPARK, x, y + mob.getBbHeight() * 0.5, z, 1.5 + size * 0.4, 12);
                else {
                    shieldUntil = now + 20L * Util.rint(cfg.shieldMinSeconds, Math.max(cfg.shieldMinSeconds, cfg.shieldMaxSeconds));
                    Util.sound(level, x, y, z, SoundEvents.BEACON_ACTIVATE, 2.5f, 1.2f);
                    announce("The boss is shielded - hold your attacks!", ChatFormatting.AQUA);
                    endSkill(now);
                }
            }
            case MEND -> {
                if (skillT == 1) {
                    Util.sound(level, x, y, z, SoundEvents.BEACON_ACTIVATE, 2f, 1.6f);
                    announce("The boss is mending itself - hit it to stop it!", ChatFormatting.GREEN);
                }
                if (skillT > 5 && now - lastHit < 20) endSkill(now);
                else if (skillT <= 80) {
                    heal(maxHp * cfg.healPercentPerCast / 100.0 / 80.0);
                    if (skillT % 4 == 0) puff(ParticleTypes.HAPPY_VILLAGER, 6);
                } else endSkill(now);
            }
            case REPRIEVE -> {
                if (skillT < 15) puff(ParticleTypes.ANGRY_VILLAGER, 4);
                else {
                    reprieveUntil = now + 100;
                    reprieveRoom = maxHp * cfg.reprievePercent / 100.0;
                    announce("The boss feeds on your blows!", ChatFormatting.RED);
                    endSkill(now);
                }
            }
            case LIFEBLOOM -> {
                if (skillT < 15) puff(ParticleTypes.HAPPY_VILLAGER, 5);
                else {
                    bloomUntil = now + 120;
                    announce("The boss is regenerating!", ChatFormatting.GREEN);
                    endSkill(now);
                }
            }
            case HEART_HOVER -> {
                if (skillT == 1) {
                    hhDamage = 0;
                    if (!flyer) { hovering = true; hoverY = y + 4; mob.setNoGravity(true); }
                    announce("The boss floats up and heals - break its focus!", ChatFormatting.LIGHT_PURPLE);
                }
                if (skillT > 15 && skillT <= 115) {
                    heal(maxHp * cfg.heartHoverPercent / 100.0 / 100.0);
                    if (skillT % 5 == 0) level.sendParticles(ParticleTypes.HEART, x, y + mob.getBbHeight() * 0.8, z, 6, size * 0.4, 0.4, size * 0.4, 0.02);
                } else if (skillT > 115) endSkill(now);
            }
            case INVIS_SPLASH -> {
                if (skillT < 20) ring(ParticleTypes.WITCH, x, y, z, 2 + size * 0.5, 14);
                else {
                    int ticks = cfg.invisSeconds * 20;
                    for (ServerPlayer p : ps) p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, ticks, 0, false, false));
                    for (Mob m : minions) m.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, ticks, 0, false, false));
                    hideBoss(ticks);
                    Util.sound(level, x, y, z, SoundEvents.SPLASH_POTION_BREAK, 2f, 1f);
                    ring(ParticleTypes.CLOUD, x, y, z, 6, 30);
                    announce("Everyone vanished! Careful who you hit.", ChatFormatting.GRAY);
                    endSkill(now);
                }
            }
            case DECOY_SWAP -> {
                if (skillT < 15) {
                    level.sendParticles(ParticleTypes.PORTAL, t.getX(), t.getY() + 1, t.getZ(), 8, 0.4, 0.8, 0.4, 0.3);
                    puff(ParticleTypes.PORTAL, 8);
                } else if (skillT == 15) {
                    Vec3 pp = t.position(), bp = mob.position();
                    t.teleportTo(level, bp.x, bp.y, bp.z, Set.of(), t.getYRot(), t.getXRot(), true);
                    mob.setPos(pp.x, pp.y, pp.z);
                    mob.setDeltaMovement(Vec3.ZERO);
                    addDecoy(t, Util.rdbl(cfg.decoyScaleMin, Math.max(cfg.decoyScaleMin, cfg.decoyScaleMax)), now);
                    hideBoss(cfg.decoySeconds * 20 + 20);
                    double ax = pp.x - bp.x, az = pp.z - bp.z, al = Math.sqrt(ax * ax + az * az);
                    awayDir = al < 0.01 ? new Vec3(1, 0, 0) : new Vec3(ax / al, 0, az / al);
                    Util.sound(level, pp.x, pp.y, pp.z, SoundEvents.ENDERMAN_TELEPORT, 2f, 0.6f);
                    Util.sound(level, bp.x, bp.y, bp.z, SoundEvents.ENDERMAN_TELEPORT, 2f, 0.6f);
                    announce("The boss swapped places with " + t.getName().getString() + "!", ChatFormatting.LIGHT_PURPLE);
                } else if (skillT <= 40) {
                    mob.setDeltaMovement(awayDir.x * 0.9, flyer ? 0 : mob.getDeltaMovement().y, awayDir.z * 0.9);
                } else if (skillT >= cfg.decoySeconds * 20) endSkill(now);
            }
            case RETREAT_HEAL -> {
                if (skillT == 1) {
                    retreatUsed.add(phase);
                    double ax = x - t.getX(), az = z - t.getZ(), al = Math.sqrt(ax * ax + az * az);
                    awayDir = al < 0.01 ? new Vec3(1, 0, 0) : new Vec3(ax / al, 0, az / al);
                    Util.sound(level, x, y, z, SoundEvents.IRON_GOLEM_ATTACK, 2f, 0.6f);
                    announce("The boss is retreating to recover!", ChatFormatting.YELLOW);
                }
                if (skillT <= 10) puff(ParticleTypes.CLOUD, 4);
                else if (movingSkill()) {
                    mob.setDeltaMovement(awayDir.x * 1.1, flyer ? 0 : mob.getDeltaMovement().y, awayDir.z * 1.1);
                    puff(ParticleTypes.CLOUD, 6);
                } else if (skillT == 46) {
                    heal(maxHp * cfg.retreatHealPercent / 100.0);
                    level.sendParticles(ParticleTypes.HEART, x, y + mob.getBbHeight() * 0.8, z, 25, size * 0.5, 0.6, size * 0.5, 0.02);
                    Util.sound(level, x, y, z, SoundEvents.BEACON_POWER_SELECT, 2f, 1.2f);
                } else if (skillT >= 60) endSkill(now);
            }
            case SHULKER_BOLT -> {
                if (skillT < 15) ring(ParticleTypes.END_ROD, x, y + mob.getBbHeight() * 0.5, z, 1.5 + size * 0.4, 10);
                else {
                    int n = Util.rint(1, 3);
                    List<ServerPlayer> sh = new ArrayList<>(ps);
                    Collections.shuffle(sh);
                    for (int i = 0; i < n && !sh.isEmpty(); i++) {
                        Bolt bt = new Bolt();
                        bt.pos = new Vec3(x, y + mob.getBbHeight() * 0.7, z);
                        bt.tgt = sh.get(i % sh.size());
                        bolts.add(bt);
                    }
                    Util.sound(level, x, y, z, SoundEvents.SHULKER_SHOOT, 2f, 1f);
                    endSkill(now);
                }
            }
            case SPLASH_POISON, SPLASH_WITHER, SPLASH_HARMING, SPLASH_LEVITATION, SPLASH_SLOWNESS, SPLASH_BLINDNESS -> {
                if (skillT == 1) {
                    strikes.clear();
                    strikes.add(t.position());
                    List<ServerPlayer> sh = new ArrayList<>(ps);
                    Collections.shuffle(sh);
                    int n = Math.min(3, 1 + phase / 2);
                    for (ServerPlayer p : sh) if (p != t && strikes.size() < n) strikes.add(p.position());
                    Util.sound(level, x, y, z, SoundEvents.SPLASH_POTION_THROW, 2f, 0.8f);
                }
                if (skillT < 30) {
                    if (skillT % 4 == 0) for (Vec3 s : strikes) ring(ParticleTypes.WITCH, s.x, s.y, s.z, 3.0, 18);
                } else {
                    for (Vec3 s : strikes) {
                        ring(ParticleTypes.CLOUD, s.x, s.y, s.z, 3.0, 24);
                        level.sendParticles(ParticleTypes.WITCH, s.x, s.y + 0.5, s.z, 30, 1.5, 0.5, 1.5, 0.05);
                        Util.sound(level, s.x, s.y, s.z, SoundEvents.SPLASH_POTION_BREAK, 2f, 1f);
                        for (ServerPlayer p : ps) if (p.position().distanceTo(s) <= 3.5) splashHit(cur, p);
                    }
                    endSkill(now);
                }
            }
            case WATERFOWL -> {
                if (skillT == 1) {
                    wfTargets.clear();
                    List<ServerPlayer> sh = new ArrayList<>(ps);
                    Collections.shuffle(sh);
                    for (int i = 0; i < 3; i++) wfTargets.add(sh.get(i % sh.size()));
                    mob.setNoGravity(true);
                    announce("The boss readies the Waterfowl Dance - dodge the dashes!", ChatFormatting.RED);
                    Util.sound(level, x, y, z, SoundEvents.IRON_GOLEM_ATTACK, 2f, 0.5f);
                }
                int k = skillT - 21;
                if (k < 0) {
                    mob.setDeltaMovement(Vec3.ZERO);
                    ring(ParticleTypes.END_ROD, x, y + mob.getBbHeight() * 0.5, z, 1.5 + size * 0.4, 8);
                } else if (k < 54) {
                    int idx = k / 18, ph = k % 18;
                    ServerPlayer v = valid(wfTargets.get(idx)) ? wfTargets.get(idx) : t;
                    if (ph == 0) {
                        wfAim = v.position().add(0, v.getBbHeight() * 0.5, 0);
                        invulnUntil = Math.max(invulnUntil, now + 14);
                        Util.sound(level, x, y, z, SoundEvents.PLAYER_ATTACK_SWEEP, 2f, 0.7f);
                    }
                    if (ph < 12) {
                        Vec3 diff = wfAim.subtract(mob.position().add(0, mob.getBbHeight() * 0.5, 0));
                        mob.setDeltaMovement(diff.length() < 1.0 ? Vec3.ZERO : diff.normalize().scale(1.3));
                        puff(ParticleTypes.CLOUD, 4);
                        if (ph == 11) {
                            for (ServerPlayer p : within(ps, 3.5 + size * 0.5)) { hurt(p, dmg * 0.45); Util.push(p, x, z, 0.5, 0.3); }
                            ring(ParticleTypes.CRIT, x, y, z, 3 + size * 0.4, 16);
                        }
                    } else mob.setDeltaMovement(Vec3.ZERO);
                } else if (skillT == 81) {
                    mob.setDeltaMovement(Vec3.ZERO);
                    for (ServerPlayer p : within(ps, 7 + size * 0.4)) { hurt(p, dmg * 0.8); Util.push(p, x, z, 1.0, 0.5); }
                    ring(ParticleTypes.EXPLOSION, x, y, z, 5, 16);
                    ring(ParticleTypes.CLOUD, x, y, z, 8, 30);
                    Util.sound(level, x, y, z, SoundEvents.ANVIL_LAND, 3f, 0.6f);
                } else if (skillT > 85) endSkill(now);
            }
            case POWER_UP -> {
                if (skillT == 1) {
                    puDamage = 0;
                    Util.sound(level, x, y, z, SoundEvents.BEACON_POWER_SELECT, 2.5f, 0.6f);
                    announce("The boss is charging its power - interrupt it!", ChatFormatting.GOLD);
                }
                if (skillT <= 60) ring(ParticleTypes.ELECTRIC_SPARK, x, y + mob.getBbHeight() * 0.5, z, Math.max(0.5, 4 - skillT * 0.05) + size * 0.3, 14);
                else {
                    powerUntil = now + 20L * cfg.powerUpSeconds;
                    announce("The boss is empowered!", ChatFormatting.RED);
                    Util.sound(level, x, y, z, SoundEvents.ENDER_DRAGON_GROWL, 2.5f, 1.2f);
                    ring(ParticleTypes.CLOUD, x, y, z, 6, 30);
                    endSkill(now);
                }
            }
            case LIFE_DRAIN -> {
                if (skillT < 20) puff(ParticleTypes.ANGRY_VILLAGER, 5);
                else {
                    drainUntil = now + 20L * cfg.lifeDrainSeconds;
                    announce("The boss's attacks now heal it!", ChatFormatting.DARK_RED);
                    Util.sound(level, x, y, z, SoundEvents.WITHER_AMBIENT, 2f, 0.8f);
                    endSkill(now);
                }
            }
            case EXECUTION_MARK -> {
                if (skillT == 1) {
                    Util.title(t, Component.literal("EXECUTION MARK").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD),
                            Component.literal("Get out of the circle!").withStyle(ChatFormatting.RED), 5, 40, 10);
                    announce(t.getName().getString() + " is marked for execution!", ChatFormatting.RED);
                }
                if (skillT <= 25) {
                    strikes.clear();
                    strikes.add(t.position());
                    if (skillT % 3 == 0) ring(ParticleTypes.SOUL_FIRE_FLAME, t.getX(), t.getY(), t.getZ(), 3.5, 20);
                } else if (skillT < 60) {
                    if (!strikes.isEmpty() && skillT % 3 == 0) {
                        Vec3 s = strikes.get(0);
                        ring(ParticleTypes.FLAME, s.x, s.y, s.z, 3.5, 24);
                        ring(ParticleTypes.SOUL_FIRE_FLAME, s.x, s.y, s.z, 2.0, 14);
                    }
                } else if (skillT == 60) {
                    if (!strikes.isEmpty()) {
                        Vec3 s = strikes.get(0);
                        for (double yy = 0; yy < 12; yy += 0.7) level.sendParticles(ParticleTypes.ELECTRIC_SPARK, s.x, s.y + yy, s.z, 3, 0.4, 0, 0.4, 0.02);
                        ring(ParticleTypes.EXPLOSION, s.x, s.y, s.z, 3.5, 12);
                        Util.sound(level, s.x, s.y, s.z, SoundEvents.LIGHTNING_BOLT_THUNDER, 3f, 1.2f);
                        for (ServerPlayer p : ps) {
                            if (Util.flat(p.getX(), p.getZ(), s.x, s.z) <= 3.8 && Math.abs(p.getY() - s.y) < 5) {
                                hurt(p, dmg * 2.0);
                                Util.push(p, s.x, s.z, 0.6, 0.6);
                            }
                        }
                    }
                } else if (skillT > 64) endSkill(now);
            }
            case RAVAGE -> {
                double rv = cfg.ravageRadius;
                if (skillT == 1) {
                    hitSet.clear();
                    Util.sound(level, x, y, z, SoundEvents.ENDER_DRAGON_GROWL, 3f, 0.6f);
                    announce("The boss is about to Ravage - jump when the ring reaches you!", ChatFormatting.DARK_RED);
                }
                if (skillT < 30) {
                    if (skillT % 5 == 0) ring(ParticleTypes.CRIT, x, y, z, rv, 60);
                    puff(ParticleTypes.LARGE_SMOKE, 4);
                } else {
                    double r = (skillT - 30) * 1.2;
                    if (skillT == 30) Util.sound(level, x, y, z, SoundEvents.ANVIL_LAND, 3f, 0.5f);
                    ring(ParticleTypes.SOUL_FIRE_FLAME, x, y, z, r, Math.max(12, Math.min(60, (int) (r * 3))));
                    ring(ParticleTypes.CLOUD, x, y, z, r, Math.max(12, Math.min(60, (int) (r * 3))));
                    for (ServerPlayer p : ps) {
                        if (hitSet.contains(p.getUUID()) || !p.onGround() || Math.abs(p.getY() - y) > 8) continue;
                        if (band(p, x, z, r, 1.4)) {
                            hitSet.add(p.getUUID());
                            hurt(p, dmg * 0.6);
                            p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 25, 5));
                            Util.push(p, x, z, 0.1, 1.0);
                            Util.sound(level, p.getX(), p.getY(), p.getZ(), SoundEvents.ANVIL_LAND, 1.5f, 1.2f);
                        }
                    }
                    if (r > rv) endSkill(now);
                }
            }
            case REVERSE_POLARITY -> {
                if (skillT == 1) {
                    Util.sound(level, x, y, z, SoundEvents.ENDERMAN_TELEPORT, 3f, 0.5f);
                    announce("The boss is pulling everyone in!", ChatFormatting.DARK_PURPLE);
                }
                if (skillT < 25) ring(ParticleTypes.PORTAL, x, y + 0.5, z, Math.max(1, 14 - skillT * 0.5), 24);
                else {
                    double dx = t.getX() - x, dz = t.getZ() - z, dl = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
                    double px = x + dx / dl * (size * 0.5 + 2.5), pz = z + dz / dl * (size * 0.5 + 2.5);
                    for (ServerPlayer p : within(ps, 32)) {
                        Vec3 dest = Util.findSafe(level, px, y, pz, 0.3, 2.5);
                        if (dest == null) continue;
                        p.teleportTo(level, dest.x, dest.y, dest.z, Set.of(), p.getYRot(), p.getXRot(), true);
                        p.setDeltaMovement(Vec3.ZERO);
                        Util.sendMotion(p);
                        hurt(p, dmg * 0.6);
                        p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 20, 5));
                    }
                    ring(ParticleTypes.PORTAL, px, y + 0.5, pz, 2.5, 20);
                    Util.sound(level, x, y, z, SoundEvents.ENDER_DRAGON_GROWL, 2f, 1.4f);
                    endSkill(now);
                }
            }
            case SUN_STRIKE -> {
                if (skillT == 1) {
                    strikes.clear();
                    List<ServerPlayer> sh = new ArrayList<>(ps);
                    Collections.shuffle(sh);
                    int n = Math.min(sh.size(), 2 + phase);
                    for (int i = 0; i < n; i++) strikes.add(sh.get(i).position());
                    Util.sound(level, x, y, z, SoundEvents.FIRECHARGE_USE, 2f, 0.6f);
                    announce("Sun Strikes incoming - get out of the circles!", ChatFormatting.GOLD);
                }
                if (skillT < 50) {
                    if (skillT % 3 == 0) for (Vec3 s : strikes) {
                        ring(ParticleTypes.FLAME, s.x, s.y, s.z, 2.5, 16);
                        level.sendParticles(ParticleTypes.END_ROD, s.x, s.y + 0.3, s.z, 2, 0.3, 0.1, 0.3, 0.01);
                    }
                } else {
                    for (Vec3 s : strikes) {
                        List<ServerPlayer> in = new ArrayList<>();
                        for (ServerPlayer p : ps) if (Util.flat(p.getX(), p.getZ(), s.x, s.z) <= 2.8 && Math.abs(p.getY() - s.y) < 4) in.add(p);
                        for (double yy = 0; yy < 14; yy += 0.7) level.sendParticles(ParticleTypes.END_ROD, s.x, s.y + yy, s.z, 2, 0.4, 0, 0.4, 0);
                        ring(ParticleTypes.FLAME, s.x, s.y, s.z, 2.5, 20);
                        Util.sound(level, s.x, s.y, s.z, SoundEvents.LIGHTNING_BOLT_THUNDER, 1.5f, 1.8f);
                        if (!in.isEmpty()) {
                            double each = Math.max(dmg * 0.35, dmg * 1.4 / in.size());
                            for (ServerPlayer p : in) hurt(p, each);
                        }
                    }
                    endSkill(now);
                }
            }
            case ECHO_SLAM -> {
                if (skillT < 15) {
                    if (skillT == 1) announce("Echo Slam - the more of you in range, the more echoes!", ChatFormatting.AQUA);
                    ring(ParticleTypes.CRIT, x, y, z, 1 + skillT * 0.4, 12);
                } else if (skillT < 30) {
                    if (skillT == 15) { hitSet.clear(); echoCount = 0; Util.sound(level, x, y, z, SoundEvents.ANVIL_LAND, 3f, 0.7f); }
                    double r = skillT - 15;
                    ring(ParticleTypes.CLOUD, x, y, z, r, 24);
                    for (ServerPlayer p : ps) {
                        if (!hitSet.contains(p.getUUID()) && band(p, x, z, r, 1.5)) {
                            hitSet.add(p.getUUID());
                            echoCount++;
                            hurt(p, dmg * 0.3);
                            Util.push(p, x, z, 0.5, 0.4);
                        }
                    }
                    if (skillT == 29) echoRounds = Math.min(cfg.echoMaxRounds, echoCount * 2);
                } else {
                    int k = (skillT - 30) / 10, local = (skillT - 30) % 10;
                    if (k >= echoRounds) endSkill(now);
                    else {
                        if (local == 0) { hitSet.clear(); Util.sound(level, x, y, z, SoundEvents.ANVIL_LAND, 2f, 1.4f); }
                        double r = local * 1.4;
                        ring(ParticleTypes.SOUL_FIRE_FLAME, x, y, z, r, 28);
                        for (ServerPlayer p : ps) {
                            if (!hitSet.contains(p.getUUID()) && band(p, x, z, r, 1.6)) {
                                hitSet.add(p.getUUID());
                                hurt(p, dmg * 0.25);
                                Util.push(p, x, z, 0.3, 0.3);
                            }
                        }
                    }
                }
            }
            case LAGUNA_BLADE -> {
                Vec3 from = new Vec3(x, y + mob.getBbHeight() * 0.6, z);
                if (skillT == 1) announce("The boss charges a laser - break the line of sight!", ChatFormatting.AQUA);
                if (skillT < 40) {
                    Vec3 aim = t.position().add(0, t.getBbHeight() * 0.5, 0);
                    lagFrom = from;
                    lagDir = aim.subtract(from).normalize();
                    if (skillT % 2 == 0) line(from, aim, ParticleTypes.END_ROD);
                } else if (skillT < 55) {
                    if (skillT % 2 == 0) line(lagFrom, lagFrom.add(lagDir.scale(40)), ParticleTypes.ELECTRIC_SPARK);
                } else if (skillT == 55) {
                    Vec3 end = lagFrom.add(lagDir.scale(40));
                    line(lagFrom, end, ParticleTypes.ELECTRIC_SPARK);
                    line(lagFrom, end, ParticleTypes.END_ROD);
                    Util.sound(level, x, y, z, SoundEvents.LIGHTNING_BOLT_THUNDER, 3f, 1.6f);
                    for (ServerPlayer p : ps) {
                        if (segDist(lagFrom, end, p.position().add(0, p.getBbHeight() * 0.5, 0)) <= 1.8) hurt(p, dmg * 2.2);
                    }
                } else if (skillT > 58) endSkill(now);
            }
            case RUPTURE -> {
                if (skillT < 20) {
                    line(new Vec3(x, y + mob.getBbHeight() * 0.6, z), t.position().add(0, 1, 0), ParticleTypes.DAMAGE_INDICATOR);
                } else {
                    ruptureId = t.getUUID();
                    ruptureUntil = now + 20L * cfg.ruptureSeconds;
                    ruptureLast = t.position();
                    ruptureAcc = 0;
                    Util.title(t, Component.literal("RUPTURE").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD),
                            Component.literal("Stand still!").withStyle(ChatFormatting.RED), 5, 40, 10);
                    announce(t.getName().getString() + " is ruptured - moving hurts!", ChatFormatting.DARK_RED);
                    Util.sound(level, t.getX(), t.getY(), t.getZ(), SoundEvents.WITHER_SPAWN, 0.8f, 1.8f);
                    endSkill(now);
                }
            }
            case FLY_POTION -> {
                mob.setNoGravity(true);
                mob.setDeltaMovement(Vec3.ZERO);
                if (skillT <= 25) {
                    mob.setPos(x, y + 0.35, z);
                    puff(ParticleTypes.CLOUD, 4);
                } else if (skillT <= 80) {
                    if (skillT % 14 == 0) throwCloud(ps);
                } else endSkill(now);
            }
            case HOOK -> {
                Vec3 hook = new Vec3(x, y + mob.getBbHeight() * 0.4, z);
                Vec3 tp = t.position().add(0, t.getBbHeight() * 0.5, 0);
                if (skillT == 1) Util.sound(level, x, y, z, SoundEvents.ANVIL_LAND, 2f, 1.6f);
                if (skillT < 25) line(hook, tp, ParticleTypes.CRIT);
                else {
                    Vec3 diff = hook.subtract(tp);
                    double dist = diff.length();
                    double stop = size * 0.5 + 1.8;
                    if (dist <= stop || skillT > 100) {
                        hurt(t, dmg * 0.8);
                        t.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 30, 3));
                        endSkill(now);
                    } else {
                        Vec3 v = diff.normalize().scale(Math.min(1.2, 0.5 + dist * 0.05));
                        t.setDeltaMovement(v.x, v.y * 0.6 + 0.08, v.z);
                        Util.sendMotion(t);
                        line(hook, tp, ParticleTypes.CRIT);
                    }
                }
            }
        }
    }

    private void throwCloud(List<ServerPlayer> ps) {
        if (ps.isEmpty()) return;
        ServerPlayer v = Util.pick(ps);
        var effect = switch (Util.rint(0, 4)) {
            case 0 -> MobEffects.WITHER;
            case 1 -> MobEffects.POISON;
            case 2 -> MobEffects.SLOWNESS;
            case 3 -> MobEffects.WEAKNESS;
            default -> MobEffects.MINING_FATIGUE;
        };
        AreaEffectCloud cloud = new AreaEffectCloud(level, v.getX(), v.getY(), v.getZ());
        cloud.setOwner(mob);
        cloud.setRadius(3.0f);
        cloud.setDuration(100);
        cloud.setWaitTime(25);
        cloud.addEffect(new MobEffectInstance(effect, 80, 0));
        level.addFreshEntity(cloud);
        Util.sound(level, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.SPLASH_POTION_THROW, 2f, 0.8f);
    }

    // ------------------------------------------------------------------ minions
    public void spawnMinions(int n) {
        Config c = WGBosses.config;
        for (int i = 0; i < n && minions.size() < c.maxMinions; i++) {
            try {
                Entity e = type.create(level, EntitySpawnReason.COMMAND);
                if (!(e instanceof Mob m)) return;
                Vec3 p = Util.findSafe(level, mob.getX(), mob.getY(), mob.getZ(), 3, 8);
                if (p == null) p = mob.position().add(Util.rdbl(-4, 4), 0, Util.rdbl(-4, 4));
                m.setPos(p.x, p.y, p.z);
                try { m.finalizeSpawn(level, level.getCurrentDifficultyAt(m.blockPosition()), EntitySpawnReason.COMMAND, null); }
                catch (Throwable ignored) { }
                AttributeInstance sc = m.getAttribute(Attributes.SCALE);
                if (sc != null) sc.setBaseValue(Util.rdbl(0.7, 1.2));
                mgr.remember(m);
                m.setPersistenceRequired();
                if (flyer) m.setNoGravity(true);
                level.addFreshEntity(m);
                minions.add(m);
                mgr.minionOwner.put(m.getUUID(), this);
                level.sendParticles(ParticleTypes.POOF, p.x, p.y + 0.5, p.z, 12, 0.3, 0.3, 0.3, 0.05);
            } catch (Throwable t) {
                WGBosses.LOG.warn("Could not spawn minion of {}", type, t);
                return;
            }
        }
    }

    private void tickMinions(List<ServerPlayer> ps) {
        Iterator<Mob> it = minions.iterator();
        while (it.hasNext()) {
            Mob m = it.next();
            if (m.isRemoved() || !m.isAlive()) { mgr.minionOwner.remove(m.getUUID()); it.remove(); continue; }
            clearEffects(m);
            ServerPlayer best = null; double bd = 900;
            for (ServerPlayer p : ps) { double d = p.distanceToSqr(m); if (d < bd) { bd = d; best = p; } }
            if (best == null) continue;
            AttributeInstance mi = m.getAttribute(Attributes.MOVEMENT_SPEED);
            double sp = Math.min(0.6, Math.max(0.2, mi != null ? mi.getValue() : 0.25) * 0.9);
            double dx = best.getX() - m.getX(), dz = best.getZ() - m.getZ();
            double d = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            m.setYRot(yaw); m.setYHeadRot(yaw);
            Vec3 dm = m.getDeltaMovement();
            if (d > 1.6) {
                double vy = dm.y;
                if (flyer) vy = Math.max(-0.4, Math.min(0.4, (best.getY() + 1.5 - m.getY()) * 0.15));
                else if (m.horizontalCollision && m.onGround()) vy = 0.45;
                m.setDeltaMovement(dx / d * sp, vy, dz / d * sp);
            } else if (m.tickCount % 20 == 0) {
                hurt(best, Math.max(1, curDamage() * 0.25));
            }
        }
    }

    public void discardAll() {
        clearDecoys();
        mob.setNoGravity(false);
        bar.removeAllPlayers();
        for (Mob m : minions) { mgr.minionOwner.remove(m.getUUID()); m.discard(); }
        minions.clear();
        if (!mob.isRemoved()) mob.discard();
    }
}
