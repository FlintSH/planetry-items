package dev.flint.planetry.items;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntegrationHooksTest {
    private final JavaPlugin plugin = mock(JavaPlugin.class);
    private final Server server = mock(Server.class);
    private final PluginManager plugins = mock(PluginManager.class);
    private final Player player = mock(Player.class);
    private final UUID id = UUID.randomUUID();
    private IntegrationHooks hooks;

    @BeforeEach void setup() {
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(server.getPluginManager()).thenReturn(plugins);
        when(player.getUniqueId()).thenReturn(id);
        FakeApi.current = new FakeApi();
        FakeBuilder.stack = null;
        hooks = new IntegrationHooks(plugin, (name, loader) -> switch (name) {
            case "dev.aurelium.auraskills.api.AuraSkillsApi" -> FakeApi.class;
            case "dev.aurelium.auraskills.api.user.SkillsUser" -> FakeUser.class;
            case "dev.aurelium.auraskills.api.skill.Skill" -> Skill.class;
            case "dev.aurelium.auraskills.api.skill.Skills" -> Skills.class;
            case "dev.aurelium.auraskills.api.stat.Stat" -> Stat.class;
            case "dev.aurelium.auraskills.api.stat.Stats" -> Stats.class;
            case "dev.aurelium.auraskills.api.stat.StatModifier" -> Modifier.class;
            case "dev.aurelium.auraskills.api.util.AuraSkillsModifier$Operation" -> Operation.class;
            case "com.nexomc.nexo.api.NexoItems" -> FakeNexo.class;
            case "com.nexomc.nexo.glyphs.GlyphTag" -> FakeGlyph.class;
            case "com.nexomc.nexo.glyphs.ShiftTag" -> FakeShift.class;
            default -> throw new ClassNotFoundException(name);
        }, ItemStack::clone);
    }

    private <T extends Plugin> T enabled(String name, Class<T> type) {
        T result = mock(type);
        when(plugins.getPlugin(name)).thenReturn(result);
        when(result.isEnabled()).thenReturn(true);
        return result;
    }

    @Test void absentOptionalPluginsHaveSafeDefaults() {
        assertTrue(hooks.inventoryReady(player));
        assertNull(hooks.gameLevel(player));
        assertNull(hooks.skillLevel(player, "mining"));
        assertNull(hooks.glyph("tag_common"));
        assertThrows(IllegalStateException.class, () -> hooks.nexoItem("sample"));
        hooks.updateBonuses(player, Map.of("auraskills-additional-health", 10.0));
        hooks.close();
    }

    @Test void installedDisabledOrIncompatibleSyncFailsClosed() {
        Plugin sync = enabled("NetworkInventorySync", Plugin.class);
        assertFalse(hooks.inventoryReady(player));
        when(sync.isEnabled()).thenReturn(false);
        assertFalse(hooks.inventoryReady(player));
    }

    @Test void readinessUsesCurrentUuidAndActualReturn() {
        ReadyPlugin sync = enabled("NetworkInventorySync", ReadyPlugin.class);
        when(sync.isReady(id)).thenReturn(true, false);
        assertTrue(hooks.inventoryReady(player));
        assertFalse(hooks.inventoryReady(player));
        verify(sync, times(2)).isReady(id);
    }

    @Test void gameLevelUsesCoreCacheAndNeverVanillaExperience() {
        CorePlugin core = enabled("PlanetryCore", CorePlugin.class);
        when(core.cachedProgress(id)).thenReturn(new Progress(42), (Progress) null);
        assertEquals(42, hooks.gameLevel(player));
        assertNull(hooks.gameLevel(player));
        verify(player, never()).getLevel();
        when(core.isEnabled()).thenReturn(false);
        assertNull(hooks.gameLevel(player));
    }

    @Test void skillLevelsRequireLoadedUsers() {
        enabled("AuraSkills", Plugin.class);
        FakeApi.current.user.level = 17;
        assertEquals(17, hooks.skillLevel(player, "mining"));
        assertSame(Skills.MINING, FakeApi.current.user.queried);
        FakeApi.current.user.loaded = false;
        assertNull(hooks.skillLevel(player, "mining"));
        assertNull(hooks.skillLevel(player, "unknown"));
    }

    @Test void bonusesAreNonpersistentAndDoNotOverwriteOtherPlugins() {
        enabled("AuraSkills", Plugin.class);
        FakeUser user = FakeApi.current.user;
        Modifier foreign = new Modifier("another:health", Stats.HEALTH, 9, Operation.ADD);
        user.modifiers.put(foreign.name, foreign);
        hooks.updateBonuses(player, Map.of("auraskills-additional-health", 7.0, "auraskills-additional-wisdom", 2.0));
        assertEquals(3, user.modifiers.size());
        Modifier health = user.modifiers.get("planetryitems:health");
        assertEquals(7, health.value);
        assertTrue(health.nonPersistent);
        assertSame(Operation.ADD, health.operation);
        assertSame(foreign, user.modifiers.get(foreign.name));
        hooks.close();
        assertEquals(Map.of(foreign.name, foreign), user.modifiers);
    }

    @Test void unchangedTotalsDoNotReapplyAndChangedAmountsReplaceWithoutRemoval() {
        enabled("AuraSkills", Plugin.class);
        FakeUser user = FakeApi.current.user;
        hooks.updateBonuses(player, Map.of("auraskills-additional-health", 4.0));
        Modifier first = user.modifiers.get("planetryitems:health");
        hooks.updateBonuses(player, Map.of("auraskills-additional-health", 4.0));
        assertSame(first, user.modifiers.get(first.name));
        assertEquals(1, user.adds);
        hooks.updateBonuses(player, Map.of("auraskills-additional-health", 5.0));
        assertNotSame(first, user.modifiers.get(first.name));
        assertEquals(2, user.adds);
        assertEquals(0, user.removes);
        hooks.updateBonuses(player, Map.of());
        assertTrue(user.modifiers.isEmpty());
        assertEquals(1, user.removes);
    }

    @Test void unloadAndDisabledDependencyRemoveOwnedBonuses() {
        Plugin aura = enabled("AuraSkills", Plugin.class);
        FakeUser user = FakeApi.current.user;
        hooks.updateBonuses(player, Map.of("auraskills-additional-strength", 3.0));
        user.loaded = false;
        hooks.updateBonuses(player, Map.of("auraskills-additional-strength", 3.0));
        assertTrue(user.modifiers.isEmpty());
        user.loaded = true;
        hooks.updateBonuses(player, Map.of("auraskills-additional-strength", 3.0));
        when(aura.isEnabled()).thenReturn(false);
        hooks.updateBonuses(player, Map.of("auraskills-additional-strength", 3.0));
        assertTrue(user.modifiers.isEmpty());
    }

    @Test void clearAndZeroOnlyRemoveOwnedNames() {
        enabled("AuraSkills", Plugin.class);
        FakeUser user = FakeApi.current.user;
        user.modifiers.put("foreign", new Modifier("foreign", Stats.WISDOM, 5, Operation.ADD));
        user.modifiers.put("planetryitems:wisdom", new Modifier("planetryitems:wisdom", Stats.WISDOM, 5, Operation.ADD));
        hooks.updateBonuses(player, Map.of("auraskills-additional-wisdom", 0.0));
        assertEquals(1, user.modifiers.size());
        hooks.updateBonuses(player, Map.of("auraskills-additional-toughness", 2.0));
        hooks.clearBonuses(player);
        assertEquals(Set.of("foreign"), user.modifiers.keySet());
    }

    @Test void nativeGlyphKeepsItsFontAndUnknownOrUnsafeIdsFallBack() {
        enabled("Nexo", Plugin.class);
        Component glyph = hooks.glyph("tag_common");
        assertEquals(Component.text("\uE001").font(Key.key("nexo", "rarity")), glyph);
        assertNull(hooks.glyph("missing"));
        assertNull(hooks.glyph("tag_common><red>"));
    }

    @Test void nativeArgumentsAndShiftAreForwardedAndDisabledHooksStopResolving() {
        Plugin nexo = enabled("Nexo", Plugin.class);
        var tags = hooks.glyphs(player); // Current API only provides the global getter.
        var mini = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage();
        assertEquals(Component.text("SHIFT[-8]"), mini.deserialize("<shift:-8>", tags));
        var options = mini.deserialize("<glyph:options:2..3:colorable:shadow:#80ffffff>", tags);
        assertEquals(Component.text("2..3,colorable,shadow,#80ffffff").font(Key.key("nexo:rarity")), options);
        when(nexo.isEnabled()).thenReturn(false);
        assertEquals(Component.text("<glyph:tag_common>"), mini.deserialize("<glyph:tag_common>", tags));
    }

    @Test void olderPlayerAwareResolverReceivesViewerAndConsoleUsesGlobal() {
        enabled("Nexo", Plugin.class);
        var playerHooks = nativeHooks(PlayerGlyph.class, FakeShift.class);
        var mini = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage();
        assertEquals(Component.text(id.toString()), mini.deserialize("<glyph:tag_common>", playerHooks.glyphs(player)));
        assertEquals(Component.text("global"), mini.deserialize("<glyph:tag_common>", playerHooks.glyphs(null)));
    }

    @Test void incompatibleAndThrowingNativeApisFailIndependently() {
        enabled("Nexo", Plugin.class);
        var mini = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage();
        for (Class<?> bad : new Class<?>[]{String.class, ThrowingGlyph.class, BadResolver.class}) {
            var tags = nativeHooks(bad, FakeShift.class).glyphs(player);
            assertEquals(Component.text("<glyph:tag_common>SHIFT[8]"),
                    mini.deserialize("<glyph:tag_common><shift:8>", tags));
        }
        assertEquals(Component.text("\uE001").font(Key.key("nexo:rarity")),
                mini.deserialize("<glyph:tag_common>", nativeHooks(FakeGlyph.class, String.class).glyphs(player)));
    }

    private IntegrationHooks nativeHooks(Class<?> glyph, Class<?> shift) {
        return new IntegrationHooks(plugin, (name, loader) -> switch (name) {
            case "com.nexomc.nexo.glyphs.GlyphTag" -> glyph;
            case "com.nexomc.nexo.glyphs.ShiftTag" -> shift;
            default -> throw new ClassNotFoundException(name);
        });
    }

    @Test void nexoBuildUsesAppearanceCopierAndMissingIdsFailSafely() {
        enabled("Nexo", Plugin.class);
        ItemStack source = mock(ItemStack.class);
        ItemStack clone = mock(ItemStack.class);
        when(source.getType()).thenReturn(Material.PAPER);
        when(source.clone()).thenReturn(clone);
        FakeBuilder.stack = source;
        assertSame(clone, hooks.nexoItem("sample"));
        assertThrows(IllegalStateException.class, () -> hooks.nexoItem("missing"));
    }

    public abstract static class ReadyPlugin extends JavaPlugin { public abstract boolean isReady(UUID id); }
    public abstract static class CorePlugin extends JavaPlugin { public abstract Progress cachedProgress(UUID id); }
    public record Progress(int level) { }
    public interface Skill { }
    public enum Skills implements Skill { FARMING, FORAGING, MINING, FISHING, FIGHTING }
    public interface Stat { }
    public enum Stats implements Stat { WISDOM, HEALTH, REGENERATION, TOUGHNESS, STRENGTH }
    public enum Operation { ADD }
    public static final class Modifier {
        final String name;
        final Stat stat;
        final double value;
        final Operation operation;
        boolean nonPersistent;
        public Modifier(String name, Stat stat, double value, Operation operation) {
            this.name = name; this.stat = stat; this.value = value; this.operation = operation;
        }
        public void setNonPersistent() { nonPersistent = true; }
    }
    public static final class FakeApi {
        static FakeApi current;
        final FakeUser user = new FakeUser();
        public static FakeApi get() { return current; }
        public FakeUser getUser(UUID id) { return user; }
    }
    public static final class FakeUser {
        boolean loaded = true;
        int level;
        Skill queried;
        int adds, removes;
        final Map<String, Modifier> modifiers = new HashMap<>();
        public boolean isLoaded() { return loaded; }
        public int getSkillLevel(Skill skill) { queried = skill; return level; }
        public Modifier getStatModifier(String name) { return modifiers.get(name); }
        public void addStatModifier(Modifier value) { modifiers.put(value.name, value); adds++; }
        public void removeStatModifier(String name) { modifiers.remove(name); removes++; }
    }
    public static final class FakeNexo {
        public static FakeBuilder itemFromId(String id) { return id.equals("sample") ? new FakeBuilder() : null; }
    }
    public static final class FakeBuilder {
        static ItemStack stack;
        public ItemStack build() { return stack; }
    }
    public static final class FakeGlyph {
        public static final FakeGlyph INSTANCE = new FakeGlyph();
        public TagResolver getRESOLVER() {
            return TagResolver.resolver("glyph", (arguments, context) -> {
                String name = arguments.pop().value();
                if (name.equals("tag_common")) return Tag.inserting(Component.text("\uE001").font(Key.key("nexo:rarity")));
                if (!name.equals("options")) return null;
                var options = new java.util.ArrayList<String>();
                while (arguments.hasNext()) options.add(arguments.pop().value());
                return Tag.inserting(Component.text(String.join(",", options)).font(Key.key("nexo:rarity")));
            });
        }
    }
    public static final class FakeShift {
        public static final FakeShift INSTANCE = new FakeShift();
        public TagResolver getRESOLVER() {
            return TagResolver.resolver("shift", (args, context) -> Tag.inserting(Component.text("SHIFT[" + args.pop().value() + "]")));
        }
    }
    public static final class PlayerGlyph {
        public static final PlayerGlyph INSTANCE = new PlayerGlyph();
        public TagResolver getResolverForPlayer(Player viewer) {
            return TagResolver.resolver("glyph", (args, context) -> { args.pop(); return Tag.inserting(Component.text(viewer.getUniqueId().toString())); });
        }
        public TagResolver getRESOLVER() { return TagResolver.resolver("glyph", (args, context) -> { args.pop(); return Tag.inserting(Component.text("global")); }); }
    }
    public static final class ThrowingGlyph {
        public static final ThrowingGlyph INSTANCE = new ThrowingGlyph();
        public TagResolver getRESOLVER() {
            return TagResolver.resolver("glyph", (args, context) -> { args.pop(); throw new LinkageError("test"); });
        }
    }
    public static final class BadResolver {
        public static final BadResolver INSTANCE = new BadResolver();
        public Object getRESOLVER() { return "incompatible"; }
    }
}
