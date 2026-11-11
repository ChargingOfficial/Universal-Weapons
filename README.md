# Universal Weapons

Universal Weapons is a custom-weapon plugin for **Paper 1.21.11**. It adds **Corrupted Calamity**, a custom mace with movement, combat, and impact abilities.

## Requirements

| Requirement | Version |
|---|---|
| Minecraft server | Paper 1.21.11 |
| Java | 21 |
| Build tool | Maven 3.9+ |

This is a standard Bukkit/Paper plugin, not a client mod. It will not run on a vanilla server.

## Corrupted Calamity

Use `/weapon` to receive the mace. It requires the `universalweapons.weapon` permission, which is granted to operators by default. If your inventory is full, the item drops at your location.

### Built-in enchantments

- Density V
- Wind Burst II
- Unbreaking III
- Mending I
- Fire Aspect II

### Abilities

| Ability | Input | Effect |
|---|---|---|
| Corrupted Dash | Right-click while holding the mace | Launches you in the direction you face with a purple particle wake. 1.8-second cooldown. |
| Void Grapple | Sneak + right-click while looking at another player | Fires a visible particle chain and pulls you toward the player. Deals 8 damage. 3.5-second cooldown. |
| Double Strike | Every third qualifying mace hit | Performs one distinct follow-up swing and deals an additional 6 damage 2 ticks (0.1 seconds) after the first hit. |
| Ground Shatter | Land after falling at least 3 blocks while holding the mace | Cancels fall damage, creates corrupted impact particles, and damages nearby living entities for 5 damage. |

Damage values are Minecraft damage points (2 points equal one full heart before armor and other mitigation).

## Install

1. Download the latest successful build artifact from the repository's **Actions** tab.
2. Stop your Paper 1.21.11 server.
3. Put the plugin JAR in the server's `plugins` folder.
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

## GitHub Actions

Pushes to `main`, pull requests targeting `main`, and manual workflow runs build the project with Java 21 and Maven. A successful run uploads the plugin JAR as a workflow artifact.

## Resource pack

The item sets custom model data `11001`. A matching resource-pack model and texture are not included yet, so without a compatible resource pack it uses the normal Minecraft mace appearance. Its abilities, enchantments, and persistent item tag still work.

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

- The weapon is identified by a persistent data tag, not its display name.
- Off-hand interaction events are ignored to prevent abilities firing twice.
- Ability damage is guarded so it does not increment the regular-hit counter.
- Double Strike is scheduled as a separate hit two server ticks after the triggering hit.
- Grapple uses velocity to pull the player rather than teleporting them.
- Ground Shatter uses particles and sound only; it does not break or modify world blocks.
- Test on a backup or staging server before deploying to a live server.
