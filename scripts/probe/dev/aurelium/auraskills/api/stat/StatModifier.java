package dev.aurelium.auraskills.api.stat;
import dev.aurelium.auraskills.api.util.AuraSkillsModifier;
public final class StatModifier extends AuraSkillsModifier {
    private final String name;
    private final Stat stat;
    private final double value;
    private final Operation operation;
    public StatModifier(String name, Stat stat, double value, Operation operation) {
        this.name = name; this.stat = stat; this.value = value; this.operation = operation;
    }
    public String getName() { return name; }
    public Stat getStat() { return stat; }
    public double getValue() { return value; }
    public Operation getOperation() { return operation; }
}
