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
        }
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
