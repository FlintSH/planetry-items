package dev.flint.planetry.items;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Optional integrations. All player/inventory operations are called on the server thread. */
public final class IntegrationHooks implements AutoCloseable {
    private static final String AURA = "dev.aurelium.auraskills.api.";
    private static final String BONUS_PREFIX = "auraskills-additional-";
    private static final String[] STATS = {"wisdom", "health", "regeneration", "toughness", "strength"};
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private final JavaPlugin plugin;
    private final ClassLookup classes;
    private final UnaryOperator<ItemStack> appearance;
    private final Set<String> warnings = new HashSet<>();
    private final Map<UUID, BonusState> bonuses = new HashMap<>();
    private AuraBridge auraBridge;

    public IntegrationHooks(JavaPlugin plugin) {
        this(plugin, (name, loader) -> Class.forName(name, true, loader));
    }

    IntegrationHooks(JavaPlugin plugin, ClassLookup classes) {
        this(plugin, classes, IntegrationHooks::appearanceOnly);
    }

    IntegrationHooks(JavaPlugin plugin, ClassLookup classes, UnaryOperator<ItemStack> appearance) {
        this.plugin = Objects.requireNonNull(plugin);
        this.classes = Objects.requireNonNull(classes);
        this.appearance = Objects.requireNonNull(appearance);
    }

    @FunctionalInterface
    interface ClassLookup {
        Class<?> load(String name, ClassLoader loader) throws ClassNotFoundException;
    }

    /** Builds at use time because Nexo loads asynchronously; imports appearance without Nexo gameplay identity. */
    public ItemStack nexoItem(String id) throws IllegalStateException {
        Plugin nexo = enabled("Nexo");
        if (nexo == null) throw new IllegalStateException("Nexo is unavailable for item '" + id + "'.");
        try {
            Class<?> api = load(nexo, "com.nexomc.nexo.api.NexoItems");
            Object builder = api.getMethod("itemFromId", String.class).invoke(null, id);
            if (builder == null) throw new IllegalStateException("Nexo item '" + id + "' is missing or still loading.");
            Object built = builder.getClass().getMethod("build").invoke(builder);
            if (!(built instanceof ItemStack stack) || stack.getType() == Material.AIR
                    || stack.getType() == Material.CAVE_AIR || stack.getType() == Material.VOID_AIR)
                throw new IllegalStateException("Nexo item '" + id + "' did not produce an item.");
            return appearance.apply(stack);
        } catch (ReflectiveOperationException | LinkageError error) {
            warn("Nexo items", error);
            throw new IllegalStateException("Nexo item '" + id + "' could not be loaded.", error);
        }
    }

    /**
     * Model selection, model inputs, head skin, dye and trim are appearance. Equippable also carries the
     * worn-model asset, so retain that component for the item factory to assign its configured slot.
     * GLIDER changes gameplay and is deliberately excluded. Fresh material defaults supply food/tool
     * behavior, while source custom data, Nexo identity, lore, attributes and mechanics stay behind.
     */
    private static ItemStack appearanceOnly(ItemStack source) {
        ItemStack result = new ItemStack(source.getType());
        Set<DataComponentType> visual = Set.of(DataComponentTypes.ITEM_MODEL, DataComponentTypes.CUSTOM_MODEL_DATA,
                DataComponentTypes.PROFILE, DataComponentTypes.DYED_COLOR, DataComponentTypes.TRIM,
                DataComponentTypes.EQUIPPABLE);
        result.copyDataFrom(source, visual::contains);
        return result;
    }

    /** Nexo's native resolver preserves the configured glyph font. Null lets callers use a text rarity. */
    public Component glyph(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_.-]{1,128}")) return null;
        Plugin nexo = enabled("Nexo");
        if (nexo == null) return null;
        try {
            Class<?> type = load(nexo, "com.nexomc.nexo.glyphs.GlyphTag");
            Object instance = type.getField("INSTANCE").get(null);
            Object resolved = type.getMethod("getRESOLVER").invoke(instance);
            if (!(resolved instanceof TagResolver resolver) || !resolver.has("glyph")) return null;
            String tag = "<glyph:" + id + ">";
            Component result = MINI.deserialize(tag, resolver);
            return PlainTextComponentSerializer.plainText().serialize(result).contains("<glyph:") ? null : result;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            warn("Nexo glyphs", error);
            return null;
        }
    }

    /** Reads Core's actual game progress rather than Minecraft's displayed experience level. */
    public Integer gameLevel(Player player) {
        Plugin core = enabled("PlanetryCore");
        if (core == null) return null;
        try {
            Object progress = core.getClass().getMethod("cachedProgress", UUID.class).invoke(core, player.getUniqueId());
            if (progress == null) return null;
            Object value = progress.getClass().getMethod("level").invoke(progress);
            return value instanceof Integer level && level >= 0 ? level : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            warn("PlanetryCore levels", error);
            return null;
        }
    }

    public Integer skillLevel(Player player, String skill) {
        if (skill == null || !Set.of("farming", "foraging", "mining", "fishing", "fighting").contains(skill)) return null;
        try {
            AuraBridge bridge = aura();
            if (bridge == null) return null;
            Object user = bridge.loadedUser(player.getUniqueId());
            if (user == null) return null;
            Object type = bridge.skills.getField(skill.toUpperCase(java.util.Locale.ROOT)).get(null);
            Object value = bridge.skillLevel.invoke(user, type);
            return value instanceof Integer level && level >= 0 ? level : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            warn("AuraSkills levels", error);
            return null;
        }
    }

    /** Installed but disabled, incompatible or not-yet-ready Inventory Sync always refuses mutation. */
    public boolean inventoryReady(Player player) {
        Plugin sync = plugin.getServer().getPluginManager().getPlugin("NetworkInventorySync");
        if (sync == null) return true;
        if (!sync.isEnabled()) return false;
        try {
            return Boolean.TRUE.equals(sync.getClass().getMethod("isReady", UUID.class).invoke(sync, player.getUniqueId()));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            warn("NetworkInventorySync readiness", error);
            return false;
        }
    }

    /**
     * Reconciles only this plugin's five modifiers. The caller has already selected active, usable equipment.
     * Nonpersistent modifiers avoid leaving equipment bonuses in AuraSkills storage after a crash.
     */
    public void updateBonuses(Player player, Map<String, Double> totals) {
        UUID id = player.getUniqueId();
        try {
            AuraBridge bridge = aura();
            Object user = bridge == null ? null : bridge.loadedUser(id);
            if (user == null) { clearStored(id); return; }
            BonusState previous = bonuses.get(id);
            if (previous != null && previous.bridge != bridge) {
                clearStored(id);
                if (bonuses.containsKey(id)) return;
            }
            BonusState state = bonuses.computeIfAbsent(id, ignored -> new BonusState(bridge, user));
            state.user = user; // AuraSkills returns a new API wrapper on every getUser call.
            for (String stat : STATS) {
                String name = modifierName(stat);
                Object current = bridge.getModifier.invoke(user, name);
                Double amount = totals.get(BONUS_PREFIX + stat);
                if (amount == null || amount == 0.0 || !Double.isFinite(amount)) {
                    if (current != null) bridge.removeModifier.invoke(user, name);
                    state.modifiers.remove(stat);
                    state.amounts.remove(stat);
                } else if (current == null || current != state.modifiers.get(stat) || !amount.equals(state.amounts.get(stat))) {
                    Object statType = bridge.stats.getField(stat.toUpperCase(java.util.Locale.ROOT)).get(null);
                    Object modifier = bridge.constructor.newInstance(name, statType, amount, bridge.add);
                    bridge.nonPersistent.invoke(modifier);
                    // Adding the same name replaces it in one recalculation, avoiding a temporary max-health drop.
                    bridge.addModifier.invoke(user, modifier);
                    state.modifiers.put(stat, modifier);
                    state.amounts.put(stat, amount);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            warn("AuraSkills bonuses", error);
            clearStored(id);
        }
    }

    public void clearBonuses(Player player) {
        UUID id = player.getUniqueId();
        clearStored(id);
        try {
            AuraBridge bridge = aura();
            Object user = bridge == null ? null : bridge.loadedUser(id);
            if (user != null) clear(new BonusState(bridge, user));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            warn("AuraSkills bonus cleanup", error);
        }
    }

    @Override public void close() {
        for (UUID id : Set.copyOf(bonuses.keySet())) clearStored(id);
        auraBridge = null;
    }

    private void clearStored(UUID id) {
        BonusState state = bonuses.get(id);
        if (state != null && clear(state)) bonuses.remove(id);
    }

    private boolean clear(BonusState state) {
        boolean success = true;
        for (String stat : STATS) {
            try {
                String name = modifierName(stat);
                if (state.bridge.getModifier.invoke(state.user, name) != null)
                    state.bridge.removeModifier.invoke(state.user, name);
                state.modifiers.remove(stat);
                state.amounts.remove(stat);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                success = false;
                warn("AuraSkills bonus cleanup", error);
            }
        }
        return success;
    }

    private AuraBridge aura() throws ReflectiveOperationException {
        Plugin aura = enabled("AuraSkills");
        if (aura == null) return null;
        if (auraBridge == null || auraBridge.plugin != aura) auraBridge = new AuraBridge(aura);
        return auraBridge;
    }

    private final class AuraBridge {
        final Plugin plugin;
        final Class<?> api, skills, stats;
        final Method getUser, isLoaded, skillLevel, getModifier, addModifier, removeModifier, nonPersistent;
        final Constructor<?> constructor;
        final Object add;

        AuraBridge(Plugin plugin) throws ReflectiveOperationException {
            this.plugin = plugin;
            api = load(plugin, AURA + "AuraSkillsApi");
            Class<?> user = load(plugin, AURA + "user.SkillsUser");
            Class<?> skill = load(plugin, AURA + "skill.Skill");
            skills = load(plugin, AURA + "skill.Skills");
            Class<?> stat = load(plugin, AURA + "stat.Stat");
            stats = load(plugin, AURA + "stat.Stats");
            Class<?> modifier = load(plugin, AURA + "stat.StatModifier");
            Class<?> operation = load(plugin, AURA + "util.AuraSkillsModifier$Operation");
            getUser = api.getMethod("getUser", UUID.class);
            isLoaded = user.getMethod("isLoaded");
            skillLevel = user.getMethod("getSkillLevel", skill);
            getModifier = user.getMethod("getStatModifier", String.class);
            addModifier = user.getMethod("addStatModifier", modifier);
            removeModifier = user.getMethod("removeStatModifier", String.class);
            constructor = modifier.getConstructor(String.class, stat, double.class, operation);
            nonPersistent = modifier.getMethod("setNonPersistent");
            add = operation.getField("ADD").get(null);
        }

        Object loadedUser(UUID id) throws ReflectiveOperationException {
            Object instance = api.getMethod("get").invoke(null);
            Object user = getUser.invoke(instance, id);
            return user != null && Boolean.TRUE.equals(isLoaded.invoke(user)) ? user : null;
        }
    }

    private static final class BonusState {
        final AuraBridge bridge;
        Object user;
        final Map<String, Object> modifiers = new LinkedHashMap<>();
        final Map<String, Double> amounts = new HashMap<>();
        BonusState(AuraBridge bridge, Object user) { this.bridge = bridge; this.user = user; }
    }

    private static String modifierName(String stat) { return "planetryitems:" + stat; }
    private Class<?> load(Plugin owner, String name) throws ClassNotFoundException {
        return classes.load(name, owner.getClass().getClassLoader());
    }
    private Plugin enabled(String name) {
        Plugin value = plugin.getServer().getPluginManager().getPlugin(name);
        return value != null && value.isEnabled() ? value : null;
    }
    private void warn(String integration, Throwable error) {
        if (warnings.add(integration)) plugin.getLogger().warning(integration + " integration is unavailable ("
                + error.getClass().getSimpleName() + "). Further warnings for this integration are suppressed.");
    }
}
