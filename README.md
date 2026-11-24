# Universal Weapons

Universal Weapons is a custom-weapon plugin for **Paper 1.21.11**. It adds Corrupted Calamity, four additional custom weapons, and the Eclipse Core trapping item.

## Requirements

| Requirement | Version |
|---|---|
| Minecraft server | Paper 1.21.11 |
| Java | 21 |
| Build tool | Maven 3.9+ |

This is a standard Bukkit/Paper plugin, not a client mod. It will not run on a vanilla server.

## Corrupted Calamity

Use `/weapon` to receive the mace. It requires the `universalweapons.weapon` permission, which is granted to operators by default. If your inventory is full, the item drops at your location. Use `/stuck` at any time to restore gravity and clear residual movement if an ability ever leaves you suspended.

### Calamity awakening ritual

Stand on a command block (regular, chain, or repeating) while holding a mace named **Calamity** in your main hand. The ritual channels for 30 seconds with expanded, widely spaced purple particle rings, larger spirals and energy columns, periodic lightning, and layered beacon, amethyst, Wither, and respawn-anchor sounds. Its completion features a multi-bolt visual-only lightning finale with thunder, dragon, Wither, and sonic-boom sounds. Stay on the command block and keep holding the mace until the ritual completes; moving away or switching items interrupts it. Completion transforms the mace into **Corrupted Calamity**.

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
| Void Grapple | Sneak + right-click while looking at another player | Shows one particle tether at activation, then pulls you toward the target with smooth horizontal steering. Steering stops when you reach or pass the target, without freezing your existing momentum. It does not perform an automatic mace hit. Uses a chain sound. 3.5-second cooldown. |
| Double Strike | Every third qualifying mace hit | Performs a separate 20-damage follow-up on the next server tick, with the target's hurt cooldown reset so a second Totem can pop after the first hit. The attacker is never frozen and gravity or movement is not modified. |
| Shield Shatter | Hit a player actively blocking with a shield | Instantly breaks their shield and triggers a Wind Burst launch with a gust effect. |


Damage values are Minecraft damage points (2 points equal one full heart before armor and other mitigation).

## Additional weapons and Eclipse Core

Use the command variants below as an operator (or with the weapon permission). Add `normal` after a weapon type to receive a plain renamed version with no special abilities. Tab-complete `/weapon` to see valid weapon names, then tab-complete `normal` where supported.

| Command | Custom item | Normal renamed version |
|---|---|---|
| `/weapon crossbow` | Infernal Requiem | `/weapon crossbow normal` gives Infernal |
| `/weapon spear` | Heaven's Judgment | `/weapon spear normal` gives Judgment |
| `/weapon trident` | Poseidon's Wrath | `/weapon trident normal` gives Storm |
| `/weapon axe` | Bloodreaver | `/weapon axe normal` gives Berserker |
| `/weapon slimeball` | Eclipse Core | — |

### Weapon awakening rituals

All rituals activate while standing on a regular, chain, or repeating command block and holding the matching renamed base item in the main hand. Each ritual takes 30 seconds. Rituals feature wider, more widely spaced rune circles, larger particle spirals and energy columns, flashes, and layered sound cues including beacon, amethyst chime, and respawn-anchor sounds. They trigger periodic visual-only lightning strikes during the channel and a multi-bolt lightning finale with electric sparks, thunder, and explosion sounds; lightning effects deal no damage. Custom weapon names use distinct colors: Infernal Requiem red, Heaven's Judgment aqua, Poseidon's Wrath blue, Bloodreaver dark red, and Eclipse Core black; Corrupted Calamity remains purple. Keep holding the item and remain on the same command block; moving away, switching items, dying, or disconnecting interrupts the ritual. Completion replaces the held item with its custom weapon.

| Hold this item | Ritual result |
|---|---|
| Mace named **Calamity** | Corrupted Calamity |
| Crossbow named **Infernal** | Infernal Requiem |
| Netherite Spear named **Judgment** | Heaven's Judgment |
| Trident named **Storm** | Poseidon's Wrath |
| Netherite Axe named **Berserker** | Bloodreaver |
| Slimeball named **Eclipse** | Eclipse Core |

Use `/weapon crossbow normal`, `/weapon spear normal`, `/weapon trident normal`, `/weapon axe normal`, or `/weapon slimeball normal` to receive the ritual starting item. The Calamity starting mace is a mace renamed **Calamity** in an anvil.

### Infernal Requiem
- Crossbow with Multishot V and Quick Charge X.
- The crossbow has no custom active ability. Arrows fired from it ignite automatically; Multishot V and Quick Charge X remain.

### Heaven's Judgment
- Uses the actual Minecraft spear item (not a trident) with a large attack-speed modifier and high-level combat enchantments. Uses the Netherite Spear material introduced in Minecraft 1.21.11.
- Every hit calls a lightning visual and deals 5 hearts of bonus damage.
- Enchanted with Lunge III. The spear retains vanilla Lunge behavior.
- While Heaven's Judgment is held in either hand, the wielder continuously receives Saturation.
- Sneak + right-click launches the wielder upward, then drives them into the ground. The impact damages and knocks back nearby entities; the wielder's fall damage is suppressed. Has a 10-second cooldown.

### Poseidon's Wrath
- Trident with Riptide V and the mace enchantments at their maximum levels (Density V, Breach IV, Wind Burst III).
- Melee hits and thrown-trident hits both trigger five rapid lightning effects, each dealing 3 damage points (1.5 hearts) as bonus damage.
- No custom right-click ability; the trident retains its normal vanilla Riptide behavior.

### Bloodreaver
- Netherite axe with high-level combat and utility enchantments.
- Every third hit grants Speed III and Strength III for 15 seconds.
- Hitting a player who is actively blocking destroys the shield.

### Eclipse Core
- Right-click while holding the slimeball to target the nearest other player within 24 blocks.
- A black particle dome encloses both players for 12 seconds. Movement and teleport attempts outside the dome are blocked while it is active; no world blocks are changed.

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

## Appearance

Corrupted Calamity uses the normal Minecraft mace appearance. No custom resource pack is required.

## Permission

| Permission | Default | Description |
|---|---|---|
| `universalweapons.weapon` | Operators | Allows use of `/weapon` and its item variants. |

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
- A player actively blocking with a shield loses that shield completely when hit by Corrupted Calamity. Because shield blocking can suppress the normal post-hit enchantment callback, the plugin schedules a Wind Burst-style launch after the damage event. Its upward force scales with the mace's Wind Burst enchantment level.
- Off-hand interaction events are ignored to prevent abilities firing twice.
- Ability damage is guarded so it does not increment the regular-hit counter.
- Double Strike does not alter gravity or velocity. It delivers its separate 20-damage follow-up on the next server tick and resets hurt immunity immediately before that hit.
- `/stuck` is available to all players and force-restores gravity and clears residual velocity.
- Grapple launches the player toward the targeted player and displays its particle tether only once when activated. It steers toward the target until the player reaches or passes them, then ends the steering task while preserving current momentum. It never attacks automatically; the player must perform any melee hit themselves.
- Test on a backup or staging server before deploying to a live server.
