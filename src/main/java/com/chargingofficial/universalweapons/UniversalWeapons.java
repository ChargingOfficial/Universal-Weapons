package com.chargingofficial.universalweapons;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
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
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
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
        getLogger().info("Universal Weapons enabled for Paper 1.21.11.");
    }

    @Override
    public void onDisable() {
        hitCounts.clear();
        dashReadyAt.clear();
        grappleReadyAt.clear();
        abilityDamageInProgress.clear();
        grapplingPlayers.clear();
    }

    private ItemStack createCalamity() {
        ItemStack item = new ItemStack(Material.MACE);
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName(ChatColor.DARK_PURPLE.toString() + ChatColor.BOLD + "Corrupted Calamity");
        meta.setLore(List.of(
                ChatColor.GRAY + "A relic consumed by the void.",
                ChatColor.LIGHT_PURPLE + "Right-click: " + ChatColor.WHITE + "Corrupted Dash",
                ChatColor.LIGHT_PURPLE + "Sneak + right-click: " + ChatColor.WHITE + "Void Grapple",
                ChatColor.DARK_PURPLE + "Every third hit triggers an immediate second strike.",
                ChatColor.LIGHT_PURPLE + "Kill Strike: " + ChatColor.WHITE + "Executes targets with exactly one Totem."
        ));
        meta.setCustomModelData(11001);
        meta.addEnchant(org.bukkit.enchantments.Enchantment.DENSITY, 5, true);
        meta.addEnchant(org.bukkit.enchantments.Enchantment.WIND_BURST, 2, true);
        meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 3, true);
        meta.addEnchant(org.bukkit.enchantments.Enchantment.MENDING, 1, true);
        meta.addEnchant(org.bukkit.enchantments.Enchantment.FIRE_ASPECT, 2, true);
        meta.getPersistentDataContainer().set(weaponKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
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
        world.playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_THROW, 1.0f, 0.7f);
        player.sendActionBar(ChatColor.LIGHT_PURPLE + "VOID GRAPPLE!");

        // A controlled launch starts the approach, while per-tick steering caps speed
        // and brakes before melee range. This avoids the previous 2.65-block/tick overshoot.
        Vector initial = target.getLocation().toVector().subtract(player.getLocation().toVector());
        initial.setY(0);
        if (initial.lengthSquared() > 0.001) initial.normalize().multiply(0.72);
        player.setVelocity(new Vector(initial.getX(), 0.62, initial.getZ()));
        player.setFallDistance(0);

        final int[] elapsed = {0};
        final boolean[] attacked = {false};
        Bukkit.getScheduler().runTaskTimer(this, task -> {
            if (attacked[0] || !player.isOnline() || !player.isValid()
                    || !target.isValid() || target.isDead() || !target.isOnline()
                    || !player.getWorld().equals(target.getWorld()) || elapsed[0]++ >= 60) {
                grapplingPlayers.remove(id);
                task.cancel();
                return;
            }

            drawParticleChain(player.getEyeLocation(), target.getEyeLocation());
            world.spawnParticle(Particle.PORTAL, player.getLocation().add(0, 0.8, 0),
                    7, 0.22, 0.3, 0.22, 0.04);

            Vector offset = target.getLocation().toVector().subtract(player.getLocation().toVector());
            double distance = offset.length();
            Vector current = player.getVelocity();

            // Steer horizontally toward the target, with a lower speed cap as the
            // player enters attack range. Preserve vertical velocity/fall distance so
            // the mace's smash calculation is not interrupted.
            Vector horizontal = new Vector(offset.getX(), 0, offset.getZ());
            double horizontalDistance = horizontal.length();
            if (horizontalDistance > 0.12) {
                horizontal.normalize();
                double desiredSpeed = horizontalDistance > 8.0 ? 0.78
                        : horizontalDistance > 4.0 ? 0.58
                        : horizontalDistance > 2.8 ? 0.34 : 0.0;
                Vector desired = horizontal.multiply(desiredSpeed);
                Vector blended = new Vector(
                        current.getX() * 0.30 + desired.getX() * 0.70,
                        current.getY(),
                        current.getZ() * 0.30 + desired.getZ() * 0.70
                );
                double maxSpeed = horizontalDistance > 4.0 ? 0.82 : 0.48;
                double speed = Math.sqrt(blended.getX() * blended.getX() + blended.getZ() * blended.getZ());
                if (speed > maxSpeed) {
                    blended.setX(blended.getX() / speed * maxSpeed);
                    blended.setZ(blended.getZ() / speed * maxSpeed);
                }
                player.setVelocity(blended);
            } else {
                player.setVelocity(new Vector(0, current.getY(), 0));
            }

            boolean smashReady = player.getVelocity().getY() < -0.12
                    && player.getFallDistance() >= 1.5f;
            // Actual melee reach is close to 3 blocks. Attack only inside 2.7 blocks
            // so Player.attack() cannot whiff from the old 3.8-5 block trigger.
            if (distance <= 2.7 && smashReady) {
                attacked[0] = true;
                grapplingPlayers.remove(id);
                task.cancel();
                player.swingMainHand();
                player.attack(target);
                Location impact = target.getLocation().add(0, 1, 0);
                world.spawnParticle(Particle.CRIT, impact, 28, 0.35, 0.45, 0.35, 0.12);
                world.spawnParticle(Particle.DRAGON_BREATH, impact, 22, 0.3, 0.4, 0.3, 0.025);
                world.playSound(impact, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.6f);
            } else if (distance <= 2.7 && elapsed[0] >= 45) {
                // In low ceilings, do not keep the player circling indefinitely.
                // The close-range normal attack still works, though it may not be a smash.
                attacked[0] = true;
                grapplingPlayers.remove(id);
                task.cancel();
                player.swingMainHand();
                player.attack(target);
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

    @EventHandler
    public void onMaceHit(EntityDamageByEntityEvent event) {
        if (event.isCancelled()
                || !(event.getDamager() instanceof Player player)
                || !(event.getEntity() instanceof LivingEntity victim)
                || !isCalamity(player.getInventory().getItemInMainHand())
                || abilityDamageInProgress.contains(player.getUniqueId())) {
            return;
        }

        // Kill Strike replaces the old Ground Shatter. Exactly one Totem in the
        // victim's inventory/hands is not enough to survive this execution.
        if (victim instanceof Player target && countTotems(target) == 1) {
            event.setCancelled(true);
            executeKillStrike(player, target);
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

    private int countTotems(Player player) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == Material.TOTEM_OF_UNDYING) count += item.getAmount();
        }
        for (ItemStack item : player.getInventory().getArmorContents()) {
            if (item != null && item.getType() == Material.TOTEM_OF_UNDYING) count += item.getAmount();
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand.getType() == Material.TOTEM_OF_UNDYING) count += offhand.getAmount();
        ItemStack cursor = player.getItemOnCursor();
        if (cursor != null && cursor.getType() == Material.TOTEM_OF_UNDYING) count += cursor.getAmount();
        return count;
    }

    private void executeKillStrike(Player holder, Player victim) {
        Location deathLocation = victim.getLocation().clone();
        World world = deathLocation.getWorld();

        world.playSound(deathLocation, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 0.65f);
        world.playSound(deathLocation, Sound.ENTITY_WITHER_DEATH, 0.7f, 1.35f);
        world.spawnParticle(Particle.EXPLOSION, deathLocation.clone().add(0, 1, 0), 2);
        world.spawnParticle(Particle.BLOCK, deathLocation, 110, 1.8, 0.5, 1.8, 0.2,
                Material.CRYING_OBSIDIAN.createBlockData());
        world.spawnParticle(Particle.DRAGON_BREATH, deathLocation, 85, 1.5, 0.6, 1.5, 0.045);
        world.spawnParticle(Particle.PORTAL, deathLocation, 100, 1.8, 0.8, 1.8, 0.25);

        carveKillStrikeCrater(holder, deathLocation);

        // Direct lethal health assignment bypasses the victim's final Totem activation.
        victim.setHealth(0.0);

        // Shockwave damages nearby entities/players, but never the mace holder.
        for (Entity entity : world.getNearbyEntities(deathLocation, 6.0, 4.0, 6.0)) {
            if (!(entity instanceof LivingEntity nearby) || nearby == holder || nearby == victim
                    || nearby.isDead()) {
                continue;
            }
            abilityDamageInProgress.add(holder.getUniqueId());
            try {
                nearby.damage(8.0, holder);
            } finally {
                abilityDamageInProgress.remove(holder.getUniqueId());
            }
        }

        holder.sendActionBar(ChatColor.DARK_PURPLE + "KILL STRIKE!");
    }

    private void carveKillStrikeCrater(Player holder, Location center) {
        World world = center.getWorld();
        int centerX = center.getBlockX();
        int centerY = center.getBlockY() - 1;
        int centerZ = center.getBlockZ();

        // Search downward for the floor so airborne deaths still leave a crater.
        int floorY = Integer.MIN_VALUE;
        for (int y = centerY; y >= Math.max(world.getMinHeight(), centerY - 8); y--) {
            Block block = world.getBlockAt(centerX, y, centerZ);
            if (isShatterable(block)) {
                floorY = y;
                break;
            }
        }
        if (floorY == Integer.MIN_VALUE) {
            return;
        }

        // Semi-large 7x7 crater with a deeper core. Blocks are removed without
        // explosions or item drops, so player death drops and nearby loot remain intact.
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                double radius = Math.sqrt(x * x + z * z);
                if (radius > 3.2) {
                    continue;
                }
                int depth = radius < 1.2 ? 3 : radius < 2.3 ? 2 : 1;
                for (int y = 0; y < depth; y++) {
                    Block block = world.getBlockAt(centerX + x, floorY - y, centerZ + z);
                    if (!isShatterable(block)) {
                        continue;
                    }
                    BlockBreakEvent breakEvent = new BlockBreakEvent(block, holder);
                    Bukkit.getPluginManager().callEvent(breakEvent);
                    if (!breakEvent.isCancelled()) {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    private boolean isShatterable(Block block) {
        Material type = block.getType();
        return !type.isAir() && type.isSolid() && !(block.getState() instanceof TileState)
                && type != Material.BEDROCK && type != Material.BARRIER
                && type != Material.END_PORTAL_FRAME && type != Material.REINFORCED_DEEPSLATE;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        grapplingPlayers.remove(event.getPlayer().getUniqueId());
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
