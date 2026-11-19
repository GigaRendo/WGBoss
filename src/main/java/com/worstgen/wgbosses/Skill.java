package com.worstgen.wgbosses;

public enum Skill {
    DASH("Dash", 0, 300),
    LEAP("Leap", 0, 260),
    ROAR("Roar", 0, 500),
    SMASH("Ground Smash", 0, 360),
    HOOK("Hook", 0, 520),
    BARRAGE("Barrage", 0, 480),
    SUMMON("Summon", 1, 900),
    TELEPORT("Shadow Step", 1, 600),
    FLY_POTION("Sky Bombard", 3, 900),
    LIGHTNING("Thunder Call", 3, 600),
    POWER_SHIELD("Power Shield", 2, 900),
    MEND("Mend", 0, 1400),
    REPRIEVE("Reprieve", 2, 1400),
    LIFEBLOOM("Lifebloom", 1, 1500),
    HEART_HOVER("Heart Hover", 3, 1800),
    INVIS_SPLASH("Veil Splash", 2, 1100),
    DECOY_SWAP("Decoy Swap", 2, 1300),
    RETREAT_HEAL("Retreat", 2, 1500),
    SHULKER_BOLT("Shulker Bolt", 0, 500),
    SPLASH_POISON("Poison Splash", 0, 500),
    SPLASH_WITHER("Wither Splash", 1, 550),
    SPLASH_HARMING("Harming Splash", 2, 550),
    SPLASH_LEVITATION("Levitation Splash", 1, 600),
    SPLASH_SLOWNESS("Slowness Splash", 0, 500),
    SPLASH_BLINDNESS("Blindness Splash", 1, 600),
    WATERFOWL("Waterfowl Dance", 3, 900),
    POWER_UP("Power Up", 1, 1300),
    LIFE_DRAIN("Life Drain", 2, 1500),
    EXECUTION_MARK("Execution Mark", 2, 800),
    RAVAGE("Ravage", 2, 900),
    REVERSE_POLARITY("Reverse Polarity", 4, 1000),
    SUN_STRIKE("Sun Strike", 0, 600),
    ECHO_SLAM("Echo Slam", 1, 700),
    LAGUNA_BLADE("Laguna Blade", 4, 800),
    RUPTURE("Rupture", 2, 1000);

    public final String label;
    /** Minimum tier index (0 = Common) before this skill can appear. */
    public final int minTier;
    /** Base cooldown in ticks. */
    public final int cooldown;

    Skill(String label, int minTier, int cooldown) {
        this.label = label;
        this.minTier = minTier;
        this.cooldown = cooldown;
    }
}
