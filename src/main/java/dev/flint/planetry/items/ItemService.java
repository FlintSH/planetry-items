package dev.flint.planetry.items;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.common.collect.HashMultimap;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.DataComponentType;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

/** Main-thread item API. Item identities and durability travel in persistent item data. */
public final class ItemService {
    private final PlanetryItems plugin;
    private final NamespacedKey identity, durability, oxygen, canPlace, soulbound, proxyGeneration;
    private static final Set<DataComponentType> DEFINITION_COMPONENTS = Set.of(
            DataComponentTypes.CUSTOM_NAME, DataComponentTypes.LORE, DataComponentTypes.MAX_STACK_SIZE,
            DataComponentTypes.UNBREAKABLE, DataComponentTypes.ATTRIBUTE_MODIFIERS, DataComponentTypes.MAX_DAMAGE,
            DataComponentTypes.DAMAGE, DataComponentTypes.CUSTOM_MODEL_DATA, DataComponentTypes.EQUIPPABLE,
            DataComponentTypes.ITEM_MODEL, DataComponentTypes.PROFILE, DataComponentTypes.DYED_COLOR,
            DataComponentTypes.TRIM, DataComponentTypes.TOOLTIP_DISPLAY);

    public ItemService(PlanetryItems plugin) {
        this.plugin = plugin;
        identity = new NamespacedKey(plugin, "item_id");
        durability = new NamespacedKey(plugin, "durability");
        oxygen = new NamespacedKey(plugin, "oxygen");
        canPlace = new NamespacedKey(plugin, "can_place");
        soulbound = new NamespacedKey(plugin, "soulbound");
        proxyGeneration = new NamespacedKey(plugin, "proxy_generation");
    }

    public void validate(ItemCatalog catalog) {
        for (var def : catalog.definitions().values()) {
            var material = Material.matchMaterial(def.material());
            if (material == null || material.isAir() || !material.isItem())
                throw new IllegalArgumentException(def.id() + ": material must be an item");
            if (present(def.base64()) && material != Material.PLAYER_HEAD)
                throw new IllegalArgumentException(def.id() + ": base64 needs material PLAYER_HEAD");
            for (var entry : def.attributes().entrySet()) {
                if (attribute(entry.getKey()) == null)
                    throw new IllegalArgumentException(def.id() + ": unknown Minecraft attribute " + entry.getKey());
                if (EquipmentSlotGroup.getByName(entry.getValue().slot().toLowerCase(Locale.ROOT)) == null)
                    throw new IllegalArgumentException(def.id() + ": invalid attribute slot");
            }
        }
    }

    public ItemStack create(String id, Player viewer) {
        var def = plugin.catalog().get(id);
        if (def == null) throw new IllegalArgumentException("Unknown item " + id);
        ItemStack stack = !present(def.nexoId()) ? new ItemStack(Objects.requireNonNull(Material.matchMaterial(def.material())))
                : plugin.hooks().nexoItem(def.nexoId()).clone();
        stack.setAmount(1);
        var meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(identity, PersistentDataType.STRING, id);
        if (present(def.base64())) {
            if (!(meta instanceof SkullMeta skull)) throw new IllegalArgumentException(id + ": Nexo item is not a player head");
            var profile = Bukkit.createProfile(UUID.nameUUIDFromBytes(def.base64().getBytes(StandardCharsets.UTF_8)));
            profile.setProperty(new ProfileProperty("textures", def.base64()));
            skull.setPlayerProfile(profile);
        }
        meta.getPersistentDataContainer().set(durability, PersistentDataType.INTEGER, def.maxDurability());
        stack.setItemMeta(meta);
        refresh(stack, viewer);
        return stack;
    }

    public String id(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return null;
        return stack.getItemMeta().getPersistentDataContainer().get(identity, PersistentDataType.STRING);
    }

    public ItemDefinition definition(ItemStack stack) {
        String id = id(stack);
        return id == null || awaitingProxyRevision(stack) ? null : plugin.catalog().get(id);
    }

    /** A stale backend must not apply older rules or clamp a newer item's saved durability. */
    public boolean awaitingProxyRevision(ItemStack stack) {
        return plugin.proxyManagedDefinitions() && id(stack) != null
                && stack.getItemMeta().getPersistentDataContainer().getOrDefault(proxyGeneration,
                        PersistentDataType.LONG, 0L) > plugin.proxyGeneration();
    }

    /** Current definitions take effect immediately, including on items not yet refreshed. */
    public boolean canPlace(ItemStack stack) {
        var def = definition(stack);
        return def == null ? savedFlag(stack, canPlace, true) : def.canPlace();
    }

    /** Removed IDs retain their last saved policy until normal unavailable-item removal. */
    public boolean soulbound(ItemStack stack) {
        var def = definition(stack);
        return def == null ? savedFlag(stack, soulbound, false) : def.soulbound();
    }

    private boolean savedFlag(ItemStack stack, NamespacedKey key, boolean fallback) {
        if (id(stack) == null) return fallback;
        return stack.getItemMeta().getPersistentDataContainer().getOrDefault(key,
                PersistentDataType.BYTE, (byte) (fallback ? 1 : 0)) != 0;
    }

    public int currentDurability(ItemStack stack) {
        var def = definition(stack);
        if (def == null) return 0;
        return Math.clamp(stack.getItemMeta().getPersistentDataContainer().getOrDefault(durability,
                PersistentDataType.INTEGER, def.maxDurability()), 0, def.maxDurability());
    }

    public Set<String> unmet(Player player, ItemDefinition def) {
        Set<String> result = new LinkedHashSet<>();
        for (var entry : def.requirements().entrySet()) {
            if (entry.getValue() <= 0) continue;
            Integer level = player == null ? null : entry.getKey().equals("planetry-gamelevel")
                    ? plugin.hooks().gameLevel(player)
                    : plugin.hooks().skillLevel(player, entry.getKey().substring("auraskills-required-".length()));
            if (level == null || level < entry.getValue()) result.add(entry.getKey());
        }
        return Collections.unmodifiableSet(result);
    }

    public boolean usable(Player player, ItemStack stack) {
        if (id(stack) == null) return true;
        var def = definition(stack);
        return def != null && plugin.hooks().inventoryReady(player) && unmet(player, def).isEmpty()
                && (def.unbreakable() || def.maxDurability() == 0 || currentDurability(stack) > 0);
    }

    /** Holding remains unrestricted; managed equipment belongs only in its configured worn slot. */
    public boolean canEquip(ItemStack stack, EquipmentSlot slot) {
        if (slot == EquipmentSlot.HAND || slot == EquipmentSlot.OFF_HAND || id(stack) == null) return true;
        var def = definition(stack);
        return def != null && slot.name().equals(def.equipSlot());
    }

    public void refresh(ItemStack stack, Player viewer) {
        var def = definition(stack);
        if (def == null) return;
        var meta = stack.getItemMeta();
        Set<String> unmet = unmet(viewer, def);
        meta.displayName(plugin.language().displayName(def, plugin.hooks().glyphs(viewer), viewer));
        meta.lore(plugin.language().lore(def, currentDurability(stack), unmet, plugin.hooks().glyphs(viewer), viewer));
        // A deferred migration must not make an existing oversized stack invalid.
        if (stack.getAmount() <= def.maxStackSize()) meta.setMaxStackSize(def.maxStackSize());
        // Native UNBREAKABLE also hides the durability bar. Breakable counters rely on
        // the PlayerItemDamageEvent guard to suppress automatic native wear instead.
        meta.setUnbreakable(def.unbreakable() || def.maxDurability() == 0);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);
        if (def.customModelData() != null) {
            var model = meta.getCustomModelDataComponent();
            model.setFloats(List.of(def.customModelData().floatValue()));
            meta.setCustomModelDataComponent(model);
        }
        if (def.equipSlot() != null && !def.equipSlot().isBlank() && stack.getAmount() == 1) {
            var equip = meta.getEquippable();
            equip.setSlot(EquipmentSlot.valueOf(def.equipSlot()));
            equip.setAllowedEntities(EntityType.PLAYER);
            equip.setDispensable(true);
            equip.setEquipOnInteract(true);
            equip.setSwappable(true);
            equip.setDamageOnHurt(false);
            meta.setEquippable(equip);
        } else meta.setEquippable(null);
        // Empty explicitly suppresses vanilla/Nexo defaults. Only configured modifiers apply.
        meta.setAttributeModifiers(HashMultimap.create());
        if (unmet.isEmpty() && (viewer == null || plugin.hooks().inventoryReady(viewer))
                && (def.unbreakable() || def.maxDurability() == 0 || currentDurability(stack) > 0)) {
            int index = 0;
            for (var entry : def.attributes().entrySet()) {
                var spec = entry.getValue();
                meta.addAttributeModifier(Objects.requireNonNull(attribute(entry.getKey())), new AttributeModifier(
                        new NamespacedKey(plugin, def.id() + "/attribute_" + index++), spec.amount(),
                        AttributeModifier.Operation.valueOf(spec.operation()),
                        Objects.requireNonNull(EquipmentSlotGroup.getByName(spec.slot().toLowerCase(Locale.ROOT)))));
            }
        }
        if (def.bonuses().containsKey("planetry-oxygen"))
            meta.getPersistentDataContainer().set(oxygen, PersistentDataType.DOUBLE, def.bonuses().get("planetry-oxygen"));
        else meta.getPersistentDataContainer().remove(oxygen);
        meta.getPersistentDataContainer().set(canPlace, PersistentDataType.BYTE, (byte) (def.canPlace() ? 1 : 0));
        meta.getPersistentDataContainer().set(soulbound, PersistentDataType.BYTE, (byte) (def.soulbound() ? 1 : 0));
        if (plugin.proxyManagedDefinitions())
            meta.getPersistentDataContainer().set(proxyGeneration, PersistentDataType.LONG, plugin.proxyGeneration());
        stack.setItemMeta(meta);
        // ItemMeta's null equippable restores material defaults (including heads and armour).
        // An explicit removal also suppresses those defaults and copied Nexo equipment.
        if (def.equipSlot() == null || stack.getAmount() != 1) stack.unsetData(DataComponentTypes.EQUIPPABLE);
        // Minecraft forbids MAX_DAMAGE with stack size >1, including unbreakable items.
        // A deferred migration can still retain an old oversized stack and capacity.
        if (stack.getMaxStackSize() > 1) {
            stack.unsetData(DataComponentTypes.MAX_DAMAGE);
            stack.unsetData(DataComponentTypes.DAMAGE);
        } else if (!def.unbreakable() && def.maxDurability() > 0) {
            // Elytra/tridents refuse their last native point before use. Scale their
            // bar equally so the final custom point stays usable without changing its ratio.
            boolean reservesLastPoint = stack.getType() == Material.ELYTRA || stack.getType() == Material.TRIDENT;
            int maximum = reservesLastPoint ? (int) Math.min(Integer.MAX_VALUE, def.maxDurability() * 2L)
                    : def.maxDurability();
            int spent = (int) ((def.maxDurability() - currentDurability(stack)) * (long) maximum / def.maxDurability());
            if (reservesLastPoint && currentDurability(stack) > 0) spent = Math.min(spent, maximum - 2);
            // The client bar also reads these components on PAPER and other custom bases.
            stack.setData(DataComponentTypes.MAX_DAMAGE, maximum);
            stack.setData(DataComponentTypes.DAMAGE, spent);
        } else {
            // Native capacity is still needed for gliding/charging, even without a custom counter.
            stack.resetData(DataComponentTypes.MAX_DAMAGE);
            stack.resetData(DataComponentTypes.DAMAGE);
        }
    }

    /** Rebuild definition-owned fields, preserving player data and never repairing durability. */
    public ItemStack migrate(ItemStack original, Player viewer) {
        var def = definition(original);
        if (def == null) throw new IllegalArgumentException("Unknown item " + id(original));
        ItemStack configured = create(def.id(), viewer);
        // Start from the instance, preserving containers, books, maps, bundles, stored enchants,
        // third-party data and native components which are not owned by the catalog.
        ItemStack replacement = original.withType(configured.getType());
        for (var type : DEFINITION_COMPONENTS) replacement.unsetData(type);
        replacement.copyDataFrom(configured, DEFINITION_COMPONENTS::contains);
        int remaining = currentDurability(original);
        replacement.editPersistentDataContainer(data -> {
            data.set(identity, PersistentDataType.STRING, def.id());
            data.set(durability, PersistentDataType.INTEGER, remaining);
            data.set(canPlace, PersistentDataType.BYTE, (byte) (def.canPlace() ? 1 : 0));
            data.set(soulbound, PersistentDataType.BYTE, (byte) (def.soulbound() ? 1 : 0));
            if (def.bonuses().containsKey("planetry-oxygen"))
                data.set(oxygen, PersistentDataType.DOUBLE, def.bonuses().get("planetry-oxygen"));
            else data.remove(oxygen);
        });
        replacement.setAmount(1);
        refresh(replacement, viewer);
        replacement.setAmount(original.getAmount());
        return replacement;
    }

    /** One successful use consumes exactly one point, independent of vanilla material damage rules. */
    public boolean damage(Player player, ItemStack stack, EquipmentSlot slot) {
        var def = definition(stack);
        if (def == null || def.unbreakable() || def.maxDurability() == 0) return false;
        int remaining = currentDurability(stack) - 1;
        if (remaining <= 0) {
            // Native equipment break packet produces normal client break particles/animation.
            player.broadcastSlotBreak(slot);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1, 1);
            // The native control-stick wrapper converts an exhausted stack into a fishing
            // rod and copies even an empty stack's patch. Retire its custom identity first.
            if (stack.getType() == Material.CARROT_ON_A_STICK || stack.getType() == Material.WARPED_FUNGUS_ON_A_STICK)
                stack.setItemMeta(null);
            stack.setAmount(0);
            return true;
        }
        var meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(durability, PersistentDataType.INTEGER, remaining);
        stack.setItemMeta(meta);
        refresh(stack, player);
        return false;
    }

    private static Attribute attribute(String name) {
        var key = NamespacedKey.fromString(name);
        return key == null ? null : Registry.ATTRIBUTE.get(key);
    }
    private static boolean present(String value) { return value != null && !value.isBlank(); }
}
