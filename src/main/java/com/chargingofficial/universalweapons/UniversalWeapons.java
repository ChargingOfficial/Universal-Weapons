package com.chargingofficial.universalweapons;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;

public final class UniversalWeapons extends JavaPlugin implements Listener, CommandExecutor {
    private NamespacedKey weaponKey;
    private final Map<UUID, Integer> hitCounts = new HashMap<>();
    private final Map<UUID, Long> dashCooldowns = new HashMap<>();
    private final Map<UUID, Long> grappleCooldowns = new HashMap<>();
    private final Set<UUID> bonusHitInProgress = new HashSet<>();

    private static final long DASH_COOLDOWN_MS = 1800;
    private static final long GRAPPLE_COOLDOWN_MS = 3500;
    private static final double HIT_DAMAGE = 8.0;
    private static final double BONUS_HIT_DAMAGE = 6.0;

    @Override
    public void onEnable() {
        weaponKey = new NamespacedKey(this, "corrupted_calamity");
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("weapon").setExecutor(this);
        getLogger().info("Universal Weapons enabled.");
    }

    @Override
    public void onDisable() {
        hitCounts.clear();
        dashCooldowns.clear();
        grappleCooldowns.clear();
        bonusHitInProgress.clear();
    }

    private ItemStack createCalamity() {
        ItemStack mace = new ItemStack(Material.MACE);
        ItemMeta meta = mace.getItemMeta();
        meta.setDisplayName(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Corrupted Calamity");
        meta.setLore(java.util.List.of(
                ChatColor.GRAY + "A relic consumed by the void.",
                ChatColor.LIGHT_PURPLE + "Right-click: " + ChatColor.WHITE + "Corrupted Dash",
                ChatColor.LIGHT_PURPLE + "Sneak + right-click: " + ChatColor.WHITE + "Void Grapple",
                ChatColor.DARK_PURPLE + "Every third hit strikes twice."
        ));
        meta.setCustomModelData(11001);
        meta.getPersistentDataContainer().set(weaponKey, PersistentDataType.BYTE, (byte) 1);
        mace.setItemMeta(meta);
        return mace;
    }

    private boolean isCalamity(ItemStack item) {
        if (item == null || item.getType() != Material.MACE || !item.hasItemMeta()) return false;
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(weaponKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }
        player.getInventory().addItem(createCalamity()).values()
                .forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        player.sendMessage(ChatColor.LIGHT_PURPLE + "The Corrupted Calamity has been placed in your inventory.");
        return true;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !isCalamity(event.getItem())) return;
        if (event.getAction().name().contains("RIGHT_CLICK")) {
            event.setCancelled(true);
            Player player = event.getPlayer();
            if (player.isSneaking()) grapple(player);
            else dash(player);
        }
    }

    private void dash(Player player) {
        long now = System.currentTimeMillis();
        if (dashCooldowns.getOrDefault(player.getUniqueId(), 0L) > now) {
            player.sendActionBar(ChatColor.DARK_PURPLE + "Corrupted Dash is recharging.");
            return;
        }
        dashCooldowns.put(player.getUniqueId(), now + DASH_COOLDOWN_MS);
        Vector direction = player.getLocation().getDirection().normalize();
        player.setVelocity(direction.multiply(1.65).setY(Math.max(0.28, direction.getY() * 0.65)));
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 0.65f);
        player.getWorld().spawnParticle(Particle.PORTAL, player.getLocation().add(0, 1, 0), 55, 0.55, 0.65, 0.55, 0.35);
        player.getWorld().spawnParticle(Particle.DRAGON_BREATH, player.getLocation(), 28, 0.4, 0.3, 0.4, 0.04);
    }

    private void grapple(Player player) {
        long now = System.currentTimeMillis();
        if (grappleCooldowns.getOrDefault(player.getUniqueId(), 0L) > now) {
            player.sendActionBar(ChatColor.DARK_PURPLE + "Void Grapple is recharging.");
            return;
        }
        RayTraceResult trace = player.getWorld().rayTraceEntities(
                player.getEyeLocation(), player.getEyeLocation().getDirection(), 24.0,
                0.8, entity -> entity instanceof Player && entity != player
        );
        if (trace == null || !(trace.getHitEntity() instanceof Player target)) {
            player.sendMessage(ChatColor.RED + "Aim at a player within 24 blocks to grapple.");
            return;
        }
        grappleCooldowns.put(player.getUniqueId(), now + GRAPPLE_COOLDOWN_MS);
        Location destination = target.getLocation().clone().subtract(
                target.getLocation().getDirection().normalize().multiply(1.35));
        destination.setY(target.getLocation().getY());
        destination.setDirection(target.getLocation().toVector().subtract(destination.toVector()));
        player.teleport(destination);
        player.getWorld().spawnParticle(Particle.PORTAL, target.getLocation().add(0, 1, 0), 45, 0.6, 0.8, 0.6, 0.25);
        player.getWorld().playSound(target.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.7f);
        target.damage(HIT_DAMAGE, player);
        player.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0, 1, 0), 18, 0.35, 0.45, 0.35, 0.15);
    }

    @EventHandler
    public void onMaceHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player) || !(event.getEntity() instanceof LivingEntity victim)) return;
        if (!isCalamity(player.getInventory().getItemInMainHand())) return;
        if (bonusHitInProgress.contains(player.getUniqueId())) return;
        int hits = hitCounts.merge(player.getUniqueId(), 1, Integer::sum);
        if (hits % 3 == 0) {
            Bukkit.getScheduler().runTask(this, () -> {
                if (!victim.isValid() || victim.isDead()) return;
                bonusHitInProgress.add(player.getUniqueId());
                try {
                    victim.damage(BONUS_HIT_DAMAGE, player);
                } finally {
                    bonusHitInProgress.remove(player.getUniqueId());
                }
                victim.getWorld().spawnParticle(Particle.SWEEP_ATTACK, victim.getLocation().add(0, 1, 0), 1);
                victim.getWorld().spawnParticle(Particle.DRAGON_BREATH, victim.getLocation().add(0, 1, 0), 22, 0.35, 0.45, 0.35, 0.025);
                victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.9f, 0.65f);
            });
            player.sendActionBar(ChatColor.LIGHT_PURPLE + "CALAMITY: DOUBLE STRIKE!");
        }
    }

    @EventHandler
    public void onGroundSlam(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!isCalamity(player.getInventory().getItemInMainHand())) return;
        if (player.getFallDistance() < 3.0f) return;

        event.setCancelled(true);
        Location center = player.getLocation();
        World world = player.getWorld();
        world.playSound(center, Sound.BLOCK_DEEPSLATE_BREAK, 1.1f, 0.65f);
        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.65f, 1.4f);
        world.spawnParticle(Particle.BLOCK, center, 65, 1.1, 0.15, 1.1, 0.18,
                Material.CRYING_OBSIDIAN.createBlockData());
        world.spawnParticle(Particle.DRAGON_BREATH, center, 45, 1.0, 0.25, 1.0, 0.04);
        world.spawnParticle(Particle.SWEEP_ATTACK, center.clone().add(0, 0.2, 0), 8, 1.2, 0.1, 1.2, 0);
        for (Entity entity : world.getNearbyEntities(center, 3.2, 1.8, 3.2)) {
            if (entity instanceof LivingEntity living && entity != player) {
                living.damage(5.0, player);
                living.setVelocity(living.getVelocity().add(new Vector(0, 0.35, 0)));
            }
        }
        player.setFallDistance(0);
    }
}
