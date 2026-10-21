package dev.flint.planetry.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

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
    private static final Pattern VALUE = Pattern.compile("\\{([A-Za-z0-9_-]+)}");
    private static final Pattern GLYPH_TAG = Pattern.compile("<glyph(?=[:>])([^>]*)(?:>|$)", Pattern.CASE_INSENSITIVE);
    private static final String NORMAL = "abcdefghijklmnopqrstuvwxyz";
    private static final String SMALL = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀѕᴛᴜᴠᴡхʏᴢ";
    private final Map<String, String> templates;

    private ItemLanguage(Map<String, String> templates) {
        this.templates = Collections.unmodifiableMap(new LinkedHashMap<>(templates));
    }

    public static ItemLanguage load(Path file) throws IOException {
        Map<String, Object> root = ItemCatalog.readYaml(file);
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
        templates.putAll(overrides);
        var language = new ItemLanguage(templates);
        for (var entry : templates.entrySet()) {
            try {
                language.render(entry.getValue(), Map.of(), Component::text);
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

    /** Replacements are inserted as components, so player names cannot introduce formatting. */
    public Component render(String template, Map<String, String> values, Function<String, Component> glyphResolver) {
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
            String id = args.popOr("A glyph ID is required").value();
            Component glyph = glyphResolver == null ? null : glyphResolver.apply(id);
            if (glyph == null || glyph.equals(Component.empty())) {
                String fallback = templates.get("glyph-fallbacks." + id);
                glyph = fallback == null ? Component.empty() : MINI_MESSAGE.deserialize(fallback);
            }
            return Tag.inserting(glyph);
        }));
        return MINI_MESSAGE.deserialize(prepared.toString(), resolver.build()).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
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
            String id = argument.substring(1);
            if (id.length() >= 2 && (id.charAt(0) == '\'' || id.charAt(0) == '"')
                    && id.charAt(id.length() - 1) == id.charAt(0)) id = id.substring(1, id.length() - 1);
            if (!id.matches("[A-Za-z0-9_.-]{1,128}")) {
                throw new IllegalArgumentException("Glyph tags require one valid glyph ID");
            }
        }
    }

    public Component text(String key, Map<String, String> values, Function<String, Component> glyphResolver) {
        return render(templates.getOrDefault(key, key), values, glyphResolver);
    }

    public List<Component> lore(ItemDefinition definition, int current, Set<String> unmetRequirements,
                                Function<String, Component> glyphResolver) {
        var result = new ArrayList<Component>();
        String typeKey = "types." + definition.type().toLowerCase(Locale.ROOT);
        result.add(render(templates.getOrDefault(typeKey, "<gray>" + smallCaps(definition.type())), Map.of(), glyphResolver));

        var flavor = new ArrayList<Component>();
        List<String> flavorLines = definition.lore();
        int start = 0;
        int end = flavorLines.size();
        while (start < end && flavorLines.get(start).isBlank()) start++;
        while (end > start && flavorLines.get(end - 1).isBlank()) end--;
        for (int i = start; i < end; i++) flavor.add(render(flavorLines.get(i), Map.of(), glyphResolver));
        section(result, flavor);

        var attributes = new ArrayList<Component>();
        definition.attributes().forEach((key, attribute) -> {
            boolean percent = !attribute.operation().equals("ADD_NUMBER");
            String unit = percent ? "%" : "";
            attributes.add(attribute(key.replace(':', '-').replace('_', '-'), key, attribute.amount(), unit, glyphResolver));
        });
        definition.bonuses().forEach((key, amount) -> attributes.add(attribute(key, key, amount, "", glyphResolver)));
        section(result, attributes);

        var requirements = new ArrayList<Component>();
        definition.requirements().forEach((key, value) -> {
            if (unmetRequirements.contains(key)) {
                String fallback = templates.getOrDefault("requirements.default", "<yellow>Requires {requirement} Level {value}");
                String template = templates.getOrDefault("requirements." + key, fallback);
                requirements.add(render(template, Map.of("value", Integer.toString(value), "requirement", humanize(key)), glyphResolver));
            }
        });
        section(result, requirements);
        section(result, List.of(text("rarities." + definition.rarity().toLowerCase(Locale.ROOT), Map.of(), glyphResolver)));

        if (definition.maxDurability() > 0) {
            int remaining = Math.max(0, Math.min(current, definition.maxDurability()));
            String key = definition.unbreakable() ? "lore.unbreakable"
                    : remaining <= Math.max(1, definition.maxDurability() / 10.0) ? "lore.durability-low" : "lore.durability";
            result.add(text(key, Map.of("current", Integer.toString(remaining), "max", Integer.toString(definition.maxDurability())), glyphResolver));
        } else if (definition.unbreakable()) {
            result.add(text("lore.unbreakable", Map.of(), glyphResolver));
        }
        return List.copyOf(result);
    }

    private Component attribute(String languageKey, String name, double amount, String unit, Function<String, Component> glyphResolver) {
        String template = templates.getOrDefault("attributes." + languageKey,
                templates.getOrDefault("attributes.default", "<gray>{attribute}: <white>{value}{unit}"));
        return render(template, Map.of("attribute", humanize(name), "value", signed(amount, unit.equals("%")), "unit", unit), glyphResolver);
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
