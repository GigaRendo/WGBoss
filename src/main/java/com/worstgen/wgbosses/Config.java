package com.worstgen.wgbosses;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Main config: config/wgbosses/config.json */
public final class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static final class TierDef {
        public String name;
        public int weight;
        public double sizeMin, sizeMax;
        public int phasesMin, phasesMax;
        public double health, damage, speedMult;
        public int skillMin, skillMax;
        public int shardMin, shardMax;
        public int xpMin, xpMax;
        /** Damage per hit as % of the TARGET's max health (so it stays threatening with huge health/protection). */
        public double hitPercent;
        /** Silent damage cap: max damage one hit can do, as % of the boss's max HP. */
        public double hitCapPercent;
        /** Silent damage cap: max damage ONE player can do per second, as % of the boss's max HP. */
        public double dpsCapPercent;
        /** Whole-fight limit: ALL players combined can't deal more than maxHp / this per second (= fastest possible kill, in seconds). */
        public double minFightSeconds;
        /** Wait before the next boss after this tier's boss ends (minutes). */
        public int cooldownMinMinutes, cooldownMaxMinutes;
    }

    public static final class Zone {
        public String dimension = "minecraft:overworld";
        public double x = 0, z = 0, radius = 200;
    }

    public static final class RewardEntry {
        public String item;
        public int min, max;
        public double chance = 1.0;
        public RewardEntry() {}
        public RewardEntry(String item, int min, int max, double chance) {
            this.item = item; this.min = min; this.max = max; this.chance = chance;
        }
    }

    // ---- spawning ----
    /** false = no automatic boss spawns or warnings (admin /wgboss spawn still works). */
    public boolean autoSpawn = true;
    public int minPlayers = 3;
    public int spawnMinMinutes = 45;
    public int spawnMaxMinutes = 90;
    public int[] warningMinutesBefore = {5, 3, 1};
    public int despawnMinMinutes = 10;
    public int despawnMaxMinutes = 20;
    public int spawnMinDistance = 30;
    public int spawnMaxDistance = 100;
    public int teleportMinDistance = 10;
    public int teleportMaxDistance = 20;
    public List<String> allowedDimensions = new ArrayList<>(List.of("minecraft:overworld", "minecraft:the_nether"));

    // ---- variants ----
    public double rainbowChance = 0.4;
    public double calamityChance = 0.05;
    public double healthPerExtraPlayerPercent = 15;
    public double healthScaleCapPercent = 300;
    public int maxMinions = 4;
    /** Bosses only target/chase players within this many blocks. */
    public int targetRange = 64;
    /** How long a boss stays on one target before re-picking (seconds). */
    public int targetMinSeconds = 6;
    public int targetMaxSeconds = 12;
    /** true = boss damage ignores armor/protection enchants (magic damage). */
    public boolean armorBypass = true;
    /** true = a single boss hit can never kill a player (leaves half a heart), unless they are already at 1 HP. */
    public boolean neverOneShot = true;

    // ---- native behaviour / arena / rescue ----
    /** true = the mob keeps its own AI (bows, thrown ice blocks, ...) and the boss adds skills on top. */
    public boolean keepNativeBehavior = true;
    /** Boss melee hit = this x a normal boss hit (percent-of-max-HP system). */
    public double nativeMeleeRatio = 1.0;
    /** Boss projectile/ranged hit = this x a normal boss hit. */
    public double nativeProjectileRatio = 0.7;
    /** Boss that ends up this many blocks below the nearest player is teleported back up. */
    public double fallRescueDepth = 6;
    /** true = bosses only spawn in the arenas below (a random one each time). */
    public boolean arenaEnabled = false;
    public List<Arena> arenas = new ArrayList<>();
    /** Boss stays within this many blocks above/below the arena's y. */
    public double arenaYRange = 3;

    public static final class Arena {
        public String name = "";
        public String dimension = "minecraft:overworld";
        public double x = 0, y = 64, z = 0, radius = 50;
    }

    public Arena arenaByName(String n) {
        if (n == null) return null;
        for (Arena a : arenas) if (a.name.equalsIgnoreCase(n)) return a;
        return null;
    }

    // ---- spawn timing / zones ----
    /** After a boss ends, the wait for the next one depends on that boss's tier (see tiers). */
    public boolean tierCooldowns = true;
    public boolean pityEnabled = true;
    /** After this many bosses in a row at pityLowTier or below, the next is at least pityMinTier. */
    public int pityStreak = 3;
    public int pityLowTier = 2;
    public int pityMinTier = 3;
    /** Auto-spawns never happen inside these circles (edit x/z/radius; dimension id). */
    public List<Zone> noSpawnZones = new ArrayList<>(List.of(new Zone()));

    // ---- affixes ----
    public boolean affixesEnabled = true;
    public int affixMinTier = 2;
    public double affixChance = 0.6;
    public int affixSecondMinTier = 3;
    public double affixSecondChance = 0.35;
    public List<String> affixPool = new ArrayList<>(List.of(
            "SWIFT", "VAMPIRIC", "THORNED", "BERSERKER", "REGENERATING", "WARDED", "BLINKER", "TITAN"));

    // ---- enrage / wound / flight nerf ----
    public boolean enrageEnabled = true;
    public int enrageMinutes = 8;
    public double enrageDamageMult = 1.5;
    public double enrageSpeedMult = 1.25;
    public boolean enrageGlow = true;
    public boolean woundEnabled = true;
    public double woundReducePercent = 50;
    public double woundEnragedReducePercent = 75;
    public boolean flightNerf = true;
    public int flightGraceSeconds = 10;
    public double flightStartPercent = 5;
    public double flightStepPercent = 2;
    public double flightMaxPercent = 25;
    public int flightResetSeconds = 3;
    public boolean flightLethal = true;

    // ---- boss damage scaling ----
    /** Global multiplier on all boss damage to players. */
    public double bossDamageMult = 1.5;
    /** Extra boss damage per player in range beyond minPlayers (percent each). */
    public double damagePerExtraPlayerPercent = 8;
    /** Cap on that extra damage (percent). */
    public double damageScaleCapPercent = 150;

    // ---- toughness (silent, no messages) ----
    public boolean effectImmunity = true;
    /** Potion effects the boss and its minions shrug off (slowness covers the Frozen enchant). */
    public List<String> immuneEffects = new ArrayList<>(List.of(
            "slowness", "weakness", "mining_fatigue", "levitation", "wither", "poison", "blindness", "nausea"));
    /** Fire can only visibly burn the boss this long. 0 = no burning at all. */
    public int maxBurnSeconds = 2;
    public boolean removeCobwebs = true;
    /** Per-tier caps live in each tier (hitCapPercent / dpsCapPercent). */
    public boolean damageCapEnabled = true;

    // ---- totem / healing ----
    /** Chance a boss spawns with a hidden totem that brings it back one phase when it would die. */
    public double totemChance = 0.30;
    /** How much of the restored phase's health bar is refilled (percent). */
    public double totemRefillPercent = 100;
    /** A boss can never heal more than this % of its max HP over the whole fight. */
    public double maxTotalHealPercent = 40;

    // ---- new skills ----
    public int shieldMinSeconds = 5, shieldMaxSeconds = 10;
    public double healPercentPerCast = 5;
    public double reprievePercent = 5;
    public double bloomPercent = 3;
    public double heartHoverPercent = 5;
    /** Heart Hover is cancelled if players deal this % of max HP during it. */
    public double heartHoverInterruptPercent = 2;
    public int invisSeconds = 5;
    public double decoyScaleMin = 2, decoyScaleMax = 5;
    public int decoySeconds = 6;
    public double retreatHealPercent = 25;
    public int shulkerLevitationMinSeconds = 3, shulkerLevitationMaxSeconds = 5;
    public int splashSeconds = 4;

    // ---- attack skills ----
    public int powerUpSeconds = 15;
    public double powerUpDamageMult = 1.5;
    /** Power Up is broken (boss stunned 2 s) if players deal this % of max HP during the 3 s charge. */
    public double powerUpInterruptPercent = 4;
    public int lifeDrainSeconds = 12;
    /** While Life Drain is active, each hit the boss lands heals it this % of max HP. */
    public double lifeDrainHealPercent = 1.0;
    public double ravageRadius = 24;
    public int echoMaxRounds = 6;
    public int ruptureSeconds = 8;

    // ---- flying ----
    /** Exact entity ids that fly (e.g. "modid:butterfly"). */
    public List<String> flyingMobs = new ArrayList<>();
    /** Entity ids containing one of these words (split by _) are treated as flyers. */
    public List<String> flyingMobKeywords = new ArrayList<>(List.of(
            "butterfly", "fly", "firefly", "dragonfly", "bird", "bat", "bee", "moth", "wasp", "bug", "wisp", "ghost"));
    public double flyerHeightMin = 3.0;
    public double flyerHeightMax = 6.0;
    /** Chance a ground boss floats up while casting a ranged skill. */
    public double hoverCastChance = 0.30;
    public int hoverMinTier = 2;
    public double hoverHeightMin = 3.0;
    public double hoverHeightMax = 5.0;

    // ---- blood moon ----
    public double bloodMoonChance = 0.08;
    public int bloodMoonMaxTierIndex = 4;
    public double bloodMoonHealthMult = 0.65;
    public double bloodMoonDamageMult = 0.75;

    // ---- chat ----
    public int chatCooldownSeconds = 6;
    public double taglishChance = 0.125;

    // ---- rewards ----
    public String shardItem = "remnant_mobs:remnant_shard";
    public int topDamageShardMin = 1;
    public int topDamageShardMax = 3;
    public double participationPercentForStats = 2.0;
    public int chestLifetimeSeconds = 300;
    public Map<String, List<RewardEntry>> rewards = new LinkedHashMap<>();
    /** Rolled separately for EACH participant (small-chance extras like totems). */
    public Map<String, List<RewardEntry>> perPlayerRewards = new LinkedHashMap<>();
    public Map<String, List<RewardEntry>> topDamageBonusRewards = new LinkedHashMap<>();

    public List<TierDef> tiers = new ArrayList<>();

    /** Supports exact ids and "modid:*" wildcards. */
    public List<String> mobBlacklist = new ArrayList<>(List.of(
            "minecraft:ender_dragon", "minecraft:wither", "minecraft:ghast", "minecraft:phantom",
            "minecraft:vex", "minecraft:bat", "minecraft:bee", "minecraft:allay", "minecraft:parrot",
            "minecraft:blaze", "minecraft:happy_ghast"));

    public Config() {
        tiers.add(t("Common", 40, 2, 3, 1, 2, 200, 5, 0.90, 1, 2, 5, 8, 300, 500));
        tiers.add(t("Uncommon", 25, 2, 4, 1, 2, 350, 6, 0.95, 1, 2, 8, 12, 500, 800));
        tiers.add(t("Rare", 17, 3, 6, 2, 2, 600, 8, 1.00, 2, 2, 12, 16, 800, 1200));
        tiers.add(t("Epic", 10, 4, 7, 2, 3, 900, 10, 1.05, 2, 3, 16, 22, 1200, 1800));
        tiers.add(t("Legendary", 6, 6, 9, 3, 3, 1400, 13, 1.10, 2, 3, 22, 30, 2000, 3000));
        tiers.add(t("Mythic", 2, 8, 10, 3, 3, 2000, 16, 1.20, 3, 3, 30, 40, 3500, 5000));
        tiers.add(t("Calamity", 0, 10, 12, 5, 5, 3000, 20, 1.25, 3, 4, 50, 70, 6000, 9000));
        double[] pct = {5, 6, 8, 10, 13, 16, 20};
        for (int i = 0; i < tiers.size() && i < pct.length; i++) tiers.get(i).hitPercent = pct[i];
        double[] hitCap = {5, 4.5, 4, 3.5, 3, 2.5, 2};
        double[] dpsCap = {10, 9, 8, 7, 6, 5, 4};
        double[] fightSec = {90, 120, 180, 240, 360, 480, 600};
        int[] cdMin = {20, 25, 30, 35, 40, 45, 60};
        int[] cdMax = {30, 35, 40, 45, 50, 55, 75};
        for (int i = 0; i < tiers.size() && i < hitCap.length; i++) {
            tiers.get(i).hitCapPercent = hitCap[i];
            tiers.get(i).dpsCapPercent = dpsCap[i];
            tiers.get(i).minFightSeconds = fightSec[i];
            tiers.get(i).cooldownMinMinutes = cdMin[i];
            tiers.get(i).cooldownMaxMinutes = cdMax[i];
        }
        buildDefaultRewards();
    }

    private static TierDef t(String n, int w, double sMin, double sMax, int pMin, int pMax, double hp, double dmg,
                             double spd, int skMin, int skMax, int shMin, int shMax, int xMin, int xMax) {
        TierDef d = new TierDef();
        d.name = n; d.weight = w; d.sizeMin = sMin; d.sizeMax = sMax; d.phasesMin = pMin; d.phasesMax = pMax;
        d.health = hp; d.damage = dmg; d.speedMult = spd; d.skillMin = skMin; d.skillMax = skMax;
        d.shardMin = shMin; d.shardMax = shMax; d.xpMin = xMin; d.xpMax = xMax;
        return d;
    }

    private void buildDefaultRewards() {
        for (int i = 0; i < tiers.size(); i++) {
            String key = tiers.get(i).name.toLowerCase(Locale.ROOT);
            List<RewardEntry> l = new ArrayList<>();
            l.add(new RewardEntry("minecraft:diamond", 1 + i, 3 + 2 * i, 1.0));
            l.add(new RewardEntry("minecraft:iron_ingot", 8 + 4 * i, 16 + 8 * i, 1.0));
            l.add(new RewardEntry("minecraft:experience_bottle", 4 + 2 * i, 8 + 4 * i, 1.0));
            l.add(new RewardEntry("minecraft:gold_ingot", 4 + 2 * i, 8 + 4 * i, 0.7));
            l.add(new RewardEntry("minecraft:emerald", 2 + i, 5 + 2 * i, 0.6));
            l.add(new RewardEntry("minecraft:enchanted_golden_apple", 1, 1 + i / 3, Math.min(1.0, 0.25 + 0.1 * i)));
            if (i >= 2) l.add(new RewardEntry("minecraft:netherite_scrap", 1, 1 + i / 2, Math.min(1.0, 0.4 + 0.08 * i)));
            if (i >= 4) l.add(new RewardEntry("minecraft:netherite_ingot", 1, 1 + (i - 4), Math.min(1.0, 0.3 + 0.1 * (i - 4))));
            rewards.put(key, l);
            List<RewardEntry> pp = new ArrayList<>();
            pp.add(new RewardEntry("minecraft:totem_of_undying", 1, 1, Math.min(1.0, 0.03 + 0.02 * i)));
            perPlayerRewards.put(key, pp);

            List<RewardEntry> b = new ArrayList<>();
            b.add(new RewardEntry("minecraft:diamond", 2 + i, 4 + 2 * i, 1.0));
            b.add(new RewardEntry("minecraft:enchanted_golden_apple", 1, 1 + i / 3, 0.5));
            b.add(new RewardEntry("minecraft:experience_bottle", 6 + 3 * i, 12 + 5 * i, 1.0));
            topDamageBonusRewards.put(key, b);
        }
    }

    public int tierIndex(String name) {
        for (int i = 0; i < tiers.size(); i++) if (tiers.get(i).name.equalsIgnoreCase(name)) return i;
        return -1;
    }

    public int calamityIndex() { return tiers.size() - 1; }

    // ---------- IO ----------
    public static Path dir() {
        Path p = FabricLoader.getInstance().getConfigDir().resolve("wgbosses");
        try { Files.createDirectories(p); } catch (Exception ignored) {}
        return p;
    }

    public static Config load() {
        Path f = dir().resolve("config.json");
        Config c = null;
        try {
            if (Files.exists(f)) {
                try (Reader r = Files.newBufferedReader(f)) { c = GSON.fromJson(r, Config.class); }
            }
        } catch (Exception e) {
            WGBosses.LOG.error("Could not read config.json, using defaults", e);
        }
        if (c == null) c = new Config();
        if (c.tiers == null || c.tiers.size() < 2) c.tiers = new Config().tiers;
        Config d = new Config();
        for (int i = 0; i < c.tiers.size() && i < d.tiers.size(); i++) {
            if (c.tiers.get(i).hitPercent <= 0) c.tiers.get(i).hitPercent = d.tiers.get(i).hitPercent;
            if (c.tiers.get(i).hitCapPercent <= 0) c.tiers.get(i).hitCapPercent = d.tiers.get(i).hitCapPercent;
            if (c.tiers.get(i).dpsCapPercent <= 0) c.tiers.get(i).dpsCapPercent = d.tiers.get(i).dpsCapPercent;
            if (c.tiers.get(i).minFightSeconds <= 0) c.tiers.get(i).minFightSeconds = d.tiers.get(i).minFightSeconds;
            if (c.tiers.get(i).cooldownMaxMinutes <= 0) {
                c.tiers.get(i).cooldownMinMinutes = d.tiers.get(i).cooldownMinMinutes;
                c.tiers.get(i).cooldownMaxMinutes = d.tiers.get(i).cooldownMaxMinutes;
            }
        }
        if (c.immuneEffects == null) c.immuneEffects = d.immuneEffects;
        if (c.arenas == null) c.arenas = new ArrayList<>();
        if (c.noSpawnZones == null) c.noSpawnZones = d.noSpawnZones;
        if (c.affixPool == null) c.affixPool = d.affixPool;
        if (c.flyingMobs == null) c.flyingMobs = new ArrayList<>();
        if (c.flyingMobKeywords == null) c.flyingMobKeywords = d.flyingMobKeywords;
        c.save();
        return c;
    }

    public void save() {
        try (Writer w = Files.newBufferedWriter(dir().resolve("config.json"))) {
            GSON.toJson(this, w);
        } catch (Exception e) {
            WGBosses.LOG.error("Could not save config.json", e);
        }
    }
}
