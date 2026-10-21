package dev.flint.planetry.items.probe;

import java.util.UUID;
import org.bukkit.plugin.java.JavaPlugin;

/** Only the public cached Core level contract; no live database or real Core claim. */
public final class CoreFixture extends JavaPlugin {
    public int level = 1;
    public boolean loaded = true;
    public Progress cachedProgress(UUID id) { return loaded ? new Progress(level) : null; }
    public record Progress(int level) { }
}
