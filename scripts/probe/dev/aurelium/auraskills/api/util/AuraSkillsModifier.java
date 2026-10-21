package dev.aurelium.auraskills.api.util;
public abstract class AuraSkillsModifier {
    public enum Operation { ADD }
    private boolean persistent = true;
    public void setNonPersistent() { persistent = false; }
    public boolean isPersistent() { return persistent; }
}
