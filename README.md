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

Use `/weapon` to receive the mace. It requires the `universalweapons.weapon` permission, which is granted to operators by default. If your inventory is full, the item drops at your location. Use `/stuck` at any time to restore gravity and clear residual movement if an ability ever leaves you suspended.

### Built-in enchantments

- Density V
- Wind Burst II
- Unbreaking III
- Mending I
- Fire Aspect II

### Abilities

| Ability | Input | Effect |
|---|---|---|
| Corrupted Dash | Right-click while holding the mace | Launches you in the direction you face with a dense purple particle ribbon that follows your movement. 1.8-second cooldown. |
| Void Grapple | Sneak + right-click while looking at another player | Launches you rapidly toward the target in a falling attack arc, then uses Paper's actual melee attack method when you are close and descending. This allows the equipped mace's smash and Wind Burst mechanics to run normally when the fall conditions are met. 3.5-second cooldown. |
| Double Strike | Every third qualifying mace hit | Performs a separate 20-damage follow-up on the next server tick, with the target's hurt cooldown reset so a second Totem can pop after the first hit. The attacker is never frozen and gravity or movement is not modified. |
| Kill Strike | Hit a player carrying exactly one Totem of Undying | Instantly executes the target, carves a protected-event-aware 7x7 crater without block drops or explosions, and damages nearby living entities while excluding the weapon holder. The victim's normal death drops remain intact. |

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
- Double Strike does not alter gravity or velocity. It delivers its separate 20-damage follow-up on the next server tick and resets hurt immunity immediately before that hit.
- `/stuck` is available to all players and force-restores gravity and clears residual velocity.
- Grapple launches the player rapidly toward the target with a live particle tether and a falling attack arc. When close and descending, it calls Paper's actual melee attack method so the mace smash and Wind Burst enchantment can activate under normal fall conditions.
- Kill Strike triggers when a direct Corrupted Calamity hit lands on a player carrying exactly one Totem of Undying. It bypasses that final Totem and kills the target, carves a 7x7 crater without spawning block drops, and damages nearby living entities except the weapon holder. Block-break events are fired so protection plugins can cancel individual terrain changes; tile entities and bedrock-like blocks are excluded.
- Test on a backup or staging server before deploying to a live server.
