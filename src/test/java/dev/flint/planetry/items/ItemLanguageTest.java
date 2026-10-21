package dev.flint.planetry.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import java.util.function.Function;
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

    @Test void unbreakableItemsHideDurabilityAndLegacyUnbreakableLore() throws Exception {
        var lang = language("lore:\n  unbreakable: '<red>Unbreakable legacy setting'\n");
        for (int maximum : List.of(0, 100)) {
            var item = item("unbreakable: true\nmax-durability: " + maximum);
            assertEquals(List.of("ɪᴛᴇᴍ", "", "COMMON"), plain(lang.lore(item, 50, Set.of(), null)));
        }
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
        assertEquals("Common fallback", PLAIN.serialize(lang.render("<glyph:tag_common>", Map.of(), glyphs(ignored -> Component.empty()))));
        assertEquals("NEXO", PLAIN.serialize(lang.render("<glyph:tag_common>", Map.of(), glyphs(ignored -> Component.text("NEXO")))));
    }

    @Test void rarityColorsOverrideExplicitNestedNameAndGlyphColors() throws Exception {
        var colors = Map.of(
                "COMMON", NamedTextColor.GRAY,
                "UNCOMMON", NamedTextColor.GREEN,
                "RARE", NamedTextColor.AQUA,
                "EPIC", TextColor.color(0x9554EA),
                "LEGENDARY", TextColor.color(0xFFBF00),
                "MYTHICAL", NamedTextColor.RED);
        var lang = language("");
        for (var entry : colors.entrySet()) {
            var item = item("rarity: " + entry.getKey()
                    + "\ndisplay-name: '<white>Probe <red>Blade</red> <bold><gradient:red:blue>Glow</gradient></bold> <glyph:name>'\n");
            Component name = lang.displayName(item, glyphs(ignored -> Component.text("Glyph", NamedTextColor.LIGHT_PURPLE)
                    .append(Component.text(" Child", NamedTextColor.GREEN))));
            assertEquals("Probe Blade Glow Glyph Child", PLAIN.serialize(name));
            assertColorTree(name, entry.getValue());
            assertEquals(TextDecoration.State.FALSE, name.decoration(TextDecoration.ITALIC));
        }
    }

    @Test void plainRequirementSummaryUsesDefinitionOrderAndLeavesLoreDecorationIntact() throws Exception {
        var item = item("""
                requirements:
                  auraskills-required-mining: 3
                  planetry-gamelevel: 5
                  auraskills-required-farming: 4
                """);
        var lang = language("");
        var unmet = Set.of("planetry-gamelevel", "auraskills-required-mining", "unknown");
        String summary = lang.requirementSummary(item, unmet, null);
        assertEquals("Mining Level 3, Game Level 5", summary);
        assertEquals("", lang.requirementSummary(item, Set.of(), null));
        assertEquals("Farming Level 4", lang.requirementLabel("auraskills-required-farming", 4, null));
        assertTrue(plain(lang.lore(item, 100, unmet, null)).contains("[☆] Requires Mining Level 3"));
        for (String key : List.of("messages.requirements", "messages.requirements-not-met")) {
            assertEquals("You cannot use this item: Mining Level 3, Game Level 5!",
                    PLAIN.serialize(lang.text(key, Map.of("requirements", summary, "item", "sample"), null)));
        }
    }

    @Test void rarityColorsOverrideTranslationArgumentsAndPreserveTypedValues() throws Exception {
        var lang = language("");
        var translatedName = item("""
                rarity: COMMON
                display-name: "<lang:chat.square_brackets:'<red>Blade'>"
                """);
        var name = lang.displayName(translatedName, null);
        var translation = assertInstanceOf(TranslatableComponent.class, name);
        assertEquals("Blade", PLAIN.serialize(assertInstanceOf(Component.class, translation.arguments().getFirst().value())));
        assertColorTree(name, NamedTextColor.GRAY);

        var glyphName = item("rarity: EPIC\ndisplay-name: '<glyph:name>'\n");
        Component glyph = Component.translatable("custom.typed").arguments(
                Component.text("Blade", NamedTextColor.RED), TranslationArgument.numeric(3), TranslationArgument.bool(true));
        var glyphTranslation = assertInstanceOf(TranslatableComponent.class, lang.displayName(glyphName, glyphs(ignored -> glyph)));
        assertColorTree(glyphTranslation, TextColor.color(0x9554EA));
        assertEquals(3, assertInstanceOf(Number.class, glyphTranslation.arguments().get(1).value()).intValue());
        assertEquals(true, glyphTranslation.arguments().get(2).value());
    }

    @Test void requirementLabelsCanBeTranslatedSeparatelyFromLore() throws Exception {
        var lang = language("""
                requirements:
                  auraskills-required-mining: '<red>[custom lore] Requires {value}'
                requirement-labels:
                  default: '<yellow>Reach {requirement} {value}'
                  planetry-gamelevel: '<green>Game Rank {value}'
                """);
        assertEquals("Reach Mining 3", lang.requirementLabel("auraskills-required-mining", 3, null));
        assertEquals("Game Rank 5", lang.requirementLabel("planetry-gamelevel", 5, null));
    }

    @Test void upgradesOnlyExactLegacyDefaultDenialsAndPreservesCustomTranslations() throws Exception {
        var upgraded = language("""
                messages:
                  requirements: '<red>You cannot use {item}: {requirements}.'
                  requirements-not-met: '<red>You cannot use {item}: {requirements}.'
                """);
        for (String key : List.of("messages.requirements", "messages.requirements-not-met")) {
            assertEquals("You cannot use this item: Game Level 5!",
                    PLAIN.serialize(upgraded.text(key, Map.of("item", "sample", "requirements", "Game Level 5"), null)));
        }
        var custom = language("""
                messages:
                  requirements: '<yellow>Benötigt: {requirements}!'
                  requirements-not-met: '<blue>You cannot use {item}: {requirements}.'
                """);
        assertEquals("Benötigt: Game Level 5!",
                PLAIN.serialize(custom.text("messages.requirements", Map.of("requirements", "Game Level 5"), null)));
        assertEquals("You cannot use sample: Game Level 5.", PLAIN.serialize(custom.text("messages.requirements-not-met",
                Map.of("item", "sample", "requirements", "Game Level 5"), null)));
    }

    @Test void migrationDefaultsIncludeCountsCooldownAndLiteralRemovedName() throws Exception {
        var lang = language("");
        var counts = Map.of("updated", "3", "removed", "2", "failed", "1");
        assertEquals("Migration complete: updated 3 items and removed 2 unavailable items.",
                PLAIN.serialize(lang.text("messages.migrate-success", counts, null)));
        assertEquals("Updated 3 items and removed 2 unavailable items, but could not update 1 items. "
                        + "Free inventory space or check the required integration, then try again.",
                PLAIN.serialize(lang.text("messages.migrate-partial", counts, null)));
        assertEquals("You can use /migrate again in 1h 59m.", PLAIN.serialize(lang.text("messages.migrate-cooldown",
                Map.of("remaining", "1h 59m"), null)));
        assertEquals("Removed <gold>Old Blade because it is no longer available. Please contact staff.",
                PLAIN.serialize(lang.text("messages.removed-item", Map.of("item", "<gold>Old Blade"), null)));
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
        assertThrows(IllegalArgumentException.class, () -> language("rarities:\n  common: '<glyph:tag_common'\n"));
    }

    @Test void acceptsQuotedGlyphIdsAndEscapedLiteralTags() throws Exception {
        var lang = language("");
        assertEquals("tag_common", PLAIN.serialize(lang.render("<glyph:'tag_common'>", Map.of(), glyphs(Component::text))));
        assertEquals("<glyph>", PLAIN.serialize(lang.render("\\<glyph>", Map.of(), null)));
    }

    @Test void nativeOptionsFontsAndShiftsWorkAcrossItemAndLanguageTemplates() throws Exception {
        var argsSeen = new java.util.ArrayList<String>();
        var nativeTags = TagResolver.builder()
                .resolver(TagResolver.resolver("glyph", (args, context) -> {
                    argsSeen.clear();
                    while (args.hasNext()) argsSeen.add(args.pop().value());
                    return Tag.inserting(Component.text("ICON").font(net.kyori.adventure.key.Key.key("nexo:icons")));
                }))
                .resolver(TagResolver.resolver("shift", (args, context) -> Tag.inserting(Component.text("SHIFT" + args.pop().value()))))
                .build();
        var lang = language("messages:\n  list: '<glyph:heart:2..3:colorable:shadow:#80ffffff><shift:-8>{items}'\n");
        var result = lang.text("messages.list", Map.of("items", "<glyph:unsafe><shift:9>"), nativeTags, null);
        assertEquals("ICONSHIFT-8<glyph:unsafe><shift:9>", PLAIN.serialize(result));
        assertEquals(List.of("heart", "2..3", "colorable", "shadow", "#80ffffff"), argsSeen);
        var item = item("display-name: '<glyph:heart:c>'\nlore: ['<glyph:heart:2>']\n");
        Component name = lang.displayName(item, nativeTags, null);
        assertEquals("ICON", PLAIN.serialize(name));
        assertTrue(hasFont(name, "nexo:icons"));
        assertTrue(plain(lang.lore(item, 10, Set.of(), nativeTags, null)).contains("ICON"));
        assertColorTree(name, NamedTextColor.GRAY);
    }

    @Test void nativeEmptyOrMissingGlyphUsesConfiguredFallbackWithoutRecursion() throws Exception {
        var lang = language("glyph-fallbacks:\n  heart: '<gold>Heart <glyph:other>'\n");
        for (TagResolver tags : java.util.Arrays.asList(null, TagResolver.empty(),
                TagResolver.resolver("glyph", (args, context) -> { while (args.hasNext()) args.pop(); return Tag.inserting(Component.empty()); }))) {
            assertEquals("Heart <glyph:other>", PLAIN.serialize(lang.render("<glyph:heart:colorable><shift:-8>", Map.of(), tags, null)));
            assertEquals("", PLAIN.serialize(lang.render("<glyph:unknown><shift:8>", Map.of(), tags, null)));
        }
    }

    @Test void nativeGlobalResolverReceivesActualTargetForEveryTemplatePath() throws Exception {
        net.kyori.adventure.pointer.Pointered viewer = new net.kyori.adventure.pointer.Pointered() { };
        var tags = TagResolver.resolver("glyph", (args, context) -> {
            args.pop();
            return Tag.inserting(Component.text(context.target() == viewer ? "VIEWER" : "GLOBAL"));
        });
        var lang = language("types:\n  item: '<glyph:heart>'\nrequirement-labels:\n  default: '<glyph:heart>'\n  planetry-gamelevel: '<glyph:heart>'\nmessages:\n  list: '<glyph:heart>'\n");
        var item = item("display-name: '<glyph:heart>'\nlore: ['<glyph:heart>']\nrequirements:\n  planetry-gamelevel: 5\n");
        assertEquals("VIEWER", PLAIN.serialize(lang.render("<glyph:heart>", Map.of(), tags, viewer)));
        assertEquals("VIEWER", PLAIN.serialize(lang.text("messages.list", Map.of(), tags, viewer)));
        assertEquals("VIEWER", PLAIN.serialize(lang.displayName(item, tags, viewer)));
        assertEquals("VIEWER", lang.requirementLabel("planetry-gamelevel", 5, tags, viewer));
        assertEquals("VIEWER", lang.requirementSummary(item, Set.of("planetry-gamelevel"), tags, viewer));
        assertEquals("VIEWER", plain(lang.lore(item, 100, Set.of(), tags, viewer)).getFirst());
        assertEquals("GLOBAL", PLAIN.serialize(lang.render("<glyph:heart>", Map.of(), tags, null)));
    }

    private static boolean hasFont(Component component, String font) {
        return component.font() != null && component.font().asString().equals(font)
                || component.children().stream().anyMatch(child -> hasFont(child, font));
    }

    private static Function<String, Component> glyphs(Function<String, Component> glyph) {
        return glyph;
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

    private static void assertColorTree(Component component, TextColor color) {
        assertEquals(color, component.color());
        component.children().forEach(child -> assertColorTree(child, color));
        if (component instanceof TranslatableComponent translated) {
            translated.arguments().forEach(argument -> {
                if (argument.value() instanceof Component value) assertColorTree(value, color);
            });
        }
    }
}
