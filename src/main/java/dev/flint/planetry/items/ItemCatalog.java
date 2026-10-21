package dev.flint.planetry.items;

import org.bukkit.Material;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Loads the entire catalog atomically: malformed definitions never partially activate. */
public final class ItemCatalog {
    private static final Set<String> FIELDS = Set.of("material", "display-name", "lore",
            "custom-model-data", "nexo-id", "base64", "rarity", "type", "max-stack-size",
            "max-durability", "unbreakable", "equip-slot", "bonuses", "requirements", "attributes");
    private static final Set<String> RARITIES = Set.of("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHICAL");
    private static final Set<String> TYPES = Set.of("ITEM", "TOOL", "WEAPON", "ARMOUR", "FOOD", "COSMETIC", "ADDON", "SPACESUIT");
    private static final Set<String> WEARABLES = Set.of("ARMOUR", "COSMETIC", "SPACESUIT");
    private static final Set<String> EQUIP_SLOTS = Set.of("HEAD", "CHEST", "LEGS", "FEET", "OFF_HAND");
    private static final Set<String> ATTRIBUTE_SLOTS = Set.of("ANY", "HAND", "MAINHAND", "OFFHAND", "ARMOR", "HEAD", "CHEST", "LEGS", "FEET", "BODY");
    private static final Set<String> OPERATIONS = Set.of("ADD_NUMBER", "ADD_SCALAR", "MULTIPLY_SCALAR_1");
    private static final Set<String> BONUS_KEYS = Set.of("planetry-oxygen", "auraskills-additional-wisdom",
            "auraskills-additional-health", "auraskills-additional-regeneration", "auraskills-additional-toughness",
            "auraskills-additional-strength");
    private static final Set<String> REQUIREMENT_KEYS = Set.of("planetry-gamelevel", "auraskills-required-farming",
            "auraskills-required-foraging", "auraskills-required-mining", "auraskills-required-fishing", "auraskills-required-fighting");
    private final Map<String, ItemDefinition> definitions;

    private ItemCatalog(Map<String, ItemDefinition> definitions) {
        this.definitions = Collections.unmodifiableMap(new LinkedHashMap<>(definitions));
    }

    /**
     * Loads a legacy single file or every .yml/.yaml file below a directory.
     * Directory files are ordered by relative path; each file retains YAML entry order.
     * Empty directories produce empty catalogs. Symbolic links are rejected, never followed.
     */
    public static ItemCatalog load(Path source) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink()) throw new IOException(source + ": symbolic links are not supported in item catalogs");
        boolean directory = attributes.isDirectory();
        if (!directory && !attributes.isRegularFile()) throw new IOException(source + ": expected an item file or directory");
        List<Path> files = directory ? catalogFiles(source) : List.of(source);
        var result = new LinkedHashMap<String, ItemDefinition>();
        var origins = new LinkedHashMap<String, String>();
        for (Path file : files) {
            String label = directory ? source.relativize(file).toString() : file.getFileName().toString();
            try {
                loadFile(file, result, origins, label);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(label + ": " + ex.getMessage(), ex);
            }
        }
        return new ItemCatalog(result);
    }

    private static List<Path> catalogFiles(Path directory) throws IOException {
        var files = new ArrayList<Path>();
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isSymbolicLink()) {
                    throw new IOException(directory.relativize(file) + ": symbolic links are not supported in item catalogs");
                }
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".yml") || name.endsWith(".yaml")) {
                    if (!attributes.isRegularFile()) throw new IOException(file + ": expected a regular item file");
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.comparing(file -> directory.relativize(file).toString()));
        return files;
    }

    private static void loadFile(Path file, Map<String, ItemDefinition> result, Map<String, String> origins,
                                 String label) throws IOException {
        Map<String, Object> root = readYaml(file);
        known(root, Set.of("schema-version", "items"), "catalog");
        if (root.containsKey("schema-version")) integer(root.get("schema-version"), "schema-version", 1, 1);
        Map<String, Object> rawItems = mapping(root.get("items"), "items");
        for (var entry : rawItems.entrySet()) {
            String id = entry.getKey();
            if (!id.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
                throw invalid("items." + id, "IDs must use 1-64 lowercase letters, digits, underscores or hyphens");
            }
            if (origins.containsKey(id)) throw invalid("items." + id, "duplicate item ID; already defined in " + origins.get(id));
            result.put(id, definition(id, mapping(entry.getValue(), "items." + id)));
            origins.put(id, label);
        }
    }

    public Map<String, ItemDefinition> definitions() { return definitions; }

    public ItemDefinition get(String id) { return definitions.get(id); }

    private static ItemDefinition definition(String id, Map<String, Object> item) {
        String path = "items." + id;
        known(item, FIELDS, path);
        String base64 = optionalString(item, "base64", path);
        String nexoId = optionalString(item, "nexo-id", path);
        if (base64 != null && nexoId != null) {
            throw invalid(path, "choose either nexo-id or base64 as the texture source");
        }
        String material = string(item.getOrDefault("material", base64 == null ? "STONE" : "PLAYER_HEAD"), path + ".material").toUpperCase(Locale.ROOT);
        Material parsedMaterial = Material.getMaterial(material);
        if (parsedMaterial == null || parsedMaterial == Material.AIR || parsedMaterial == Material.CAVE_AIR || parsedMaterial == Material.VOID_AIR) {
            throw invalid(path + ".material", "expected a non-air Minecraft material");
        }
        if (base64 != null) {
            if (!material.equals("PLAYER_HEAD")) {
                throw invalid(path + ".base64", "custom head textures require material PLAYER_HEAD");
            }
            try {
                if (base64.length() > 16_384 || Base64.getDecoder().decode(base64).length == 0) {
                    throw invalid(path + ".base64", "expected a nonempty base64 texture value of at most 16384 characters");
                }
            } catch (IllegalArgumentException ex) {
                throw invalid(path + ".base64", "expected valid base64 texture data");
            }
        }
        String rarity = choice(item.getOrDefault("rarity", "COMMON"), path + ".rarity", RARITIES);
        String type = choice(item.getOrDefault("type", "ITEM"), path + ".type", TYPES);
        String equipSlot = optionalString(item, "equip-slot", path);
        if (equipSlot != null) {
            equipSlot = choice(equipSlot, path + ".equip-slot", EQUIP_SLOTS);
            if (!WEARABLES.contains(type)) {
                throw invalid(path + ".equip-slot", "equip-slot applies to ARMOUR, COSMETIC and SPACESUIT items");
            }
        } else if (WEARABLES.contains(type)) {
            equipSlot = "HEAD";
        }
        int maxStack = integer(item.getOrDefault("max-stack-size", 1), path + ".max-stack-size", 1, 99);
        int durability = integer(item.getOrDefault("max-durability", 100), path + ".max-durability", 0, Integer.MAX_VALUE);
        boolean unbreakable = bool(item.getOrDefault("unbreakable", false), path + ".unbreakable");
        if (durability > 0 && !unbreakable && maxStack != 1) {
            throw invalid(path + ".max-stack-size", "breakable items must have max-stack-size 1; use max-durability 0 or unbreakable true for stacks");
        }
        if (equipSlot != null && maxStack != 1) {
            throw invalid(path + ".max-stack-size", "wearable items must have max-stack-size 1");
        }
        Integer model = item.containsKey("custom-model-data") && item.get("custom-model-data") != null
                ? integer(item.get("custom-model-data"), path + ".custom-model-data", 0, Integer.MAX_VALUE) : null;
        var attributes = new LinkedHashMap<String, ItemDefinition.AttributeSpec>();
        String defaultSlot = equipSlot == null ? "MAINHAND" : equipSlot.equals("OFF_HAND") ? "OFFHAND" : equipSlot;
        Map<String, Object> rawAttributes = optionalMapping(item, "attributes", path);
        for (var entry : rawAttributes.entrySet()) {
            if (blank(entry.getValue())) continue;
            String key = attributeKey(entry.getKey(), path + ".attributes");
            Object raw = entry.getValue();
            double amount;
            String operation = "ADD_NUMBER";
            String slot = defaultSlot;
            if (raw instanceof Map<?, ?>) {
                Map<String, Object> spec = mapping(raw, path + ".attributes." + key);
                known(spec, Set.of("amount", "operation", "slot"), path + ".attributes." + key);
                amount = number(spec.get("amount"), path + ".attributes." + key + ".amount");
                operation = choice(spec.getOrDefault("operation", operation), path + ".attributes." + key + ".operation", OPERATIONS);
                slot = choice(spec.getOrDefault("slot", slot), path + ".attributes." + key + ".slot", ATTRIBUTE_SLOTS);
            } else {
                amount = number(raw, path + ".attributes." + key);
            }
            if (attributes.putIfAbsent(key, new ItemDefinition.AttributeSpec(amount, operation, slot)) != null) {
                throw invalid(path + ".attributes." + key, "duplicate normalized attribute");
            }
        }
        var bonuses = new LinkedHashMap<String, Double>();
        for (var entry : optionalMapping(item, "bonuses", path).entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("minecraft-") && !BONUS_KEYS.contains(key)) throw invalid(path + ".bonuses." + key, "unknown bonus");
            if (blank(entry.getValue())) continue;
            if (key.startsWith("minecraft-")) {
                String attribute = attributeKey(key.substring("minecraft-".length()), path + ".bonuses." + key);
                var spec = new ItemDefinition.AttributeSpec(number(entry.getValue(), path + ".bonuses." + key), "ADD_NUMBER", defaultSlot);
                if (attributes.putIfAbsent(attribute, spec) != null) {
                    throw invalid(path + ".bonuses." + key, "attribute is already configured in attributes");
                }
            } else {
                double value = number(entry.getValue(), path + ".bonuses." + key);
                if (value < 0 && key.equals("planetry-oxygen")) throw invalid(path + ".bonuses." + key, "oxygen must not be negative");
                bonuses.put(key, value);
            }
        }
        var requirements = new LinkedHashMap<String, Integer>();
        for (var entry : optionalMapping(item, "requirements", path).entrySet()) {
            if (!REQUIREMENT_KEYS.contains(entry.getKey())) throw invalid(path + ".requirements." + entry.getKey(), "unknown requirement");
            if (blank(entry.getValue())) continue;
            requirements.put(entry.getKey(), integer(entry.getValue(), path + ".requirements." + entry.getKey(), 0, Integer.MAX_VALUE));
        }
        String displayName = string(item.getOrDefault("display-name", id), path + ".display-name");
        return new ItemDefinition(id, material, displayName, lore(item.get("lore"), path + ".lore"), model,
                nexoId, base64, rarity, type, maxStack, durability, unbreakable, equipSlot, bonuses, requirements, attributes);
    }

    private static String attributeKey(String key, String path) {
        String result = key.toLowerCase(Locale.ROOT);
        if (!result.contains(":")) result = "minecraft:" + result.replace('-', '_');
        if (!result.matches("minecraft:[a-z0-9_./-]+")) throw invalid(path, "expected a Minecraft attribute key, for example minecraft:attack_damage");
        return result;
    }

    static Map<String, Object> readYaml(Path file) throws IOException {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setCodePointLimit(2_000_000);
        try (Reader reader = new InputStreamReader(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), StandardCharsets.UTF_8.newDecoder())) {
            return readYaml(reader, file.getFileName().toString(), options);
        }
    }

    static Map<String, Object> readYaml(Reader reader, String label) {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setCodePointLimit(2_000_000);
        return readYaml(reader, label, options);
    }

    private static Map<String, Object> readYaml(Reader reader, String label, LoaderOptions options) {
        try {
            Object value = new Yaml(new SafeConstructor(options)).load(reader);
            return mapping(value, label);
        } catch (YAMLException ex) {
            throw new IllegalArgumentException(label + ": invalid YAML: " + ex.getMessage(), ex);
        }
    }

    static Map<String, Object> mapping(Object value, String path) {
        if (!(value instanceof Map<?, ?> raw)) throw invalid(path, "expected a mapping");
        var result = new LinkedHashMap<String, Object>();
        for (var entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank()) throw invalid(path, "mapping keys must be nonempty strings");
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static Map<String, Object> optionalMapping(Map<String, Object> source, String key, String path) {
        Object value = source.get(key);
        return value == null ? Map.of() : mapping(value, path + "." + key);
    }

    private static List<String> lore(Object raw, String path) {
        if (raw == null) return List.of();
        if (raw instanceof String text) return List.of(text.split("\\R", -1));
        if (!(raw instanceof List<?> lines)) throw invalid(path, "expected a string or list of strings");
        var result = new ArrayList<String>();
        for (int i = 0; i < lines.size(); i++) {
            Object line = lines.get(i);
            if (!(line instanceof String text)) throw invalid(path + "[" + i + "]", "expected a string");
            result.addAll(List.of(text.split("\\R", -1)));
        }
        return List.copyOf(result);
    }

    private static void known(Map<String, Object> map, Set<String> known, String path) {
        for (String key : map.keySet()) if (!known.contains(key)) throw invalid(path + "." + key, "unknown field");
    }

    private static String optionalString(Map<String, Object> source, String key, String path) {
        Object value = source.get(key);
        if (value == null) return null;
        if (value instanceof String string && string.isBlank()) return null;
        return string(value, path + "." + key);
    }

    private static String string(Object value, String path) {
        if (!(value instanceof String text) || text.isBlank()) throw invalid(path, "expected a nonempty string");
        return text;
    }

    private static String choice(Object value, String path, Set<String> allowed) {
        String text = string(value, path).toUpperCase(Locale.ROOT);
        if (!allowed.contains(text)) throw invalid(path, "expected one of " + allowed.stream().sorted().toList());
        return text;
    }

    private static boolean bool(Object value, String path) {
        if (!(value instanceof Boolean result)) throw invalid(path, "expected true or false");
        return result;
    }

    private static boolean blank(Object value) {
        return value == null || value instanceof String text && text.isBlank();
    }

    private static double number(Object value, String path) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) throw invalid(path, "expected a finite number");
        return number.doubleValue();
    }

    private static int integer(Object value, String path, int min, int max) {
        double parsed = number(value, path);
        if (parsed != Math.rint(parsed) || parsed < min || parsed > max) throw invalid(path, "expected a whole number from " + min + " to " + max);
        return (int) parsed;
    }

    private static IllegalArgumentException invalid(String path, String detail) {
        return new IllegalArgumentException(path + ": " + detail);
    }
}
