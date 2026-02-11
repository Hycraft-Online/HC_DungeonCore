package com.hcdungeonparty.models;

/**
 * State of an individual party member within a dungeon session.
 */
public enum MemberState {
    /** Active and playing in the dungeon */
    ALIVE,

    /** Died, waiting to respawn (still has party lives remaining) */
    RESPAWNING,

    /** Out of lives, can no longer respawn */
    ELIMINATED,

    /** Left the game / disconnected */
    DISCONNECTED
}
