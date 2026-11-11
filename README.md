# Universal Weapons

**Universal Weapons** is a custom-weapons plugin for **Paper 1.21.11**. The first included weapon is **Corrupted Calamity**, a corrupted mace with movement, combat, and impact abilities.

## Compatibility

| Requirement | Version |
|---|---|
| Minecraft server | Paper 1.21.11 |
| Java | 21 |
| Build tool | Maven 3.9+ |

This project targets the Paper API. It is not intended for a vanilla, Bukkit-only, or Spigot server.

## Included weapon: Corrupted Calamity

Get the mace in-game with:

```
/weapon
```

The command requires the `universalweapons.weapon` permission, which is granted to operators by default.

### Abilities

| Ability | How to use | Current behavior |
|---|---|---|
| Corrupted Dash | Right-click while holding the mace | Launches you in the direction you're looking. 1.8-second cooldown. |
| Void Grapple | Sneak + right-click while looking at another player | Targets a player within 24 blocks, teleports you beside them, and deals an automatic 8 damage. 3.5-second cooldown. |
| Double Strike | Hit an entity with the mace | Every third qualifying mace hit triggers an additional 6 damage strike. |
| Ground Shatter | Fall at least 3 blocks and land while holding the mace | Cancels the fall damage and creates a purple/obsidian impact effect, damaging nearby living entities for 5 damage. |

Cooldowns and damage values are currently defined in the plugin source.

## Install the plugin

1. Download the latest `Universal-Weapons-1.0.0.jar` from the repository's **Actions** build artifact (or build it yourself using the steps below).
2. Stop your Paper 1.21.11 server.
3. Place the JAR in your server's `plugins` folder.
4. Start the server.
5. Run `/weapon` in-game as an operator or a player with the permission.

To remove the plugin, stop the server and delete its JAR from the `plugins` folder.

## Build from source

You need JDK 21 and Maven 3.9 or newer.

Clone the repository:

```bash
git clone https://github.com/ChargingOfficial/Universal-Weapons.git
cd Universal-Weapons
```

Build the plugin:

```bash
mvn clean package
```

The compiled plugin will be at:

```
target/Universal-Weapons-1.0.0.jar
```

The Paper API is a provided dependency, so it is not bundled into the plugin JAR. Your server must be running Paper 1.21.11.

## Resource-pack model

The item is tagged internally as Corrupted Calamity and currently sets custom model data to `11001`. A matching resource-pack model and texture are not included yet. Without that resource pack, the item will use the normal Minecraft mace appearance while retaining its abilities.

## Permissions

| Permission | Default | Purpose |
|---|---|---|
| `universalweapons.weapon` | Operators | Allows use of `/weapon` |

## Project structure

```
src/
  main/
    java/com/chargingofficial/universalweapons/
      UniversalWeapons.java
    resources/
      plugin.yml
pom.xml
README.md
```

## Notes

- The plugin identifies its custom mace using a persistent item-data tag, not its display name. Renaming an ordinary mace will not grant the abilities.
- The current implementation tracks the double-strike counter per player for the lifetime of that server session.
- Test the plugin on a backup or staging server before installing it on a live server.
