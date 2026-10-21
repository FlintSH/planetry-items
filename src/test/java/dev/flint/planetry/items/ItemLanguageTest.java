package dev.flint.planetry.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ItemLanguageTest {
    @TempDir Path directory;
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Test void minimalLoreHasExactlyOneBlankLine() throws Exception {
        var item = item("max-durability: 0");
        assertEquals(List.of("ɪᴛᴇᴍ", "", "COMMON"), plain(language("").lore(item, 0, Set.of(), null)));
    }

    @Test void ordersSectionsAndOnlyShowsUnmetRequirements() throws Exception {
        var item = item("""
                type: WEAPON
                lore: [first, second]
                rarity: RARE
                attributes:
                  minecraft:attack_damage: 7
                bonuses:
                  planetry-oxygen: 4
                requirements:
                  auraskills-required-mining: 3
                  planetry-gamelevel: 5
                """);
        var lang = language("");
        var lines = plain(lang.lore(item, 80, Set.of("auraskills-required-mining"), null));
        assertEquals(List.of("ᴡᴇᴀᴘᴏɴ", "", "first", "second", "", "[🗡] - Attack Damage +7", "[🫧] Oxygen +4", "",
                "[☆] Requires Mining Level 3", "", "RARE", "Durability: 80/100"), lines);
        assertTrue(plain(lang.lore(item, 80, Set.of(), null)).stream().noneMatch(line -> line.contains("Requires")));
    }

    @Test void doesNotInventWhitespaceForBlankFlavorAndSections() throws Exception {
        var item = item("lore: ['', ' ', '']\nmax-durability: 0\nbonuses: {}\nrequirements: {}\n");
        assertEquals(List.of("ɪᴛᴇᴍ", "", "COMMON"), plain(language("").lore(item, 0, Set.of(), null)));
    }

    @Test void marksOnlyLowRemainingDurabilityRedAndClampsValues() throws Exception {
        var item = item("");
        var lang = language("");
        var low = lang.lore(item, 10, Set.of(), null).getLast();
        assertEquals("Durability: 10/100", PLAIN.serialize(low));
        assertTrue(hasColor(low, NamedTextColor.RED));
        assertFalse(hasColor(lang.lore(item, 11, Set.of(), null).getLast(), NamedTextColor.RED));
        assertEquals("Durability: 0/100", PLAIN.serialize(lang.lore(item, -10, Set.of(), null).getLast()));
        assertEquals("Durability: 100/100", PLAIN.serialize(lang.lore(item, 200, Set.of(), null).getLast()));
    }

    @Test void unbreakableLoreReplacesNumericDurability() throws Exception {
        var item = item("unbreakable: true");
        assertEquals("Unbreakable", PLAIN.serialize(language("").lore(item, 50, Set.of(), null).getLast()));
    }

    @Test void finalDurabilityPointIsRedEvenForSmallMaximums() throws Exception {
        var item = item("max-durability: 3");
        var lang = language("");
        assertTrue(hasColor(lang.lore(item, 1, Set.of(), null).getLast(), NamedTextColor.RED));
        assertFalse(hasColor(lang.lore(item, 2, Set.of(), null).getLast(), NamedTextColor.RED));
    }

    @Test void valuesCannotInjectMiniMessageOrRecursivePlaceholders() throws Exception {
        var lang = language("");
        var rendered = lang.render("<green>Hello {player}, {amount}", Map.of("player", "<red>Bad</red> {amount}", "amount", "2"), null);
        assertEquals("Hello <red>Bad</red> {amount}, 2", PLAIN.serialize(rendered));
        assertFalse(hasColor(rendered, NamedTextColor.RED));
        assertEquals(TextDecoration.State.FALSE, rendered.decoration(TextDecoration.ITALIC));
    }

    @Test void missingValuesStayLiteralAndExplicitItalicsRemainSupported() throws Exception {
        var rendered = language("").render("<italic>{unknown}", Map.of(), null);
        assertEquals("{unknown}", PLAIN.serialize(rendered));
        assertEquals(TextDecoration.State.TRUE, rendered.decoration(TextDecoration.ITALIC));
    }

    @Test void resolvesGlyphsAndFallsBackWhenNexoIsAbsent() throws Exception {
        var lang = language("glyph-fallbacks:\n  tag_common: '<gold>Common fallback'\n");
        assertEquals("Common fallback", PLAIN.serialize(lang.render("<glyph:tag_common>", Map.of(), null)));
        assertEquals("Common fallback", PLAIN.serialize(lang.render("<glyph:tag_common>", Map.of(), ignored -> Component.empty())));
        assertEquals("NEXO", PLAIN.serialize(lang.render("<glyph:tag_common>", Map.of(), ignored -> Component.text("NEXO"))));
    }

    @Test void allowsCustomAttributePrefixesAndPercentages() throws Exception {
        var lang = language("attributes:\n  minecraft-attack-speed: '<blue>Speed {value}{unit}'\n");
        var item = item("attributes:\n  minecraft:attack_speed: {amount: 0.125, operation: ADD_SCALAR}\n  minecraft:scale: -0.5\n");
        var lines = plain(lang.lore(item, 100, Set.of(), null));
        assertTrue(lines.contains("Speed +12.5%"));
        assertTrue(lines.contains("Scale: -0.5"));
    }

    @Test void rejectsDuplicateOrNonStringLanguageEntries() {
        assertThrows(IllegalArgumentException.class, () -> language("messages:\n  usage: one\n  usage: two\n"));
        assertThrows(IllegalArgumentException.class, () -> language("messages:\n  usage: [one, two]\n"));
        assertThrows(IllegalArgumentException.class, () -> language("messages.usage: one\nmessages:\n  usage: two\n"));
        assertThrows(IllegalArgumentException.class, () -> language("schema-version: 2\n"));
    }

    @Test void rejectsMalformedGlyphTemplatesBeforeReloadCanCommit() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> language("rarities:\n  common: '<glyph>'\n"));
        assertTrue(error.getMessage().contains("lang.yml.rarities.common"));
        assertThrows(IllegalArgumentException.class, () -> language("rarities:\n  common: '<glyph:>'\n"));
        assertThrows(IllegalArgumentException.class, () -> language("rarities:\n  common: '<glyph:tag_common:extra>'\n"));
        assertThrows(IllegalArgumentException.class, () -> language("rarities:\n  common: '<glyph:tag_common'\n"));
    }

    @Test void acceptsQuotedGlyphIdsAndEscapedLiteralTags() throws Exception {
        var lang = language("");
        assertEquals("tag_common", PLAIN.serialize(lang.render("<glyph:'tag_common'>", Map.of(), Component::text)));
        assertEquals("<glyph>", PLAIN.serialize(lang.render("\\<glyph>", Map.of(), null)));
    }

    private ItemLanguage language(String content) throws Exception {
        Path file = directory.resolve("lang.yml");
        Files.writeString(file, content.isEmpty() ? "{}\n" : content);
        return ItemLanguage.load(file);
    }

    private ItemDefinition item(String content) throws Exception {
        Path file = directory.resolve("items.yml");
        String indented = content.lines().map(line -> "    " + line + "\n").collect(java.util.stream.Collectors.joining());
        Files.writeString(file, "items:\n  sample:\n    material: PAPER\n" + indented);
        return ItemCatalog.load(file).get("sample");
    }

    private static List<String> plain(List<Component> lines) {
        return lines.stream().map(PLAIN::serialize).toList();
    }

    private static boolean hasColor(Component component, TextColor color) {
        return color.equals(component.color()) || component.children().stream().anyMatch(child -> hasColor(child, color));
    }
}
