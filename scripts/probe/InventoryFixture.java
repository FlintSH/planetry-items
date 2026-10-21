package dev.flint.planetry.items.probe;

import java.util.UUID;
import org.bukkit.plugin.java.JavaPlugin;

/** Main-thread readiness seam, not real Inventory Sync acceptance. */
public final class InventoryFixture extends JavaPlugin {
    public boolean ready = true;
    public boolean isReady(UUID id) { return ready; }
}
