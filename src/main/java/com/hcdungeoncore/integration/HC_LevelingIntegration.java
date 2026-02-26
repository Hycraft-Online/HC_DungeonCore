package com.hcdungeoncore.integration;

import com.hcdungeoncore.HC_DungeonCorePlugin;
import com.hcleveling.api.HC_LevelingAPI;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;
import java.util.logging.Level;

/**
 * Integration with HC_Leveling plugin for enemy level scaling in dungeons.
 * Uses HC_LevelingAPI for all interactions to avoid tight coupling.
 *
 * This integration allows dungeons to:
 * - Register as exempt from position-based NPC scaling
 * - Apply custom levels to spawned NPCs
 * - Scale enemies based on party average level
 */
public class HC_LevelingIntegration {

    private static boolean initialized = false;
    private static boolean available = false;

    /**
     * Check if HC_Leveling is available without loading classes.
     */
    public static boolean isHC_LevelingAvailable() {
        try {
            Class.forName("com.hcleveling.api.HC_LevelingAPI");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Initialize HC_Leveling integration.
     */
    public static void initialize(HC_DungeonCorePlugin plugin) {
        if (initialized) {
            return;
        }
        initialized = true;

        if (!isHC_LevelingAvailable()) {
            plugin.getLogger().at(Level.INFO).log("HC_Leveling not found - enemy level scaling disabled");
            available = false;
            return;
        }

        try {
            // Check if the API is actually available
            if (HC_LevelingAPI.isAvailable()) {
                available = true;
                plugin.getLogger().at(Level.INFO).log("HC_Leveling integration initialized - enemy level scaling enabled");
            } else {
                plugin.getLogger().at(Level.INFO).log("HC_Leveling available but not initialized - enemy level scaling disabled");
                available = false;
            }
        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log("Failed to initialize HC_Leveling integration: " + e.getMessage());
            available = false;
        }
    }

    /**
     * Check if HC_Leveling integration is active.
     */
    public static boolean isAvailable() {
        return available;
    }

    /**
     * Register a world as exempt from automatic NPC scaling.
     * Dungeon worlds should be exempt so we can apply custom levels.
     *
     * @param worldName the world name to exempt
     */
    public static void registerExemptWorld(String worldName) {
        if (!available) {
            return;
        }
        try {
            HC_LevelingAPI.registerExemptWorld(worldName);
        } catch (Exception e) {
            // Silently ignore
        }
    }

    /**
     * Unregister a world from exemption.
     *
     * @param worldName the world name to unexempt
     */
    public static void unregisterExemptWorld(String worldName) {
        if (!available) {
            return;
        }
        try {
            HC_LevelingAPI.unregisterExemptWorld(worldName);
        } catch (Exception e) {
            // Silently ignore
        }
    }

    /**
     * Set an NPC's level (for XP calculations).
     *
     * @param npcUuid the NPC's UUID
     * @param level the level to set
     */
    public static void setNPCLevel(UUID npcUuid, int level) {
        if (!available) {
            return;
        }
        try {
            HC_LevelingAPI.setNPCLevel(npcUuid, level);
        } catch (Exception e) {
            // Silently ignore
        }
    }

    /**
     * Apply full NPC level scaling including health modification.
     *
     * @param npcUuid the NPC's UUID
     * @param level the level to apply
     * @param npcRef reference to the NPC entity
     * @param store the entity store
     * @return true if scaling was applied successfully
     */
    public static boolean applyNPCScaling(UUID npcUuid, int level, Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (!available) {
            return false;
        }
        try {
            return HC_LevelingAPI.applyNPCScaling(npcUuid, level, npcRef, store);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get a player's current level.
     *
     * @param playerUuid the player's UUID
     * @return the player's level, or 1 if unavailable
     */
    public static int getPlayerLevel(UUID playerUuid) {
        if (!available) {
            return 1;
        }
        try {
            return HC_LevelingAPI.getPlayerLevel(playerUuid);
        } catch (Exception e) {
            return 1;
        }
    }

    /**
     * Get the maximum level in the system.
     *
     * @return max level, or 50 if unavailable
     */
    public static int getMaxLevel() {
        if (!available) {
            return 50;
        }
        try {
            return HC_LevelingAPI.getMaxLevel();
        } catch (Exception e) {
            return 50;
        }
    }
}
