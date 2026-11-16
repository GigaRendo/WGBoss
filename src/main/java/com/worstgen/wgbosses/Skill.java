package com.worstgen.wgbosses;

public enum Skill {
    DASH("Dash", 0, 300),
    LEAP("Leap", 0, 260),
    ROAR("Roar", 0, 500),
    SMASH("Ground Smash", 0, 360),
    HOOK("Hook", 1, 520),
    BARRAGE("Barrage", 1, 480),
    SUMMON("Summon", 1, 900),
    TELEPORT("Shadow Step", 2, 600),
    FLY_POTION("Sky Bombard", 3, 900),
    LIGHTNING("Thunder Call", 3, 600);

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
