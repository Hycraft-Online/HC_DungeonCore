package com.hcdungeoncore.systems;

import com.hcdungeoncore.HC_DungeonCorePlugin;
import com.hcdungeoncore.managers.DungeonSessionManager;
import com.hcdungeoncore.models.DungeonSession;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.asset.type.gameplay.DeathConfig;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.awt.Color;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * ECS System that intercepts player death/respawn in dungeons with DungeonPartyGameplayConfig.
 * Implements the shared lives system:
 * - When any party member dies: Lose 1 life from shared pool
 * - If lives remain: Respawn at dungeon spawn point
 * - If no lives remain: Party wipe, all teleported out
 */
public class DungeonRespawnSystem extends RefChangeSystem<EntityStore, DeathComponent> {

    private static final Query<EntityStore> QUERY = Query.and(
        Player.getComponentType(),
        PlayerRef.getComponentType()
    );

    private HC_DungeonCorePlugin plugin;

    public void initialize(HC_DungeonCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Nonnull
    @Override
    public ComponentType<EntityStore, DeathComponent> componentType() {
        return DeathComponent.getComponentType();
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return QUERY;
    }

    /**
     * System dependencies to ensure we run at the right time:
     * - AFTER PlayerDropItemsConfig (which sets initial ItemsLossMode from world config)
     * - BEFORE DropPlayerDeathItems (which actually drops items)
     */
    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return Set.of(
            new SystemDependency<>(Order.AFTER, DeathSystems.PlayerDropItemsConfig.class),
            new SystemDependency<>(Order.BEFORE, DeathSystems.DropPlayerDeathItems.class)
        );
    }

    @Override
    public void onComponentAdded(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull DeathComponent component,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        // Player died - check if they're in a dungeon session
        PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }

        UUID playerUuid = playerRef.getUuid();
        DungeonSessionManager manager = DungeonSessionManager.getInstance();
        if (manager == null) {
            return;
        }

        DungeonSession session = manager.getSession(playerUuid);
        if (session == null) {
            return; // Not in a dungeon session
        }

        // CRITICAL: Verify the player is actually in the dungeon world, not just has a session
        // Players may have left the dungeon (e.g., to FFA arena) but still have a session
        World currentWorld = store.getExternalData().getWorld();
        UUID currentWorldUuid = currentWorld != null ? currentWorld.getWorldConfig().getUuid() : null;
        UUID dungeonWorldUuid = session.getDungeonWorldUuid();

        if (currentWorldUuid == null || !currentWorldUuid.equals(dungeonWorldUuid)) {
            // Player is not in the dungeon world - clean up their stale session
            log(Level.INFO, "Player " + playerRef.getUsername() + " died outside dungeon (in " +
                (currentWorld != null ? currentWorld.getName() : "null") + ") - cleaning up stale session");
            manager.cleanupPlayerFromSession(playerUuid);
            return;
        }

        // Prevent inventory loss if configured
        if (session.shouldPreventItemLoss()) {
            component.setItemsLossMode(DeathConfig.ItemsLossMode.NONE);
            component.setItemsAmountLossPercentage(0.0);
            component.setItemsDurabilityLossPercentage(0.0);
        }

        // Handle death with shared party lives system
        int livesRemaining = session.handleMemberDeath(playerUuid);

        if (livesRemaining > 0) {
            // Party still has lives - player will respawn in dungeon
            String livesText = livesRemaining == 1 ? "1 life" : livesRemaining + " lives";
            playerRef.sendMessage(Message.raw("You died!").color(Color.RED));
            playerRef.sendMessage(Message.raw("Respawning in " + session.getRespawnDelaySeconds() +
                " seconds...").color(Color.YELLOW));

            // Notify all party members about remaining lives
            session.broadcastToMembers(
                Message.raw(playerRef.getUsername() + " died! Party has " + livesText + " remaining.")
                    .color(Color.ORANGE)
            );

            log(Level.INFO, "Player " + playerRef.getUsername() + " died in dungeon - " +
                livesRemaining + " party lives remaining - will respawn");
        } else {
            // No lives left - party wipe!
            playerRef.sendMessage(Message.raw("OUT OF LIVES!").color(Color.RED).bold(true));
            session.broadcastToMembers(
                Message.raw("PARTY WIPE - No lives remaining!").color(Color.RED).bold(true)
            );

            log(Level.INFO, "Party wipe - no lives remaining after " + playerRef.getUsername() + " died");

            // Schedule teleporting all members out
            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
                manager.teleportAllMembersOut(session);
                manager.endSession(session);
            }, 2, TimeUnit.SECONDS);
        }
    }

    @Override
    public void onComponentRemoved(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull DeathComponent component,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        // Player clicked Respawn - check if they were in a dungeon session
        PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }

        UUID playerUuid = playerRef.getUuid();
        DungeonSessionManager manager = DungeonSessionManager.getInstance();
        if (manager == null) {
            return;
        }

        DungeonSession session = manager.getSession(playerUuid);
        if (session == null) {
            return; // Not in a dungeon session
        }

        // Verify player is actually in the dungeon world
        World currentWorld = store.getExternalData().getWorld();
        UUID currentWorldUuid = currentWorld != null ? currentWorld.getWorldConfig().getUuid() : null;
        UUID dungeonWorldUuid = session.getDungeonWorldUuid();

        if (currentWorldUuid == null || !currentWorldUuid.equals(dungeonWorldUuid)) {
            // Player is respawning outside dungeon - clean up stale session
            log(Level.INFO, "Player " + playerRef.getUsername() + " respawned outside dungeon - cleaning up stale session");
            manager.cleanupPlayerFromSession(playerUuid);
            return;
        }

        // Check if party has lives remaining
        if (!session.isOutOfLives()) {
            // Party still has lives - respawn player in the dungeon
            log(Level.INFO, "Player " + playerRef.getUsername() + " respawning in dungeon (" +
                session.getRemainingLives() + " party lives left)");

            manager.respawnPlayerInDungeon(playerUuid, session);
        } else {
            // Party wiped - teleport back to faction spawn
            log(Level.INFO, "Player " + playerRef.getUsername() +
                " - party wiped, teleporting to faction spawn");

            manager.teleportPlayerOut(playerUuid, session);
        }
    }

    @Override
    public void onComponentSet(
            @Nonnull Ref<EntityStore> ref,
            DeathComponent oldComponent,
            @Nonnull DeathComponent newComponent,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        // Not used
    }

    private void log(Level level, String message) {
        if (plugin != null) {
            plugin.getLogger().at(level).log("[DungeonRespawn] " + message);
        }
    }
}
