package com.chargingofficial.universalweapons;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Paper 1.21.11 implementation of the Corrupted Calamity weapon.
 */
public final class UniversalWeapons extends JavaPlugin implements Listener, CommandExecutor {
    private static final long DASH_COOLDOWN_MS = 1_800L;
    private static final long GRAPPLE_COOLDOWN_MS = 3_500L;
    private static final double GRAPPLE_RANGE = 24.0;
    private static final double BONUS_HIT_DAMAGE = 20.0;

    private NamespacedKey weaponKey;
    private final Map<UUID, Integer> hitCounts = new HashMap<>();
    private final Map<UUID, Long> dashReadyAt = new HashMap<>();
    private final Map<UUID, Long> grappleReadyAt = new HashMap<>();
    private final Set<UUID> abilityDamageInProgress = new HashSet<>();
    private final Set<UUID> grapplingPlayers = new HashSet<>();
    private final Map<UUID, BukkitTask> ritualTasks = new HashMap<>();
    private final Map<UUID, Location> ritualBlocks = new HashMap<>();

    @Override
    public void onEnable() {
        weaponKey = new NamespacedKey(this, "corrupted_calamity");
        if (getCommand("weapon") == null || getCommand("stuck") == null) {
            getLogger().severe("Missing /weapon or /stuck declaration in plugin.yml. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getCommand("weapon").setExecutor(this);
        getCommand("stuck").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        // Detect players standing on command blocks while holding a mace named "Calamity".
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (isRitualReady(online) && !ritualTasks.containsKey(online.getUniqueId())) {
                    beginCalamityRitual(online);
                }
            }
        }, 1L, 5L);
        getLogger().info("Universal Weapons enabled for Paper 1.21.11.");
    }

    @Override
    public void onDisable() {
        hitCounts.clear();
        dashReadyAt.clear();
        grappleReadyAt.clear();
        abilityDamageInProgress.clear();
        grapplingPlayers.clear();
        for (BukkitTask task : ritualTasks.values()) task.cancel();
        ritualTasks.clear();
        ritualBlocks.clear();
    }

    private ItemStack createCalamity() {
        ItemStack item = new ItemStack(Material.MACE);
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName(ChatColor.DARK_PURPLE.toString() + ChatColor.BOLD + "Corrupted Calamity");
        meta.setLore(List.of(
                ChatColor.GRAY + "A relic consumed by the void.",
                ChatColor.LIGHT_PURPLE + "Right-click: " + ChatColor.WHITE + "Corrupted Dash",
                ChatColor.LIGHT_PURPLE + "Sneak + right-click: " + ChatColor.WHITE + "Void Grapple",
                ChatColor.LIGHT_PURPLE + "Shield Shatter: " + ChatColor.WHITE + "Breaks blocking shields with Wind Burst.",
                ChatColor.DARK_PURPLE + "Every third hit triggers an immediate second strike."
        ));
        meta.setCustomModelData(11001);
        // Use the vanilla Minecraft mace appearance; custom resource-pack model removed.
        meta.addEnchant(org.bukkit.enchantments.Enchantment.DENSITY, 5, true);
        meta.addEnchant(org.bukkit.enchantments.Enchantment.WIND_BURST, 2, true);
        meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 3, true);
        meta.addEnchant(org.bukkit.enchantments.Enchantment.MENDING, 1, true);
        meta.addEnchant(org.bukkit.enchantments.Enchantment.FIRE_ASPECT, 2, true);
        meta.getPersistentDataContainer().set(weaponKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private Location getRitualBlock(Player player) {
        Location feet = player.getLocation();
        Block beneath = feet.getBlock().getRelative(BlockFace.DOWN);
        Material type = beneath.getType();
        if (type != Material.COMMAND_BLOCK
                && type != Material.CHAIN_COMMAND_BLOCK
                && type != Material.REPEATING_COMMAND_BLOCK) {
            return null;
        }
        return beneath.getLocation();
    }

    private boolean isRitualReady(Player player) {
        if (!player.isOnline() || !player.isValid() || player.isDead()) return false;
        if (getRitualBlock(player) == null) return false;

        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() != Material.MACE || !held.hasItemMeta()) return false;
        String name = held.getItemMeta().getDisplayName();
        return name != null && "Calamity".equalsIgnoreCase(ChatColor.stripColor(name).trim());
    }

    private void beginCalamityRitual(Player player) {
        UUID id = player.getUniqueId();
        Location ritualBlock = getRitualBlock(player);
        if (ritualBlock == null) return;

        ritualBlocks.put(id, ritualBlock.clone());
        player.sendMessage(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD
                + "The command block begins to resonate...");
        player.sendActionBar(ChatColor.LIGHT_PURPLE + "The Calamity ritual has begun. Stay still.");

        World world = player.getWorld();
        final int[] tick = {0};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, scheduled -> {
            Location currentBlock = getRitualBlock(player);
            Location startingBlock = ritualBlocks.get(id);
            boolean stillAtRitual = currentBlock != null && startingBlock != null
                    && currentBlock.getWorld().equals(startingBlock.getWorld())
                    && currentBlock.getBlockX() == startingBlock.getBlockX()
                    && currentBlock.getBlockY() == startingBlock.getBlockY()
                    && currentBlock.getBlockZ() == startingBlock.getBlockZ();

            if (!isRitualReady(player) || !stillAtRitual || !player.getWorld().equals(world)) {
                scheduled.cancel();
                ritualTasks.remove(id);
                ritualBlocks.remove(id);
                if (player.isOnline()) {
                    player.sendActionBar(ChatColor.RED + "Ritual interrupted.");
                    player.getWorld().playSound(player.getLocation(),
                            Sound.BLOCK_BEACON_DEACTIVATE, 0.7f, 0.7f);
                }
                return;
            }

            int age = tick[0]++;
            Location at = player.getLocation();
            double radius = 1.35;
            double angle = age * 0.22;
            for (int i = 0; i < 8; i++) {
                double a = angle + (Math.PI * 2.0 * i / 8.0);
                Location point = at.clone().add(Math.cos(a) * radius,
                        0.12 + (i % 2) * 0.08, Math.sin(a) * radius);
                world.spawnParticle(Particle.PORTAL, point, 3, 0.06, 0.08, 0.06, 0.01);
                world.spawnParticle(Particle.WITCH, point, 1, 0.02, 0.02, 0.02, 0.0);
            }
            world.spawnParticle(Particle.DRAGON_BREATH, at.clone().add(0, 0.7, 0),
                    5, 0.35, 0.35, 0.35, 0.015);

            if (age % 10 == 0) {
                world.playSound(at, Sound.BLOCK_BEACON_AMBIENT, 0.7f, 0.55f + age / 120.0f);
                player.sendActionBar(ChatColor.LIGHT_PURPLE + "Corruption gathering "
                        + ChatColor.DARK_PURPLE + "[" + Math.min(100, (age * 100) / 60) + "%]");
            }

            if (age >= 60) {
                scheduled.cancel();
                ritualTasks.remove(id);
                ritualBlocks.remove(id);

                // Re-check all requirements immediately before replacing the held item.
                if (!isRitualReady(player)) {
                    player.sendActionBar(ChatColor.RED + "Ritual interrupted.");
                    return;
                }

                player.getInventory().setItemInMainHand(createCalamity());
                Location burst = player.getLocation().add(0, 1, 0);
                world.spawnParticle(Particle.EXPLOSION, burst, 1);
                world.spawnParticle(Particle.DRAGON_BREATH, burst, 80, 0.8, 0.9, 0.8, 0.04);
                world.spawnParticle(Particle.PORTAL, burst, 140, 0.9, 0.8, 0.9, 0.18);
                world.spawnParticle(Particle.END_ROD, burst, 45, 0.55, 0.8, 0.55, 0.08);
                world.playSound(burst, Sound.BLOCK_BEACON_POWER_SELECT, 1.2f, 0.55f);
                world.playSound(burst, Sound.ENTITY_WARDEN_SONIC_BOOM, 0.8f, 1.25f);
                player.sendTitle(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "CALAMITY AWAKENED",
                        ChatColor.LIGHT_PURPLE + "The Corrupted Calamity is yours",
                        10, 55, 20);
            }
        }, 1L, 1L);
        ritualTasks.put(id, task);
    }

    private boolean isCalamity(ItemStack item) {
        if (item == null || item.getType() != Material.MACE || !item.hasItemMeta()) {
            return false;
        }
        Byte marker = item.getItemMeta().getPersistentDataContainer()
                .get(weaponKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        if (command.getName().equalsIgnoreCase("stuck")) {
            player.setGravity(true);
            player.setFallDistance(0);
            player.setVelocity(new Vector(0, 0, 0));
            player.sendMessage(ChatColor.GREEN + "Your movement has been reset. Gravity restored.");
            return true;
        }

        ItemStack weapon = createCalamity();
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(weapon);
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
        player.sendMessage(ChatColor.LIGHT_PURPLE + "You received the Corrupted Calamity.");
        return true;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !isCalamity(event.getItem())) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();
        if (player.isSneaking()) {
            grapple(player);
        } else {
            dash(player);
        }
    }

    private void dash(Player player) {
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        if (dashReadyAt.getOrDefault(id, 0L) > now) {
            player.sendActionBar(ChatColor.DARK_PURPLE + "Corrupted Dash is recharging.");
            return;
        }

        dashReadyAt.put(id, now + DASH_COOLDOWN_MS);
        Vector direction = player.getLocation().getDirection().normalize();
        Vector velocity = direction.clone().multiply(1.75);
        velocity.setY(Math.max(0.30, velocity.getY()));
        player.setVelocity(velocity);

        World world = player.getWorld();
        Location origin = player.getLocation().add(0, 1, 0);
        world.playSound(origin, Sound.ENTITY_ENDERMAN_TELEPORT, 0.9f, 0.6f);
        world.spawnParticle(Particle.DRAGON_BREATH, origin, 30, 0.35, 0.3, 0.35, 0.04);

        // A layered purple particle ribbon follows the player's real movement.
        final int[] ticks = {0};
        Bukkit.getScheduler().runTaskTimer(this, task -> {
            if (!player.isOnline() || !player.isValid() || !player.getWorld().equals(world)
                    || ticks[0]++ >= 10) {
                task.cancel();
                return;
            }

            Vector motion = player.getVelocity().clone();
            Vector backwards = motion.lengthSquared() > 0.01
                    ? motion.normalize().multiply(-0.75)
                    : direction.clone().multiply(-0.75);
            Location trail = player.getLocation().add(0, 0.85, 0);
            Location point = trail.add(backwards);

            world.spawnParticle(Particle.PORTAL, point, 20, 0.24, 0.30, 0.24, 0.10);
            world.spawnParticle(Particle.DRAGON_BREATH, point, 12, 0.20, 0.22, 0.20, 0.025);
            world.spawnParticle(Particle.END_ROD, point, 6, 0.14, 0.18, 0.14, 0.015);
            world.spawnParticle(Particle.CRIT, trail, 4, 0.18, 0.20, 0.18, 0.08);
        }, 0L, 1L);
    }

    private void grapple(Player player) {
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        if (grappleReadyAt.getOrDefault(id, 0L) > now) {
            player.sendActionBar(ChatColor.DARK_PURPLE + "Void Grapple is recharging.");
            return;
        }
        if (grapplingPlayers.contains(id)) {
            player.sendActionBar(ChatColor.LIGHT_PURPLE + "Void Grapple is already pulling you in.");
            return;
        }

        Location eye = player.getEyeLocation();
        RayTraceResult trace = player.getWorld().rayTraceEntities(
                eye, eye.getDirection(), GRAPPLE_RANGE, 0.8,
                entity -> entity instanceof Player && entity != player
        );
        if (trace == null || !(trace.getHitEntity() instanceof Player target)) {
            player.sendMessage(ChatColor.RED + "Aim at an enemy player within 24 blocks to grapple.");
            return;
        }
        if (!player.getWorld().equals(target.getWorld())) return;

        grappleReadyAt.put(id, now + GRAPPLE_COOLDOWN_MS);
        grapplingPlayers.add(id);
        World world = player.getWorld();
        world.playSound(player.getLocation(), Sound.BLOCK_CHAIN_PLACE, 1.0f, 0.8f);
        player.sendActionBar(ChatColor.LIGHT_PURPLE + "VOID GRAPPLE!");

        // Show the tether once at activation. It is intentionally not redrawn while
        // either player moves, preventing a long trail of duplicated particle lines.
        drawParticleChain(player.getEyeLocation(), target.getEyeLocation());

        Vector initial = target.getLocation().toVector().subtract(player.getLocation().toVector());
        initial.setY(0);
        if (initial.lengthSquared() > 0.001) initial.normalize().multiply(0.68);
        player.setVelocity(new Vector(initial.getX(), 0.52, initial.getZ()));
        player.setFallDistance(0);

        final int[] elapsed = {0};
        final double[] previousDistance = {
                player.getLocation().distance(target.getLocation())
        };
        Bukkit.getScheduler().runTaskTimer(this, task -> {
            if (!player.isOnline() || !player.isValid()
                    || !target.isValid() || target.isDead() || !target.isOnline()
                    || !player.getWorld().equals(target.getWorld()) || elapsed[0]++ >= 60) {
                grapplingPlayers.remove(id);
                task.cancel();
                return;
            }

            double distance = player.getLocation().distance(target.getLocation());

            // Once the player reaches the target's position and begins moving past it,
            // stop steering immediately. Keep the player's current velocity so the
            // grapple ends smoothly instead of abruptly freezing them in midair.
            if (distance <= 1.8 || (elapsed[0] > 2 && distance > previousDistance[0] + 0.12)) {
                grapplingPlayers.remove(id);
                task.cancel();
                return;
            }
            previousDistance[0] = distance;

            Vector offset = target.getLocation().toVector().subtract(player.getLocation().toVector());
            Vector current = player.getVelocity();
            Vector horizontal = new Vector(offset.getX(), 0, offset.getZ());
            double horizontalDistance = horizontal.length();

            if (horizontalDistance > 0.12) {
                horizontal.normalize();
                double desiredSpeed = horizontalDistance > 8.0 ? 0.78
                        : horizontalDistance > 4.0 ? 0.58 : 0.32;
                Vector desired = horizontal.multiply(desiredSpeed);
                Vector blended = new Vector(
                        current.getX() * 0.18 + desired.getX() * 0.82,
                        current.getY(),
                        current.getZ() * 0.18 + desired.getZ() * 0.82
                );
                double maxSpeed = horizontalDistance > 4.0 ? 0.82 : 0.42;
                double speed = Math.sqrt(blended.getX() * blended.getX()
                        + blended.getZ() * blended.getZ());
                if (speed > maxSpeed) {
                    blended.setX(blended.getX() / speed * maxSpeed);
                    blended.setZ(blended.getZ() / speed * maxSpeed);
                }
                player.setVelocity(blended);
            } else {
                player.setVelocity(new Vector(
                        current.getX() * 0.92, current.getY(), current.getZ() * 0.92
                ));
            }
        }, 1L, 1L);
    }

    private void drawParticleChain(Location from, Location to) {
        if (from.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return;
        }
        Vector line = to.toVector().subtract(from.toVector());
        double length = line.length();
        int points = Math.max(2, (int) (length / 0.3));
        Vector step = line.multiply(1.0 / points);
        Location point = from.clone();
        for (int i = 0; i <= points; i++) {
            from.getWorld().spawnParticle(Particle.END_ROD, point, 1, 0, 0, 0, 0);
            if (i % 2 == 0) {
                from.getWorld().spawnParticle(Particle.PORTAL, point, 2, 0.035, 0.035, 0.035, 0.01);
            }
            point.add(step);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMaceHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)
                || !(event.getEntity() instanceof LivingEntity victim)
                || !isCalamity(player.getInventory().getItemInMainHand())
                || abilityDamageInProgress.contains(player.getUniqueId())) {
            return;
        }

        // A Corrupted Calamity hit shatters a shield that is actively blocking.
        // Remove the shield itself rather than applying a temporary disable cooldown.
        // Un-cancel this shield-blocked damage so the mace impact still lands and
        // vanilla mace smash/Wind Burst processing can continue normally.
        if (victim instanceof Player target && target.isBlocking()) {
            EquipmentSlot activeHand = target.getActiveItemHand();
            ItemStack activeItem = activeHand == EquipmentSlot.HAND
                    ? target.getInventory().getItemInMainHand()
                    : target.getInventory().getItemInOffHand();
            if (activeItem != null && activeItem.getType() == Material.SHIELD) {
                if (activeHand == EquipmentSlot.HAND) {
                    target.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
                } else {
                    target.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
                }
                target.clearActiveItem();
                target.broadcastSlotBreak(activeHand);
                event.setCancelled(false);
                // Shield blocking can suppress the vanilla mace post-hit enchantment
                // callback even after the shield is removed. Reproduce Wind Burst here
                // so a shield-shattering mace hit still launches its wielder.
                int windBurstLevel = player.getInventory().getItemInMainHand()
                        .getEnchantmentLevel(org.bukkit.enchantments.Enchantment.WIND_BURST);
                triggerShieldBreakWindBurst(player, windBurstLevel);
                target.getWorld().playSound(target.getLocation(), Sound.ITEM_SHIELD_BREAK, 1.0f, 0.85f);
                target.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0, 1, 0),
                        18, 0.35, 0.45, 0.35, 0.12);
                target.sendActionBar(ChatColor.RED + "Your shield was shattered!");
            }
        }

        if (event.isCancelled()) {
            return;
        }

        int hits = hitCounts.merge(player.getUniqueId(), 1, Integer::sum);
        if (hits % 3 != 0) {
            return;
        }

        player.sendActionBar(ChatColor.LIGHT_PURPLE + "CALAMITY: DOUBLE STRIKE!");

        // The original hit resolves first; follow up on the very next server tick.
        // Reset hurt immunity immediately before the second genuine damage event so
        // a second Totem can activate instead of the hit being swallowed by immunity.
        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline() || !player.isValid() || !victim.isValid() || victim.isDead()
                    || !player.getWorld().equals(victim.getWorld())) {
                return;
            }

            player.swingMainHand();
            victim.setNoDamageTicks(0);
            damageFromAbility(player, victim, BONUS_HIT_DAMAGE);

            Location hitLocation = victim.getLocation().add(0, 1, 0);
            World world = victim.getWorld();
            world.spawnParticle(Particle.SWEEP_ATTACK, hitLocation, 1);
            world.spawnParticle(Particle.DRAGON_BREATH, hitLocation,
                    36, 0.35, 0.45, 0.35, 0.035);
            world.spawnParticle(Particle.CRIT, hitLocation, 24, 0.3, 0.4, 0.3, 0.14);
            world.playSound(hitLocation, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.55f);
        });
    }

    private void triggerShieldBreakWindBurst(Player player, int level) {
        if (level <= 0) {
            return;
        }

        // Run after the incoming damage event has completed, preserving the mace hit.
        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline() || !player.isValid() || player.isDead()) {
                return;
            }

            // Scale the upward launch with the actual Wind Burst enchantment level.
            Vector velocity = player.getVelocity();
            double launch = 0.90 + (0.18 * (level - 1));
            velocity.setY(Math.max(velocity.getY(), launch));
            player.setVelocity(velocity);
            player.setFallDistance(0);

            Location burst = player.getLocation().add(0, 0.8, 0);
            World world = player.getWorld();
            world.playSound(burst, Sound.ITEM_MACE_SMASH_GROUND, 0.9f, 1.25f);
            world.spawnParticle(Particle.GUST, burst, 18, 0.35, 0.25, 0.35, 0.12);
            world.spawnParticle(Particle.CLOUD, burst, 14, 0.3, 0.2, 0.3, 0.08);
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        grapplingPlayers.remove(event.getPlayer().getUniqueId());
        UUID playerId = event.getPlayer().getUniqueId();
        BukkitTask ritual = ritualTasks.remove(playerId);
        if (ritual != null) ritual.cancel();
        ritualBlocks.remove(playerId);
    }

    private void damageFromAbility(Player source, LivingEntity target, double damage) {
        UUID id = source.getUniqueId();
        abilityDamageInProgress.add(id);
        try {
            target.damage(damage, source);
        } finally {
            abilityDamageInProgress.remove(id);
        }
    }
}
