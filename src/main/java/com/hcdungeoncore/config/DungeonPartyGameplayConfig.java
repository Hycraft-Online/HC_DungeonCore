package com.hcdungeoncore.config;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * GameplayConfig.Plugin configuration for dungeon party gameplay.
 * Add this to a world's GameplayConfig to enable shared lives in that dungeon:
 *
 * <pre>
 * "Plugin": {
 *   "DungeonParty": {
 *     "Enabled": true,
 *     "LivesPerPlayer": 3,
 *     "RespawnDelaySeconds": 5,
 *     "PreventItemLoss": true,
 *     "EnemyMinLevel": 10,
 *     "EnemyMaxLevel": 15,
 *     "ScaleToPartyLevel": true,
 *     "BossRoleNames": ["Shiva", "Azaroth"],
 *     "BossLevel": 25,
 *     "DifficultyMultiplier": 1.0,
 *     "MaxNPCsPerCluster": 8,
 *     "ClusterRadiusBlocks": 4.0,
 *     "DensityCheckIntervalSeconds": 2.0
 *   }
 * }
 * </pre>
 */
public class DungeonPartyGameplayConfig {

    public static final BuilderCodec<DungeonPartyGameplayConfig> CODEC = ((BuilderCodec.Builder<DungeonPartyGameplayConfig>)
        ((BuilderCodec.Builder<DungeonPartyGameplayConfig>)
        ((BuilderCodec.Builder<DungeonPartyGameplayConfig>)
        ((BuilderCodec.Builder<DungeonPartyGameplayConfig>)
        ((BuilderCodec.Builder<DungeonPartyGameplayConfig>)
        ((BuilderCodec.Builder<DungeonPartyGameplayConfig>)
        ((BuilderCodec.Builder<DungeonPartyGameplayConfig>)
        ((BuilderCodec.Builder<DungeonPartyGameplayConfig>)
        ((BuilderCodec.Builder<DungeonPartyGameplayConfig>)
        ((BuilderCodec.Builder<DungeonPartyGameplayConfig>)
        ((BuilderCodec.Builder<DungeonPartyGameplayConfig>)
        ((BuilderCodec.Builder<DungeonPartyGameplayConfig>)
        ((BuilderCodec.Builder<DungeonPartyGameplayConfig>)
        BuilderCodec.builder(DungeonPartyGameplayConfig.class, DungeonPartyGameplayConfig::new)
            .append(new KeyedCodec<>("Enabled", Codec.BOOLEAN), (config, val) -> {
                config.enabled = val;
            }, config -> config.enabled).add())
            .append(new KeyedCodec<>("LivesPerPlayer", Codec.INTEGER), (config, val) -> {
                config.livesPerPlayer = val;
            }, config -> config.livesPerPlayer).add())
            .append(new KeyedCodec<>("RespawnDelaySeconds", Codec.INTEGER), (config, val) -> {
                config.respawnDelaySeconds = val;
            }, config -> config.respawnDelaySeconds).add())
            .append(new KeyedCodec<>("PreventItemLoss", Codec.BOOLEAN), (config, val) -> {
                config.preventItemLoss = val;
            }, config -> config.preventItemLoss).add())
            .append(new KeyedCodec<>("EnemyMinLevel", Codec.INTEGER), (config, val) -> {
                config.enemyMinLevel = val;
            }, config -> config.enemyMinLevel).add())
            .append(new KeyedCodec<>("EnemyMaxLevel", Codec.INTEGER), (config, val) -> {
                config.enemyMaxLevel = val;
            }, config -> config.enemyMaxLevel).add())
            .append(new KeyedCodec<>("ScaleToPartyLevel", Codec.BOOLEAN), (config, val) -> {
                config.scaleToPartyLevel = val;
            }, config -> config.scaleToPartyLevel).add())
            .append(new KeyedCodec<>("BossRoleNames", Codec.STRING_ARRAY), (config, val) -> {
                config.bossRoleNames = val;
            }, config -> config.bossRoleNames).add())
            .append(new KeyedCodec<>("BossLevel", Codec.INTEGER), (config, val) -> {
                config.bossLevel = val;
            }, config -> config.bossLevel).add())
            .append(new KeyedCodec<>("DifficultyMultiplier", Codec.FLOAT), (config, val) -> {
                config.difficultyMultiplier = val;
            }, config -> config.difficultyMultiplier).add())
            .append(new KeyedCodec<>("MaxNPCsPerCluster", Codec.INTEGER), (config, val) -> {
                config.maxNPCsPerCluster = val;
            }, config -> config.maxNPCsPerCluster).add())
            .append(new KeyedCodec<>("ClusterRadiusBlocks", Codec.FLOAT), (config, val) -> {
                config.clusterRadiusBlocks = val;
            }, config -> config.clusterRadiusBlocks).add())
            .append(new KeyedCodec<>("DensityCheckIntervalSeconds", Codec.FLOAT), (config, val) -> {
                config.densityCheckIntervalSeconds = val;
            }, config -> config.densityCheckIntervalSeconds).add())
        .build();

    // Default values - party/lives settings
    private boolean enabled = false;
    private int livesPerPlayer = 1;
    private int respawnDelaySeconds = 5;
    private boolean preventItemLoss = true;

    // Enemy level settings
    private int enemyMinLevel = 1;
    private int enemyMaxLevel = 50;
    private boolean scaleToPartyLevel = false;

    // Boss settings
    private String[] bossRoleNames = new String[0];
    private int bossLevel = 0; // 0 means use enemy max level
    private float difficultyMultiplier = 1.0f;

    // Entity density settings (prevents crashes from too many NPCs in doorways)
    private int maxNPCsPerCluster = 8;       // Max NPCs allowed within cluster radius
    private float clusterRadiusBlocks = 4.0f; // Radius in blocks to check for clustering
    private float densityCheckIntervalSeconds = 2.0f; // How often to run density check

    // Cached boss name set for fast lookup
    private transient Set<String> bossRoleNameSet = null;

    public DungeonPartyGameplayConfig() {
    }

    /**
     * Whether dungeon party gameplay is enabled for this world.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Number of lives each player contributes to the shared pool.
     * Total party lives = livesPerPlayer x partySize
     */
    public int getLivesPerPlayer() {
        return livesPerPlayer;
    }

    /**
     * Seconds to wait before player can respawn after death.
     */
    public int getRespawnDelaySeconds() {
        return respawnDelaySeconds;
    }

    /**
     * Whether to prevent item/durability loss on death in this dungeon.
     */
    public boolean shouldPreventItemLoss() {
        return preventItemLoss;
    }

    /**
     * Minimum level for enemies in this dungeon.
     * Used when ScaleToPartyLevel is false, or as a floor when scaling.
     */
    public int getEnemyMinLevel() {
        return enemyMinLevel;
    }

    /**
     * Maximum level for enemies in this dungeon.
     * Used when ScaleToPartyLevel is false, or as a ceiling when scaling.
     */
    public int getEnemyMaxLevel() {
        return enemyMaxLevel;
    }

    /**
     * Whether to scale enemy levels based on the party's average level.
     * If true, enemies will be scaled to match the party's average level,
     * clamped between EnemyMinLevel and EnemyMaxLevel.
     * If false, enemies will be set to EnemyMinLevel (or random between min/max).
     */
    public boolean shouldScaleToPartyLevel() {
        return scaleToPartyLevel;
    }

    /**
     * Array of NPC role names that should be treated as bosses.
     * These NPCs will use BossLevel instead of normal enemy scaling.
     * Example: ["Shiva", "Azaroth", "Shadow_Knight_Boss"]
     */
    public String[] getBossRoleNames() {
        return bossRoleNames;
    }

    /**
     * Check if a given role name is configured as a boss.
     * Uses cached set for O(1) lookup performance.
     */
    public boolean isBossRole(String roleName) {
        if (bossRoleNames == null || bossRoleNames.length == 0) {
            return false;
        }
        if (bossRoleNameSet == null) {
            bossRoleNameSet = new HashSet<>(Arrays.asList(bossRoleNames));
        }
        return bossRoleNameSet.contains(roleName);
    }

    /**
     * The level to apply to boss NPCs.
     * If 0, bosses use EnemyMaxLevel.
     * If ScaleToPartyLevel is true, boss level is also scaled but uses this as a base modifier.
     */
    public int getBossLevel() {
        return bossLevel;
    }

    /**
     * Get the effective boss level, using EnemyMaxLevel as fallback if BossLevel is 0.
     */
    public int getEffectiveBossLevel() {
        return bossLevel > 0 ? bossLevel : enemyMaxLevel;
    }

    /**
     * Difficulty multiplier that scales enemy health and damage.
     * 1.0 = normal, 1.5 = 50% harder, 2.0 = double difficulty.
     * Applied on top of level-based scaling.
     */
    public float getDifficultyMultiplier() {
        return difficultyMultiplier;
    }

    /**
     * Check if enemy level configuration is set (not default values).
     */
    public boolean hasEnemyLevelConfig() {
        return enemyMinLevel > 1 || enemyMaxLevel < 50 || scaleToPartyLevel;
    }

    /**
     * Check if boss configuration is set.
     */
    public boolean hasBossConfig() {
        return bossRoleNames != null && bossRoleNames.length > 0;
    }

    /**
     * Maximum number of NPCs allowed within the cluster radius before excess
     * NPCs are despawned. Prevents crashes from too many entities in doorways.
     * Default: 8
     */
    public int getMaxNPCsPerCluster() {
        return maxNPCsPerCluster;
    }

    /**
     * Radius in blocks to check for NPC clustering. NPCs within this radius
     * of each other are considered part of the same cluster.
     * Default: 4.0 blocks (typical doorway width)
     */
    public float getClusterRadiusBlocks() {
        return clusterRadiusBlocks;
    }

    /**
     * How often (in seconds) to run the entity density check.
     * Lower values catch clusters faster but use more CPU.
     * Default: 2.0 seconds
     */
    public float getDensityCheckIntervalSeconds() {
        return densityCheckIntervalSeconds;
    }
}
