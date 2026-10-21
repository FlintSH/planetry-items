package dev.flint.planetry.items.probe;

import dev.aurelium.auraskills.api.AuraSkillsApi;
import dev.aurelium.auraskills.api.skill.Skill;
import dev.aurelium.auraskills.api.stat.StatModifier;
import dev.aurelium.auraskills.api.user.SkillsUser;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.plugin.java.JavaPlugin;

/** Controlled AuraSkills levels and transient modifiers, excluded from every release artifact. */
public final class AuraFixture extends JavaPlugin {
    public static boolean loaded = true;
    public static int level = 1;
    public static final Map<UUID, User> users = new HashMap<>();
    public static final AuraSkillsApi api = id -> users.computeIfAbsent(id, ignored -> new User());
    public static final class User implements SkillsUser {
        public final Map<String, StatModifier> modifiers = new HashMap<>();
        public boolean isLoaded() { return loaded; }
        public int getSkillLevel(Skill skill) { return level; }
        public void addStatModifier(StatModifier modifier) { modifiers.put(modifier.getName(), modifier); }
        public void removeStatModifier(String name) { modifiers.remove(name); }
        public StatModifier getStatModifier(String name) { return modifiers.get(name); }
    }
}
