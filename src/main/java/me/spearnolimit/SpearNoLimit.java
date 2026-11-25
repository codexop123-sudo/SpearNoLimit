package me.spearnolimit;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

public final class SpearNoLimit extends JavaPlugin implements Listener {

    private NamespacedKey boostedKey;
    private NamespacedKey attackSpeedModifierKey;
    private Enchantment lungeEnchant;

    // Ticks since each player last landed a hit with a Lunge-enchanted spear.
    private final Map<UUID, Long> recentLungeHit = new HashMap<>();
    private static final long LUNGE_HUNGER_WINDOW_TICKS = 3L;
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

        // Periodically scan every online player's held item so a spear gets its
        // cooldown removed whenever it ends up in a player's hand, however it got there
        // (crafted, picked up, given, swapped from another slot, etc.).
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
            return; // Already boosted; don't stack another modifier on top.
        }

        // A large flat addition to attack speed makes the cooldown bar refill almost
        // instantly, so jabs (and with them, Lunge) can be thrown out back-to-back.
        AttributeModifier modifier = new AttributeModifier(
                attackSpeedModifierKey, 50.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HAND);
        meta.addAttributeModifier(Attribute.ATTACK_SPEED, modifier);
        meta.getPersistentDataContainer().set(boostedKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
    }

    // ---------- Lunge hunger removal ----------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (lungeEnchant == null || !(event.getDamager() instanceof Player player)) {
            return;
        }
        if (!selected.contains(player.getUniqueId())) {
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isSpear(item.getType())) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasEnchant(lungeEnchant)) {
            recentLungeHit.put(player.getUniqueId(), (long) Bukkit.getCurrentTick());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (event.getFoodLevel() >= player.getFoodLevel()) {
            return; // Not a decrease; nothing to do.
        }

        Long lastHit = recentLungeHit.get(player.getUniqueId());
        if (lastHit != null && (Bukkit.getCurrentTick() - lastHit) <= LUNGE_HUNGER_WINDOW_TICKS) {
            event.setCancelled(true);
        }
    }

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
