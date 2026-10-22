package dev.flint.planetry.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.Inserting;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Configurable item tooltips and command messages, with literal-safe dynamic values. */
public final class ItemLanguage {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final String LEGACY_REQUIREMENTS_MESSAGE = "<red>You cannot use {item}: {requirements}.";
    private static final Pattern VALUE = Pattern.compile("\\{([A-Za-z0-9_-]+)}");
    private static final Pattern GLYPH_TAG = Pattern.compile("<glyph(?=[:>])([^>]*)(?:>|$)", Pattern.CASE_INSENSITIVE);
    private static final String NORMAL = "abcdefghijklmnopqrstuvwxyz";
    private static final String SMALL = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀѕᴛᴜᴠᴡхʏᴢ";
    private final Map<String, String> templates;

    private ItemLanguage(Map<String, String> templates) {
        this.templates = Collections.unmodifiableMap(new LinkedHashMap<>(templates));
    }

    public static ItemLanguage load(Path file) throws IOException {
        return loadRoot(ItemCatalog.readYaml(file));
    }

    /** Language and definitions are validated together before a proxy revision activates. */
    public static ItemLanguage parse(String yaml) throws IOException {
        return loadRoot(ItemCatalog.readYaml(new java.io.StringReader(yaml), "proxy lang.yml"));
    }

    private static ItemLanguage loadRoot(Map<String, Object> root) throws IOException {
        var templates = new LinkedHashMap<String, String>();
        try (var stream = ItemLanguage.class.getResourceAsStream("/lang.yml")) {
            if (stream == null) throw new IOException("Missing bundled lang.yml");
            var defaults = ItemCatalog.readYaml(new InputStreamReader(stream, StandardCharsets.UTF_8), "bundled lang.yml");
            validateVersion(defaults);
            flatten(defaults, "", templates);
        }
        validateVersion(root);
        var overrides = new LinkedHashMap<String, String>();
        flatten(root, "", overrides);
        // Upgrade only the old bundled wording; keep every customized translation intact.
        for (String key : List.of("messages.requirements", "messages.requirements-not-met")) {
            if (LEGACY_REQUIREMENTS_MESSAGE.equals(overrides.get(key))) overrides.remove(key);
        }
        templates.putAll(overrides);
        var language = new ItemLanguage(templates);
        for (var entry : templates.entrySet()) {
            try {
                language.render(entry.getValue(), Map.of(), null);
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("lang.yml." + entry.getKey() + ": invalid message template", error);
            }
        }
        return language;
    }

    private static void validateVersion(Map<String, Object> root) {
        Object version = root.remove("schema-version");
        if (version != null && (!(version instanceof Number n) || n.doubleValue() != 1)) {
            throw new IllegalArgumentException("lang.yml.schema-version: expected 1");
        }
    }

    // Retain the original simple-glyph API for addons; native callers also provide a MiniMessage target.
    public Component render(String template, Map<String, String> values, Function<String, Component> glyph) {
        return render(template, values, legacyGlyphs(glyph), null);
    }
    public Component text(String key, Map<String, String> values, Function<String, Component> glyph) {
        return text(key, values, legacyGlyphs(glyph), null);
    }
    public Component displayName(ItemDefinition definition, Function<String, Component> glyph) {
        return displayName(definition, legacyGlyphs(glyph), null);
    }
    public String requirementLabel(String key, int value, Function<String, Component> glyph) {
        return requirementLabel(key, value, legacyGlyphs(glyph), null);
    }
    public String requirementSummary(ItemDefinition definition, Set<String> unmet, Function<String, Component> glyph) {
        return requirementSummary(definition, unmet, legacyGlyphs(glyph), null);
    }
    public List<Component> lore(ItemDefinition definition, int current, Set<String> unmet, Function<String, Component> glyph) {
        return lore(definition, current, unmet, legacyGlyphs(glyph), null);
    }

    private static TagResolver legacyGlyphs(Function<String, Component> glyph) {
        if (glyph == null) return TagResolver.empty();
        return TagResolver.resolver("glyph", (args, context) -> {
            String id = args.popOr("A glyph ID is required").value();
            while (args.hasNext()) args.pop();
            Component value = glyph.apply(id);
            return value == null ? null : Tag.inserting(value);
        });
    }

    /** Replacements are inserted as components, so player names cannot introduce formatting. */
    public Component render(String template, Map<String, String> values, TagResolver glyphResolver, Pointered target) {
        validateGlyphTags(template);
        var resolver = TagResolver.builder();
        var prepared = new StringBuilder();
        Matcher matcher = VALUE.matcher(template);
        int index = 0;
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            if (value == null) continue;
            String tag = "planetry_value_" + index++;
            resolver.resolver(TagResolver.resolver(tag, Tag.inserting(Component.text(value))));
            matcher.appendReplacement(prepared, Matcher.quoteReplacement("<" + tag + ">"));
        }
        matcher.appendTail(prepared);
        resolver.resolver(TagResolver.resolver("glyph", (args, context) -> {
            if (!args.hasNext()) throw context.newException("A glyph ID is required");
            String id = args.peek().value();
            Tag nativeTag = glyphResolver == null ? null : glyphResolver.resolve("glyph", args, context);
            if (nativeTag != null && !(nativeTag instanceof Inserting inserting
                    && inserting.value().equals(Component.empty()))) return nativeTag;
            while (args.hasNext()) args.pop();
            String fallback = templates.get("glyph-fallbacks." + id);
            return Tag.inserting(fallback == null ? Component.empty() : MINI_MESSAGE.deserialize(fallback));
        }));
        resolver.resolver(TagResolver.resolver("shift", (args, context) -> {
            Tag nativeTag = glyphResolver == null ? null : glyphResolver.resolve("shift", args, context);
            if (nativeTag != null) return nativeTag;
            while (args.hasNext()) args.pop();
            return Tag.inserting(Component.empty());
        }));
        Component rendered = target == null ? MINI_MESSAGE.deserialize(prepared.toString(), resolver.build())
                : MINI_MESSAGE.deserialize(prepared.toString(), target, resolver.build());
        return rendered.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private static void validateGlyphTags(String template) {
        Matcher glyph = GLYPH_TAG.matcher(template);
        while (glyph.find()) {
            int escapes = 0;
            for (int i = glyph.start() - 1; i >= 0 && template.charAt(i) == '\\'; i--) escapes++;
            if (escapes % 2 != 0) continue;
            String argument = glyph.group(1);
            if (!glyph.group().endsWith(">") || !argument.startsWith(":")) {
                throw new IllegalArgumentException("Glyph tags require one valid glyph ID");
            }
            String id = argument.substring(1).split(":", 2)[0];
            if (id.length() >= 2 && (id.charAt(0) == '\'' || id.charAt(0) == '"')
                    && id.charAt(id.length() - 1) == id.charAt(0)) id = id.substring(1, id.length() - 1);
            if (!id.matches("[A-Za-z0-9_.-]{1,128}")) {
                throw new IllegalArgumentException("Glyph tags require one valid glyph ID");
            }
        }
    }

    public Component text(String key, Map<String, String> values, TagResolver glyphResolver, Pointered target) {
        return render(templates.getOrDefault(key, key), values, glyphResolver, target);
    }

    /** Rarity owns the complete visible name color, including explicitly colored nested text and glyphs. */
    public Component displayName(ItemDefinition definition, TagResolver glyphResolver, Pointered target) {
        TextColor color = switch (definition.rarity().toUpperCase(Locale.ROOT)) {
            case "UNCOMMON" -> NamedTextColor.GREEN;
            case "RARE" -> NamedTextColor.AQUA;
            case "EPIC" -> TextColor.color(0x9554EA);
            case "LEGENDARY" -> TextColor.color(0xFFBF00);
            case "MYTHICAL" -> NamedTextColor.RED;
            default -> NamedTextColor.GRAY;
        };
        return colorName(render(definition.displayName(), Map.of(), glyphResolver, target), color);
    }

    private static Component colorName(Component component, TextColor color) {
        if (component instanceof TranslatableComponent translated) {
            component = translated.arguments(translated.arguments().stream()
                    .map(argument -> argument.value() instanceof Component value
                            ? TranslationArgument.component(colorName(value, color)) : argument).toList());
        }
        return component.color(color).children(component.children().stream().map(child -> colorName(child, color)).toList());
    }

    /** A plain requirement label for command output, independent of decorated requirement lore. */
    public String requirementLabel(String key, int value, TagResolver glyphResolver, Pointered target) {
        String fallback = templates.getOrDefault("requirement-labels.default", "{requirement} Level {value}");
        String template = templates.getOrDefault("requirement-labels." + key, fallback);
        return PLAIN.serialize(render(template, Map.of("value", Integer.toString(value), "requirement", humanize(key)), glyphResolver, target));
    }

    /** Unmet requirements retain catalog order and never inherit decorative lore prefixes. */
    public String requirementSummary(ItemDefinition definition, Set<String> unmetRequirements,
                                     TagResolver glyphResolver, Pointered target) {
        var labels = new ArrayList<String>();
        definition.requirements().forEach((key, value) -> {
            if (unmetRequirements.contains(key)) labels.add(requirementLabel(key, value, glyphResolver, target));
        });
        return String.join(", ", labels);
    }

    public List<Component> lore(ItemDefinition definition, int current, Set<String> unmetRequirements,
                                TagResolver glyphResolver, Pointered target) {
        var result = new ArrayList<Component>();
        String typeKey = "types." + definition.type().toLowerCase(Locale.ROOT);
        Component type = render(templates.getOrDefault(typeKey, "<gray>" + smallCaps(definition.type())), Map.of(), glyphResolver, target);
        if (definition.soulbound()) type = type.append(text("lore.soulbound", Map.of(), glyphResolver, target));
        result.add(type);

        var flavor = new ArrayList<Component>();
        List<String> flavorLines = definition.lore();
        int start = 0;
        int end = flavorLines.size();
        while (start < end && flavorLines.get(start).isBlank()) start++;
        while (end > start && flavorLines.get(end - 1).isBlank()) end--;
        for (int i = start; i < end; i++) flavor.add(render(flavorLines.get(i), Map.of(), glyphResolver, target));
        section(result, flavor);

        var attributes = new ArrayList<Component>();
        definition.attributes().forEach((key, attribute) -> {
            boolean percent = !attribute.operation().equals("ADD_NUMBER");
            String unit = percent ? "%" : "";
            attributes.add(attribute(key.replace(':', '-').replace('_', '-'), key, attribute.amount(), unit, glyphResolver, target));
        });
        definition.bonuses().forEach((key, amount) -> attributes.add(attribute(key, key, amount, "", glyphResolver, target)));
        section(result, attributes);

        var requirements = new ArrayList<Component>();
        definition.requirements().forEach((key, value) -> {
            if (unmetRequirements.contains(key)) {
                String fallback = templates.getOrDefault("requirements.default", "<yellow>Requires {requirement} Level {value}");
                String template = templates.getOrDefault("requirements." + key, fallback);
                requirements.add(render(template, Map.of("value", Integer.toString(value), "requirement", humanize(key)), glyphResolver, target));
            }
        });
        section(result, requirements);
        section(result, List.of(text("rarities." + definition.rarity().toLowerCase(Locale.ROOT), Map.of(), glyphResolver, target)));

        if (!definition.unbreakable() && definition.maxDurability() > 0) {
            int remaining = Math.max(0, Math.min(current, definition.maxDurability()));
            String key = remaining <= 1 || remaining * 10L <= definition.maxDurability() * 3L
                    ? "lore.durability-low" : "lore.durability";
            result.add(text(key, Map.of("current", Integer.toString(remaining), "max", Integer.toString(definition.maxDurability())), glyphResolver, target));
        }
        return List.copyOf(result);
    }

    private Component attribute(String languageKey, String name, double amount, String unit, TagResolver glyphResolver, Pointered target) {
        String template = templates.getOrDefault("attributes." + languageKey,
                templates.getOrDefault("attributes.default", "<gray>{attribute}: <white>{value}{unit}"));
        return render(template, Map.of("attribute", humanize(name), "value", signed(amount, unit.equals("%")), "unit", unit), glyphResolver, target);
    }

    private static String signed(double amount, boolean percentage) {
        BigDecimal value = BigDecimal.valueOf(amount);
        if (percentage) value = value.multiply(BigDecimal.valueOf(100));
        return (amount < 0 ? "" : "+") + value.stripTrailingZeros().toPlainString();
    }

    private static String humanize(String value) {
        String normalized = value.replace("minecraft:", "").replace("auraskills-additional-", "").replace("auraskills-required-", "")
                .replace("planetry-", "").replace('_', ' ').replace('-', ' ').replace('.', ' ');
        var result = new StringBuilder();
        boolean capitalize = true;
        for (char c : normalized.toCharArray()) {
            result.append(capitalize ? Character.toUpperCase(c) : c);
            capitalize = c == ' ';
        }
        return result.toString();
    }

    private static String smallCaps(String value) {
        var result = new StringBuilder();
        for (char c : value.toLowerCase(Locale.ROOT).toCharArray()) {
            int i = NORMAL.indexOf(c);
            result.append(i >= 0 ? SMALL.charAt(i) : c);
        }
        return result.toString();
    }

    private static void section(List<Component> target, List<Component> section) {
        if (!section.isEmpty()) {
            if (!target.isEmpty()) target.add(Component.empty());
            target.addAll(section);
        }
    }

    private static void flatten(Map<String, Object> map, String prefix, Map<String, String> result) {
        map.forEach((key, value) -> {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (value instanceof Map<?, ?>) flatten(ItemCatalog.mapping(value, path), path, result);
            else if (value instanceof String text) {
                if (result.putIfAbsent(path, text) != null) throw new IllegalArgumentException("Duplicate language key: " + path);
            } else throw new IllegalArgumentException(path + ": expected a message string or mapping");
        });
    }
}
