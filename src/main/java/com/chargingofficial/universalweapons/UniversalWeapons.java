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
import org.bukkit.event.entity.EntityDamageEvent;
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
    private static final double SLAM_DAMAGE = 5.0;
    private static final long GROUND_SHATTER_COOLDOWN_MS = 450L;
    private static final long DOUBLE_STRIKE_DELAY_TICKS = 10L;

    private NamespacedKey weaponKey;
    private final Map<UUID, Integer> hitCounts = new HashMap<>();
    private final Map<UUID, Long> dashReadyAt = new HashMap<>();
    private final Map<UUID, Long> grappleReadyAt = new HashMap<>();
    private final Map<UUID, Long> groundShatterReadyAt = new HashMap<>();
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
        groundShatterReadyAt.clear();
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
                ChatColor.DARK_PURPLE + "Every third hit triggers a rapid second strike.",
                ChatColor.GRAY + "A fall of 3+ blocks triggers Ground Shatter."
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
        if (!player.getWorld().equals(target.getWorld())) {
            return;
        }

        grappleReadyAt.put(id, now + GRAPPLE_COOLDOWN_MS);
        grapplingPlayers.add(id);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_THROW, 1.0f, 0.7f);
        player.sendActionBar(ChatColor.LIGHT_PURPLE + "VOID GRAPPLE!");

        // Pull horizontally only. Preserve vertical velocity and fall distance so normal
        // gravity and the mace's falling-damage calculation are never interrupted.
        final int[] elapsed = {0};
        Bukkit.getScheduler().runTaskTimer(this, task -> {
            if (!player.isOnline() || !player.isValid() || !target.isValid() || target.isDead()
                    || !target.isOnline() || !player.getWorld().equals(target.getWorld())
                    || elapsed[0]++ >= 60) {
                grapplingPlayers.remove(id);
                task.cancel();
                return;
            }

            drawParticleChain(player.getEyeLocation(), target.getEyeLocation());
            Vector offset = target.getLocation().toVector().subtract(player.getLocation().toVector());
            Vector horizontal = new Vector(offset.getX(), 0, offset.getZ());
            double horizontalDistance = horizontal.length();
            double distance = player.getLocation().distance(target.getLocation());

            if (distance <= 2.6 || horizontalDistance <= 1.4) {
                grapplingPlayers.remove(id);
                task.cancel();
                // No teleport or velocity reset at the end of the pull.
                Vector face = target.getLocation().toVector().subtract(player.getLocation().toVector());
                face.setY(0);
                if (face.lengthSquared() > 0.001) {
                    Location look = player.getLocation();
                    look.setDirection(face);
                    player.teleport(look);
                }
                player.swingMainHand();
                target.setNoDamageTicks(0);
                damageFromAbility(player, target, 8.0);
                Location impact = target.getLocation().add(0, 1, 0);
                World world = target.getWorld();
                world.spawnParticle(Particle.SWEEP_ATTACK, impact, 1);
                world.spawnParticle(Particle.CRIT, impact, 24, 0.35, 0.45, 0.35, 0.12);
                world.spawnParticle(Particle.DRAGON_BREATH, impact, 18, 0.3, 0.4, 0.3, 0.025);
                world.playSound(impact, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.65f);
                return;
            }

            if (horizontalDistance > 0.001) {
                Vector direction = horizontal.multiply(1.0 / horizontalDistance);
                Vector current = player.getVelocity();
                // Small acceleration plus a speed cap gives a steady reel instead of
                // repeatedly snapping the player's entire velocity toward the target.
                Vector nextHorizontal = new Vector(
                        current.getX() * 0.78 + direction.getX() * 0.16,
                        0,
                        current.getZ() * 0.78 + direction.getZ() * 0.16
                );
                double speed = nextHorizontal.length();
                if (speed > 0.82) {
                    nextHorizontal.multiply(0.82 / speed);
                }
                player.setVelocity(new Vector(nextHorizontal.getX(), current.getY(), nextHorizontal.getZ()));
            }

            player.getWorld().spawnParticle(Particle.PORTAL, player.getLocation().add(0, 0.8, 0),
                    4, 0.16, 0.22, 0.16, 0.025);
        }, 0L, 1L);
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

        triggerGroundShatter(player, victim);
        int hits = hitCounts.merge(player.getUniqueId(), 1, Integer::sum);
        if (hits % 3 != 0) {
            return;
        }

        player.sendActionBar(ChatColor.LIGHT_PURPLE + "CALAMITY: DOUBLE STRIKE!");

        // No gravity changes, velocity lock, or airborne pause: only delay the follow-up hit.
        Bukkit.getScheduler().runTaskLater(this, () -> {
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
        }, DOUBLE_STRIKE_DELAY_TICKS);
    }

    /**
     * Ground Shatter triggers on a direct Corrupted Calamity mace hit.
     * It creates a small crater around the victim's feet and respects BlockBreakEvent protections.
     */
    private void triggerGroundShatter(Player player, LivingEntity victim) {
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        if (groundShatterReadyAt.getOrDefault(id, 0L) > now) {
            return;
        }
        groundShatterReadyAt.put(id, now + GROUND_SHATTER_COOLDOWN_MS);

        Location impact = victim.getLocation();
        World world = impact.getWorld();
        world.playSound(impact, Sound.ENTITY_GENERIC_EXPLODE, 0.9f, 1.25f);
        world.playSound(impact, Sound.BLOCK_DEEPSLATE_BREAK, 1.0f, 0.7f);
        world.spawnParticle(Particle.EXPLOSION, impact.clone().add(0, 0.25, 0), 1);
        world.spawnParticle(Particle.BLOCK, impact, 55, 1.0, 0.25, 1.0, 0.18,
                Material.CRYING_OBSIDIAN.createBlockData());
        world.spawnParticle(Particle.DRAGON_BREATH, impact, 35, 0.9, 0.35, 0.9, 0.035);
        world.spawnParticle(Particle.SWEEP_ATTACK, impact.clone().add(0, 0.2, 0), 5, 0.9, 0.1, 0.9, 0);

        // Find the real floor beneath the victim, even if the hit has knocked them upward.
        int floorY = impact.getBlockY() - 1;
        boolean foundFloor = false;
        for (int y = impact.getBlockY() - 1; y >= Math.max(world.getMinHeight(), impact.getBlockY() - 6); y--) {
            Block candidate = world.getBlockAt(impact.getBlockX(), y, impact.getBlockZ());
            if (isShatterable(candidate)) {
                floorY = y;
                foundFloor = true;
                break;
            }
        }
        if (!foundFloor) {
            return;
        }

        // A compact 3x3 crater, with a two-block-deep center.
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                int depth = (x == 0 && z == 0) ? 2 : 1;
                for (int y = 0; y < depth; y++) {
                    Block block = world.getBlockAt(impact.getBlockX() + x, floorY - y,
                            impact.getBlockZ() + z);
                    if (!isShatterable(block)) {
                        continue;
                    }
                    BlockBreakEvent breakEvent = new BlockBreakEvent(block, player);
                    Bukkit.getPluginManager().callEvent(breakEvent);
                    if (!breakEvent.isCancelled()) {
                        block.breakNaturally();
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
