package com.worstgen.wgbosses;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
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
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.GoalSelector;
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
    private boolean lowSaid;

    Boss(BossManager mgr, int id, ServerLevel level, Mob mob, BossManager.Spec s, String name, int playersOnline) {
        Config c = WGBosses.config;
        this.mgr = mgr; this.id = id; this.level = level; this.mob = mob; this.type = mob.getType();
        this.trueTier = s.tier; this.calamity = s.calamity; this.rainbow = s.rainbow; this.bloodMoon = s.blood;
        this.abnormal = s.abnormal; this.personality = s.personality; this.baseName = name;
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
                if (sk.minTier <= pIdx && !prev.contains(sk) && !(sk == Skill.SUMMON && p < 2)) pool.add(sk);
            }
            Collections.shuffle(pool);
            List<Skill> now = new ArrayList<>(prev);
            while (now.size() < n && !pool.isEmpty()) now.add(pool.remove(0));
            phaseSkills.add(now);
            prev = now;
        }

        applyPhase(1);
        mob.setCustomName(displayName());
        mob.setCustomNameVisible(true);
        bar = new ServerBossEvent(UUID.randomUUID(), barTitle(), color(), BossEvent.BossBarOverlay.PROGRESS);
        refreshBar();
        resetDespawn(0);
        nextSkillTick = 100;
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

    private boolean valid(ServerPlayer p) {
        return p != null && p.isAlive() && !p.isSpectator() && p.level() == level;
    }

    private void hurt(ServerPlayer p, double amount) {
        if (!p.isAlive() || amount <= 0) return;
        p.hurtServer(level, level.damageSources().mobAttack(mob), (float) amount);
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
        if (transition > 0) return false;
        Entity a = src.getEntity();
        ServerPlayer attacker = a instanceof ServerPlayer sp ? sp : null;
        double floor = lower(phase);
        double eff = Math.min(amount, Math.max(0, hp - floor));
        hp -= amount;
        if (attacker != null) {
            damage.merge(attacker.getUUID(), eff, Double::sum);
            resetDespawn(mgr.tick);
            if (Util.chance(0.12)) mgr.say(this, "hit", attacker, false);
        }
        if (hp <= floor) {
            hp = floor;
            if (phase < phases) startTransition();
            else mgr.onBossKilled(this, attacker);
            return false;
        }
        mob.setHealth(mob.getMaxHealth());
        return true;
    }

    // ------------------------------------------------------------------ phase transition
    private void startTransition() {
        transition = 60;
        endSkillNow();
        Util.sound(level, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.ENDER_DRAGON_GROWL, 3.0f, 0.8f);
    }

    private void tickTransition() {
        transition--;
        mob.setDeltaMovement(0, mob.getDeltaMovement().y, 0);
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
        mob.clearFire();
        mob.setAirSupply(mob.getMaxAirSupply());
        safety(now);
        if (now >= despawnAt) { mgr.despawn(this); return; }
        if (now % 5 == 0) refreshBar();
        if (now % 20 == 0) syncBar();
        if (rainbow && now % 8 == 0) bar.setColor(color());

        if (transition > 0) { tickTransition(); return; }

        List<ServerPlayer> ps = players();
        tickMinions(ps);
        if (ps.isEmpty()) return;
        validateTarget(ps, now);
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
        if (!valid(target)) {
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
        targetUntil = now + Util.rint(200, 400);
        if (m == Mode.FARTHEST && n > 1 && cur == null) dashLeft = Util.rint(40, 100);
    }

    // ------------------------------------------------------------------ movement
    private boolean frozen() { return transition > 0 || cur != null; }

    private void move() {
        if (target == null) return;
        face(target);
        Vec3 dm = mob.getDeltaMovement();
        if (frozen()) {
            if (cur != Skill.LEAP) mob.setDeltaMovement(0, dm.y, 0);
            return;
        }
        double dx = target.getX() - mob.getX(), dz = target.getZ() - mob.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        double reach = mob.getBbWidth() * 0.5 + 1.8;
        double speed = Math.min(1.0, curSpeed * 0.85 * (dashLeft > 0 ? 2.4 : 1.0));
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
    }

    // ------------------------------------------------------------------ skills
    private boolean canUse(Skill s, long now, List<ServerPlayer> ps) {
        if (cd.getOrDefault(s, 0L) > now || target == null) return false;
        double d = Math.sqrt(target.distanceToSqr(mob));
        return switch (s) {
            case LEAP -> d >= 6 && d <= 26;
            case HOOK -> d >= 8 && d <= 40;
            case BARRAGE -> d <= 34;
            case SMASH -> d <= 14;
            case ROAR -> d <= 24;
            case SUMMON -> minions.size() < WGBosses.config.maxMinions;
            case TELEPORT -> d > 4;
            case DASH -> d > 5;
            default -> true;
        };
    }

    private void maybeStartSkill(long now, List<ServerPlayer> ps) {
        if (now < nextSkillTick) return;
        List<Skill> avail = new ArrayList<>();
        for (Skill s : phaseSkills.get(phase - 1)) if (canUse(s, now, ps)) avail.add(s);
        if (avail.isEmpty()) { nextSkillTick = now + 40; return; }
        Skill s = Util.pick(avail);
        cur = s;
        skillT = 0;
        cd.put(s, now + (s == Skill.TELEPORT ? Util.rint(600, 1200) : (long) (s.cooldown * Util.rdbl(0.8, 1.3))));
        skillTarget = switch (s) {
            case HOOK -> farthest(ps);
            case TELEPORT -> Util.pick(ps);
            default -> target;
        };
        if (Util.chance(0.2)) mgr.say(this, "skill", skillTarget, false);
    }

    private void endSkillNow() {
        cur = null;
        skillT = 0;
        mob.setNoGravity(false);
    }

    private void endSkill(long now) {
        endSkillNow();
        nextSkillTick = now + Util.rint(70, 140 - Math.min(50, (phase - 1) * 12));
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
                    mob.setDeltaMovement(dx / d * h, 0.75, dz / d * h);
                    Util.sound(level, x, y, z, SoundEvents.IRON_GOLEM_ATTACK, 2f, 0.5f);
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
                    targetUntil = now + Util.rint(200, 400);
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
                if (m.horizontalCollision && m.onGround()) vy = 0.45;
                m.setDeltaMovement(dx / d * sp, vy, dz / d * sp);
            } else if (m.tickCount % 20 == 0) {
                hurt(best, Math.max(1, curDamage() * 0.25));
            }
        }
    }

    public void discardAll() {
        mob.setNoGravity(false);
        bar.removeAllPlayers();
        for (Mob m : minions) { mgr.minionOwner.remove(m.getUUID()); m.discard(); }
        minions.clear();
        if (!mob.isRemoved()) mob.discard();
    }
}
