package com.chargingofficial.universalweapons;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scheduler.BukkitRunnable;
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
import org.bukkit.entity.Projectile;
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
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.Component;
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
    private static final long GROUND_SLAM_COOLDOWN_MS = 10_000L;
    private static final double GRAPPLE_RANGE = 24.0;
    private static final double BONUS_HIT_DAMAGE = 20.0;

    private NamespacedKey weaponKey;
    private NamespacedKey weaponTypeKey;
    private final Map<UUID, Integer> axeHitCounts = new HashMap<>();
    private final Set<UUID> groundSlamming = new HashSet<>();
    private final Map<UUID, Long> groundSlamReadyAt = new HashMap<>();
    private final Map<UUID, DomeSession> activeDomes = new HashMap<>();
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
        weaponTypeKey = new NamespacedKey(this, "weapon_type");
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
        for (DomeSession dome : new HashSet<>(activeDomes.values())) dome.task.cancel();
        activeDomes.clear();
        axeHitCounts.clear();
        groundSlamming.clear();
        groundSlamReadyAt.clear();
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
        // Player#getLocation() can floor to the command-block's Y when the
        // player's feet are exactly on (or fractionally below) the block top.
        // Sample just below the feet so the supporting block is found reliably.
        Location supportPoint = player.getLocation().clone().subtract(0.0, 0.01, 0.0);
        Block beneath = supportPoint.getBlock();
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
        ItemMeta meta = held.getItemMeta();
        String legacyName = meta.hasDisplayName() ? meta.getDisplayName() : "";
        String componentName = meta.displayName() == null ? ""
                : LegacyComponentSerializer.legacySection().serialize(meta.displayName());
        String normalizedLegacy = ChatColor.stripColor(legacyName).trim();
        String normalizedComponent = ChatColor.stripColor(componentName).trim();
        return "Calamity".equalsIgnoreCase(normalizedLegacy)
                || "Calamity".equalsIgnoreCase(normalizedComponent);
    }

    private void beginCalamityRitual(Player player) {
        UUID id = player.getUniqueId();
        Location ritualBlock = getRitualBlock(player);
        if (ritualBlock == null || ritualTasks.containsKey(id)) return;

        // Keep the exact block and world that started the ritual. The player must
        // remain on this block and keep the original Calamity mace in their hand.
        ritualBlocks.put(id, ritualBlock.clone());
        World world = player.getWorld();
        player.sendMessage(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD
                + "The command block begins to resonate...");
        player.sendActionBar(Component.text("CALAMITY AWAKENING 0%"));

        BukkitRunnable ritual = new BukkitRunnable() {
            private int elapsedTicks = 0;
            private static final int RITUAL_DURATION_TICKS = 600;

            @Override
            public void run() {
                if (!player.isOnline() || !player.isValid() || player.isDead()
                        || !player.getWorld().equals(world)) {
                    interrupt();
                    return;
                }

                // Once started, don't repeat the fragile custom-name parser every tick.
                // The ritual only requires that the mace remains in the main hand.
                ItemStack currentHeld = player.getInventory().getItemInMainHand();
                if (currentHeld.getType() != Material.MACE) {
                    interrupt();
                    return;
                }

                Location currentBlock = getRitualBlock(player);
                Location originalBlock = ritualBlocks.get(id);
                if (currentBlock == null || originalBlock == null
                        || !currentBlock.getWorld().equals(originalBlock.getWorld())
                        || currentBlock.getBlockX() != originalBlock.getBlockX()
                        || currentBlock.getBlockY() != originalBlock.getBlockY()
                        || currentBlock.getBlockZ() != originalBlock.getBlockZ()) {
                    interrupt();
                    return;
                }

                elapsedTicks++;
                Location at = player.getLocation();

                // Live 20-segment progress bar, refreshed every tick.
                int progress = Math.min(100, elapsedTicks * 100 / RITUAL_DURATION_TICKS);
                int filled = progress / 5;
                StringBuilder bar = new StringBuilder();
                for (int segment = 0; segment < 20; segment++) {
                    bar.append(segment < filled
                            ? ChatColor.DARK_PURPLE + "■"
                            : ChatColor.GRAY + "■");
                }
                String actionBar = ChatColor.LIGHT_PURPLE.toString() + ChatColor.BOLD
                        + "AWAKENING " + ChatColor.WHITE + progress + "% "
                        + bar + ChatColor.RESET + ChatColor.LIGHT_PURPLE
                        + " " + String.format(java.util.Locale.US, "%.1fs", Math.max(0, RITUAL_DURATION_TICKS - elapsedTicks) / 20.0);
                player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(actionBar));

                // Complete immediately at 30 seconds, before cosmetic effects run.
                // The original renamed mace is replaced in the same tick.
                if (elapsedTicks >= RITUAL_DURATION_TICKS) {
                    cancel();
                    ritualTasks.remove(id);
                    ritualBlocks.remove(id);
                    player.getInventory().setItemInMainHand(createCalamity());
                    player.sendActionBar(Component.text("CALAMITY AWAKENED"));
                    Location burst = player.getLocation().add(0, 1, 0);
                    try {
                        world.spawnParticle(Particle.EXPLOSION, burst, 4);
                        world.spawnParticle(Particle.FLASH, burst, 8, 0.25, 0.25, 0.25, 0.0);
                        world.spawnParticle(Particle.DRAGON_BREATH, burst, 700, 1.8, 2.0, 1.8, 0.12);
                        world.spawnParticle(Particle.PORTAL, burst, 900, 2.0, 2.0, 2.0, 0.30);
                        world.spawnParticle(Particle.REVERSE_PORTAL, burst, 600, 1.8, 1.8, 1.8, 0.22);
                        world.spawnParticle(Particle.SOUL_FIRE_FLAME, burst, 350, 1.4, 1.6, 1.4, 0.12);
                        world.spawnParticle(Particle.ELECTRIC_SPARK, burst, 350, 1.5, 1.6, 1.5, 0.18);
                        world.spawnParticle(Particle.END_ROD, burst, 250, 1.2, 1.6, 1.2, 0.18);
                        world.spawnParticle(Particle.SONIC_BOOM, burst, 3);
                        world.playSound(burst, Sound.BLOCK_BEACON_POWER_SELECT, 1.2f, 0.55f);
                        world.playSound(burst, Sound.ENTITY_WARDEN_SONIC_BOOM, 0.8f, 1.25f);
                    } catch (RuntimeException effectError) {
                        getLogger().warning("Calamity awakening finale effect failed: " + effectError.getMessage());
                    }
                    player.sendTitle(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "CALAMITY AWAKENED",
                            ChatColor.LIGHT_PURPLE + "The Corrupted Calamity is yours",
                            10, 55, 20);
                    return;
                }

                double angle = elapsedTicks * 0.024;

                // Particle rendering is cosmetic; a particle/API issue must never
                // terminate the timer or freeze the progress bar.
                try {
                double build = Math.min(1.8, 0.65 + elapsedTicks / 450.0);

                // Three dense rotating rings form a large, layered halo around the player.
                for (int i = 0; i < 32; i++) {
                    double a = angle + (Math.PI * 2.0 * i / 32.0);
                    double x = Math.cos(a);
                    double z = Math.sin(a);
                    Location groundRing = at.clone().add(x * 2.15 * build, 0.10, z * 2.15 * build);
                    Location waistRing = at.clone().add(x * 1.45 * build, 1.05, z * 1.45 * build);
                    Location crownRing = at.clone().add(x * 1.85 * build, 2.15, z * 1.85 * build);
                    world.spawnParticle(Particle.PORTAL, groundRing, 5, 0.08, 0.05, 0.08, 0.12);
                    world.spawnParticle(Particle.SOUL_FIRE_FLAME, groundRing, 2, 0.04, 0.08, 0.04, 0.015);
                    world.spawnParticle(Particle.DRAGON_BREATH, waistRing, 5, 0.07, 0.08, 0.07, 0.035);
                    world.spawnParticle(Particle.ELECTRIC_SPARK, crownRing, 2, 0.04, 0.06, 0.04, 0.02);
                    world.spawnParticle(Particle.REVERSE_PORTAL, crownRing, 2, 0.06, 0.06, 0.06, 0.04);
                }

                // Five thick corkscrew streams rise from the command block and coil overhead.
                for (int i = 0; i < 5; i++) {
                    double spiral = angle * 1.35 + (Math.PI * 2.0 * i / 5.0);
                    double height = (elapsedTicks * 0.095 + i * 0.50) % 3.2;
                    double radius = 0.75 + Math.sin(height * 1.3) * 0.42;
                    Location vortex = at.clone().add(Math.cos(spiral) * radius,
                            0.10 + height, Math.sin(spiral) * radius);
                    world.spawnParticle(Particle.DRAGON_BREATH, vortex, 9, 0.13, 0.16, 0.13, 0.04);
                    world.spawnParticle(Particle.SOUL_FIRE_FLAME, vortex, 4, 0.07, 0.12, 0.07, 0.025);
                    world.spawnParticle(Particle.PORTAL, vortex, 10, 0.15, 0.16, 0.15, 0.16);
                    world.spawnParticle(Particle.ELECTRIC_SPARK, vortex, 2, 0.06, 0.09, 0.06, 0.03);
                }

                // A pulsing column and dense aura fill the space around the player.
                world.spawnParticle(Particle.DRAGON_BREATH, at.clone().add(0, 1.15, 0),
                        28, 0.72, 1.05, 0.72, 0.055);
                world.spawnParticle(Particle.PORTAL, at.clone().add(0, 1.1, 0),
                        36, 0.85, 1.0, 0.85, 0.18);
                world.spawnParticle(Particle.WITCH, at.clone().add(0, 1.0, 0),
                        24, 0.65, 0.9, 0.65, 0.06);
                world.spawnParticle(Particle.SOUL_FIRE_FLAME, at.clone().add(0, 0.9, 0),
                        16, 0.55, 0.8, 0.55, 0.04);

                // The ground circle expands in pulses beneath the ritualist.
                if (elapsedTicks % 8 == 0) {
                    double pulseRadius = 1.0 + (elapsedTicks % 32) / 32.0 * 2.2;
                    for (int i = 0; i < 48; i++) {
                        double p = Math.PI * 2.0 * i / 48.0;
                        Location pulse = at.clone().add(Math.cos(p) * pulseRadius,
                                0.08, Math.sin(p) * pulseRadius);
                        world.spawnParticle(Particle.SOUL_FIRE_FLAME, pulse, 2, 0.03, 0.02, 0.03, 0.01);
                        world.spawnParticle(Particle.ELECTRIC_SPARK, pulse, 1, 0.02, 0.02, 0.02, 0.01);
                    }
                }

                } catch (RuntimeException effectError) {
                    if (elapsedTicks == 1) {
                        getLogger().warning("A Calamity ritual particle effect failed: "
                                + effectError.getMessage());
                    }
                }


                if (elapsedTicks % 20 == 0) {
                    world.playSound(at, Sound.BLOCK_BEACON_AMBIENT,
                            1.0f, 0.45f + elapsedTicks / 600.0f);
                    world.playSound(at, Sound.BLOCK_BEACON_POWER_SELECT,
                            0.55f, 0.65f + elapsedTicks / 1200.0f);
                }

                // The aura becomes increasingly violent in the final third.
                if (elapsedTicks >= 400) {
                    double surge = 1.0 + (elapsedTicks - 400) / 40.0;
                    for (int i = 0; i < 8; i++) {
                        double a = angle * 1.8 + Math.PI * 2.0 * i / 8.0;
                        double y = 0.25 + ((elapsedTicks * 0.13 + i * 0.37) % 3.0);
                        Location surgePoint = at.clone().add(
                                Math.cos(a) * (1.1 + surge * 0.25), y,
                                Math.sin(a) * (1.1 + surge * 0.25));
                        world.spawnParticle(Particle.REVERSE_PORTAL, surgePoint,
                                7, 0.12, 0.16, 0.12, 0.12);
                        world.spawnParticle(Particle.SOUL_FIRE_FLAME, surgePoint,
                                4, 0.08, 0.12, 0.08, 0.035);
                        world.spawnParticle(Particle.ELECTRIC_SPARK, surgePoint,
                                3, 0.08, 0.10, 0.08, 0.05);
                    }
                    world.spawnParticle(Particle.DRAGON_BREATH, at.clone().add(0, 1.0, 0),
                            18 + (elapsedTicks - 80), 0.8, 1.0, 0.8, 0.06);
                }

                // Final two seconds: frequent surges, stronger sound, and a bright pulse.
                if (elapsedTicks >= 500) {
                    if (elapsedTicks % 5 == 0) {
                        world.playSound(at, Sound.BLOCK_BEACON_ACTIVATE,
                                0.7f, 0.8f + (elapsedTicks - 500) * 0.007f);
                    }
                    if (elapsedTicks % 4 == 0) {
                        world.spawnParticle(Particle.FLASH, at.clone().add(0, 1.1, 0),
                                1, 0.35, 0.55, 0.35, 0.0);
                        world.spawnParticle(Particle.SONIC_BOOM, at.clone().add(0, 1.0, 0),
                                1, 0.0, 0.0, 0.0, 0.0);
                        world.spawnParticle(Particle.PORTAL, at.clone().add(0, 1.0, 0),
                                90, 1.0, 1.2, 1.0, 0.24);
                    }
                }


            }

            private void interrupt() {
                cancel();
                ritualTasks.remove(id);
                ritualBlocks.remove(id);
                if (player.isOnline()) {
                    player.sendActionBar(Component.text("Ritual interrupted. Stay on the command block and keep Calamity in your main hand."));
                    player.getWorld().playSound(player.getLocation(),
                            Sound.BLOCK_BEACON_DEACTIVATE, 0.7f, 0.7f);
                }
            }
        };

        BukkitTask task = ritual.runTaskTimer(this, 1L, 1L);
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

        String kind = args.length == 0 ? "calamity" : args[0].toLowerCase(java.util.Locale.ROOT);
        boolean normal = args.length > 1 && args[1].equalsIgnoreCase("normal");
        ItemStack weapon;
        switch (kind) {
            case "calamity" -> weapon = createCalamity();
            case "crossbow", "infernal" -> weapon = createNamedWeapon(Material.CROSSBOW,
                    normal ? "Infernal" : "Infernal Requiem", "infernal_crossbow", normal);
            case "spear", "judgment" -> weapon = createNamedWeapon(
                    resolveSpearMaterial(), normal ? "Judgment" : "Heaven's Judgment",
                    "judgment_spear", normal);
            case "trident", "storm" -> weapon = createNamedWeapon(Material.TRIDENT,
                    normal ? "Storm" : "Poseidon's Wrath", "storm_trident", normal);
            case "axe", "berserker" -> weapon = createNamedWeapon(Material.NETHERITE_AXE,
                    normal ? "Berserker" : "Bloodreaver", "bloodreaver_axe", normal);
            case "slimeball", "eclipse" -> weapon = createEclipseCore();
            default -> {
                player.sendMessage(ChatColor.RED + "Use /weapon [calamity|crossbow|spear|trident|axe|slimeball] [normal]");
                return true;
            }
        }
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(weapon);
        for (ItemStack leftover : leftovers.values()) player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        player.sendMessage(ChatColor.LIGHT_PURPLE + "You received " + weapon.getItemMeta().getDisplayName() + ChatColor.LIGHT_PURPLE + ".");
        return true;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack held = event.getItem();
        if (held == null || !held.hasItemMeta()) return;
        Player player = event.getPlayer();
        String type = held.getItemMeta().getPersistentDataContainer().get(weaponTypeKey, PersistentDataType.STRING);
        if (isCalamity(held)) {
            event.setCancelled(true);
            if (player.isSneaking()) grapple(player); else dash(player);
            return;
        }
        if (type == null) return;
        switch (type) {
            case "eclipse_core" -> { event.setCancelled(true); activateEclipseCore(player); }
            case "judgment_spear" -> {
                if (player.isSneaking()) { event.setCancelled(true); groundSlam(player); }
            }
            case "storm_trident" -> { /* No custom right-click ability; vanilla trident behavior remains. */ }
        }
    }

    private ItemStack createNamedWeapon(Material material, String name, String type, boolean normal) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.LIGHT_PURPLE.toString() + ChatColor.BOLD + name);
        if (!normal) {
            meta.setLore(switch (type) {
                case "infernal_crossbow" -> List.of(ChatColor.GRAY + "A crossbow tempered in the Nether.",
                        ChatColor.GOLD + "Arrows fired from this crossbow ignite.");
                case "judgment_spear" -> List.of(ChatColor.GRAY + "A weapon of storm and impact.",
                        ChatColor.GOLD + "Hit: " + ChatColor.WHITE + "Lightning judgment (5 hearts)",
                        ChatColor.GOLD + "Sneak + right-click: " + ChatColor.WHITE + "Skyfall Ground Slam");
                case "storm_trident" -> List.of(ChatColor.GRAY + "The fury of the deep.",
                        ChatColor.AQUA + "Hit: " + ChatColor.WHITE + "Repeated lightning strikes");
                default -> List.of(ChatColor.GRAY + "A weapon that feeds on battle.",
                        ChatColor.RED + "Every third hit: " + ChatColor.WHITE + "Berserk for 15 seconds",
                        ChatColor.RED + "Passive: " + ChatColor.WHITE + "Destroys blocking shields");
            });
            meta.getPersistentDataContainer().set(weaponTypeKey, PersistentDataType.STRING, type);
            switch (type) {
                case "infernal_crossbow" -> {
                    addEnchant(meta, "multishot", 5);
                    addEnchant(meta, "quick_charge", 10);
                    addEnchant(meta, "unbreaking", 3);
                    addEnchant(meta, "mending", 1);
                }
                case "judgment_spear" -> {
                    addEnchant(meta, "sharpness", 5); addEnchant(meta, "smite", 5);
                    addEnchant(meta, "bane_of_arthropods", 5); addEnchant(meta, "fire_aspect", 2);
                    addEnchant(meta, "looting", 3); addEnchant(meta, "knockback", 2);
                    addEnchant(meta, "unbreaking", 3); addEnchant(meta, "mending", 1);
                    addEnchant(meta, "sweeping_edge", 3);
                    try {
                        meta.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(
                                new NamespacedKey(this, "judgment_infinite_attack_speed"),
                                1024.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
                    } catch (IllegalArgumentException ignored) { }
                }
                case "storm_trident" -> {
                    addEnchant(meta, "riptide", 5);
                    addEnchant(meta, "loyalty", 3); addEnchant(meta, "impaling", 5);
                    addEnchant(meta, "density", 5); addEnchant(meta, "breach", 4);
                    addEnchant(meta, "wind_burst", 3); addEnchant(meta, "unbreaking", 3);
                    addEnchant(meta, "mending", 1);
                }
                case "bloodreaver_axe" -> {
                    addEnchant(meta, "sharpness", 5); addEnchant(meta, "smite", 5);
                    addEnchant(meta, "bane_of_arthropods", 5); addEnchant(meta, "efficiency", 5);
                    addEnchant(meta, "fortune", 3); addEnchant(meta, "fire_aspect", 2);
                    addEnchant(meta, "unbreaking", 3); addEnchant(meta, "mending", 1);
                }
            }
        }
        item.setItemMeta(meta);
        return item;
    }

    private void addEnchant(ItemMeta meta, String key, int level) {
        org.bukkit.enchantments.Enchantment enchantment =
                org.bukkit.enchantments.Enchantment.getByKey(NamespacedKey.minecraft(key));
        if (enchantment != null) meta.addEnchant(enchantment, level, true);
    }

    private ItemStack createEclipseCore() {
        ItemStack item = new ItemStack(Material.SLIME_BALL);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.BLACK.toString() + ChatColor.BOLD + "Eclipse Core");
        meta.setLore(List.of(ChatColor.GRAY + "A sphere of living darkness.",
                ChatColor.DARK_PURPLE + "Right-click: " + ChatColor.WHITE + "Trap you and the nearest player in a black dome."));
        meta.getPersistentDataContainer().set(weaponTypeKey, PersistentDataType.STRING, "eclipse_core");
        item.setItemMeta(meta);
        return item;
    }


    private void groundSlam(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        long readyAt = groundSlamReadyAt.getOrDefault(id, 0L);
        if (readyAt > now) {
            player.sendActionBar(ChatColor.GOLD + "Ground Slam recharging: "
                    + String.format(java.util.Locale.US, "%.1fs", (readyAt - now) / 1000.0));
            return;
        }
        if (!groundSlamming.add(id)) return;
        groundSlamReadyAt.put(id, now + GROUND_SLAM_COOLDOWN_MS);
        player.sendActionBar(ChatColor.LIGHT_PURPLE + "SKYFALL GROUND SLAM!");
        player.setVelocity(new Vector(0, 1.65, 0));
        player.setFallDistance(0);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1, 0.8f);
        player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation(), 35, 0.7, 0.3, 0.7, 0.12);
        final int[] ticks = {0};
        Bukkit.getScheduler().runTaskTimer(this, task -> {
            if (!player.isOnline() || !player.isValid() || ticks[0]++ > 80) {
                groundSlamming.remove(id); task.cancel(); return;
            }
            player.setFallDistance(0);
            if (ticks[0] > 12) player.setVelocity(new Vector(player.getVelocity().getX(), -2.8, player.getVelocity().getZ()));
            if (ticks[0] > 12 && player.isOnGround()) {
                Location impact = player.getLocation();
                World world = player.getWorld();
                world.spawnParticle(Particle.EXPLOSION, impact, 2);
                world.spawnParticle(Particle.BLOCK, impact, 90, 2.2, 0.35, 2.2, 0.0, Material.DEEPSLATE.createBlockData());
                world.spawnParticle(Particle.CLOUD, impact, 65, 1.8, 0.4, 1.8, 0.16);
                world.playSound(impact, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.7f);
                for (Entity entity : world.getNearbyEntities(impact, 5, 3, 5)) {
                    if (entity instanceof LivingEntity target && entity != player) {
                        damageFromAbility(player, target, 8.0);
                        target.setVelocity(target.getVelocity().add(new Vector(0, 0.65, 0)));
                    }
                }
                player.setFallDistance(0);
                groundSlamming.remove(id); task.cancel();
            }
        }, 1L, 1L);
    }

    private void activateEclipseCore(Player player) {
        if (activeDomes.containsKey(player.getUniqueId())) return;
        Player target = player.getWorld().getPlayers().stream()
                .filter(other -> other != player && other.getLocation().distanceSquared(player.getLocation()) <= 24 * 24)
                .min(java.util.Comparator.comparingDouble(other -> other.getLocation().distanceSquared(player.getLocation())))
                .orElse(null);
        if (target == null) {
            player.sendMessage(ChatColor.RED + "No other player is within 24 blocks.");
            return;
        }
        Location center = player.getLocation().clone().add(target.getLocation()).multiply(0.5);
        double radius = Math.min(14.0, Math.max(5.0, player.getLocation().distance(target.getLocation()) / 2.0 + 3.0));
        DomeSession dome = new DomeSession(player.getUniqueId(), target.getUniqueId(), center, radius);
        activeDomes.put(player.getUniqueId(), dome);
        activeDomes.put(target.getUniqueId(), dome);
        player.sendTitle(ChatColor.BLACK + "" + ChatColor.BOLD + "ECLIPSE", ChatColor.DARK_PURPLE + "The void closes in", 5, 35, 10);
        target.sendTitle(ChatColor.BLACK + "" + ChatColor.BOLD + "ECLIPSE", ChatColor.DARK_PURPLE + "You are trapped", 5, 35, 10);
        World world = player.getWorld();
        world.playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1.4f, 0.55f);
        dome.task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!player.isOnline() || !target.isOnline() || !player.isValid() || !target.isValid()
                    || !player.getWorld().equals(world) || !target.getWorld().equals(world)) {
                endDome(dome); return;
            }
            for (int y = 0; y <= 16; y++) {
                double phi = Math.PI * y / 16.0;
                double ring = radius * Math.sin(phi);
                double height = radius * Math.cos(phi);
                for (int i = 0; i < 48; i++) {
                    double theta = Math.PI * 2 * i / 48.0;
                    Location p = center.clone().add(Math.cos(theta) * ring, height + radius, Math.sin(theta) * ring);
                    world.spawnParticle(Particle.DUST, p, 2, 0.025, 0.025, 0.025, 0.0,
                            new Particle.DustOptions(Color.fromRGB(5, 5, 8), 2.0f));
                    if (i % 3 == 0) world.spawnParticle(Particle.SMOKE, p, 1, 0.04, 0.04, 0.04, 0.005);
                }
            }
        }, 0L, 5L);
        Bukkit.getScheduler().runTaskLater(this, () -> endDome(dome), 240L);
    }

    private void endDome(DomeSession dome) {
        dome.task.cancel();
        activeDomes.remove(dome.first);
        activeDomes.remove(dome.second);
        Player first = Bukkit.getPlayer(dome.first);
        Player second = Bukkit.getPlayer(dome.second);
        if (first != null && first.isOnline()) first.sendMessage(ChatColor.GRAY + "The Eclipse dome fades.");
        if (second != null && second.isOnline()) second.sendMessage(ChatColor.GRAY + "The Eclipse dome fades.");
    }

    private static final class DomeSession {
        private final UUID first, second;
        private final Location center;
        private final double radius;
        private BukkitTask task;
        private DomeSession(UUID first, UUID second, Location center, double radius) {
            this.first = first; this.second = second; this.center = center; this.radius = radius;
        }
    }

    @EventHandler
    public void keepPlayersInsideEclipse(PlayerMoveEvent event) {
        DomeSession dome = activeDomes.get(event.getPlayer().getUniqueId());
        if (dome == null || event.getTo() == null) return;
        if (!event.getTo().getWorld().equals(dome.center.getWorld())) { event.setTo(event.getFrom()); return; }
        if (event.getTo().distanceSquared(dome.center) > dome.radius * dome.radius) {
            event.setTo(event.getFrom());
        }
    }

    @EventHandler
    public void keepPlayersInsideEclipseOnTeleport(PlayerTeleportEvent event) {
        DomeSession dome = activeDomes.get(event.getPlayer().getUniqueId());
        if (dome == null || event.getTo() == null) return;
        if (!event.getTo().getWorld().equals(dome.center.getWorld())
                || event.getTo().distanceSquared(dome.center) > dome.radius * dome.radius) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(ChatColor.DARK_PURPLE + "The Eclipse dome prevents escape!");
        }
    }

    private Material resolveSpearMaterial() {
        // Minecraft 1.21.11 adds material variants rather than a generic SPEAR.
        // Use the strongest vanilla spear so the weapon is unmistakably a spear.
        return Material.NETHERITE_SPEAR;
    }

    private boolean isInfernalCrossbow(ItemStack item) {
        if (item == null || item.getType() != Material.CROSSBOW || !item.hasItemMeta()) return false;
        return "infernal_crossbow".equals(item.getItemMeta().getPersistentDataContainer()
                .get(weaponTypeKey, PersistentDataType.STRING));
    }

    @EventHandler
    public void igniteInfernalCrossbowProjectiles(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Player shooter)
                || !(projectile instanceof org.bukkit.entity.AbstractArrow arrow)) return;
        // Crossbows can be fired from either hand. Checking both avoids silently
        // losing the passive when the weapon is equipped in the off hand.
        if (isInfernalCrossbow(shooter.getInventory().getItemInMainHand())
                || isInfernalCrossbow(shooter.getInventory().getItemInOffHand())) {
            arrow.setFireTicks(20 * 8);
        }
    }

    @EventHandler
    public void onAdditionalWeaponHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player) || !(event.getEntity() instanceof LivingEntity victim)) return;
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!held.hasItemMeta()) return;
        String type = held.getItemMeta().getPersistentDataContainer().get(weaponTypeKey, PersistentDataType.STRING);
        if (type == null || abilityDamageInProgress.contains(player.getUniqueId())) return;
        if (event.isCancelled() && !type.equals("bloodreaver_axe")) return;
        switch (type) {
            case "judgment_spear" -> {
                victim.getWorld().strikeLightningEffect(victim.getLocation());
                damageFromAbility(player, victim, 10.0);
                victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 1.1f);
            }
            case "storm_trident" -> {
                for (int i = 0; i < 5; i++) {
                    final int delay = i * 2;
                    Bukkit.getScheduler().runTaskLater(this, () -> {
                        if (victim.isValid() && !victim.isDead() && player.isOnline()) {
                            victim.getWorld().strikeLightningEffect(victim.getLocation());
                            damageFromAbility(player, victim, 3.0);
                        }
                    }, delay);
                }
            }
            case "bloodreaver_axe" -> {
                if (victim instanceof Player target && target.isBlocking()) {
                    EquipmentSlot hand = target.getActiveItemHand();
                    ItemStack shield = hand == EquipmentSlot.HAND ? target.getInventory().getItemInMainHand()
                            : target.getInventory().getItemInOffHand();
                    if (shield.getType() == Material.SHIELD) {
                        if (hand == EquipmentSlot.HAND) target.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
                        else target.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
                        target.clearActiveItem();
                        target.broadcastSlotBreak(hand);
                        target.getWorld().playSound(target.getLocation(), Sound.ITEM_SHIELD_BREAK, 1, 0.8f);
                    }
                }
                int hits = axeHitCounts.merge(player.getUniqueId(), 1, Integer::sum);
                if (hits % 3 == 0) {
                    player.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SPEED, 300, 2, true, true));
                    player.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.STRENGTH, 300, 2, true, true));
                    player.sendTitle(ChatColor.RED + "" + ChatColor.BOLD + "BERSERK", ChatColor.DARK_RED + "Speed III • Strength III", 5, 35, 10);
                    player.getWorld().playSound(player.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1.2f, 0.7f);
                }
            }
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
        groundSlamming.remove(playerId);
        groundSlamReadyAt.remove(playerId);
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
