package me.spearnolimit;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class SpearNoLimit extends JavaPlugin implements Listener {

    private NamespacedKey boostedKey;
    private NamespacedKey attackSpeedModifierKey;
    private Enchantment lungeEnchant;

    // Ticks since each player last triggered a Lunge-enchanted spear (hit or air).
    private final Map<UUID, Long> recentLungeHit = new HashMap<>();
    private static final long LUNGE_HUNGER_WINDOW_TICKS = 10L;

    private File selectedFile;
    private final Set<UUID> selected = new HashSet<>();

    @Override
    public void onEnable() {
        boostedKey = new NamespacedKey(this, "spear_speed_boosted");
        attackSpeedModifierKey = new NamespacedKey(this, "spear_attack_speed_boost");
        selectedFile = new File(getDataFolder(), "selected.yml");
        loadSelected();

        lungeEnchant = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("lunge"));
        if (lungeEnchant == null) {
            getLogger().warning("Could not find the 'lunge' enchantment in this server's registry. "
                    + "The hunger-removal part of this plugin will not work until that's fixed.");
        }

        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::boostAllHeldSpears, 0L, 10L);

        getLogger().info("SpearNoLimit enabled.");
    }

    private void boostAllHeldSpears() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!selected.contains(player.getUniqueId())) {
                continue;
            }
            ItemStack item = player.getInventory().getItemInMainHand();
            if (isSpear(item.getType())) {
                boostIfNeeded(item);
            }
        }
    }

    private boolean isSpear(org.bukkit.Material material) {
        return material.name().endsWith("_SPEAR");
    }

    private void boostIfNeeded(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || meta.getPersistentDataContainer().has(boostedKey, PersistentDataType.BYTE)) {
            return;
        }

        AttributeModifier modifier = new AttributeModifier(
                attackSpeedModifierKey, 50.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HAND);
        meta.addAttributeModifier(Attribute.ATTACK_SPEED, modifier);
        meta.getPersistentDataContainer().set(boostedKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
    }

    // ---------- Lunge hunger removal (Hit + Air) ----------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            tryTriggerLungeProtection(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        tryTriggerLungeProtection(event.getPlayer());
    }

    private void tryTriggerLungeProtection(Player player) {
        if (lungeEnchant == null || !selected.contains(player.getUniqueId())) {
            return;
        }

        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isSpear(item.getType())) {
            return;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasEnchant(lungeEnchant)) {
            return;
        }

        recentLungeHit.put(player.getUniqueId(), (long) Bukkit.getCurrentTick());

        final int lockedFood = player.getFoodLevel();
        final float lockedSaturation = player.getSaturation();
        final UUID uuid = player.getUniqueId();

        new BukkitRunnable() {
            int ticksLeft = 8;

            @Override
            public void run() {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline() || ticksLeft-- <= 0) {
                    cancel();
                    return;
                }
                if (p.getFoodLevel() < lockedFood) {
                    p.setFoodLevel(lockedFood);
                }
                if (p.getSaturation() < lockedSaturation) {
                    p.setSaturation(lockedSaturation);
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (event.getFoodLevel() >= player.getFoodLevel()) {
            return;
        }

        Long lastHit = recentLungeHit.get(player.getUniqueId());
        if (lastHit != null && (Bukkit.getCurrentTick() - lastHit) <= LUNGE_HUNGER_WINDOW_TICKS) {
            event.setCancelled(true);
        }
    }

    // ---------- Commands & Persistence ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("spearnolimit")) {
            return false;
        }
        if (!sender.isOp()) {
            sender.sendMessage("You don't have permission to do that.");
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
            if (selected.isEmpty()) {
                sender.sendMessage("No players are currently selected.");
                return true;
            }
            StringBuilder sb = new StringBuilder();
            for (UUID id : selected) {
                String name = Bukkit.getOfflinePlayer(id).getName();
                sb.append(name != null ? name : id.toString()).append(", ");
            }
            sb.setLength(sb.length() - 2);
            sender.sendMessage("Selected players: " + sb);
            return true;
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("select") || args[0].equalsIgnoreCase("remove"))) {
            org.bukkit.OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
            if (!target.hasPlayedBefore() && !target.isOnline()) {
                sender.sendMessage("That player has never joined this server.");
                return true;
            }

            if (args[0].equalsIgnoreCase("select")) {
                selected.add(target.getUniqueId());
                saveSelected();
                sender.sendMessage(args[1] + " can now use the spear boost.");
            } else {
                selected.remove(target.getUniqueId());
                saveSelected();
                sender.sendMessage(args[1] + " can no longer use the spear boost.");
            }
            return true;
        }

        sender.sendMessage("Usage: /spearnolimit select <player> | remove <player> | list");
        return true;
    }

    private void loadSelected() {
        selected.clear();
        if (!selectedFile.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(selectedFile);
        for (String id : yaml.getStringList("selected")) {
            try {
                selected.add(UUID.fromString(id));
            } catch (IllegalArgumentException ignored) {
                // Skip any corrupted entry.
            }
        }
    }

    private void saveSelected() {
        YamlConfiguration yaml = new YamlConfiguration();
        List<String> ids = new ArrayList<>();
        for (UUID id : selected) {
            ids.add(id.toString());
        }
        yaml.set("selected", ids);
        try {
            if (!getDataFolder().exists()) {
                getDataFolder().mkdirs();
            }
            yaml.save(selectedFile);
        } catch (IOException e) {
            getLogger().severe("Could not save selected.yml: " + e.getMessage());
        }
    }
}
