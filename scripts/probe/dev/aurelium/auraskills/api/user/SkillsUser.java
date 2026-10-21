package dev.aurelium.auraskills.api.user;
import dev.aurelium.auraskills.api.skill.Skill;
import dev.aurelium.auraskills.api.stat.StatModifier;
public interface SkillsUser {
    boolean isLoaded();
    int getSkillLevel(Skill skill);
    void addStatModifier(StatModifier modifier);
    void removeStatModifier(String name);
    StatModifier getStatModifier(String name);
}
