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

public final class SpearNoLimit extends JavaPlugin implements Listener {

    private NamespacedKey boostedKey;
    private NamespacedKey attackSpeedModifierKey;
    private Enchantment lungeEnchant;

    // Ticks since each player last landed a hit with a Lunge-enchanted spear.
    private final Map<UUID, Long> recentLungeHit = new HashMap<>();
    private static final long LUNGE_HUNGER_WINDOW_TICKS = 3L;

    @Override
    public void onEnable() {
        boostedKey = new NamespacedKey(this, "spear_speed_boosted");
        attackSpeedModifierKey = new NamespacedKey(this, "spear_attack_speed_boost");
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
        meta.addAttributeModifier(Attribute.GENERIC_ATTACK_SPEED, modifier);
        meta.getPersistentDataContainer().set(boostedKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
    }

    // ---------- Lunge hunger removal ----------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (lungeEnchant == null || !(event.getDamager() instanceof Player player)) {
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isSpear(item.getType())) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasEnchant(lungeEnchant)) {
            recentLungeHit.put(player.getUniqueId(), Bukkit.getCurrentTick());
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
}
