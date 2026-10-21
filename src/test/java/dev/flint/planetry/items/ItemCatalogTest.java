package dev.flint.planetry.items;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ItemCatalogTest {
    @TempDir Path directory;

    @Test void defaultsAreImmutableAndDoNotInventBonuses() throws Exception {
        var catalog = load("items:\n  rock:\n    material: STONE\n");
        var item = catalog.get("rock");
        assertEquals(100, item.maxDurability());
        assertEquals(1, item.maxStackSize());
        assertEquals("COMMON", item.rarity());
        assertEquals("ITEM", item.type());
        assertEquals("", item.nexoId());
        assertEquals("", item.base64());
        assertTrue(item.lore().isEmpty());
        assertTrue(item.attributes().isEmpty());
        assertTrue(item.bonuses().isEmpty());
        assertTrue(item.requirements().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> catalog.definitions().clear());
        assertThrows(UnsupportedOperationException.class, () -> item.bonuses().put("planetry-oxygen", 1.0));
        assertThrows(UnsupportedOperationException.class, () -> item.lore().add("changed"));
    }

    @Test void allExampleItemsLoad() throws Exception {
        Path example = directory.resolve("examples.yml");
        try (var input = getClass().getResourceAsStream("/items.yml")) {
            assertNotNull(input);
            Files.copy(input, example);
        }
        var catalog = ItemCatalog.load(example);
        assertEquals(4, catalog.definitions().size());
        assertEquals("HEAD", catalog.get("explorer_helmet").equipSlot());
        assertEquals(3.0, catalog.get("explorer_helmet").attributes().get("minecraft:armor").amount());
        assertEquals(100.0, catalog.get("explorer_helmet").bonuses().get("planetry-oxygen"));
        assertEquals(10, catalog.get("moonblade").requirements().get("auraskills-required-fighting"));
    }

    @Test void normalizesAliasesAndUsesWearableAttributeSlots() throws Exception {
        var item = load("""
                items:
                  hat:
                    material: PAPER
                    type: cosmetic
                    equip-slot: HEAD
                    bonuses:
                      minecraft-armor: 3
                      planetry-oxygen: 10
                    attributes:
                      attack-speed:
                        amount: 0.25
                        operation: add_scalar
                        slot: hand
                      minecraft:movement_speed: 0.1
                """).get("hat");
        assertEquals("HEAD", item.attributes().get("minecraft:armor").slot());
        assertEquals("HEAD", item.attributes().get("minecraft:movement_speed").slot());
        assertEquals(new ItemDefinition.AttributeSpec(0.25, "ADD_SCALAR", "HAND"), item.attributes().get("minecraft:attack_speed"));
        assertFalse(item.bonuses().containsKey("minecraft-armor"));
    }

    @Test void acceptsMultilineLoreAndBlankOptionalValues() throws Exception {
        var item = load("""
                items:
                  rock:
                    lore: |
                      first
                      second
                    bonuses:
                      planetry-oxygen:
                      auraskills-additional-wisdom: ''
                    requirements:
                      auraskills-required-mining:
                    attributes:
                      minecraft:armor:
                """).get("rock");
        assertEquals(List.of("first", "second", ""), item.lore());
        assertTrue(item.attributes().isEmpty());
        assertTrue(item.requirements().isEmpty());
        assertTrue(item.bonuses().isEmpty());
    }

    @Test void supportsNoDurabilityOrUnbreakableStacks() throws Exception {
        assertEquals(64, load("items:\n  rock:\n    max-stack-size: 64\n    max-durability: 0\n").get("rock").maxStackSize());
        assertEquals(16, load("items:\n  rock:\n    max-stack-size: 16\n    unbreakable: true\n").get("rock").maxStackSize());
    }

    @Test void headTextureDefaultsToPlayerHead() throws Exception {
        assertEquals("PLAYER_HEAD", load("items:\n  head:\n    base64: e30=\n").get("head").material());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "max-stack-size: 0", "max-stack-size: 100", "max-stack-size: 2", "max-stack-size: 1.5",
            "max-durability: -1", "max-durability: 2147483648", "max-durability: .nan",
            "max-durability: '100'", "unbreakable: 'yes'", "rarity: SUPER", "type: ARMOR",
            "material: NOT_A_MATERIAL", "material: AIR", "unknown-setting: 1", "lore: [1]",
            "equip-slot: HEAD", "custom-model-data: -1", "base64: 'not base64'",
            "bonuses: {planetry-oxygen: -1}", "bonuses: {auraskills-additional-health: .inf}",
            "bonuses: {unrecognized: 2}", "requirements: {planetry-gamelevel: -1}",
            "requirements: {auraskills-required-mining: 1.5}", "requirements: {unrecognized: 2}",
            "attributes: {minecraft:armor: {amount: 1, operation: INVALID}}",
            "attributes: {minecraft:armor: {amount: 1, slot: INVALID}}",
            "attributes: {minecraft:armor: {amount: 1, typo: 2}}",
            "attributes: {minecraft:armor: {operation: ADD_NUMBER}}",
            "attributes: {other:armor: 1}"
    })
    void rejectsInvalidDefinitions(String settings) {
        assertThrows(IllegalArgumentException.class, () -> load("items:\n  rock:\n    " + settings + "\n"));
    }

    @Test void rejectsDuplicateYamlAndNormalizedAttributes() {
        assertThrows(IllegalArgumentException.class, () -> load("items:\n  rock: {}\n  rock: {}\n"));
        assertThrows(IllegalArgumentException.class, () -> load("items:\n  rock:\n    rarity: RARE\n    rarity: EPIC\n"));
        assertThrows(IllegalArgumentException.class, () -> load("items:\n  rock:\n    attributes: {armor: 1, 'minecraft:armor': 2}\n"));
        assertThrows(IllegalArgumentException.class, () -> load("items:\n  rock:\n    attributes: {armor: 1}\n    bonuses: {minecraft-armor: 2}\n"));
    }

    @Test void rejectsIncompatibleSourcesAndWearableStacks() {
        assertThrows(IllegalArgumentException.class, () -> load("items:\n  rock:\n    base64: e30=\n    nexo-id: rock\n"));
        assertThrows(IllegalArgumentException.class, () -> load("items:\n  rock:\n    material: PAPER\n    base64: e30=\n"));
        assertThrows(IllegalArgumentException.class, () -> load("items:\n  rock:\n    type: ARMOUR\n    max-stack-size: 2\n    max-durability: 0\n"));
        assertThrows(IllegalArgumentException.class, () -> load("items:\n  rock:\n    type: ARMOUR\n    equip-slot: BODY\n"));
    }

    @Test void rejectsUnsafeYamlMalformedIdsAndUnsupportedSchemas() {
        assertThrows(IllegalArgumentException.class, () -> load("items: !!java.util.TreeMap {}\n"));
        assertThrows(IllegalArgumentException.class, () -> load("schema-version: 2\nitems: {}\n"));
        assertThrows(IllegalArgumentException.class, () -> load("items:\n  UpperCase: {}\n"));
        assertThrows(IllegalArgumentException.class, () -> load("items: []\n"));
    }

    @Test void recordDefensivelyCopiesInputCollections() {
        var mutable = new java.util.LinkedHashMap<>(Map.of("planetry-oxygen", 5.0));
        var item = new ItemDefinition("id", "STONE", "name", List.of(), null, null, null, "COMMON", "ITEM", 1, 100, false, null,
                mutable, Map.of(), Map.of());
        mutable.clear();
        assertEquals(5.0, item.bonuses().get("planetry-oxygen"));
    }

    private ItemCatalog load(String content) throws Exception {
        Path file = directory.resolve("items.yml");
        Files.writeString(file, content);
        return ItemCatalog.load(file);
    }
}
