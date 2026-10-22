package dev.flint.planetry.items;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Exercise the configuration readers on the files and snippets staff actually copy. */
class DocumentationExamplesTest {
    @TempDir Path temporary;

    @Test void completeStaffCatalogLoadsWithItsIntendedGameplaySettings() throws Exception {
        var catalog = ItemCatalog.load(Path.of("docs/examples/items"));
        assertEquals(Set.of("staff_crystal", "staff_blade", "staff_helmet", "staff_ration",
                "staff_keepsake", "staff_nexo_pickaxe"), catalog.definitions().keySet());
        assertEquals(64, catalog.get("staff_crystal").maxStackSize());
        assertEquals(0, catalog.get("staff_crystal").maxDurability());
        var blade = catalog.get("staff_blade");
        assertEquals(Map.of("planetry-gamelevel", 5, "auraskills-required-fighting", 10), blade.requirements());
        assertEquals(new ItemDefinition.AttributeSpec(0.1, "ADD_SCALAR", "MAINHAND"),
                blade.attributes().get("minecraft:movement_speed"));
        assertEquals("HEAD", catalog.get("staff_helmet").equipSlot());
        assertEquals(100.0, catalog.get("staff_helmet").bonuses().get("planetry-oxygen"));
        assertEquals("BREAD", catalog.get("staff_ration").material());
        assertEquals(0, catalog.get("staff_ration").maxDurability());
        assertTrue(catalog.get("staff_keepsake").unbreakable());
        assertFalse(catalog.get("staff_keepsake").canPlace());
        assertTrue(catalog.get("staff_keepsake").soulbound());
        assertEquals("your_loaded_nexo_pickaxe", catalog.get("staff_nexo_pickaxe").nexoId());
    }

    @Test void allReferenceSnippetsLoadWithTheirDocumentedPoliciesStatsAndMinimums() throws Exception {
        var matcher = Pattern.compile("(?m)^```yaml\\R([\\s\\S]*?)^```$")
                .matcher(Files.readString(Path.of("docs/CONFIGURATION.md")));
        var snippets = new java.util.ArrayList<ItemDefinition>();
        while (matcher.find()) {
            Path file = temporary.resolve("reference-" + snippets.size() + ".yml");
            Files.writeString(file, "items:\n  reference:\n" + matcher.group(1).indent(4));
            snippets.add(ItemCatalog.load(file).get("reference"));
        }
        assertEquals(4, snippets.size());
        assertFalse(snippets.get(0).canPlace());
        assertTrue(snippets.get(0).soulbound());
        assertEquals(new ItemDefinition.AttributeSpec(0.1, "ADD_SCALAR", "HAND"),
                snippets.get(1).attributes().get("minecraft:movement_speed"));
        assertEquals(Map.of("planetry-oxygen", 100.0, "auraskills-additional-wisdom", 2.0,
                "auraskills-additional-health", 1.0, "auraskills-additional-regeneration", 1.0,
                "auraskills-additional-toughness", 1.0, "auraskills-additional-strength", 3.0), snippets.get(2).bonuses());
        assertEquals(Map.of("planetry-gamelevel", 5, "auraskills-required-farming", 10,
                "auraskills-required-foraging", 10, "auraskills-required-mining", 10,
                "auraskills-required-fishing", 10, "auraskills-required-fighting", 10), snippets.get(3).requirements());
    }

    @Test void attributeReferenceListsEveryPinnedPaperAttributeExactlyOnce() throws Exception {
        var expected = java.util.Arrays.stream(org.bukkit.attribute.Attribute.class.getFields())
                .filter(field -> field.getType() == org.bukkit.attribute.Attribute.class)
                .map(field -> "minecraft:" + field.getName().toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        var matcher = Pattern.compile("(?m)^\\| `(minecraft:[a-z_]+)` \\|")
                .matcher(Files.readString(Path.of("docs/CONFIGURATION.md")));
        var documented = new java.util.HashSet<String>();
        while (matcher.find()) assertTrue(documented.add(matcher.group(1)), "Duplicate attribute: " + matcher.group(1));
        assertFalse(expected.isEmpty());
        assertEquals(expected, documented);
    }

    @Test void copiedFirstItemWalkthroughMatchesTheCompleteExample() throws Exception {
        Path file = temporary.resolve("staff.yml");
        Files.writeString(file, yamlBlock("docs/STAFF_GUIDE.md"));
        var tutorial = ItemCatalog.load(file);
        var complete = ItemCatalog.load(Path.of("docs/examples/items"));
        assertEquals(Set.of("staff_crystal"), tutorial.definitions().keySet());
        assertEquals(complete.get("staff_crystal"), tutorial.get("staff_crystal"));
    }

    @Test void copiedLanguageOverrideRendersLabelsValuesAndFallbackGlyphs() throws Exception {
        Path file = temporary.resolve("lang.yml");
        Files.writeString(file, yamlBlock("docs/LANGUAGE.md"));
        var language = ItemLanguage.load(file);
        var plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        assertEquals("Mining Level 10", language.requirementLabel("auraskills-required-mining", 10, null));
        assertEquals("RARE", plain.serialize(language.text("rarities.rare", Map.of(), null)));
        assertEquals("Received 2 × staff_crystal.", plain.serialize(language.text("messages.received",
                Map.of("amount", "2", "item", "staff_crystal"), null)));
        assertEquals("You cannot use this item: Mining Level 10!", plain.serialize(language.text(
                "messages.requirements-not-met", Map.of("requirements", "Mining Level 10"), null)));
        // Omitted keys still render bundled defaults, as promised for a partial override.
        assertEquals("PlanetryItems reloaded.", plain.serialize(language.text("messages.reloaded", Map.of(), null)));
    }

    private static String yamlBlock(String document) throws Exception {
        var matcher = Pattern.compile("(?m)^```yaml\\R([\\s\\S]*?)^```$").matcher(Files.readString(Path.of(document)));
        assertTrue(matcher.find(), "Missing copyable YAML in " + document);
        String yaml = matcher.group(1);
        assertFalse(matcher.find(), "Select additional YAML examples explicitly in " + document);
        return yaml;
    }
}
