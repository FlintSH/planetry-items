package dev.aurelium.auraskills.api;
import java.util.UUID;
import dev.aurelium.auraskills.api.user.SkillsUser;
import dev.flint.planetry.items.probe.AuraFixture;
public interface AuraSkillsApi {
    static AuraSkillsApi get() { return AuraFixture.api; }
    SkillsUser getUser(UUID id);
}
