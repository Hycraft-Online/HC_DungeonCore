package com.hcdungeoncore.systems;

import com.hcdungeoncore.HC_DungeonCorePlugin;
import com.hcdungeoncore.config.DungeonPartyGameplayConfig;
import com.hcdungeoncore.managers.DungeonSessionManager;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;

/**
 * ECS TickingSystem that prevents entity density crashes in dungeon worlds.
 *
 * When too many hostile NPCs cluster in a small area (e.g., doorway chokepoints),
 * the collision/steering/damage systems can overwhelm the world thread and crash
 * the client. This system periodically scans for dense NPC clusters and despawns
 * excess entities to keep the count within safe limits.
 *
 * Only runs in worlds with DungeonPartyGameplayConfig enabled.
 *
 * Configuration (via GameplayConfig Plugin.DungeonParty):
 * - MaxNPCsPerCluster: Maximum NPCs allowed within the cluster radius (default: 8)
 * - ClusterRadiusBlocks: Radius in blocks to check for clustering (default: 4.0)
 * - DensityCheckIntervalSeconds: How often to run the density check (default: 2.0)
 */
public class DungeonEntityDensitySystem extends TickingSystem<EntityStore> {

    /** Default maximum NPCs allowed in a cluster before culling */
    private static final int DEFAULT_MAX_NPCS_PER_CLUSTER = 8;

    /** Default radius (in blocks) to check for NPC clustering */
    private static final double DEFAULT_CLUSTER_RADIUS = 4.0;

    /** Default interval between density checks (in seconds) */
    private static final float DEFAULT_CHECK_INTERVAL_SECONDS = 2.0f;

    // Component types
    private final ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
    private final ComponentType<EntityStore, TransformComponent> transformType = TransformComponent.getComponentType();

    // Query for NPCs with transforms (all NPCs that have a position)
    private final Query<EntityStore> query = Query.and(npcType, transformType);

    // Query for players (to clear their interaction state before bulk NPC removal)
    private final ComponentType<EntityStore, Player> playerType = Player.getComponentType();
    private final Query<EntityStore> playerQuery = Query.and(playerType);

    private HC_DungeonCorePlugin plugin;

    // Internal timer for throttling density checks
    private float elapsedSinceLastCheck = 0.0f;

    /**
     * Initialize with plugin reference.
     */
    public void initialize(HC_DungeonCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void tick(float deltaTime, int tickIndex, @Nonnull Store<EntityStore> store) {
        if (plugin == null) {
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
        if (config == null || !config.isEnabled()) {
            return; // Not a dungeon party world
        }

        // Throttle checks using internal timer
        float checkInterval = config.getDensityCheckIntervalSeconds();
        if (checkInterval <= 0) {
            checkInterval = DEFAULT_CHECK_INTERVAL_SECONDS;
        }
        elapsedSinceLastCheck += deltaTime;
        if (elapsedSinceLastCheck < checkInterval) {
            return;
        }
        elapsedSinceLastCheck = 0.0f;

        // Get density parameters from config
        int maxPerCluster = config.getMaxNPCsPerCluster();
        if (maxPerCluster <= 0) {
            maxPerCluster = DEFAULT_MAX_NPCS_PER_CLUSTER;
        }
        double clusterRadius = config.getClusterRadiusBlocks();
        if (clusterRadius <= 0) {
            clusterRadius = DEFAULT_CLUSTER_RADIUS;
        }

        // Collect all NPC positions
        List<NPCRecord> npcs = new ArrayList<>();
        final double radiusSq = clusterRadius * clusterRadius;

        store.forEachEntityParallel(query, (index, archetypeChunk, commandBuffer) -> {
            Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
            if (ref == null || !ref.isValid()) {
                return;
            }
            TransformComponent transform = commandBuffer.getComponent(ref, transformType);
            if (transform == null) {
                return;
            }
            Vector3d pos = transform.getPosition();
            synchronized (npcs) {
                npcs.add(new NPCRecord(ref, pos.getX(), pos.getY(), pos.getZ()));
            }
        });

        if (npcs.size() <= maxPerCluster) {
            return; // Total NPC count is below threshold, no clustering possible
        }

        // Find dense clusters and mark excess NPCs for removal
        // Use a simple approach: for each NPC, count neighbors within radius.
        // If any NPC has too many neighbors, remove the NPCs furthest from
        // the cluster center (they are the stragglers piling in).
        boolean[] markedForRemoval = new boolean[npcs.size()];
        int totalRemoved = 0;

        for (int i = 0; i < npcs.size(); i++) {
            if (markedForRemoval[i]) {
                continue;
            }
            NPCRecord pivot = npcs.get(i);

            // Find all neighbors within cluster radius
            List<Integer> neighborIndices = new ArrayList<>();
            neighborIndices.add(i);

            for (int j = 0; j < npcs.size(); j++) {
                if (i == j || markedForRemoval[j]) {
                    continue;
                }
                NPCRecord other = npcs.get(j);
                double dx = pivot.x - other.x;
                double dy = pivot.y - other.y;
                double dz = pivot.z - other.z;
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq <= radiusSq) {
                    neighborIndices.add(j);
                }
            }

            // Check if this cluster exceeds the max
            if (neighborIndices.size() <= maxPerCluster) {
                continue;
            }

            // Calculate cluster center
            double cx = 0, cy = 0, cz = 0;
            for (int idx : neighborIndices) {
                NPCRecord n = npcs.get(idx);
                cx += n.x;
                cy += n.y;
                cz += n.z;
            }
            cx /= neighborIndices.size();
            cy /= neighborIndices.size();
            cz /= neighborIndices.size();

            // Sort by distance from cluster center (furthest first)
            final double fcx = cx, fcy = cy, fcz = cz;
            neighborIndices.sort(Comparator.comparingDouble((Integer idx) -> {
                NPCRecord n = npcs.get(idx);
                double ddx = n.x - fcx;
                double ddy = n.y - fcy;
                double ddz = n.z - fcz;
                return ddx * ddx + ddy * ddy + ddz * ddz;
            }).reversed());

            // Remove excess NPCs (the ones furthest from center)
            int excessCount = neighborIndices.size() - maxPerCluster;
            for (int k = 0; k < excessCount; k++) {
                int removeIdx = neighborIndices.get(k);
                markedForRemoval[removeIdx] = true;
                totalRemoved++;
            }
        }

        if (totalRemoved > 0) {
            // Clear interaction state for all players in this world before removing NPCs.
            // Without this, players interacting with an NPC that gets despawned here will
            // trigger ArrayIndexOutOfBoundsException in InteractionManager.
            store.forEachEntityParallel(playerQuery, (index, archetypeChunk, commandBuffer) -> {
                Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
                if (ref == null || !ref.isValid()) {
                    return;
                }
                InteractionManager im = store.getComponent(ref,
                    InteractionModule.get().getInteractionManagerComponent());
                if (im != null) {
                    im.clear();
                }
            });

            // Perform removals via forEachEntityParallel + commandBuffer
            final boolean[] finalMarked = markedForRemoval;
            final List<NPCRecord> finalNpcs = npcs;

            store.forEachEntityParallel(query, (index, archetypeChunk, commandBuffer) -> {
                Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
                if (ref == null || !ref.isValid()) {
                    return;
                }
                // Check if this ref matches any marked-for-removal NPC
                for (int i = 0; i < finalNpcs.size(); i++) {
                    if (finalMarked[i] && finalNpcs.get(i).ref.equals(ref)) {
                        commandBuffer.removeEntity(ref, RemoveReason.REMOVE);
                        break;
                    }
                }
            });

            log(Level.INFO, "Density check in %s: despawned %d excess NPCs (total: %d, max/cluster: %d, radius: %.1f)",
                world.getName(), totalRemoved, npcs.size(), maxPerCluster, clusterRadius);
        }
    }

    private void log(Level level, String format, Object... args) {
        if (plugin != null) {
            plugin.getLogger().at(level).log("[DungeonDensity] " + String.format(format, args));
        }
    }

    /**
     * Record of an NPC's position and entity reference for density calculations.
     */
    private static class NPCRecord {
        final Ref<EntityStore> ref;
        final double x, y, z;

        NPCRecord(Ref<EntityStore> ref, double x, double y, double z) {
            this.ref = ref;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
