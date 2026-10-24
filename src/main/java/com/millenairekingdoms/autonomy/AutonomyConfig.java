package com.millenairekingdoms.autonomy;

import net.neoforged.neoforge.common.ModConfigSpec;

/** The "npcAutonomy" section of the server config (same file as the natural capitals). Kingdoms with a player ruler are
 * never decided for by these settings; operator commands are never blocked by them. */
public final class AutonomyConfig {
    public enum WarFrequency {
        /** Chance per political evaluation that an eligible NPC kingdom declares war, starts a conquest order, or attacks a
         * hostile independent village; peaceFactor scales how readily NPC kingdoms end wars. */
        RARE(0.06, 0.45, 0.05, 1.4), BALANCED(0.15, 0.70, 0.12, 1.0), FREQUENT(0.35, 0.90, 0.25, 0.7);
        public final double declare, campaign, independent, peaceFactor;
        WarFrequency(double declare, double campaign, double independent, double peaceFactor) {
            this.declare = declare; this.campaign = campaign; this.independent = independent; this.peaceFactor = peaceFactor;
        }
    }

    /** One immutable view of the settings, so a decision never mixes two config states and tests can pass their own. */
    public record Settings(boolean autonomy, boolean joining, boolean campaigns, WarFrequency frequency, int decisionDays,
                           int warThreshold, int raidCooldownDays, int truceDays) {
        public static final Settings DEFAULTS = new Settings(true, true, true, WarFrequency.BALANCED, 1, -70, 1, 5);
        public Settings with(boolean autonomy, boolean joining, boolean campaigns) {
            return new Settings(autonomy, joining, campaigns, frequency, decisionDays, warThreshold, raidCooldownDays, truceDays);
        }
        public Settings withFrequency(WarFrequency f) {
            return new Settings(autonomy, joining, campaigns, f, decisionDays, warThreshold, raidCooldownDays, truceDays);
        }
    }

    public static ModConfigSpec.BooleanValue ENABLED, JOINING, CAMPAIGNS;
    public static ModConfigSpec.EnumValue<WarFrequency> FREQUENCY;
    public static ModConfigSpec.IntValue DECISION_DAYS, WAR_THRESHOLD, RAID_COOLDOWN_DAYS, TRUCE_DAYS;

    private AutonomyConfig() {}

    public static void define(ModConfigSpec.Builder b) {
        b.comment("Stage 6.2: political decisions of NPC-governed kingdoms (kingdoms without a player ruler).",
                "Decisions read persistent data only; real conquest victories always come from completed Millénaire raids.").push("npcAutonomy");
        ENABLED = b.comment("Let NPC-governed kingdoms decide on their own (war, peace, conquest raids, accepting villages).")
                .define("enabled", true);
        JOINING = b.comment("Let independent NPC villages with good relations join nearby kingdoms on their own.")
                .define("diplomaticJoining", true);
        FREQUENCY = b.comment("How often NPC kingdoms go to war: RARE, BALANCED or FREQUENT.")
                .defineEnum("warFrequency", WarFrequency.BALANCED);
        DECISION_DAYS = b.comment("Minecraft days between two political evaluations.")
                .defineInRange("decisionIntervalDays", 1, 1, 30);
        WAR_THRESHOLD = b.comment("Relation between two capitals at or below which an NPC kingdom may declare war (Millénaire scale -100..100).")
                .defineInRange("warRelationThreshold", -70, -100, 0);
        RAID_COOLDOWN_DAYS = b.comment("Minecraft days after a conquest raid on a village before the next one may start.")
                .defineInRange("raidCooldownDays", 1, 0, 60);
        TRUCE_DAYS = b.comment("Minecraft days after a peace during which the two kingdoms cannot declare war on each other.")
                .defineInRange("truceDays", 5, 0, 100);
        CAMPAIGNS = b.comment("Let NPC kingdoms send real Millénaire conquest raids on their own.")
                .define("automaticCampaigns", true);
        b.pop();
    }

    /** The current settings, or the defaults while the server config is not loaded. */
    public static Settings current() {
        if (ENABLED == null) return Settings.DEFAULTS;
        try {
            return new Settings(ENABLED.get(), JOINING.get(), CAMPAIGNS.get(), FREQUENCY.get(), DECISION_DAYS.get(),
                    WAR_THRESHOLD.get(), RAID_COOLDOWN_DAYS.get(), TRUCE_DAYS.get());
        } catch (IllegalStateException notLoaded) {
            return Settings.DEFAULTS;
        }
    }
    public static int truceDays() { return current().truceDays(); }
}
