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
 * All world/entity changes are performed synchronously on the server thread.
 */
public final class UniversalWeapons extends JavaPlugin implements Listener, CommandExecutor {
    private static final long DASH_COOLDOWN_MS = 1_800L;
    private static final long GRAPPLE_COOLDOWN_MS = 3_500L;
    private static final double GRAPPLE_RANGE = 24.0;
    private static final double BONUS_HIT_DAMAGE = 6.0;
    private static final double SLAM_DAMAGE = 5.0;
    private static final double SLAM_MIN_FALL_DISTANCE = 3.0;

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
                ChatColor.DARK_PURPLE + "Every third hit unleashes a second strike.",
                ChatColor.GRAY + "A fall of 3+ blocks triggers Ground Shatter."
        ));
        meta.setCustomModelData(11001);
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
        // Bukkit fires this event once for each hand; ignore the off-hand copy.
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
        Vector velocity = direction.multiply(1.65);
        velocity.setY(Math.max(0.28, velocity.getY()));
        player.setVelocity(velocity);

        Location effectLocation = player.getLocation().add(0, 1, 0);
        World world = player.getWorld();
        world.playSound(effectLocation, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 0.65f);
        world.spawnParticle(Particle.PORTAL, effectLocation, 55, 0.55, 0.65, 0.55, 0.35);
        world.spawnParticle(Particle.DRAGON_BREATH, effectLocation, 28, 0.4, 0.3, 0.4, 0.04);
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
                eye,
                eye.getDirection(),
                GRAPPLE_RANGE,
                0.8,
                entity -> entity instanceof Player && entity != player
        );

        if (trace == null || !(trace.getHitEntity() instanceof Player target)) {
            player.sendMessage(ChatColor.RED + "Aim at a player within 24 blocks to grapple.");
            return;
        }

        Location targetLocation = target.getLocation();
        Vector awayFromTarget = targetLocation.getDirection().setY(0).normalize();
        if (awayFromTarget.lengthSquared() < 0.0001) {
            awayFromTarget = new Vector(0, 0, 1);
        }

        // Keep the player outside the target's hitbox and preserve the target's world.
        Location destination = targetLocation.clone().subtract(awayFromTarget.multiply(1.35));
        destination.setY(targetLocation.getY());
        destination.setDirection(targetLocation.toVector().subtract(destination.toVector()));

        if (!player.teleport(destination)) {
            player.sendMessage(ChatColor.RED + "The grapple destination is unavailable.");
            return;
        }

        grappleReadyAt.put(id, now + GRAPPLE_COOLDOWN_MS);
        World world = target.getWorld();
        Location effectLocation = target.getLocation().add(0, 1, 0);
        world.spawnParticle(Particle.PORTAL, effectLocation, 45, 0.6, 0.8, 0.6, 0.25);
        world.playSound(effectLocation, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.7f);

        damageFromAbility(player, target, 8.0);
        world.spawnParticle(Particle.CRIT, target.getLocation().add(0, 1, 0),
                18, 0.35, 0.45, 0.35, 0.15);
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

        UUID id = player.getUniqueId();
        int hits = hitCounts.merge(id, 1, Integer::sum);
        if (hits % 3 != 0) {
            return;
        }

        player.sendActionBar(ChatColor.LIGHT_PURPLE + "CALAMITY: DOUBLE STRIKE!");
        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline() || !victim.isValid() || victim.isDead()) {
                return;
            }

            damageFromAbility(player, victim, BONUS_HIT_DAMAGE);
            Location hitLocation = victim.getLocation().add(0, 1, 0);
            World world = victim.getWorld();
            world.spawnParticle(Particle.SWEEP_ATTACK, hitLocation, 1);
            world.spawnParticle(Particle.DRAGON_BREATH, hitLocation,
                    22, 0.35, 0.45, 0.35, 0.025);
            world.playSound(hitLocation, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.9f, 0.65f);
        });
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
