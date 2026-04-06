package com.hcdungeoncore;

import com.hcdungeoncore.config.DungeonPartyGameplayConfig;
import com.hcdungeoncore.integration.HC_LevelingIntegration;
import com.hcdungeoncore.integration.PartyModIntegration;
import com.hcdungeoncore.managers.DungeonSessionManager;
import com.hcdungeoncore.models.DungeonSession;
import com.hcdungeoncore.systems.DungeonEntityDensitySystem;
import com.hcdungeoncore.systems.DungeonNPCScalingSystem;
import com.hcdungeoncore.systems.DungeonRespawnSystem;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.asset.type.gameplay.GameplayConfig;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.builtin.instances.config.InstanceEntityConfig;
import com.hypixel.hytale.builtin.instances.config.WorldReturnPoint;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import java.util.UUID;
import java.util.logging.Level;

/**
 * HC_DungeonCore - Party-based dungeon gameplay with shared lives and enemy scaling.
 *
 * This plugin provides reusable party-based session management for dungeons.
 * When enabled via GameplayConfig, players who enter a dungeon share a lives pool.
 * When any party member dies, one life is deducted. When all lives are exhausted,
 * the party wipes and all members are teleported out.
 *
 * Optionally integrates with HC_Leveling to scale dungeon enemies to configured levels
 * or to the party's average level.
 *
 * Configuration via GameplayConfig (in world's gameplay config JSON):
 * <pre>
 * "Plugin": {
 *   "DungeonParty": {
 *     "Enabled": true,
 *     "LivesPerPlayer": 3,
 *     "RespawnDelaySeconds": 5,
 *     "PreventItemLoss": true,
 *     "EnemyMinLevel": 10,
 *     "EnemyMaxLevel": 15,
 *     "ScaleToPartyLevel": true
 *   }
 * }
 * </pre>
 */
public class HC_DungeonCorePlugin extends JavaPlugin {

    public static final String VERSION = "1.0.0";

    private static volatile HC_DungeonCorePlugin instance;

    // ECS Systems
    private DungeonRespawnSystem dungeonRespawnSystem;
    private DungeonNPCScalingSystem dungeonNPCScalingSystem;
    private DungeonEntityDensitySystem dungeonEntityDensitySystem;

    public HC_DungeonCorePlugin(@NonNullDecl JavaPluginInit init) {
        super(init);
        instance = this;
    }

    public static HC_DungeonCorePlugin getInstance() {
        return instance;
    }

    @Override
    protected void setup() {
        super.setup();

        this.getLogger().at(Level.INFO).log("=================================");
        this.getLogger().at(Level.INFO).log("   HC DUNGEON CORE " + VERSION);
        this.getLogger().at(Level.INFO).log("=================================");

        // ═══════════════════════════════════════════════════════
        // CODEC REGISTRATION
        // ═══════════════════════════════════════════════════════
        // Register the DungeonParty config codec so it can be read from GameplayConfig.Plugin
        this.getCodecRegistry(GameplayConfig.PLUGIN_CODEC)
            .register(DungeonPartyGameplayConfig.class, "DungeonParty", DungeonPartyGameplayConfig.CODEC);
        this.getLogger().at(Level.INFO).log("Registered DungeonPartyGameplayConfig codec");

        // ═══════════════════════════════════════════════════════
        // MANAGER INITIALIZATION
        // ═══════════════════════════════════════════════════════
        DungeonSessionManager.initialize(this);
        this.getLogger().at(Level.INFO).log("Dungeon session manager initialized");

        // ═══════════════════════════════════════════════════════
        // PLUGIN INTEGRATIONS
        // ═══════════════════════════════════════════════════════

        // PartyMod integration for party support
        if (PartyModIntegration.isPartyModAvailable()) {
            PartyModIntegration.initialize(this);
        } else {
            this.getLogger().at(Level.INFO).log("PartyMod not available - solo dungeon play only");
        }

        // HC_Leveling integration for enemy level scaling
        if (HC_LevelingIntegration.isHC_LevelingAvailable()) {
            HC_LevelingIntegration.initialize(this);
        } else {
            this.getLogger().at(Level.INFO).log("HC_Leveling not available - enemy scaling disabled");
        }

        // ═══════════════════════════════════════════════════════
        // ECS SYSTEMS
        // ═══════════════════════════════════════════════════════

        // Respawn system - handles death/respawn with shared lives
        dungeonRespawnSystem = new DungeonRespawnSystem();
        dungeonRespawnSystem.initialize(this);
        this.getEntityStoreRegistry().registerSystem(dungeonRespawnSystem);
        this.getLogger().at(Level.INFO).log("Registered DungeonRespawnSystem");

        // NPC scaling system - applies configured enemy levels (only if HC_Leveling available)
        if (HC_LevelingIntegration.isAvailable()) {
            dungeonNPCScalingSystem = new DungeonNPCScalingSystem();
            dungeonNPCScalingSystem.initialize(this);
            this.getEntityStoreRegistry().registerSystem(dungeonNPCScalingSystem);
            this.getLogger().at(Level.INFO).log("Registered DungeonNPCScalingSystem");
        }

        // Entity density system - prevents crashes from too many NPCs clustering in doorways
        dungeonEntityDensitySystem = new DungeonEntityDensitySystem();
        dungeonEntityDensitySystem.initialize(this);
        this.getEntityStoreRegistry().registerSystem(dungeonEntityDensitySystem);
        this.getLogger().at(Level.INFO).log("Registered DungeonEntityDensitySystem");

        // ═══════════════════════════════════════════════════════
        // EVENT HANDLERS
        // ═══════════════════════════════════════════════════════

        // Handle player entering a world - create/join dungeon session if applicable
        this.getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class, this::onPlayerAddedToWorld);
        this.getLogger().at(Level.INFO).log("Registered AddPlayerToWorldEvent handler");

        // Handle player disconnect - cleanup session membership
        this.getEventRegistry().register(PlayerDisconnectEvent.class, event -> {
            var playerUuid = event.getPlayerRef().getUuid();
            DungeonSessionManager manager = DungeonSessionManager.getInstance();
            if (manager != null) {
                manager.handlePlayerDisconnect(playerUuid);
            }
        });
        this.getLogger().at(Level.INFO).log("Registered PlayerDisconnectEvent handler");

        // Handle world removal - clean up leaked sessions when dungeon worlds are removed (HYC-248)
        this.getEventRegistry().registerGlobal(RemoveWorldEvent.class, event -> {
            DungeonSessionManager manager = DungeonSessionManager.getInstance();
            if (manager != null) {
                manager.handleWorldRemoved(event.getWorld());
            }
        });
        this.getLogger().at(Level.INFO).log("Registered RemoveWorldEvent handler");

        // ═══════════════════════════════════════════════════════
        // STARTUP COMPLETE
        // ═══════════════════════════════════════════════════════
        this.getLogger().at(Level.INFO).log("=================================");
        this.getLogger().at(Level.INFO).log("HC_DungeonCore enabled!");
        this.getLogger().at(Level.INFO).log("Configure via GameplayConfig:");
        this.getLogger().at(Level.INFO).log("  Plugin.DungeonParty.Enabled: true");
        this.getLogger().at(Level.INFO).log("  Plugin.DungeonParty.EnemyMinLevel: 10");
        this.getLogger().at(Level.INFO).log("  Plugin.DungeonParty.ScaleToPartyLevel: true");
        this.getLogger().at(Level.INFO).log("=================================");
    }

    /**
     * Handle player being added to a world.
     * If the world has DungeonPartyGameplayConfig enabled, create or join a session.
     */
    private void onPlayerAddedToWorld(AddPlayerToWorldEvent event) {
        World world = event.getWorld();
        Holder<EntityStore> holder = event.getHolder();

        // Get PlayerRef from the holder
        PlayerRef playerRef = holder.getComponent(PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }

        DungeonSessionManager manager = DungeonSessionManager.getInstance();
        if (manager == null) {
            return;
        }

        // Check if this world has dungeon party config
        DungeonPartyGameplayConfig config = manager.getDungeonPartyConfig(world);
        if (config == null || !config.isEnabled()) {
            return; // Not a dungeon party world
        }

        // Register world as exempt from HC_Leveling's position-based NPC scaling
        // DungeonParty handles NPC scaling in dungeon worlds (scales to party level)
        if (HC_LevelingIntegration.isAvailable()) {
            HC_LevelingIntegration.registerExemptWorld(world.getName());
        }

        // Read canonical return point from InstanceEntityConfig (set by InstancesPlugin's handler
        // which fires first). This is more reliable than playerRef.getTransform() which can
        // return the dungeon position on reconnect instead of the overworld position.
        Transform returnTransform = null;
        UUID returnWorldUuid = null;

        try {
            InstanceEntityConfig entityConfig = holder.getComponent(InstanceEntityConfig.getComponentType());
            if (entityConfig != null) {
                WorldReturnPoint returnPoint = entityConfig.getReturnPoint();
                if (returnPoint != null) {
                    returnTransform = returnPoint.getReturnPoint();
                    returnWorldUuid = returnPoint.getWorld();
                }
            }
        } catch (Exception e) {
            this.getLogger().at(Level.WARNING).log("Could not read InstanceEntityConfig return point: " + e.getMessage());
        }

        // Fall back to playerRef.getTransform() if InstanceEntityConfig unavailable
        if (returnTransform == null) {
            returnTransform = DungeonSession.getPlayerTransformSafely(playerRef);
        }
        if (returnTransform == null) {
            // Last resort: world spawn
            var spawnProvider = world.getWorldConfig().getSpawnProvider();
            if (spawnProvider != null) {
                returnTransform = spawnProvider.getSpawnPoint(world, playerRef.getUuid());
            } else {
                returnTransform = new Transform(
                    new Vector3d(0, 100, 0),
                    new Vector3f(0, 0, 0)
                );
            }
        }

        // Handle the player entering the dungeon world
        manager.handlePlayerEnterWorld(playerRef, world, returnTransform, returnWorldUuid);
    }

    @Override
    protected void shutdown() {
        super.shutdown();

        // Clean up any active sessions
        DungeonSessionManager manager = DungeonSessionManager.getInstance();
        if (manager != null) {
            for (var session : manager.getActiveSessions()) {
                manager.endSession(session);
            }
        }

        instance = null;
        this.getLogger().at(Level.INFO).log("HC_DungeonCore disabled");
    }
}
