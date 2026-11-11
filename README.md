# Universal Weapons

Universal Weapons is a custom-weapon plugin for **Paper 1.21.11**. It currently adds **Corrupted Calamity**, a custom mace with movement, combat, and impact abilities.

## Requirements

| Requirement | Version |
|---|---|
| Minecraft server | Paper 1.21.11 |
| Java | 21 |
| Build tool | Maven 3.9+ |

This is a standard Bukkit/Paper plugin. It is not a mod and does not run on a vanilla server.

## Corrupted Calamity

Give yourself the weapon in-game:

```
/weapon
```

The command requires `universalweapons.weapon`, granted to operators by default. If the inventory is full, the item drops safely at the player's location.

### Abilities

| Ability | Input | Effect |
|---|---|---|
| Corrupted Dash | Right-click while holding the mace | Launches you in the direction you face. 1.8-second cooldown. |
| Void Grapple | Sneak + right-click while looking at another player | Pulls you beside the player within 24 blocks and deals 8 damage. 3.5-second cooldown. |
| Double Strike | Hit an entity with the mace | Every third qualifying hit triggers an additional 6 damage strike. |
| Ground Shatter | Land after falling at least 3 blocks while holding the mace | Cancels fall damage, creates corrupted impact particles and damages nearby living entities for 5 damage. |

Damage values are Minecraft damage points (2 points equal one full heart before armor and other mitigation).

## Install

1. Download the latest `Universal-Weapons-1.0.0` artifact from the repository's **Actions** tab.
2. Stop your Paper 1.21.11 server.
3. Put the downloaded JAR in the server's `plugins` folder.
4. Start the server and check the console for the plugin enable message.
5. Run `/weapon` as an operator or a player with the permission.

## Build locally

Install JDK 21 and Maven 3.9 or newer, then run:

```bash
git clone https://github.com/ChargingOfficial/Universal-Weapons.git
cd Universal-Weapons
mvn --batch-mode clean package
```

The output JAR is:

```
target/Universal-Weapons-1.0.0.jar
```

The Paper API is a provided dependency and is not bundled into the plugin JAR.

## GitHub Actions build

Every push to `main`, pull request targeting `main`, or manual workflow run builds the project with Java 21 and Maven. A successful run uploads the plugin JAR as an artifact named `Universal-Weapons-1.0.0`.

## Resource pack

The item currently sets custom model data `11001`. A matching resource-pack model and texture are not included yet, so without a compatible resource pack it uses the normal Minecraft mace appearance. Its abilities and persistent item tag still work.

## Permission

| Permission | Default | Description |
|---|---|---|
| `universalweapons.weapon` | Operators | Allows use of `/weapon`. |

## Project layout

```
.github/workflows/main.yml
src/main/java/com/chargingofficial/universalweapons/UniversalWeapons.java
src/main/resources/plugin.yml
pom.xml
README.md
```

## Implementation notes

- The weapon is identified by a persistent data tag, not its display name. Renaming a normal mace does not grant the abilities.
- Off-hand interaction events are ignored to prevent abilities firing twice.
- Ability damage is guarded so it does not increment the regular-hit counter.
- Ground Shatter uses particles and sound only; it does not change or break actual world blocks.
- Test on a backup or staging server before deploying to a live server.
