# HC_DungeonCore

Party-based dungeon gameplay system with shared lives, enemy scaling, and entity density management. When a world is configured with the `DungeonParty` GameplayConfig, players who enter share a lives pool. Deaths deduct from the shared pool, and when all lives are exhausted the entire party wipes and is teleported out. Optionally scales dungeon enemy levels to the party's average level.

## Features

- Shared lives system: configurable lives per player, deducted on any party member's death
- Timed respawn with configurable delay before reviving at the dungeon entrance
- Item loss prevention option to protect inventory on death in dungeons
- NPC level scaling via HC_Leveling integration (scales to configured range or party average)
- Entity density management system to prevent NPC clustering crashes in doorways
- Configuration via Hytale's GameplayConfig JSON (`Plugin.DungeonParty`)
- PartyMod integration for multi-player party support (falls back to solo play)
- Automatic session cleanup on player disconnect and world removal
- Canonical return point tracking via InstanceEntityConfig for reliable exit teleportation

## Dependencies

- **EntityModule** (required) -- Hytale entity system
- **HC_Factions** (optional, compile-time) -- faction spawn teleportation
- **PartyMod** (optional) -- multi-player party support
- **HC_Leveling** (optional) -- enemy level scaling to party level

## Building

```bash
./gradlew build
```
