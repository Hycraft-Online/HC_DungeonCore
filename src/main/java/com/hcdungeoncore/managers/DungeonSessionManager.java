package com.hcdungeoncore.managers;

import com.hcdungeoncore.HC_DungeonCorePlugin;
import com.hcdungeoncore.config.DungeonPartyGameplayConfig;
import com.hcdungeoncore.integration.PartyModIntegration;
import com.hcdungeoncore.models.DungeonSession;
import com.hcdungeoncore.models.MemberState;
import com.hcfactions.HC_FactionsPlugin;
import com.hcfactions.models.Faction;
import com.hcfactions.models.PlayerData;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.gameplay.GameplayConfig;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.builtin.instances.InstancesPlugin;

import java.awt.Color;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Manages dungeon sessions with shared party lives.
 * Creates sessions when players enter dungeon worlds with DungeonPartyGameplayConfig enabled.
 */
public class DungeonSessionManager {

    private static DungeonSessionManager instance;

    private HC_DungeonCorePlugin plugin;

    // Sessions indexed by player UUID (for quick lookup)
    private final Map<UUID, DungeonSession> sessionsByPlayer = new ConcurrentHashMap<>();

    // Sessions indexed by dungeon world UUID
    private final Map<UUID, DungeonSession> sessionsByWorld = new ConcurrentHashMap<>();

    // Player faction spawns cache (optional, used if HC_Factions is available)
    private final Map<UUID, FactionSpawnInfo> factionSpawnCache = new ConcurrentHashMap<>();

    // Lock for atomic updates across both session maps
    private final Object sessionLock = new Object();

    /**
     * Faction spawn info for returning players after dungeon.
     */
    public static class FactionSpawnInfo {
        public final World world;
        public final Transform transform;

        public FactionSpawnInfo(World world, Transform transform) {
            this.world = world;
            this.transform = transform;
        }
    }

    public static void initialize(HC_DungeonCorePlugin plugin) {
        instance = new DungeonSessionManager();
        instance.plugin = plugin;
    }

    public static DungeonSessionManager getInstance() {
        return instance;
    }

    // ═══════════════════════════════════════════════════════
    // SESSION MANAGEMENT
    // ═══════════════════════════════════════════════════════

    /**
     * Handle a player entering a world. If the world has DungeonPartyGameplayConfig enabled,
     * create or join a session.
     *
     * @param playerRef The player entering the world
     * @param world The world being entered
     * @param returnTransform The player's previous location (for return on exit)
     * @param returnWorldUuid The UUID of the world to return to (from InstanceEntityConfig), or null
     */
    public void handlePlayerEnterWorld(PlayerRef playerRef, World world, Transform returnTransform, UUID returnWorldUuid) {
        UUID playerUuid = playerRef.getUuid();
        UUID worldUuid = world.getWorldConfig().getUuid();

        // Check if this world has DungeonParty config enabled
        DungeonPartyGameplayConfig config = getDungeonPartyConfig(world);
        if (config == null || !config.isEnabled()) {
            return; // Not a dungeon party world
        }

        // Atomically check both maps and update mappings
        // We capture the result to handle post-lock messaging outside the lock
        DungeonSession existingSession;
        DungeonSession newSession = null;
        boolean joinedExisting = false;
        boolean alreadyInSession = false;

        // Prepare party info outside the lock (may involve cross-plugin calls)
        String partyId = null;
        Set<UUID> partyMembers = new HashSet<>();
        if (PartyModIntegration.isAvailable() && PartyModIntegration.isInParty(playerUuid)) {
            partyId = PartyModIntegration.getPartyId(playerUuid);
            partyMembers = PartyModIntegration.getPartyMemberUuids(playerUuid);
        }

        synchronized (sessionLock) {
            // Check if there's already a session for this world
            existingSession = sessionsByWorld.get(worldUuid);
            if (existingSession != null) {
                // Join existing session
                if (!existingSession.isMember(playerUuid)) {
                    existingSession.registerMember(playerUuid, playerRef, returnTransform, returnWorldUuid);
                    sessionsByPlayer.put(playerUuid, existingSession);
                    joinedExisting = true;
                }
            } else if (sessionsByPlayer.containsKey(playerUuid)) {
                // Player already has a session (shouldn't happen normally)
                alreadyInSession = true;
            } else {
                // Create session with config values
                newSession = new DungeonSession(
                    partyId,
                    worldUuid,
                    config.getLivesPerPlayer(),
                    config.getRespawnDelaySeconds(),
                    config.shouldPreventItemLoss()
                );

                // Store world reference
                newSession.setDungeonWorld(world);

                // Set spawn position from world spawn point
                var spawnProvider = world.getWorldConfig().getSpawnProvider();
                if (spawnProvider != null) {
                    Transform spawnTransform = spawnProvider.getSpawnPoint(world, playerUuid);
                    newSession.setSpawnPosition(spawnTransform.getPosition());
                    newSession.setSpawnRotation(spawnTransform.getRotation());
                } else {
                    newSession.setSpawnPosition(returnTransform.getPosition());
                    newSession.setSpawnRotation(returnTransform.getRotation());
                }

                // Register the entering player and update BOTH maps atomically
                newSession.registerMember(playerUuid, playerRef, returnTransform, returnWorldUuid);
                sessionsByPlayer.put(playerUuid, newSession);
                sessionsByWorld.put(worldUuid, newSession);
            }
        }

        // Handle results outside the lock (messaging, scheduling)
        if (existingSession != null) {
            if (joinedExisting) {
                log(Level.INFO, "Player " + playerRef.getUsername() + " joined existing dungeon session");
            }
            scheduleMarkPlayerEntered(existingSession, playerUuid, playerRef);
            return;
        }

        if (alreadyInSession) {
            log(Level.WARNING, "Player " + playerRef.getUsername() + " already in a session, skipping");
            return;
        }

        // Log session creation (newSession is guaranteed non-null here)
        if (partyId != null && partyMembers.size() > 1) {
            log(Level.INFO, "Created dungeon session for party of " + partyMembers.size() +
                " with " + newSession.getTotalLives() + " shared lives");
            newSession.broadcastToMembers(Message.raw("Dungeon started! Party has " +
                newSession.getTotalLives() + " shared lives.").color(Color.GREEN));
        } else {
            log(Level.INFO, "Created solo dungeon session with " + newSession.getTotalLives() + " lives");
            playerRef.sendMessage(Message.raw("Dungeon started! You have " +
                newSession.getTotalLives() + " lives.").color(Color.GREEN));
        }

        // Mark player as entered (after delay to handle teleport race)
        scheduleMarkPlayerEntered(newSession, playerUuid, playerRef);
    }

    /**
     * Get the dungeon party config for a world.
     */
    public DungeonPartyGameplayConfig getDungeonPartyConfig(World world) {
        if (world == null) {
            return null;
        }
        GameplayConfig gameplayConfig = world.getGameplayConfig();
        if (gameplayConfig == null) {
            return null;
        }
        return gameplayConfig.getPluginConfig().get(DungeonPartyGameplayConfig.class);
    }

    /**
     * Schedule marking a player as entered with a delay to handle teleport race condition.
     */
    private void scheduleMarkPlayerEntered(DungeonSession session, UUID playerUuid, PlayerRef playerRef) {
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            World targetWorld = session.getDungeonWorld();
            if (targetWorld == null) {
                return;
            }
            targetWorld.execute(() -> {
                Ref<EntityStore> ref = playerRef.getReference();
                if (ref != null && ref.isValid()) {
                    session.setMemberEnteredDungeon(playerUuid, true);
                    log(Level.FINE, "Marked " + playerRef.getUsername() + " as entered dungeon");
                }
            });
        }, 2, TimeUnit.SECONDS);
    }

    /**
     * Get the session for a player.
     */
    public DungeonSession getSession(UUID playerUuid) {
        return sessionsByPlayer.get(playerUuid);
    }

    /**
     * Get the session for a world.
     */
    public DungeonSession getSessionByWorld(UUID worldUuid) {
        return sessionsByWorld.get(worldUuid);
    }

    /**
     * Check if a player is in a dungeon session.
     */
    public boolean isInDungeon(UUID playerUuid) {
        return sessionsByPlayer.containsKey(playerUuid);
    }

    /**
     * Get all active sessions.
     */
    public Collection<DungeonSession> getActiveSessions() {
        return new HashSet<>(sessionsByWorld.values());
    }

    // ═══════════════════════════════════════════════════════
    // RESPAWN HANDLING
    // ═══════════════════════════════════════════════════════

    /**
     * Respawn a player in the dungeon at the spawn point.
     */
    public void respawnPlayerInDungeon(UUID playerUuid, DungeonSession session) {
        PlayerRef playerRef = session.getMemberRef(playerUuid);
        if (playerRef == null || !DungeonSession.isPlayerRefValid(playerRef)) {
            log(Level.WARNING, "Cannot respawn - player ref invalid");
            return;
        }

        World dungeonWorld = session.getDungeonWorld();
        if (dungeonWorld == null) {
            log(Level.WARNING, "Cannot respawn - dungeon world null");
            return;
        }

        Vector3d spawnPos = session.getSpawnPosition();
        Vector3f spawnRot = session.getSpawnRotation();

        if (spawnPos == null) {
            // Get spawn from world spawn provider
            var spawnProvider = dungeonWorld.getWorldConfig().getSpawnProvider();
            if (spawnProvider != null) {
                Transform spawnTransform = spawnProvider.getSpawnPoint(dungeonWorld, playerUuid);
                spawnPos = spawnTransform.getPosition();
                spawnRot = spawnTransform.getRotation();
            } else {
                spawnPos = new Vector3d(0, 100, 0);
                spawnRot = new Vector3f(0, 0, 0);
            }
        }

        final Vector3d finalSpawnPos = spawnPos;
        final Vector3f finalSpawnRot = spawnRot;
        final PlayerRef finalPlayerRef = playerRef;

        // Schedule respawn
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            Ref<EntityStore> playerEntityRef = finalPlayerRef.getReference();
            if (playerEntityRef == null || !playerEntityRef.isValid()) {
                return;
            }

            World playerCurrentWorld = playerEntityRef.getStore().getExternalData().getWorld();
            if (playerCurrentWorld == null) {
                return;
            }
            playerCurrentWorld.execute(() -> {
                try {
                    Ref<EntityStore> freshRef = finalPlayerRef.getReference();
                    if (freshRef == null || !freshRef.isValid()) {
                        return;
                    }

                    Store<EntityStore> entityStore = freshRef.getStore();

                    // Clear interaction state before teleport to prevent ArrayIndexOutOfBoundsException
                    InteractionManager interactionManager = entityStore.getComponent(freshRef,
                        InteractionModule.get().getInteractionManagerComponent());
                    if (interactionManager != null) {
                        interactionManager.clear();
                    }

                    entityStore.addComponent(freshRef, Teleport.getComponentType(),
                        Teleport.createForPlayer(dungeonWorld, finalSpawnPos, finalSpawnRot));

                    session.markMemberRespawned(playerUuid);
                    log(Level.INFO, "Respawned " + finalPlayerRef.getUsername() + " in dungeon");
                } catch (Exception e) {
                    log(Level.WARNING, "Error respawning player in dungeon: " + e.getMessage());
                }
            });
        }, 50, TimeUnit.MILLISECONDS);
    }

    /**
     * Teleport a player out of the dungeon to their faction spawn or return location.
     */
    public void teleportPlayerOut(UUID playerUuid, DungeonSession session) {
        PlayerRef playerRef = session.getMemberRef(playerUuid);
        if (playerRef == null || !DungeonSession.isPlayerRefValid(playerRef)) {
            log(Level.WARNING, "Cannot teleport out - player ref invalid");
            cleanupPlayerFromSession(playerUuid);
            return;
        }

        // Get faction spawn or return location
        FactionSpawnInfo factionSpawn = getFactionSpawn(playerUuid);

        World targetWorld = null;
        Vector3d targetPos;
        Vector3f targetRot;

        if (factionSpawn != null) {
            targetWorld = factionSpawn.world;
            targetPos = factionSpawn.transform.getPosition();
            targetRot = factionSpawn.transform.getRotation();
        } else {
            // Fall back to return transform
            Transform returnTransform = session.getMemberReturnTransform(playerUuid);

            // Look up the return world from InstanceEntityConfig (stored at entry time)
            UUID returnWorldUuid = session.getMemberReturnWorldUuid(playerUuid);
            if (returnWorldUuid != null) {
                targetWorld = Universe.get().getWorld(returnWorldUuid);
            }
            if (targetWorld == null) {
                targetWorld = Universe.get().getDefaultWorld();
            }

            if (returnTransform == null) {
                if (targetWorld != null) {
                    var spawnProvider = targetWorld.getWorldConfig().getSpawnProvider();
                    if (spawnProvider != null) {
                        Transform spawnTransform = spawnProvider.getSpawnPoint(targetWorld, playerUuid);
                        targetPos = spawnTransform.getPosition();
                        targetRot = spawnTransform.getRotation();
                    } else {
                        targetPos = new Vector3d(0, 100, 0);
                        targetRot = new Vector3f(0, 0, 0);
                    }
                } else {
                    targetPos = new Vector3d(0, 100, 0);
                    targetRot = new Vector3f(0, 0, 0);
                }
            } else {
                targetPos = returnTransform.getPosition();
                targetRot = returnTransform.getRotation();
            }
        }

        if (targetWorld == null) {
            log(Level.WARNING, "Cannot teleport out - no target world");
            return;
        }

        final World finalTargetWorld = targetWorld;
        final Vector3d finalTargetPos = targetPos;
        final Vector3f finalTargetRot = targetRot;
        final PlayerRef finalPlayerRef = playerRef;

        // Schedule teleport
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            Ref<EntityStore> playerEntityRef = finalPlayerRef.getReference();
            if (playerEntityRef == null || !playerEntityRef.isValid()) {
                return;
            }

            World playerCurrentWorld = playerEntityRef.getStore().getExternalData().getWorld();
            if (playerCurrentWorld == null) {
                return;
            }
            playerCurrentWorld.execute(() -> {
                try {
                    Ref<EntityStore> freshRef = finalPlayerRef.getReference();
                    if (freshRef == null || !freshRef.isValid()) {
                        return;
                    }

                    Store<EntityStore> entityStore = freshRef.getStore();

                    // Clear interaction state before teleport to prevent ArrayIndexOutOfBoundsException
                    InteractionManager interactionManager = entityStore.getComponent(freshRef,
                        InteractionModule.get().getInteractionManagerComponent());
                    if (interactionManager != null) {
                        interactionManager.clear();
                    }

                    entityStore.addComponent(freshRef, Teleport.getComponentType(),
                        Teleport.createForPlayer(finalTargetWorld, finalTargetPos, finalTargetRot));

                    log(Level.INFO, "Teleported " + finalPlayerRef.getUsername() + " out of dungeon");
                } catch (Exception e) {
                    log(Level.WARNING, "Error teleporting player out: " + e.getMessage());
                }
            });
        }, 50, TimeUnit.MILLISECONDS);
    }

    /**
     * Teleport all remaining party members out of the dungeon.
     */
    public void teleportAllMembersOut(DungeonSession session) {
        for (UUID memberUuid : session.getMemberUuids()) {
            MemberState state = session.getMemberState(memberUuid);
            if (state != MemberState.DISCONNECTED) {
                teleportPlayerOut(memberUuid, session);
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // DISCONNECT HANDLING
    // ═══════════════════════════════════════════════════════

    /**
     * Handle a player disconnecting while in a dungeon session.
     */
    public void handlePlayerDisconnect(UUID playerUuid) {
        DungeonSession session;
        boolean shouldEndSession = false;

        synchronized (sessionLock) {
            session = sessionsByPlayer.get(playerUuid);
            if (session == null) {
                return;
            }

            // Only process if player actually entered the dungeon
            if (!session.hasMemberEnteredDungeon(playerUuid)) {
                log(Level.FINE, "Disconnect event during teleport transition (ignoring)");
                return;
            }

            session.markMemberDisconnected(playerUuid);

            // Check if all members are gone
            if (session.isPartyWiped()) {
                shouldEndSession = true;
            }
        }

        log(Level.INFO, "Player disconnected during dungeon session");

        if (shouldEndSession) {
            log(Level.INFO, "All party members disconnected/eliminated, ending session");
            endSession(session);
        }
    }

    // ═══════════════════════════════════════════════════════
    // SESSION CLEANUP
    // ═══════════════════════════════════════════════════════

    /** Delay before checking if the built-in RemovalSystem actually removed the world */
    private static final long REMOVAL_VERIFY_DELAY_S = 60;

    /** Maximum retry attempts for force-removing a leaked world */
    private static final int MAX_REMOVAL_RETRIES = 3;

    /**
     * End a dungeon session and clean up.
     */
    public void endSession(DungeonSession session) {
        if (session == null) {
            return;
        }

        // Atomically remove all mappings from both maps
        synchronized (sessionLock) {
            for (UUID memberUuid : session.getMemberUuids()) {
                sessionsByPlayer.remove(memberUuid);
            }
            sessionsByWorld.remove(session.getDungeonWorldUuid());
        }

        // Expensive operations (world removal, scheduling) happen outside the lock
        World dungeonWorld = session.getDungeonWorld();
        if (dungeonWorld != null) {
            String worldName = dungeonWorld.getName();
            try {
                InstancesPlugin.safeRemoveInstance(dungeonWorld);
                log(Level.INFO, "Marked dungeon world " + worldName + " for safe removal");
            } catch (Exception e) {
                log(Level.WARNING, "Error marking dungeon instance for removal: " + e.getMessage());
            }

            // Safety net: the built-in RemovalSystem silently fails ~50% of the time
            // (sets isRemoving=true then removeWorld fails with no retry).
            // Verify the world is actually gone after a delay and force-remove if not.
            scheduleWorldRemovalVerification(worldName, 1);
        }

        log(Level.INFO, "Dungeon session ended after " +
            formatTime(session.getSessionDurationMs()));
    }

    /**
     * Verify a world was actually removed. If it still exists, force-remove it.
     * The built-in RemovalSystem has a bug where it sets isRemoving=true before
     * calling removeWorld() via CompletableFuture with no error handler — if
     * removeWorld() throws, the world is permanently leaked.
     */
    private void scheduleWorldRemovalVerification(String worldName, int attempt) {
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            try {
                World world = Universe.get().getWorld(worldName);
                if (world == null) {
                    log(Level.FINE, "World " + worldName + " confirmed removed");
                    return;
                }

                if (world.getPlayerCount() > 0) {
                    log(Level.INFO, "World " + worldName + " still has players, rescheduling removal check");
                    scheduleWorldRemovalVerification(worldName, attempt);
                    return;
                }

                log(Level.WARNING, "World " + worldName + " still exists after " +
                    (attempt * REMOVAL_VERIFY_DELAY_S) + "s — forcing removal (attempt " +
                    attempt + "/" + MAX_REMOVAL_RETRIES + ")");

                try {
                    Universe.get().removeWorld(worldName);
                    log(Level.INFO, "Force-removed leaked dungeon world: " + worldName);
                } catch (Exception ex) {
                    log(Level.WARNING, "Force removal of " + worldName + " failed: " + ex.getMessage());
                    if (attempt < MAX_REMOVAL_RETRIES) {
                        scheduleWorldRemovalVerification(worldName, attempt + 1);
                    } else {
                        log(Level.SEVERE, "LEAKED WORLD: " + worldName +
                            " could not be removed after " + MAX_REMOVAL_RETRIES + " attempts");
                    }
                }
            } catch (Exception e) {
                log(Level.WARNING, "Error verifying world removal for " + worldName + ": " + e.getMessage());
            }
        }, REMOVAL_VERIFY_DELAY_S, TimeUnit.SECONDS);
    }

    /**
     * Clean up a single player from their session.
     */
    public void cleanupPlayerFromSession(UUID playerUuid) {
        DungeonSession session;
        boolean shouldEndSession = false;

        synchronized (sessionLock) {
            session = sessionsByPlayer.remove(playerUuid);
            if (session != null) {
                session.markMemberDisconnected(playerUuid);

                // Check if all members are gone
                if (session.isPartyWiped()) {
                    shouldEndSession = true;
                }
            }
        }

        // End session outside the lock (endSession acquires the lock itself for map cleanup)
        if (shouldEndSession) {
            endSession(session);
        }
    }

    // ═══════════════════════════════════════════════════════
    // FACTION SPAWN LOOKUP
    // ═══════════════════════════════════════════════════════

    /**
     * Get the faction spawn location for a player.
     * Returns null if HC_Factions is not available or player has no faction.
     */
    public FactionSpawnInfo getFactionSpawn(UUID playerUuid) {
        try {
            HC_FactionsPlugin factionPlugin = HC_FactionsPlugin.getInstance();
            if (factionPlugin == null) {
                return null;
            }

            // Get player's faction data
            PlayerData playerData = factionPlugin.getPlayerDataRepository().getPlayerData(playerUuid);
            if (playerData == null || playerData.getFactionId() == null) {
                return null;
            }

            // Get the faction
            Faction faction = factionPlugin.getFactionManager().getFaction(playerData.getFactionId());
            if (faction == null) {
                return null;
            }

            // Get the faction spawn world
            World spawnWorld = Universe.get().getWorld(faction.getSpawnWorld());
            if (spawnWorld == null) {
                return null;
            }

            // Build spawn transform
            double safeY = faction.getSpawnY() + 0.5;
            Vector3d spawnPos = new Vector3d(faction.getSpawnX(), safeY, faction.getSpawnZ());
            Vector3f rotation = new Vector3f(0, 0, 0);
            Transform spawnTransform = new Transform(spawnPos, rotation);

            return new FactionSpawnInfo(spawnWorld, spawnTransform);

        } catch (Exception e) {
            log(Level.FINE, "Error getting faction spawn: " + e.getMessage());
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════
    // UTILITIES
    // ═══════════════════════════════════════════════════════

    private String formatTime(long ms) {
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        return String.format("%d:%02d", minutes, seconds);
    }

    private void log(Level level, String message) {
        if (plugin != null) {
            plugin.getLogger().at(level).log("[DungeonSession] " + message);
        }
    }
}
