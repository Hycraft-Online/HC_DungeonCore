package com.hcdungeoncore.integration;

import com.hcdungeoncore.HC_DungeonCorePlugin;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Integration with PartyMod plugin using pure reflection.
 * This allows HC_DungeonCore to work with or without PartyMod installed.
 * All PartyMod interactions are done via reflection to avoid compile-time dependencies.
 */
public class PartyModIntegration {

    private static boolean initialized = false;
    private static boolean available = false;

    // Cached reflection objects for performance
    private static Object partyModInstance = null;
    private static Object partyManagerInstance = null;
    private static Method getPartyByPlayerMethod = null;
    private static Method getMemberCountMethod = null;
    private static Method isLeaderMethod = null;
    private static Method getIdMethod = null;
    private static Method getMemberUuidsMethod = null;
    private static Method getNameMethod = null;
    private static Method getMaxMembersMethod = null;

    /**
     * Check if PartyMod is available without loading PartyMod classes.
     * Uses reflection to check class presence.
     */
    public static boolean isPartyModAvailable() {
        try {
            Class.forName("com.gaukh.partymod.PartyMod");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Initialize PartyMod integration.
     * Should only be called if isPartyModAvailable() returns true.
     */
    public static void initialize(HC_DungeonCorePlugin plugin) {
        if (initialized) {
            return;
        }
        initialized = true;

        if (!isPartyModAvailable()) {
            plugin.getLogger().at(Level.INFO).log("PartyMod not found - party features disabled, solo play available");
            available = false;
            return;
        }

        try {
            // Get PartyMod instance via reflection
            Class<?> partyModClass = Class.forName("com.gaukh.partymod.PartyMod");
            Method getInstanceMethod = partyModClass.getMethod("getInstance");
            partyModInstance = getInstanceMethod.invoke(null);

            if (partyModInstance == null) {
                plugin.getLogger().at(Level.INFO).log("PartyMod instance not available - party features disabled");
                available = false;
                return;
            }

            // Get PartyManager
            Method getPartyManagerMethod = partyModClass.getMethod("getPartyManager");
            partyManagerInstance = getPartyManagerMethod.invoke(partyModInstance);

            if (partyManagerInstance == null) {
                plugin.getLogger().at(Level.INFO).log("PartyManager not available - party features disabled");
                available = false;
                return;
            }

            // Cache Party class methods
            Class<?> partyManagerClass = partyManagerInstance.getClass();
            getPartyByPlayerMethod = partyManagerClass.getMethod("getPartyByPlayer", UUID.class);

            Class<?> partyClass = Class.forName("com.gaukh.partymod.party.Party");
            getMemberCountMethod = partyClass.getMethod("getMemberCount");
            isLeaderMethod = partyClass.getMethod("isLeader", UUID.class);
            getIdMethod = partyClass.getMethod("getId");
            getMemberUuidsMethod = partyClass.getMethod("getMemberUuids");
            getNameMethod = partyClass.getMethod("getName");
            getMaxMembersMethod = partyClass.getMethod("getMaxMembers");

            available = true;
            plugin.getLogger().at(Level.INFO).log("PartyMod integration initialized successfully");

        } catch (Exception e) {
            plugin.getLogger().at(Level.WARNING).log("Failed to initialize PartyMod integration: " + e.getMessage());
            available = false;
        }
    }

    /**
     * Check if PartyMod integration is active.
     */
    public static boolean isAvailable() {
        return available;
    }

    /**
     * Get the party object for a player via reflection.
     */
    private static Object getParty(UUID playerUuid) {
        if (!available || partyManagerInstance == null || getPartyByPlayerMethod == null) {
            return null;
        }
        try {
            return getPartyByPlayerMethod.invoke(partyManagerInstance, playerUuid);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Check if a player is in a party with more than 1 member.
     */
    public static boolean isInParty(UUID playerUuid) {
        if (!available) {
            return false;
        }
        try {
            Object party = getParty(playerUuid);
            if (party == null) {
                return false;
            }
            int memberCount = (int) getMemberCountMethod.invoke(party);
            return memberCount > 1;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Check if the player is the party leader.
     */
    public static boolean isPartyLeader(UUID playerUuid) {
        if (!available) {
            return false;
        }
        try {
            Object party = getParty(playerUuid);
            if (party == null) {
                return false;
            }
            return (boolean) isLeaderMethod.invoke(party, playerUuid);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get the party ID for a player, or null if not in a party.
     */
    public static String getPartyId(UUID playerUuid) {
        if (!available) {
            return null;
        }
        try {
            Object party = getParty(playerUuid);
            if (party == null) {
                return null;
            }
            return (String) getIdMethod.invoke(party);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Get all member UUIDs for a player's party.
     */
    @SuppressWarnings("unchecked")
    public static Set<UUID> getPartyMemberUuids(UUID playerUuid) {
        if (!available) {
            return Collections.emptySet();
        }
        try {
            Object party = getParty(playerUuid);
            if (party == null) {
                return Collections.emptySet();
            }
            Set<UUID> members = (Set<UUID>) getMemberUuidsMethod.invoke(party);
            return members != null ? new HashSet<>(members) : Collections.emptySet();
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }

    /**
     * Get party member count.
     */
    public static int getPartyMemberCount(UUID playerUuid) {
        if (!available) {
            return 0;
        }
        try {
            Object party = getParty(playerUuid);
            if (party == null) {
                return 0;
            }
            return (int) getMemberCountMethod.invoke(party);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Get party name, or null if not in a party.
     */
    public static String getPartyName(UUID playerUuid) {
        if (!available) {
            return null;
        }
        try {
            Object party = getParty(playerUuid);
            if (party == null) {
                return null;
            }
            return (String) getNameMethod.invoke(party);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Get max party members.
     */
    public static int getMaxPartyMembers(UUID playerUuid) {
        if (!available) {
            return 0;
        }
        try {
            Object party = getParty(playerUuid);
            if (party == null) {
                return 0;
            }
            return (int) getMaxMembersMethod.invoke(party);
        } catch (Exception e) {
            return 0;
        }
    }
}
