# Universal Weapons

A Paper plugin for Minecraft 1.21.11. The first weapon is **Corrupted Calamity**, a custom mace with dash, grapple, double-strike, and ground-slam abilities.

## Requirements
- Minecraft server software compatible with Paper 1.21.11
- Java 21
- Maven 3.9+

## Build
Run:

```bash
mvn clean package
```

The plugin JAR will be created at `target/Universal-Weapons-1.0.0.jar`.

## Commands
- `/weapon` — gives the Corrupted Calamity mace (operator permission by default).

## Abilities
- Right-click while holding the mace: forward dash with portal/dragon-breath particles (1.8 second cooldown).
- Sneak + right-click while aiming at another player within 24 blocks: instantly pull yourself to them and perform an attack (3.5 second cooldown).
- Every third successful mace hit triggers a bonus strike.
- Landing after a fall of at least 3 blocks while holding the mace cancels fall damage and triggers an area ground slam with crack particles, sound, and nearby damage.

## Resource-pack texture
The item uses custom model data value `11001`. Add the Corrupted Calamity model/texture to your resource pack and map that model data value to it.
