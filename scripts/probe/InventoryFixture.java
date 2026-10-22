package dev.flint.planetry.items.probe;

import java.util.UUID;
import org.bukkit.plugin.java.JavaPlugin;

/** Main-thread readiness seam, not real Inventory Sync acceptance. */
public final class InventoryFixture extends JavaPlugin {
    public boolean ready = true;
    public int readinessChecks;
    public int readyForChecks = Integer.MAX_VALUE;
    public Runnable beforeReadiness;
    public boolean isReady(UUID id) {
        readinessChecks++;
        if (beforeReadiness != null) beforeReadiness.run();
        return ready && readinessChecks <= readyForChecks;
    }
}
