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
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
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
    private static final double BONUS_HIT_DAMAGE = 6.0;
    private static final double SLAM_DAMAGE = 5.0;
    private static final double SLAM_MIN_FALL_DISTANCE = 3.0;
    private static final long DOUBLE_STRIKE_DELAY_TICKS = 2L;

    private NamespacedKey weaponKey;
    private final Map<UUID, Integer> hitCounts = new HashMap<>();
    private final Map<UUID, Long> dashReadyAt = new HashMap<>();
    private final Map<UUID, Long> grappleReadyAt = new HashMap<>();
    private final Set<UUID> abilityDamageInProgress = new HashSet<>();

    @Override
    public void onEnable() {
        weaponKey = new NamespacedKey(this, "corrupted_calamity");
        if (getCommand("weapon") == null) {
            getLogger().severe("Missing /weapon declaration in plugin.yml. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getCommand("weapon").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Universal Weapons enabled for Paper 1.21.11.");
    }

    @Override
    public void onDisable() {
        hitCounts.clear();
        dashReadyAt.clear();
        grappleReadyAt.clear();
        abilityDamageInProgress.clear();
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
        Vector velocity = direction.clone().multiply(1.65);
        velocity.setY(Math.max(0.28, velocity.getY()));
        player.setVelocity(velocity);

        World world = player.getWorld();
        Location origin = player.getLocation().add(0, 1, 0);
        world.playSound(origin, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 0.65f);

        // A short particle wake follows the direction of the dash.
        Vector trailDirection = direction.clone().multiply(-1);
        for (int i = 0; i < 8; i++) {
            Location point = origin.clone().add(trailDirection.clone().multiply(i * 0.32));
            world.spawnParticle(Particle.PORTAL, point, 7, 0.16, 0.22, 0.16, 0.08);
            world.spawnParticle(Particle.END_ROD, point, 2, 0.08, 0.10, 0.08, 0.01);
        }
        world.spawnParticle(Particle.DRAGON_BREATH, origin, 24, 0.35, 0.3, 0.35, 0.035);
    }

    private void grapple(Player player) {
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        if (grappleReadyAt.getOrDefault(id, 0L) > now) {
            player.sendActionBar(ChatColor.DARK_PURPLE + "Void Grapple is recharging.");
            return;
        }

        Location eye = player.getEyeLocation();
        RayTraceResult trace = player.getWorld().rayTraceEntities(
                eye, eye.getDirection(), GRAPPLE_RANGE, 0.8,
                entity -> entity instanceof Player && entity != player
        );
        if (trace == null || !(trace.getHitEntity() instanceof Player target)) {
            player.sendMessage(ChatColor.RED + "Aim at a player within 24 blocks to grapple.");
            return;
        }

        Vector pull = target.getLocation().toVector().subtract(player.getLocation().toVector());
        if (pull.lengthSquared() < 0.01) {
            return;
        }

        grappleReadyAt.put(id, now + GRAPPLE_COOLDOWN_MS);
        drawParticleChain(player.getEyeLocation(), target.getEyeLocation());
        Vector pullVelocity = pull.normalize().multiply(1.55);
        pullVelocity.setY(Math.max(0.18, pullVelocity.getY() + 0.18));
        player.setVelocity(pullVelocity);

        World world = target.getWorld();
        Location targetEffect = target.getLocation().add(0, 1, 0);
        world.spawnParticle(Particle.PORTAL, targetEffect, 24, 0.45, 0.6, 0.45, 0.15);
        world.playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1.0f, 0.65f);
        damageFromAbility(player, target, 8.0);
        world.spawnParticle(Particle.CRIT, targetEffect, 18, 0.35, 0.45, 0.35, 0.15);
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

        int hits = hitCounts.merge(player.getUniqueId(), 1, Integer::sum);
        if (hits % 3 != 0) {
            return;
        }

        player.sendActionBar(ChatColor.LIGHT_PURPLE + "CALAMITY: DOUBLE STRIKE!");
        // Delay by two ticks so this is a distinct follow-up hit, not extra damage
        // folded into the original hit. The ability guard prevents recursion.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline() || !victim.isValid() || victim.isDead()
                    || !player.getWorld().equals(victim.getWorld())) {
                return;
            }

            player.swingMainHand();
            damageFromAbility(player, victim, BONUS_HIT_DAMAGE);
            Location hitLocation = victim.getLocation().add(0, 1, 0);
            World world = victim.getWorld();
            world.spawnParticle(Particle.SWEEP_ATTACK, hitLocation, 1);
            world.spawnParticle(Particle.DRAGON_BREATH, hitLocation,
                    22, 0.35, 0.45, 0.35, 0.025);
            world.playSound(hitLocation, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.9f, 0.65f);
        }, DOUBLE_STRIKE_DELAY_TICKS);
    }

    @EventHandler
    public void onGroundSlam(EntityDamageEvent event) {
        if (event.isCancelled()
                || !(event.getEntity() instanceof Player player)
                || event.getCause() != EntityDamageEvent.DamageCause.FALL
                || !isCalamity(player.getInventory().getItemInMainHand())
                || player.getFallDistance() < SLAM_MIN_FALL_DISTANCE) {
            return;
        }

        event.setCancelled(true);
        player.setFallDistance(0);
        Location center = player.getLocation();
        World world = player.getWorld();
        world.playSound(center, Sound.BLOCK_DEEPSLATE_BREAK, 1.1f, 0.65f);
        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.65f, 1.4f);
        world.spawnParticle(Particle.BLOCK, center, 65, 1.1, 0.15, 1.1, 0.18,
                Material.CRYING_OBSIDIAN.createBlockData());
        world.spawnParticle(Particle.DRAGON_BREATH, center, 45, 1.0, 0.25, 1.0, 0.04);
        world.spawnParticle(Particle.SWEEP_ATTACK, center.clone().add(0, 0.2, 0),
                8, 1.2, 0.1, 1.2, 0);

        for (Entity entity : world.getNearbyEntities(center, 3.2, 1.8, 3.2)) {
            if (entity instanceof LivingEntity living && entity != player) {
                damageFromAbility(player, living, SLAM_DAMAGE);
                living.setVelocity(living.getVelocity().add(new Vector(0, 0.35, 0)));
            }
        }
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
