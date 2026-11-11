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

public final class UniversalWeapons extends JavaPlugin implements Listener, CommandExecutor {
    private static final long DASH_COOLDOWN_MS = 1800L;
    private static final long GRAPPLE_COOLDOWN_MS = 3500L;
    private static final double GRAPPLE_RANGE = 24.0;
    private static final double BONUS_HIT_DAMAGE = 6.0;
    private static final double SLAM_DAMAGE = 5.0;

    private NamespacedKey weaponKey;
    private final Map<UUID, Integer> hitCounts = new HashMap<>();
    private final Map<UUID, Long> dashCooldowns = new HashMap<>();
    private final Map<UUID, Long> grappleCooldowns = new HashMap<>();
    // Extra damage caused by abilities must not count as ordinary mace swings.
    private final Set<UUID> abilityDamageInProgress = new HashSet<>();

    @Override
    public void onEnable() {
        weaponKey = new NamespacedKey(this, "corrupted_calamity");
        if (getCommand("weapon") == null) {
            getLogger().severe("The /weapon command is missing from plugin.yml; disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getCommand("weapon").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Universal Weapons enabled.");
    }

    @Override
    public void onDisable() {
        hitCounts.clear();
        dashCooldowns.clear();
        grappleCooldowns.clear();
        abilityDamageInProgress.clear();
    }

    private ItemStack createCalamity() {
        ItemStack mace = new ItemStack(Material.MACE);
        ItemMeta meta = mace.getItemMeta();
        meta.setDisplayName(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Corrupted Calamity");
        meta.setLore(List.of(
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

        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(createCalamity());
        leftovers.values().forEach(item ->
                player.getWorld().dropItemNaturally(player.getLocation(), item));
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
        if (dashCooldowns.getOrDefault(player.getUniqueId(), 0L) > now) {
            player.sendActionBar(ChatColor.DARK_PURPLE + "Corrupted Dash is recharging.");
            return;
        }

        dashCooldowns.put(player.getUniqueId(), now + DASH_COOLDOWN_MS);
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
        if (grappleCooldowns.getOrDefault(player.getUniqueId(), 0L) > now) {
            player.sendActionBar(ChatColor.DARK_PURPLE + "Void Grapple is recharging.");
            return;
        }

        RayTraceResult trace = player.getWorld().rayTraceEntities(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                GRAPPLE_RANGE,
                0.8,
                entity -> entity instanceof Player && entity != player
        );

        if (trace == null || !(trace.getHitEntity() instanceof Player target)) {
            player.sendMessage(ChatColor.RED + "Aim at a player within 24 blocks to grapple.");
            return;
        }

        grappleCooldowns.put(player.getUniqueId(), now + GRAPPLE_COOLDOWN_MS);

        // Place the grappler just beside the target instead of inside their hitbox.
        Vector offset = target.getLocation().getDirection().normalize().multiply(1.35);
        Location destination = target.getLocation().clone().subtract(offset);
        destination.setY(target.getLocation().getY());
        destination.setDirection(target.getLocation().toVector().subtract(destination.toVector()));
        player.teleport(destination);

        World world = target.getWorld();
        world.spawnParticle(Particle.PORTAL, target.getLocation().add(0, 1, 0),
                45, 0.6, 0.8, 0.6, 0.25);
        world.playSound(target.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.7f);

        abilityDamageInProgress.add(player.getUniqueId());
        try {
            target.damage(8.0, player);
        } finally {
            abilityDamageInProgress.remove(player.getUniqueId());
        }
        world.spawnParticle(Particle.CRIT, target.getLocation().add(0, 1, 0),
                18, 0.35, 0.45, 0.35, 0.15);
    }

    @EventHandler
    public void onMaceHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)
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
        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline() || !victim.isValid() || victim.isDead()) {
                return;
            }

            abilityDamageInProgress.add(player.getUniqueId());
            try {
                victim.damage(BONUS_HIT_DAMAGE, player);
            } finally {
                abilityDamageInProgress.remove(player.getUniqueId());
            }

            Location hitLocation = victim.getLocation().add(0, 1, 0);
            victim.getWorld().spawnParticle(Particle.SWEEP_ATTACK, hitLocation, 1);
            victim.getWorld().spawnParticle(Particle.DRAGON_BREATH, hitLocation,
                    22, 0.35, 0.45, 0.35, 0.025);
            victim.getWorld().playSound(hitLocation,
                    Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.9f, 0.65f);
        });
    }

    @EventHandler
    public void onGroundSlam(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || event.getCause() != EntityDamageEvent.DamageCause.FALL
                || !isCalamity(player.getInventory().getItemInMainHand())
                || player.getFallDistance() < 3.0f) {
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
            if (!(entity instanceof LivingEntity living) || entity == player) {
                continue;
            }
            abilityDamageInProgress.add(player.getUniqueId());
            try {
                living.damage(SLAM_DAMAGE, player);
            } finally {
                abilityDamageInProgress.remove(player.getUniqueId());
            }
            living.setVelocity(living.getVelocity().add(new Vector(0, 0.35, 0)));
        }
    }
}
