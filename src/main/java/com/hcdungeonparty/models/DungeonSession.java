package com.hcdungeonparty.models;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;

import java.awt.Color;
import java.util.*;

/**
 * Represents an active dungeon session for one or more players (party).
 * Tracks the players, dungeon world, shared lives, and member states.
 * Supports both solo play (party of 1) and co-op with multiple players.
 */
public class DungeonSession {

    /** Default lives per player in the shared pool */
    public static final int DEFAULT_LIVES_PER_PLAYER = 1;

    // Session ID (unique for this dungeon run)
    private final UUID sessionId;

    // PartyMod party ID (null for solo sessions)
    private final String partyModPartyId;

    // All party members
    private final Set<UUID> memberUuids = new HashSet<>();
    private final Map<UUID, PlayerRef> memberRefs = new HashMap<>();
    private final Map<UUID, MemberState> memberStates = new HashMap<>();
    private final Map<UUID, Transform> memberReturnTransforms = new HashMap<>();

    // Shared party lives pool
    private int partyLives;
    private final int livesPerPlayer;

    // Dungeon world reference
    private final UUID dungeonWorldUuid;
    private World dungeonWorld;

    // Spawn point in the dungeon (for respawning)
    private Vector3d spawnPosition;
    private Vector3f spawnRotation;

    // Configuration
    private final int respawnDelaySeconds;
    private final boolean preventItemLoss;

    // Track whether members have entered the dungeon world
    private final Set<UUID> membersEnteredDungeon = new HashSet<>();

    // Session start time
    private final long sessionStartTime;

    /**
     * Create a dungeon session.
     *
     * @param partyModPartyId   The PartyMod party ID (null for solo play)
     * @param dungeonWorldUuid  The UUID of the dungeon world
     * @param livesPerPlayer    Lives per player in the shared pool
     * @param respawnDelaySeconds Seconds to wait before respawn
     * @param preventItemLoss   Whether to prevent item loss on death
     */
    public DungeonSession(String partyModPartyId, UUID dungeonWorldUuid,
                          int livesPerPlayer, int respawnDelaySeconds, boolean preventItemLoss) {
        this.sessionId = UUID.randomUUID();
        this.partyModPartyId = partyModPartyId;
        this.dungeonWorldUuid = dungeonWorldUuid;
        this.livesPerPlayer = livesPerPlayer;
        this.partyLives = 0; // Will be calculated when members are registered
        this.respawnDelaySeconds = respawnDelaySeconds;
        this.preventItemLoss = preventItemLoss;
        this.sessionStartTime = System.currentTimeMillis();
    }

    // ═══════════════════════════════════════════════════════
    // PLAYER REF VALIDATION
    // ═══════════════════════════════════════════════════════

    /**
     * Checks if a PlayerRef is still valid (player is connected).
     */
    public static boolean isPlayerRefValid(PlayerRef playerRef) {
        if (playerRef == null) {
            return false;
        }
        try {
            return playerRef.isValid();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Safely gets a player's transform, returning null if invalid.
     */
    public static Transform getPlayerTransformSafely(PlayerRef playerRef) {
        if (playerRef == null) {
            return null;
        }
        try {
            return playerRef.getTransform();
        } catch (Exception e) {
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════
    // PARTY MEMBER MANAGEMENT
    // ═══════════════════════════════════════════════════════

    /**
     * Register a party member for this dungeon session.
     * Should be called for each member before entering the dungeon.
     */
    public void registerMember(UUID memberUuid, PlayerRef memberRef, Transform memberReturnTransform) {
        memberUuids.add(memberUuid);
        memberRefs.put(memberUuid, memberRef);
        memberStates.put(memberUuid, MemberState.ALIVE);
        // Copy the transform
        if (memberReturnTransform != null) {
            memberReturnTransforms.put(memberUuid, new Transform(
                new Vector3d(memberReturnTransform.getPosition()),
                new Vector3f(memberReturnTransform.getRotation())
            ));
        }
        // Recalculate shared party lives pool based on party size
        partyLives = memberUuids.size() * livesPerPlayer;
    }

    /**
     * Check if a player is a member of this session.
     */
    public boolean isMember(UUID playerUuid) {
        return memberUuids.contains(playerUuid);
    }

    /**
     * Handle a member death. Decrements shared party lives and returns remaining.
     *
     * @return Remaining party lives (0 = party wipe)
     */
    public int handleMemberDeath(UUID memberUuid) {
        if (partyLives > 0) {
            partyLives--;
        }

        // Player is respawning as long as there are lives left
        if (partyLives > 0) {
            memberStates.put(memberUuid, MemberState.RESPAWNING);
        } else {
            // No lives left - this death ends the session
            memberStates.put(memberUuid, MemberState.ELIMINATED);
        }

        return partyLives;
    }

    /**
     * Mark a member as respawned (back to ALIVE state).
     */
    public void markMemberRespawned(UUID memberUuid) {
        if (memberStates.get(memberUuid) == MemberState.RESPAWNING) {
            memberStates.put(memberUuid, MemberState.ALIVE);
        }
    }

    /**
     * Mark a member as disconnected.
     */
    public void markMemberDisconnected(UUID memberUuid) {
        memberStates.put(memberUuid, MemberState.DISCONNECTED);
    }

    /**
     * Get remaining shared party lives.
     */
    public int getRemainingLives() {
        return partyLives;
    }

    /**
     * Get the total lives the party started with.
     */
    public int getTotalLives() {
        return memberUuids.size() * livesPerPlayer;
    }

    /**
     * Check if the party is out of lives.
     */
    public boolean isOutOfLives() {
        return partyLives <= 0;
    }

    /**
     * Check if a member is eliminated.
     */
    public boolean isMemberEliminated(UUID memberUuid) {
        return memberStates.get(memberUuid) == MemberState.ELIMINATED;
    }

    /**
     * Check if all active members are eliminated or disconnected (party wipe).
     */
    public boolean isPartyWiped() {
        for (UUID memberUuid : memberUuids) {
            MemberState state = memberStates.get(memberUuid);
            if (state == MemberState.ALIVE || state == MemberState.RESPAWNING) {
                return false;
            }
        }
        return true;
    }

    /**
     * Get the count of alive members (not eliminated or disconnected).
     */
    public int getAliveCount() {
        int count = 0;
        for (UUID memberUuid : memberUuids) {
            MemberState state = memberStates.get(memberUuid);
            if (state == MemberState.ALIVE || state == MemberState.RESPAWNING) {
                count++;
            }
        }
        return count;
    }

    /**
     * Get the party size (total registered members).
     */
    public int getPartySize() {
        return memberUuids.size();
    }

    /**
     * Check if this is a solo session (party of 1).
     */
    public boolean isSoloSession() {
        return memberUuids.size() == 1;
    }

    /**
     * Get all member UUIDs.
     */
    public Set<UUID> getMemberUuids() {
        return Collections.unmodifiableSet(memberUuids);
    }

    /**
     * Get the PlayerRef for a member.
     */
    public PlayerRef getMemberRef(UUID memberUuid) {
        return memberRefs.get(memberUuid);
    }

    /**
     * Get all member PlayerRefs.
     */
    public Collection<PlayerRef> getAllMemberRefs() {
        return Collections.unmodifiableCollection(memberRefs.values());
    }

    /**
     * Get the return transform for a specific member.
     */
    public Transform getMemberReturnTransform(UUID memberUuid) {
        return memberReturnTransforms.get(memberUuid);
    }

    /**
     * Get the state of a member.
     */
    public MemberState getMemberState(UUID memberUuid) {
        return memberStates.getOrDefault(memberUuid, MemberState.DISCONNECTED);
    }

    /**
     * Mark a specific member as having entered the dungeon.
     */
    public void setMemberEnteredDungeon(UUID memberUuid, boolean entered) {
        if (entered) {
            membersEnteredDungeon.add(memberUuid);
        } else {
            membersEnteredDungeon.remove(memberUuid);
        }
    }

    /**
     * Check if a specific member has entered the dungeon.
     */
    public boolean hasMemberEnteredDungeon(UUID memberUuid) {
        return membersEnteredDungeon.contains(memberUuid);
    }

    /**
     * Check if any member has entered the dungeon.
     */
    public boolean hasAnyMemberEnteredDungeon() {
        return !membersEnteredDungeon.isEmpty();
    }

    // ═══════════════════════════════════════════════════════
    // MESSAGING
    // ═══════════════════════════════════════════════════════

    /**
     * Send a message to all party members.
     */
    public void broadcastToMembers(Message message) {
        for (UUID memberUuid : memberUuids) {
            MemberState state = memberStates.get(memberUuid);
            if (state != MemberState.DISCONNECTED) {
                try {
                    PlayerRef ref = memberRefs.get(memberUuid);
                    if (ref != null && isPlayerRefValid(ref)) {
                        ref.sendMessage(message);
                    }
                } catch (Exception e) {
                    // Player may have disconnected
                }
            }
        }
    }

    /**
     * Send a lives status message to the specified member.
     */
    public void sendLivesStatus(UUID memberUuid) {
        PlayerRef ref = memberRefs.get(memberUuid);
        if (ref != null && isPlayerRefValid(ref)) {
            String livesText = partyLives == 1 ? "1 life" : partyLives + " lives";
            ref.sendMessage(Message.raw("Party lives remaining: " + livesText)
                .color(partyLives <= 1 ? Color.RED : Color.YELLOW));
        }
    }

    /**
     * Broadcast lives status to all party members.
     */
    public void broadcastLivesStatus() {
        String livesText = partyLives == 1 ? "1 life" : partyLives + " lives";
        Color color = partyLives <= 1 ? Color.RED : (partyLives <= 3 ? Color.ORANGE : Color.YELLOW);
        broadcastToMembers(Message.raw("Party lives: " + livesText).color(color));
    }

    // ═══════════════════════════════════════════════════════
    // GETTERS / SETTERS
    // ═══════════════════════════════════════════════════════

    public UUID getSessionId() {
        return sessionId;
    }

    public String getPartyId() {
        return partyModPartyId;
    }

    public UUID getDungeonWorldUuid() {
        return dungeonWorldUuid;
    }

    public World getDungeonWorld() {
        return dungeonWorld;
    }

    public void setDungeonWorld(World dungeonWorld) {
        this.dungeonWorld = dungeonWorld;
    }

    public Vector3d getSpawnPosition() {
        return spawnPosition;
    }

    public void setSpawnPosition(Vector3d spawnPosition) {
        this.spawnPosition = spawnPosition;
    }

    public Vector3f getSpawnRotation() {
        return spawnRotation;
    }

    public void setSpawnRotation(Vector3f spawnRotation) {
        this.spawnRotation = spawnRotation;
    }

    public int getRespawnDelaySeconds() {
        return respawnDelaySeconds;
    }

    public boolean shouldPreventItemLoss() {
        return preventItemLoss;
    }

    public int getLivesPerPlayer() {
        return livesPerPlayer;
    }

    public long getSessionDurationMs() {
        return System.currentTimeMillis() - sessionStartTime;
    }
}
