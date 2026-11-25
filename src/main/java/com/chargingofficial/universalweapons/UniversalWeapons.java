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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Paper 1.21.11 implementation of the Corrupted Calamity weapon.
 */
public final class UniversalWeapons extends JavaPlugin implements Listener, CommandExecutor, org.bukkit.command.TabCompleter {
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
        getCommand("weapon").setTabCompleter(this);
        getCommand("stuck").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        // Keep Saturation refreshed while the custom Heaven's Judgment spear is held.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (isHeavensJudgment(online.getInventory().getItemInMainHand())
                        || isHeavensJudgment(online.getInventory().getItemInOffHand())) {
                    online.addPotionEffect(new PotionEffect(
                            PotionEffectType.SATURATION, 30, 0, true, false, false));
                }
            }
        }, 1L, 10L);
        // Detect every weapon ritual while a player stands on a command block.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (ritualTasks.containsKey(online.getUniqueId())) continue;
                String ritualType = getRitualType(online);
                if ("calamity".equals(ritualType)) beginCalamityRitual(online);
                else if (ritualType != null) beginWeaponRitual(online, ritualType);
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
        // Player location is the feet position. Sample several points below it
        // because collision resolution can leave the feet fractionally above
        // the command block's top surface.
        Location feet = player.getLocation();
        double[] offsets = {0.02, 0.12, 0.28, 0.48};
        for (double offset : offsets) {
            Block beneath = feet.clone().subtract(0.0, offset, 0.0).getBlock();
            Material type = beneath.getType();
            if (type == Material.COMMAND_BLOCK
                    || type == Material.CHAIN_COMMAND_BLOCK
                    || type == Material.REPEATING_COMMAND_BLOCK) {
                return beneath.getLocation();
            }
        }
        return null;
    }


    private String getRitualType(Player player) {
        if (getRitualBlock(player) == null) return null;
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!held.hasItemMeta()) return null;
        ItemMeta heldMeta = held.getItemMeta();
        String legacyName = heldMeta.hasDisplayName() ? heldMeta.getDisplayName() : "";
        String componentName = heldMeta.displayName() == null ? ""
                : LegacyComponentSerializer.legacySection().serialize(heldMeta.displayName());
        String name = ChatColor.stripColor(legacyName).trim();
        if (name.isEmpty()) name = ChatColor.stripColor(componentName).trim();
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "calamity" -> held.getType() == Material.MACE ? "calamity" : null;
            case "infernal" -> held.getType() == Material.CROSSBOW ? "infernal_crossbow" : null;
            case "judgment" -> held.getType() == resolveSpearMaterial() ? "judgment_spear" : null;
            case "storm" -> held.getType() == Material.TRIDENT ? "storm_trident" : null;
            case "berserker" -> held.getType() == Material.NETHERITE_AXE ? "bloodreaver_axe" : null;
            case "eclipse" -> held.getType() == Material.SLIME_BALL ? "eclipse_core" : null;
            default -> null;
        };
    }

    private ItemStack createRitualResult(String type) {
        return switch (type) {
            case "infernal_crossbow" -> createNamedWeapon(Material.CROSSBOW, "Infernal Requiem", type, false);
            case "judgment_spear" -> createNamedWeapon(resolveSpearMaterial(), "Heaven's Judgment", type, false);
            case "storm_trident" -> createNamedWeapon(Material.TRIDENT, "Poseidon's Wrath", type, false);
            case "bloodreaver_axe" -> createNamedWeapon(Material.NETHERITE_AXE, "Bloodreaver", type, false);
            case "eclipse_core" -> createEclipseCore();
            default -> null;
        };
    }

    private void beginWeaponRitual(Player player, String type) {
        UUID id = player.getUniqueId();
        Location startingBlock = getRitualBlock(player);
        if (startingBlock == null || ritualTasks.containsKey(id)) return;
        ritualBlocks.put(id, startingBlock.clone());
        World world = player.getWorld();
        String displayName = switch (type) {
            case "infernal_crossbow" -> "INFERNAL REQUIEM";
            case "judgment_spear" -> "HEAVEN'S JUDGMENT";
            case "storm_trident" -> "POSEIDON'S WRATH";
            case "bloodreaver_axe" -> "BLOODREAVER";
            case "eclipse_core" -> "ECLIPSE CORE";
            default -> "WEAPON";
        };
        player.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + displayName
                + ChatColor.YELLOW + " ritual has begun. Stay still and keep holding the item!");
        player.sendActionBar(ChatColor.GOLD + displayName + " AWAKENING 0%");
        world.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 0.7f);
        world.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 0.8f);

        BukkitRunnable ritual = new BukkitRunnable() {
            private int elapsedTicks = 0;
            private static final int DURATION_TICKS = 600;

            @Override
            public void run() {
                if (!player.isOnline() || !player.isValid() || player.isDead()
                        || !player.getWorld().equals(world)
                        || getRitualType(player) == null
                        || !type.equals(getRitualType(player))) {
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
                Location center = player.getLocation().add(0, 1.0, 0);
                int progress = Math.min(100, elapsedTicks * 100 / DURATION_TICKS);
                player.sendActionBar(ChatColor.GOLD + displayName + " "
                        + ChatColor.WHITE + progress + "% "
                        + ChatColor.YELLOW + "■".repeat(progress / 5)
                        + ChatColor.DARK_GRAY + "■".repeat(20 - progress / 5));

                Particle primary = switch (type) {
                    case "infernal_crossbow" -> Particle.FLAME;
                    case "judgment_spear" -> Particle.ELECTRIC_SPARK;
                    case "storm_trident" -> Particle.BUBBLE;
                    case "bloodreaver_axe" -> Particle.CRIMSON_SPORE;
                    case "eclipse_core" -> Particle.PORTAL;
                    default -> Particle.END_ROD;
                };
                Particle secondary = switch (type) {
                    case "infernal_crossbow" -> Particle.SOUL_FIRE_FLAME;
                    case "judgment_spear" -> Particle.END_ROD;
                    case "storm_trident" -> Particle.DRIPPING_WATER;
                    case "bloodreaver_axe" -> Particle.ASH;
                    case "eclipse_core" -> Particle.REVERSE_PORTAL;
                    default -> Particle.CRIT;
                };
                // Distinct choreography for every weapon: fire spiral, lightning lance,
                // ocean vortex, bloodstorm shockwaves, or imploding void.
                switch (type) {
                    case "infernal_crossbow" -> {
                        // Keep the fire spiral tight and light around the player.
                        for (int i=0;i<16;i++) {
                            double a=elapsedTicks*0.16+Math.PI*2*i/16.0, y=0.15+(i%8)*0.27;
                            double radius=1.6+(i%4)*0.38+progress*0.009;
                            Location p=center.clone().add(Math.cos(a)*radius,y,Math.sin(a)*radius);
                            world.spawnParticle(Particle.FLAME,p,2,0.08,0.12,0.08,0.018);
                            world.spawnParticle(Particle.SOUL_FIRE_FLAME,p,1,0.05,0.08,0.05,0.01);
                        }
                        // Scattered embers form a much wider perimeter around the ritual site.
                        if (elapsedTicks % 5 == 0) {
                            double outerRadius = 5.0 + progress * 0.035;
                            for (int i=0;i<32;i++) {
                                double a=Math.PI*2*i/32.0+elapsedTicks*0.012;
                                Location ember=center.clone().add(Math.cos(a)*outerRadius,
                                        -0.65+(i%4)*0.48,Math.sin(a)*outerRadius);
                                world.spawnParticle(Particle.FLAME,ember,2,0.22,0.18,0.22,0.025);
                                if (i%2==0) world.spawnParticle(Particle.SMOKE,ember,1,0.16,0.22,0.16,0.008);
                            }
                        }
                        world.spawnParticle(Particle.LAVA,center.clone().add(0,-0.65,0),3,0.7,0.06,0.7,0);
                        world.spawnParticle(Particle.SMOKE,center,12,0.6,0.8,0.6,0.025);
                        if(elapsedTicks%12==0) world.spawnParticle(Particle.FLASH,center.clone().add(0,1.8,0),1);
                    }
                    case "judgment_spear" -> {
                        for(int y=0;y<18;y++){
                            double h=y*0.19, a=elapsedTicks*0.10+y*0.32, radius=0.18+y*0.025;
                            Location p=center.clone().add(Math.cos(a)*radius,h-1.5,Math.sin(a)*radius);
                            world.spawnParticle(Particle.ELECTRIC_SPARK,p,5,0.12,0.16,0.12,0.08);
                            if(y%3==0) world.spawnParticle(Particle.END_ROD,p,3,0.04,0.12,0.04,0.02);
                        }
                        for(int i=0;i<12;i++){
                            double a=Math.PI*2*i/12.0+elapsedTicks*0.08;
                            Location p=center.clone().add(Math.cos(a)*2.8,0.1,Math.sin(a)*2.8);
                            world.spawnParticle(Particle.ELECTRIC_SPARK,p,5,0.12,0.35,0.12,0.12);
                        }
                        if(elapsedTicks%8==0) world.spawnParticle(Particle.FLASH,center.clone().add(0,2,0),1);
                    }
                    case "storm_trident" -> {
                        // Broad twin tidal rings rotate in opposite directions around the player.
                        for(int i=0;i<56;i++){
                            double a=elapsedTicks*0.075+Math.PI*2*i/56.0;
                            double radius=4.0+(i%7)*0.34+Math.sin(elapsedTicks*0.06+i)*0.24;
                            Location p=center.clone().add(Math.cos(a)*radius,
                                    -0.75+(i%10)*0.29,Math.sin(a)*radius);
                            world.spawnParticle(Particle.BUBBLE,p,4,0.18,0.14,0.18,0.035);
                            world.spawnParticle(Particle.DRIPPING_WATER,p,2,0.12,0.16,0.12,0.015);
                            if(i%2==0) {
                                Location outer=p.clone().add(Math.cos(a+Math.PI)*1.15,0.55,
                                        Math.sin(a+Math.PI)*1.15);
                                world.spawnParticle(Particle.SPLASH,outer,3,0.14,0.12,0.14,0.045);
                            }
                        }
                        // A rising geyser gives the center a stronger, unmistakable water column.
                        for(int column=0;column<8;column++){
                            double a=elapsedTicks*0.055+Math.PI*2*column/8.0;
                            Location base=center.clone().add(Math.cos(a)*1.45,-0.55,
                                    Math.sin(a)*1.45);
                            world.spawnParticle(Particle.BUBBLE,base,8,0.22,0.55,0.22,0.08);
                            world.spawnParticle(Particle.SPLASH,base.clone().add(0,1.4,0),
                                    6,0.25,0.28,0.25,0.06);
                        }
                        world.spawnParticle(Particle.SPLASH,center.clone().add(0,-0.55,0),24,1.0,0.10,1.0,0.06);
                        world.spawnParticle(Particle.DOLPHIN,center,16,1.2,1.4,1.2,0.06);
                        if(elapsedTicks%24==0) {
                            world.spawnParticle(Particle.ELECTRIC_SPARK,center,70,2.8,2.0,2.8,0.18);
                            world.playSound(center,Sound.ENTITY_DOLPHIN_SPLASH,0.8f,0.65f+progress/180.0f);
                        }
                    }
                    case "bloodreaver_axe" -> {
                        for(int i=0;i<28;i++){
                            double a=Math.PI*2*i/28.0, radius=0.8+((elapsedTicks/5+i)%9)*0.27;
                            Location p=center.clone().add(Math.cos(a)*radius,1.5-((elapsedTicks/3+i*2)%18)*0.13,Math.sin(a)*radius);
                            world.spawnParticle(Particle.CRIMSON_SPORE,p,5,0.12,0.18,0.12,0.01);
                            world.spawnParticle(Particle.ASH,p,2,0.16,0.12,0.16,0.01);
                        }
                        if(elapsedTicks%12==0){
                            double radius=1.0+(elapsedTicks%48)/48.0*2.8;
                            for(int i=0;i<40;i++){
                                double a=Math.PI*2*i/40.0;
                                Location p=center.clone().add(Math.cos(a)*radius,-0.72,Math.sin(a)*radius);
                                world.spawnParticle(Particle.CRIMSON_SPORE,p,3,0.08,0.04,0.08,0.01);
                                world.spawnParticle(Particle.SMOKE,p,1,0.05,0.04,0.05,0.005);
                            }
                        }
                        world.spawnParticle(Particle.ASH,center,36,1.3,1.2,1.3,0.02);
                    }
                    case "eclipse_core" -> {
                        for(int i=0;i<40;i++){
                            double a=Math.PI*2*i/40.0-elapsedTicks*0.07, radius=3.2-progress/100.0*1.4;
                            Location p=center.clone().add(Math.cos(a)*radius,-0.8+(i%10)*0.32,Math.sin(a)*radius);
                            world.spawnParticle(Particle.SQUID_INK,p,3,0.12,0.16,0.12,0.01);
                            world.spawnParticle(Particle.PORTAL,p,5,0.18,0.20,0.18,0.18);
                        }
                        world.spawnParticle(Particle.REVERSE_PORTAL,center,55,1.6,1.8,1.6,0.12);
                        world.spawnParticle(Particle.WITCH,center,20,0.8,1.2,0.8,0.02);
                        if(elapsedTicks%30==0) world.spawnParticle(Particle.SONIC_BOOM,center.clone().add(0,0.4,0),1);
                    }
                    default -> world.spawnParticle(Particle.END_ROD,center,12,0.8,1.0,0.8,0.03);
                }

                // Every 10% unlocks a stronger, weapon-specific burst with a wider radius.
                if (progress > 0 && progress % 10 == 0 && elapsedTicks % 6 == 0) {
                    int tier = progress / 10;
                    double spread = 1.8 + tier * 0.72;
                    Particle burstParticle = switch (type) {
                        case "infernal_crossbow" -> Particle.FLAME;
                        case "judgment_spear" -> Particle.ELECTRIC_SPARK;
                        case "storm_trident" -> Particle.SPLASH;
                        case "bloodreaver_axe" -> Particle.CRIMSON_SPORE;
                        case "eclipse_core" -> Particle.REVERSE_PORTAL;
                        default -> Particle.PORTAL;
                    };
                    Particle accentParticle = switch (type) {
                        case "infernal_crossbow" -> Particle.SOUL_FIRE_FLAME;
                        case "judgment_spear" -> Particle.END_ROD;
                        case "storm_trident" -> Particle.BUBBLE;
                        case "bloodreaver_axe" -> Particle.ASH;
                        case "eclipse_core" -> Particle.SQUID_INK;
                        default -> Particle.ELECTRIC_SPARK;
                    };
                    for (int i = 0; i < 24; i++) {
                        double direction = Math.PI * 2.0 * i / 24.0;
                        double distance = spread * (0.65 + (i % 4) * 0.12);
                        Location burstPoint = center.clone().add(
                                Math.cos(direction) * distance,
                                -0.65 + (i % 6) * 0.42,
                                Math.sin(direction) * distance);
                        world.spawnParticle(burstParticle, burstPoint, 2 + tier * 2,
                                0.10 + tier * 0.025, 0.12 + tier * 0.025,
                                0.10 + tier * 0.025, 0.025 + tier * 0.006);
                        world.spawnParticle(accentParticle, burstPoint, 1 + tier,
                                0.08 + tier * 0.02, 0.10 + tier * 0.02,
                                0.08 + tier * 0.02, 0.02 + tier * 0.004);
                    }
                    float chargePitch = 0.55f + tier * 0.095f;
                    world.playSound(center, Sound.BLOCK_BEACON_POWER_SELECT,
                            0.65f + tier * 0.035f, chargePitch);
                    world.playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE,
                            0.45f + tier * 0.045f, chargePitch);
                }

                // A visual-only lightning cadence builds anticipation without damage.
                if (elapsedTicks % 100 == 0 && elapsedTicks < DURATION_TICKS) {
                    double boltAngle = elapsedTicks * 0.17;
                    Location bolt = center.clone().add(Math.cos(boltAngle) * 2.2, 0,
                            Math.sin(boltAngle) * 2.2);
                    world.strikeLightningEffect(bolt);
                    world.spawnParticle(Particle.ELECTRIC_SPARK, bolt, 45, 0.55, 0.9, 0.55, 0.16);
                    world.playSound(center, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.75f, 1.15f);
                }
                if (elapsedTicks % 20 == 0) {
                    float pitch = 0.65f + progress / 180.0f;
                    world.playSound(center, Sound.BLOCK_BEACON_AMBIENT, 0.8f, pitch);
                    switch(type) {
                        case "infernal_crossbow" -> world.playSound(center, Sound.BLOCK_FIRE_AMBIENT, 0.9f, 0.75f+progress/250.0f);
                        case "judgment_spear" -> world.playSound(center, Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.4f);
                        case "storm_trident" -> world.playSound(center, Sound.WEATHER_RAIN, 0.65f, 0.8f);
                        case "bloodreaver_axe" -> world.playSound(center, Sound.ENTITY_ZOGLIN_ANGRY, 0.65f, 0.75f);
                        case "eclipse_core" -> world.playSound(center, Sound.BLOCK_PORTAL_AMBIENT, 0.8f, 0.5f);
                        default -> { }
                    }
                }
                if (elapsedTicks % 40 == 0) {
                    world.playSound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 0.65f+progress/160.0f);
                    world.playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.75f, 0.7f+progress/220.0f);
                    switch(type) {
                        case "infernal_crossbow" -> world.playSound(center, Sound.ITEM_FIRECHARGE_USE, 0.9f, 0.8f);
                        case "judgment_spear" -> world.playSound(center, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.7f, 1.3f);
                        case "storm_trident" -> world.playSound(center, Sound.ENTITY_DOLPHIN_SPLASH, 0.8f, 0.9f);
                        case "bloodreaver_axe" -> world.playSound(center, Sound.ENTITY_ZOGLIN_ANGRY, 0.7f, 0.8f);
                        case "eclipse_core" -> world.playSound(center, Sound.ENTITY_ENDERMAN_STARE, 0.8f, 0.6f);
                        default -> { }
                    }
                }

                if (elapsedTicks >= DURATION_TICKS) {
                    cancel();
                    ritualTasks.remove(id);
                    ritualBlocks.remove(id);
                    ItemStack result = createRitualResult(type);
                    if (result == null) {
                        player.sendMessage(ChatColor.RED + "Ritual failed: result item could not be created.");
                        return;
                    }
                    player.getInventory().setItemInMainHand(result);
                    world.spawnParticle(Particle.EXPLOSION, center, 2);
                    world.spawnParticle(primary, center, 180, 1.2, 1.5, 1.2, 0.12);
                    world.spawnParticle(secondary, center, 140, 1.0, 1.3, 1.0, 0.10);
                    // A five-point lightning finale: all bolts are visual-only and deal no damage.
                    for (int bolt = 0; bolt < 5; bolt++) {
                        double angle = Math.PI * 2.0 * bolt / 5.0;
                        Location strike = center.clone().add(Math.cos(angle) * 2.4, 0,
                                Math.sin(angle) * 2.4);
                        world.strikeLightningEffect(strike);
                        world.spawnParticle(Particle.ELECTRIC_SPARK, strike, 65,
                                0.55, 1.0, 0.55, 0.2);
                    }
                    world.strikeLightningEffect(center);
                    world.spawnParticle(Particle.FLASH, center, 8, 0.5, 0.65, 0.5, 0.0);
                    world.spawnParticle(Particle.ELECTRIC_SPARK, center, 240, 2.0, 2.2, 2.0, 0.24);
                    world.spawnParticle(Particle.END_ROD, center, 170, 2.0, 2.0, 2.0, 0.16);
                    world.spawnParticle(Particle.TOTEM_OF_UNDYING, center, 130, 1.8, 2.0, 1.8, 0.18);
                    for (int i = 0; i < 36; i++) {
                        double direction = Math.PI * 2.0 * i / 36.0;
                        double distance = 2.0 + (i % 8) * 0.95;
                        Location dispersal = center.clone().add(Math.cos(direction) * distance,
                                -0.8 + (i % 9) * 0.42, Math.sin(direction) * distance);
                        world.spawnParticle(primary, dispersal, 12, 0.22, 0.24, 0.22, 0.10);
                        world.spawnParticle(secondary, dispersal, 8, 0.18, 0.22, 0.18, 0.08);
                    }
                    world.spawnParticle(Particle.FLASH, center, 8, 0.5, 0.65, 0.5, 0.0);
                    world.playSound(center, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.4f, 0.85f);
                    world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.9f, 0.7f);
                    world.playSound(center, Sound.BLOCK_BEACON_POWER_SELECT, 1.2f, 0.6f);
                    world.playSound(center, Sound.ENTITY_PLAYER_LEVELUP, 1.2f, 0.7f);
                    switch (type) {
                        case "infernal_crossbow" -> world.playSound(center, Sound.ENTITY_BLAZE_DEATH, 1.2f, 0.75f);
                        case "judgment_spear" -> world.playSound(center, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.4f, 1.25f);
                        case "storm_trident" -> world.playSound(center, Sound.ENTITY_GENERIC_SPLASH, 1.3f, 0.7f);
                        case "bloodreaver_axe" -> world.playSound(center, Sound.ENTITY_ZOGLIN_ANGRY, 1.1f, 0.55f);
                        case "eclipse_core" -> world.playSound(center, Sound.ENTITY_ENDERMAN_TELEPORT, 1.2f, 0.55f);
                        default -> { }
                    }
                    player.sendTitle(ChatColor.GOLD + "" + ChatColor.BOLD + displayName + " AWAKENED",
                            ChatColor.YELLOW + "The weapon answers your call", 10, 50, 20);
                }
            }

            private void interrupt() {
                cancel();
                ritualTasks.remove(id);
                ritualBlocks.remove(id);
                if (player.isOnline()) player.sendActionBar(ChatColor.RED + "Ritual interrupted.");
            }
        };
        ritualTasks.put(id, ritual.runTaskTimer(this, 1L, 1L));
    }

    private boolean isRitualReady(Player player) {
        if (!player.isOnline() || !player.isValid() || player.isDead()) return false;

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
        Location opening = player.getLocation().add(0,1.0,0);
        world.playSound(opening, Sound.ENTITY_WITHER_SPAWN, 1.0f, 0.65f);
        world.playSound(opening, Sound.BLOCK_END_PORTAL_SPAWN, 0.9f, 0.55f);
        world.playSound(opening, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.7f, 0.55f);
        // Opening detonation and three expanding corrupted rune rings.
        world.spawnParticle(Particle.EXPLOSION,opening,3);
        world.spawnParticle(Particle.FLASH,opening,18,1.2,1.4,1.2,0.0);
        world.spawnParticle(Particle.DRAGON_BREATH,opening,260,2.8,2.2,2.8,0.14);
        world.spawnParticle(Particle.REVERSE_PORTAL,opening,320,3.2,2.4,3.2,0.22);
        world.spawnParticle(Particle.SOUL_FIRE_FLAME,opening,180,2.0,1.6,2.0,0.10);
        world.spawnParticle(Particle.ELECTRIC_SPARK,opening,220,2.4,2.0,2.4,0.18);
        world.spawnParticle(Particle.SONIC_BOOM,opening,2);
        for(int ring=0;ring<3;ring++){
            double radius=1.4+ring*1.15;
            for(int point=0;point<48;point++){
                double angle=Math.PI*2.0*point/48.0;
                Location spark=opening.clone().add(Math.cos(angle)*radius,-0.75+ring*0.75,Math.sin(angle)*radius);
                world.spawnParticle(Particle.PORTAL,spark,4,0.08,0.08,0.08,0.12);
                world.spawnParticle(Particle.SOUL_FIRE_FLAME,spark,2,0.04,0.08,0.04,0.01);
            }
        }
        world.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.2f, 0.5f);
        world.playSound(player.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 0.8f, 0.65f);
        world.playSound(player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.9f, 0.7f);

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

                // Once the ritual begins, don't repeatedly test the supporting block.
                // Player collision coordinates can fluctuate at the block boundary and
                // cancel the task even when the player appears to be standing still.
                // Keep the task alive while the player remains online, in this world,
                // and holds a mace; initial activation already verified the Calamity name.
                ItemStack currentHeld = player.getInventory().getItemInMainHand();
                if (currentHeld.getType() != Material.MACE) {
                    interrupt();
                    return;
                }

                elapsedTicks++;
                Location at = player.getLocation();
                int progress = Math.min(100, elapsedTicks * 100 / RITUAL_DURATION_TICKS);

                // Ten distinct corruption stages: each 10% expands the effect and raises the charge.
                if (progress > 0 && progress % 10 == 0 && elapsedTicks % 6 == 0) {
                    int tier = progress / 10;
                    double radius = 1.8 + tier * 0.78;
                    for (int i = 0; i < 32; i++) {
                        double direction = Math.PI * 2.0 * i / 32.0 + elapsedTicks * 0.024;
                        double distance = radius * (0.72 + (i % 5) * 0.07);
                        Location shard = at.clone().add(Math.cos(direction) * distance,
                                -0.75 + (i % 8) * 0.38,
                                Math.sin(direction) * distance);
                        world.spawnParticle(Particle.DRAGON_BREATH, shard, 2 + tier * 2,
                                0.10 + tier * 0.025, 0.12 + tier * 0.025,
                                0.10 + tier * 0.025, 0.035 + tier * 0.006);
                        world.spawnParticle(Particle.REVERSE_PORTAL, shard, 2 + tier,
                                0.08 + tier * 0.02, 0.10 + tier * 0.02,
                                0.08 + tier * 0.02, 0.05 + tier * 0.008);
                        if (tier >= 5) world.spawnParticle(Particle.ELECTRIC_SPARK, shard,
                                1 + tier / 2, 0.12, 0.14, 0.12, 0.08);
                    }
                    float pitch = 0.55f + tier * 0.095f;
                    world.playSound(at, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE,
                            0.55f + tier * 0.04f, pitch);
                    world.playSound(at, Sound.BLOCK_BEACON_POWER_SELECT,
                            0.65f + tier * 0.035f, pitch);
                    world.playSound(at, Sound.ENTITY_WITHER_AMBIENT,
                            0.35f + tier * 0.035f, 0.5f + tier * 0.07f);
                }

                // Live 20-segment progress bar, refreshed every tick.
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
                        world.spawnParticle(Particle.DRAGON_BREATH, burst, 700, 3.0, 2.4, 3.0, 0.16);
                        world.spawnParticle(Particle.PORTAL, burst, 900, 3.2, 2.6, 3.2, 0.34);
                        world.spawnParticle(Particle.REVERSE_PORTAL, burst, 600, 3.0, 2.4, 3.0, 0.26);
                        world.spawnParticle(Particle.SOUL_FIRE_FLAME, burst, 350, 1.4, 1.6, 1.4, 0.12);
                        world.spawnParticle(Particle.ELECTRIC_SPARK, burst, 350, 1.5, 1.6, 1.5, 0.18);
                        world.spawnParticle(Particle.END_ROD, burst, 250, 1.2, 1.6, 1.2, 0.18);
                        world.spawnParticle(Particle.SONIC_BOOM, burst, 3);
                        // Six surrounding bolts plus a central bolt; all are visual-only.
                        for (int bolt = 0; bolt < 6; bolt++) {
                            double a = Math.PI * 2.0 * bolt / 6.0;
                            Location strike = burst.clone().add(Math.cos(a) * 2.8, 0,
                                    Math.sin(a) * 2.8);
                            world.strikeLightningEffect(strike);
                            world.spawnParticle(Particle.ELECTRIC_SPARK, strike, 85,
                                    0.7, 1.2, 0.7, 0.22);
                        }
                        world.strikeLightningEffect(burst);
                        world.spawnParticle(Particle.FLASH, burst, 12, 0.4, 0.6, 0.4, 0.0);
                        world.spawnParticle(Particle.ELECTRIC_SPARK, burst, 240, 1.2, 1.5, 1.2, 0.24);
                        world.spawnParticle(Particle.TOTEM_OF_UNDYING, burst, 180, 1.1, 1.5, 1.1, 0.18);
                        world.spawnParticle(Particle.END_ROD, burst, 220, 1.0, 1.8, 1.0, 0.16);
                        world.playSound(burst, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.5f, 0.75f);
                        world.playSound(burst, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.65f);
                        world.playSound(burst, Sound.BLOCK_BEACON_POWER_SELECT, 1.3f, 0.5f);
                        world.playSound(burst, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1.0f, 0.8f);
                        world.playSound(burst, Sound.ENTITY_WARDEN_SONIC_BOOM, 0.9f, 1.15f);
                        world.playSound(burst, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 0.7f);
                        world.playSound(burst, Sound.ENTITY_WITHER_DEATH, 0.8f, 0.8f);
                        world.playSound(burst, Sound.BLOCK_END_PORTAL_SPAWN, 1.2f, 0.6f);
                        // Corrupted energy explodes out in 48 separate directions.
                        for (int i = 0; i < 48; i++) {
                            double direction = Math.PI * 2.0 * i / 48.0;
                            double distance = 1.5 + (i % 8) * 0.7;
                            Location shard = burst.clone().add(Math.cos(direction) * distance,
                                    -0.8 + (i % 10) * 0.42, Math.sin(direction) * distance);
                            world.spawnParticle(Particle.DRAGON_BREATH, shard, 14,
                                    0.22, 0.24, 0.22, 0.13);
                            world.spawnParticle(Particle.REVERSE_PORTAL, shard, 10,
                                    0.18, 0.22, 0.18, 0.16);
                            world.spawnParticle(Particle.SOUL_FIRE_FLAME, shard, 6,
                                    0.12, 0.16, 0.12, 0.08);
                        }
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
                double build = Math.min(2.35, 0.85 + elapsedTicks / 380.0);

                // Three dense rotating rings form a large, layered halo around the player.
                for (int i = 0; i < 32; i++) {
                    double a = angle + (Math.PI * 2.0 * i / 32.0);
                    double x = Math.cos(a);
                    double z = Math.sin(a);
                    Location groundRing = at.clone().add(x * 6.2 * build, 0.10, z * 6.2 * build);
                    Location waistRing = at.clone().add(x * 4.6 * build, 1.05, z * 4.6 * build);
                    Location crownRing = at.clone().add(x * 5.6 * build, 2.15, z * 5.6 * build);
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
                    double radius = 2.8 + Math.sin(height * 1.3) * 1.25;
                    Location vortex = at.clone().add(Math.cos(spiral) * radius,
                            0.10 + height, Math.sin(spiral) * radius);
                    world.spawnParticle(Particle.DRAGON_BREATH, vortex, 12, 0.28, 0.22, 0.28, 0.05);
                    world.spawnParticle(Particle.SOUL_FIRE_FLAME, vortex, 6, 0.16, 0.18, 0.16, 0.035);
                    world.spawnParticle(Particle.PORTAL, vortex, 14, 0.28, 0.22, 0.28, 0.18);
                    world.spawnParticle(Particle.ELECTRIC_SPARK, vortex, 3, 0.14, 0.16, 0.14, 0.04);
                }

                // A pulsing column and dense aura fill the space around the player.
                world.spawnParticle(Particle.DRAGON_BREATH, at.clone().add(0, 1.15, 0),
                        42, 1.8, 1.7, 1.8, 0.075);
                world.spawnParticle(Particle.PORTAL, at.clone().add(0, 1.1, 0),
                        56, 2.0, 1.8, 2.0, 0.22);
                world.spawnParticle(Particle.WITCH, at.clone().add(0, 1.0, 0),
                        38, 1.7, 1.6, 1.7, 0.08);
                world.spawnParticle(Particle.SOUL_FIRE_FLAME, at.clone().add(0, 0.9, 0),
                        30, 1.5, 1.4, 1.5, 0.06);

                // The ground circle expands in pulses beneath the ritualist.
                if (elapsedTicks % 8 == 0) {
                    double pulseRadius = 2.2 + (elapsedTicks % 32) / 32.0 * 4.6;
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
                    world.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                            0.65f, 0.55f + elapsedTicks / 1000.0f);
                }
                if (elapsedTicks % 100 == 0) {
                    world.playSound(at, Sound.ENTITY_WITHER_AMBIENT, 0.8f, 0.55f + progress / 200.0f);
                    world.playSound(at, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.9f, 0.65f + progress / 180.0f);
                    world.playSound(at.clone().add(4.0, 0.5, 0), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 0.7f);
                }

                // The aura becomes increasingly violent in the final third.
                if (elapsedTicks >= 400) {
                    double surge = 1.0 + (elapsedTicks - 400) / 40.0;
                    for (int i = 0; i < 8; i++) {
                        double a = angle * 1.8 + Math.PI * 2.0 * i / 8.0;
                        double y = 0.25 + ((elapsedTicks * 0.13 + i * 0.37) % 3.0);
                        Location surgePoint = at.clone().add(
                                Math.cos(a) * (2.0 + surge * 0.35), y,
                                Math.sin(a) * (2.0 + surge * 0.35));
                        world.spawnParticle(Particle.REVERSE_PORTAL, surgePoint,
                                7, 0.12, 0.16, 0.12, 0.12);
                        world.spawnParticle(Particle.SOUL_FIRE_FLAME, surgePoint,
                                4, 0.08, 0.12, 0.08, 0.035);
                        world.spawnParticle(Particle.ELECTRIC_SPARK, surgePoint,
                                3, 0.08, 0.10, 0.08, 0.05);
                    }
                    world.spawnParticle(Particle.DRAGON_BREATH, at.clone().add(0, 1.0, 0),
                            18 + (elapsedTicks - 80), 1.6, 1.5, 1.6, 0.08);
                }

                // Periodic visual-only lightning strikes around the Calamity ritual.
                if (elapsedTicks >= 100 && elapsedTicks < RITUAL_DURATION_TICKS
                        && elapsedTicks % 80 == 0) {
                    double boltAngle = elapsedTicks * 0.11;
                    Location bolt = at.clone().add(Math.cos(boltAngle) * 6.2, 0,
                            Math.sin(boltAngle) * 6.2);
                    world.strikeLightningEffect(bolt);
                    world.spawnParticle(Particle.ELECTRIC_SPARK, bolt, 75,
                            0.65, 1.1, 0.65, 0.2);
                    world.playSound(at, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1.2f, 0.65f);
                    world.playSound(bolt, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.9f, 0.9f);
                }

                // Final two seconds: frequent surges, stronger sound, and a bright pulse.
                if (elapsedTicks >= 500) {
                    if (elapsedTicks % 5 == 0) {
                        world.playSound(at, Sound.BLOCK_BEACON_ACTIVATE,
                                0.7f, 0.8f + (elapsedTicks - 500) * 0.007f);
                        world.playSound(at, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE,
                                0.8f, 0.75f + (elapsedTicks - 500) * 0.01f);
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
            case "slimeball", "eclipse" -> weapon = normal
                    ? createNamedWeapon(Material.SLIME_BALL, "Eclipse", "eclipse_core", true)
                    : createEclipseCore();
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


    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("weapon")) return List.of();
        if (args.length == 1) {
            return List.of("calamity", "crossbow", "spear", "trident", "axe", "slimeball")
                    .stream().filter(option -> option.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))).toList();
        }
        if (args.length == 2 && List.of("crossbow", "spear", "trident", "axe", "slimeball", "infernal", "judgment", "storm", "berserker", "eclipse")
                .contains(args[0].toLowerCase(java.util.Locale.ROOT))) {
            return List.of("normal").stream()
                    .filter(option -> option.startsWith(args[1].toLowerCase(java.util.Locale.ROOT))).toList();
        }
        return List.of();
    }

    private boolean isHeavensJudgment(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        return "judgment_spear".equals(item.getItemMeta().getPersistentDataContainer()
                .get(weaponTypeKey, PersistentDataType.STRING));
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
                if (player.isSneaking()) {
                    event.setCancelled(true);
                    groundSlam(player);
                }
                // Normal right-click is deliberately not cancelled: vanilla spear Lunge
                // enchantment must receive the interaction.
            }
            case "storm_trident" -> { /* No custom right-click ability; vanilla trident behavior remains. */ }
        }
    }

    private ItemStack createNamedWeapon(Material material, String name, String type, boolean normal) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        ChatColor nameColor = switch (type) {
            case "infernal_crossbow" -> ChatColor.RED;
            case "judgment_spear" -> ChatColor.AQUA;
            case "storm_trident" -> ChatColor.BLUE;
            case "bloodreaver_axe" -> ChatColor.DARK_RED;
            case "eclipse_core" -> ChatColor.BLACK;
            default -> ChatColor.LIGHT_PURPLE;
        };
        meta.setDisplayName(nameColor.toString() + ChatColor.BOLD + name);
        if (!normal) {
            meta.setLore(switch (type) {
                case "infernal_crossbow" -> List.of(ChatColor.GRAY + "A crossbow tempered in the Nether.",
                        ChatColor.GOLD + "Arrows fired from this crossbow ignite.");
                case "judgment_spear" -> List.of(ChatColor.GRAY + "A weapon of storm and impact.",
                        ChatColor.AQUA + "Passive: " + ChatColor.WHITE + "Infinite Saturation while held",
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
                    addEnchant(meta, "lunge", 3);
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
        if (!(event.getEntity() instanceof LivingEntity victim)) return;

        Player player = null;
        String type = null;
        if (event.getDamager() instanceof Player attacker) {
            player = attacker;
            ItemStack held = attacker.getInventory().getItemInMainHand();
            if (held.hasItemMeta()) {
                type = held.getItemMeta().getPersistentDataContainer()
                        .get(weaponTypeKey, PersistentDataType.STRING);
            }
        } else if (event.getDamager() instanceof org.bukkit.entity.Trident thrown
                && thrown.getShooter() instanceof Player shooter) {
            ItemStack thrownItem = thrown.getItemStack();
            if (thrownItem.hasItemMeta()) {
                type = thrownItem.getItemMeta().getPersistentDataContainer()
                        .get(weaponTypeKey, PersistentDataType.STRING);
                player = shooter;
            }
        }
        if (player == null || type == null
                || abilityDamageInProgress.contains(player.getUniqueId())) return;
        if (event.isCancelled() && !type.equals("bloodreaver_axe")) return;
        switch (type) {
            case "judgment_spear" -> {
                victim.getWorld().strikeLightningEffect(victim.getLocation());
                damageFromAbility(player, victim, 10.0);
                victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 1.1f);
            }
            case "storm_trident" -> {
                // One immediate lightning hit plus four rapid follow-ups. The ability
                // damage guard prevents these bonus hits from recursively retriggering.
                final Player tridentOwner = player;
                final LivingEntity tridentVictim = victim;
                for (int i = 0; i < 5; i++) {
                    final int delay = i * 2;
                    Bukkit.getScheduler().runTaskLater(this, () -> {
                        if (tridentVictim.isValid() && !tridentVictim.isDead() && tridentOwner.isOnline()
                                && tridentOwner.getWorld().equals(tridentVictim.getWorld())) {
                            Location strikeAt = tridentVictim.getLocation();
                            tridentVictim.getWorld().strikeLightningEffect(strikeAt);
                            damageFromAbility(tridentOwner, tridentVictim, 3.0);
                            tridentVictim.getWorld().playSound(strikeAt,
                                    Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.65f, 1.35f);
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
