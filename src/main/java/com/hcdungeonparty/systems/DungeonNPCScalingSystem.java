package com.hcdungeonparty.systems;

import com.hcdungeonparty.HC_DungeonPartyPlugin;
import com.hcdungeonparty.config.DungeonPartyGameplayConfig;
import com.hcdungeonparty.integration.HC_LevelingIntegration;
import com.hcdungeonparty.managers.DungeonSessionManager;
import com.hcdungeonparty.models.DungeonSession;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.HolderSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsSystems;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.systems.BalancingInitialisationSystem;
import com.hypixel.hytale.server.npc.systems.RoleBuilderSystem;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * ECS HolderSystem that applies configured level scaling to NPCs spawning in dungeon worlds.
 *
 * This system runs AFTER the default NPC scaling systems to override their behavior
 * for dungeons that have DungeonPartyGameplayConfig enabled.
 *
 * Level Calculation:
 * - Regular enemies: Uses EnemyMinLevel/EnemyMaxLevel (or party level if ScaleToPartyLevel)
 * - Boss enemies: Uses BossLevel (configured per dungeon via BossRoleNames)
 * - All enemies: Health scaled by DifficultyMultiplier
 */
public class DungeonNPCScalingSystem extends HolderSystem<EntityStore> {

    private static final String DUNGEON_LEVEL_MODIFIER = "HC_DungeonParty_Level";
    private static final String DUNGEON_DIFFICULTY_MODIFIER = "HC_DungeonParty_Difficulty";
    private static final String HEALTH_STAT_ID = "Health";

    // Component types
    private final ComponentType<EntityStore, NPCEntity> npcComponentType = NPCEntity.getComponentType();
    private final ComponentType<EntityStore, EntityStatMap> entityStatMapType = EntityStatMap.getComponentType();
    private final ComponentType<EntityStore, TransformComponent> transformType = TransformComponent.getComponentType();
    private final ComponentType<EntityStore, UUIDComponent> uuidType = UUIDComponent.getComponentType();
    private final ComponentType<EntityStore, Nameplate> nameplateType = Nameplate.getComponentType();

    // Query for NPCs with stats and transform
    private final Query<EntityStore> query = Archetype.of(npcComponentType, entityStatMapType, transformType);

    // Dependencies - run AFTER both role setup and default balancing
    // This ensures our scaling overrides any default HC_Leveling behavior
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
        new SystemDependency<>(Order.AFTER, RoleBuilderSystem.class),
        new SystemDependency<>(Order.AFTER, EntityStatsSystems.Setup.class),
        new SystemDependency<>(Order.AFTER, BalancingInitialisationSystem.class)
    );

    private HC_DungeonPartyPlugin plugin;

    /**
     * Initialize with plugin reference.
     */
    public void initialize(HC_DungeonPartyPlugin plugin) {
        this.plugin = plugin;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Override
    public void onEntityAdd(@Nonnull Holder<EntityStore> holder, @Nonnull AddReason reason, @Nonnull Store<EntityStore> store) {
        if (plugin == null) {
            return;
        }

        // Only process if HC_Leveling integration is available
        if (!HC_LevelingIntegration.isAvailable()) {
            return;
        }

        // Get world
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return;
        }

        // Check if this world has DungeonParty config
        DungeonSessionManager manager = DungeonSessionManager.getInstance();
        if (manager == null) {
            return;
        }

        DungeonPartyGameplayConfig config = manager.getDungeonPartyConfig(world);
        if (config == null) {
            return; // No DungeonParty config for this world
        }

        if (!config.isEnabled()) {
            log(Level.FINE, "DungeonParty config found but disabled for world %s", world.getName());
            return;
        }

        // Always apply scaling in dungeon party worlds when HC_Leveling is available
        // This ensures dungeon NPCs get proper level scaling even without explicit config
        // (HC_Leveling's position-based scaling doesn't work well in instance worlds)

        // Get NPC component
        NPCEntity npcEntity = holder.getComponent(npcComponentType);
        if (npcEntity == null) {
            return;
        }

        log(Level.INFO, "Processing NPC '%s' in dungeon world %s", npcEntity.getRoleName(), world.getName());

        // Get UUID for caching
        UUIDComponent uuidComponent = holder.getComponent(uuidType);
        if (uuidComponent == null) {
            return;
        }
        UUID npcUuid = uuidComponent.getUuid();

        // Check if this NPC is a boss
        String roleName = npcEntity.getRoleName();
        boolean isBoss = config.isBossRole(roleName);

        // Calculate the level to apply
        int level;
        if (isBoss && config.hasBossConfig()) {
            level = calculateBossLevel(world, config);
        } else {
            level = calculateDungeonNPCLevel(world, config);
        }

        // Cache the level in HC_Leveling for XP calculations
        HC_LevelingIntegration.setNPCLevel(npcUuid, level);

        // Apply health scaling directly
        float difficultyMultiplier = config.getDifficultyMultiplier();
        if (level > 1 || difficultyMultiplier != 1.0f) {
            applyHealthScaling(holder, npcEntity, level, difficultyMultiplier, isBoss);
        }

        // Update nameplate to show correct level (fixes race condition with HC_Leveling's nameplate system)
        updateNameplate(holder, npcEntity, level);

        if (isBoss) {
            log(Level.INFO, "Scaled BOSS NPC '%s' to level %d (difficulty: %.1fx) in world %s",
                roleName, level, difficultyMultiplier, world.getName());
        } else {
            log(Level.FINE, "Scaled dungeon NPC '%s' to level %d in world %s",
                roleName, level, world.getName());
        }
    }

    /**
     * Apply health scaling to an NPC based on level and difficulty.
     *
     * @param holder the entity holder
     * @param npcEntity the NPC entity
     * @param level the calculated level
     * @param difficultyMultiplier the difficulty multiplier (1.0 = normal)
     * @param isBoss whether this NPC is a boss
     */
    private void applyHealthScaling(Holder<EntityStore> holder, NPCEntity npcEntity, int level,
                                    float difficultyMultiplier, boolean isBoss) {
        // Get EntityStatMap for health modification
        EntityStatMap stats = holder.getComponent(entityStatMapType);
        if (stats == null) {
            return;
        }

        // Get health stat index
        int healthIndex = EntityStatType.getAssetMap().getIndex(HEALTH_STAT_ID);
        if (healthIndex == Integer.MIN_VALUE) {
            return;
        }

        // Get the base stat max from the asset
        EntityStatType healthStat = EntityStatType.getAssetMap().getAsset(healthIndex);
        float baseMaxHealth = healthStat.getMax();

        // Get role's initial max health for reference
        Role role = npcEntity.getRole();
        int roleInitialHealth = role != null ? role.getInitialMaxHealth() : (int) baseMaxHealth;

        // Calculate target health based on level
        // Using HC_Leveling's formula: MaxHP = 20 + (Stamina * 4)
        // Where Stamina = baseStamina + (level - 1) * staminaPerLevel
        // Using default values: baseStamina = 10, staminaPerLevel = 3
        int stamina = 10 + (level - 1) * 3;
        int targetHealth = 20 + (stamina * 4);

        // For bosses, preserve their high base health and add level scaling on top
        if (isBoss && roleInitialHealth > targetHealth) {
            // Boss has more health than level formula suggests - use their base health
            // and add a level-based bonus instead of replacing
            float levelBonus = (level - 1) * 20; // +20 HP per level for bosses
            targetHealth = (int)(roleInitialHealth + levelBonus);
        }

        // Calculate the health bonus needed
        float healthBonus = targetHealth - roleInitialHealth;

        // Apply level-based health modifier
        if (healthBonus != 0) {
            StaticModifier levelModifier = new StaticModifier(
                Modifier.ModifierTarget.MAX,
                StaticModifier.CalculationType.ADDITIVE,
                healthBonus
            );
            stats.putModifier(healthIndex, DUNGEON_LEVEL_MODIFIER, levelModifier);
        }

        // Apply difficulty multiplier as a separate modifier (multiplicative)
        if (difficultyMultiplier != 1.0f) {
            // Convert multiplier to percentage bonus (1.5x = 50% bonus)
            float difficultyBonus = difficultyMultiplier - 1.0f;
            StaticModifier difficultyModifier = new StaticModifier(
                Modifier.ModifierTarget.MAX,
                StaticModifier.CalculationType.MULTIPLICATIVE,
                difficultyBonus
            );
            stats.putModifier(healthIndex, DUNGEON_DIFFICULTY_MODIFIER, difficultyModifier);
        }

        // Maximize health to apply new max
        stats.maximizeStatValue(healthIndex);
    }

    /**
     * Calculate the level for a boss NPC.
     *
     * @param world the dungeon world
     * @param config the dungeon party config
     * @return the boss level
     */
    private int calculateBossLevel(World world, DungeonPartyGameplayConfig config) {
        int bossLevel = config.getEffectiveBossLevel();
        int systemMax = HC_LevelingIntegration.getMaxLevel();

        // Optionally scale boss level with party if configured
        if (config.shouldScaleToPartyLevel()) {
            DungeonSessionManager manager = DungeonSessionManager.getInstance();
            if (manager != null) {
                DungeonSession session = manager.getSessionByWorld(world.getWorldConfig().getUuid());
                if (session != null) {
                    int avgLevel = calculatePartyAverageLevel(session);
                    // Boss is party level + configured boss offset
                    // If BossLevel is set, use it as a minimum, otherwise scale from max
                    int minBossLevel = config.getBossLevel() > 0 ? config.getBossLevel() : config.getEnemyMaxLevel();
                    bossLevel = Math.max(minBossLevel, avgLevel + 5); // Boss is at least 5 levels above party
                }
            }
        }

        return Math.max(1, Math.min(bossLevel, systemMax));
    }

    /**
     * Calculate the appropriate level for an NPC in a dungeon.
     *
     * @param world the dungeon world
     * @param config the dungeon party config
     * @return the level to apply
     */
    private int calculateDungeonNPCLevel(World world, DungeonPartyGameplayConfig config) {
        int systemMax = HC_LevelingIntegration.getMaxLevel();

        // Get party average level - this is the primary source for dungeon scaling
        int partyLevel = 1;
        DungeonSessionManager manager = DungeonSessionManager.getInstance();
        if (manager != null) {
            DungeonSession session = manager.getSessionByWorld(world.getWorldConfig().getUuid());
            if (session != null) {
                partyLevel = calculatePartyAverageLevel(session);
                log(Level.FINE, "Found session for world %s, party level: %d", world.getName(), partyLevel);
            } else {
                log(Level.FINE, "No session found for world UUID %s", world.getWorldConfig().getUuid());
            }
        }

        // If explicit level config is set, use those bounds
        if (config.hasEnemyLevelConfig()) {
            int minLevel = Math.max(1, Math.min(config.getEnemyMinLevel(), systemMax));
            int maxLevel = Math.max(minLevel, Math.min(config.getEnemyMaxLevel(), systemMax));

            if (config.shouldScaleToPartyLevel()) {
                // Clamp party level to dungeon's min/max
                return Math.max(minLevel, Math.min(partyLevel, maxLevel));
            }

            // Use configured range
            if (minLevel == maxLevel) {
                return minLevel;
            }
            return minLevel + (int)(Math.random() * (maxLevel - minLevel + 1));
        }

        // No explicit config - default to party level scaling
        // This ensures dungeon NPCs are appropriate for the party
        int finalLevel = Math.max(1, Math.min(partyLevel, systemMax));
        log(Level.INFO, "Calculated enemy level %d (party level: %d, no explicit config)", finalLevel, partyLevel);
        return finalLevel;
    }

    /**
     * Calculate the average level of all party members in a session.
     */
    private int calculatePartyAverageLevel(DungeonSession session) {
        Set<UUID> memberUuids = session.getMemberUuids();
        if (memberUuids.isEmpty()) {
            return 1;
        }

        int totalLevel = 0;
        int count = 0;
        for (UUID memberUuid : memberUuids) {
            int level = HC_LevelingIntegration.getPlayerLevel(memberUuid);
            totalLevel += level;
            count++;
        }

        return count > 0 ? totalLevel / count : 1;
    }

    @Override
    public void onEntityRemoved(@Nonnull Holder<EntityStore> holder, @Nonnull RemoveReason reason, @Nonnull Store<EntityStore> store) {
        // No cleanup needed - HC_Leveling handles its own cache cleanup
    }

    /**
     * Update the NPC's nameplate to show the correct level.
     * This fixes the race condition where HC_Leveling's nameplate system
     * may have already set the nameplate with a wrong level.
     */
    private void updateNameplate(Holder<EntityStore> holder, NPCEntity npcEntity, int level) {
        Nameplate nameplate = holder.getComponent(nameplateType);

        // Get the base name from the role
        String baseName = npcEntity.getRoleName();
        if (baseName != null) {
            baseName = baseName.replace("_", " ");
        } else {
            baseName = "Unknown";
        }

        String newText = "[Lv." + level + "] " + baseName;

        if (nameplate != null) {
            nameplate.setText(newText);
        }
        // If no nameplate component exists yet, HC_Leveling's system will create it
        // with the correct level since we've already cached it via setNPCLevel()
    }

    private void log(Level level, String format, Object... args) {
        if (plugin != null) {
            plugin.getLogger().at(level).log("[DungeonNPCScaling] " + String.format(format, args));
        }
    }
}
