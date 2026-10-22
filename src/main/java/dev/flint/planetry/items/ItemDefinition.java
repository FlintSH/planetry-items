package dev.flint.planetry.items;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Immutable, validated configuration; no player or live Bukkit state is retained. */
public record ItemDefinition(
        String id, String material, String displayName, List<String> lore,
        Integer customModelData, String nexoId, String base64, String rarity,
        String type, int maxStackSize, int maxDurability, boolean unbreakable,
        String equipSlot, Map<String, Double> bonuses,
        Map<String, Integer> requirements, Map<String, AttributeSpec> attributes,
        boolean canPlace, boolean soulbound) {

    /** Retain the original constructor for addons; omitted policy flags keep their defaults. */
    public ItemDefinition(String id, String material, String displayName, List<String> lore,
                          Integer customModelData, String nexoId, String base64, String rarity,
                          String type, int maxStackSize, int maxDurability, boolean unbreakable,
                          String equipSlot, Map<String, Double> bonuses,
                          Map<String, Integer> requirements, Map<String, AttributeSpec> attributes) {
        this(id, material, displayName, lore, customModelData, nexoId, base64, rarity, type,
                maxStackSize, maxDurability, unbreakable, equipSlot, bonuses, requirements, attributes, true, false);
    }

    public ItemDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(rarity, "rarity");
        Objects.requireNonNull(type, "type");
        nexoId = nexoId == null ? "" : nexoId;
        base64 = base64 == null ? "" : base64;
        lore = List.copyOf(lore);
        bonuses = immutableMap(bonuses);
        requirements = immutableMap(requirements);
        attributes = immutableMap(attributes);
    }

    private static <V> Map<String, V> immutableMap(Map<String, V> input) {
        Objects.requireNonNull(input, "map");
        var copy = new LinkedHashMap<String, V>();
        input.forEach((key, value) -> copy.put(Objects.requireNonNull(key), Objects.requireNonNull(value)));
        return Collections.unmodifiableMap(copy);
    }

    public record AttributeSpec(double amount, String operation, String slot) {
        public AttributeSpec {
            if (!Double.isFinite(amount)) {
                throw new IllegalArgumentException("Attribute amount must be finite");
            }
            operation = Objects.requireNonNull(operation, "operation").toUpperCase(Locale.ROOT);
            slot = Objects.requireNonNull(slot, "slot").toUpperCase(Locale.ROOT);
        }
    }
}
