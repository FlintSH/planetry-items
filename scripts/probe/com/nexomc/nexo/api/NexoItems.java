package com.nexomc.nexo.api;

import dev.flint.planetry.items.probe.NexoFixture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.key.Key;
import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** A controlled public Nexo API shape. Its metadata proves the actual builder stack was used. */
public final class NexoItems {
    public static Builder itemFromId(String id) {
        return "probe_texture".equals(id) && JavaPlugin.getPlugin(NexoFixture.class).available ? new Builder() : null;
    }
    public static final class Builder {
        public ItemStack build() {
            ItemStack stack = ItemStack.of(Material.AMETHYST_SHARD);
            stack.editMeta(meta -> {
                meta.displayName(Component.text("Nexo fixture name"));
                var model = meta.getCustomModelDataComponent();
                model.setFloats(java.util.List.of(991f));
                meta.setCustomModelDataComponent(model);
                meta.lore(java.util.List.of(Component.text("Nexo gameplay lore must not transfer")));
                meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(new NamespacedKey("nexo", "damage"), 100,
                        AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
                meta.getPersistentDataContainer().set(new NamespacedKey("nexo", "probe"), PersistentDataType.STRING, "builder-metadata");
            });
            stack.setData(DataComponentTypes.ITEM_MODEL, Key.key("nexo:probe_texture"));
            stack.setData(DataComponentTypes.EQUIPPABLE, io.papermc.paper.datacomponent.item.Equippable
                    .equippable(org.bukkit.inventory.EquipmentSlot.HEAD).build());
            stack.addUnsafeEnchantment(Enchantment.SHARPNESS, 10);
            return stack;
        }
    }
}
