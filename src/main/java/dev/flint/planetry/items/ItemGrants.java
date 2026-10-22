package dev.flint.planetry.items;

import java.util.Arrays;
import java.util.Map;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** Shared synchronous grant path for local commands and the optional network bridge. */
final class ItemGrants {
    enum Result { SUCCESS, OFFLINE, UNKNOWN_ITEM, INVALID_AMOUNT, INVENTORY_UNAVAILABLE, INVENTORY_FULL, UNAVAILABLE }

    private ItemGrants() { }

    /** Narrow engine boundary; tests do not need registry-backed item/guard implementations. */
    interface Context {
        boolean available();
        ItemCatalog catalog();
        boolean inventoryReady(Player player);
        ItemStack create(String id, Player player);
        void reconcile(Player player);
        Logger logger();
    }

    private record PluginContext(PlanetryItems plugin) implements Context {
        public boolean available() { return ItemGrants.available(plugin); }
        public ItemCatalog catalog() { return plugin.catalog(); }
        public boolean inventoryReady(Player player) { return plugin.hooks().inventoryReady(player); }
        public ItemStack create(String id, Player player) { return plugin.items().create(id, player); }
        public void reconcile(Player player) { plugin.guard().reconcile(player); }
        public Logger logger() { return plugin.getLogger(); }
    }

    static boolean available(PlanetryItems plugin) {
        return Bukkit.isPrimaryThread() && plugin.isEnabled() && plugin.catalog() != null
                && plugin.items() != null && plugin.hooks() != null && plugin.guard() != null;
    }

    private static boolean available(Context context) {
        return Bukkit.isPrimaryThread() && context.available() && context.catalog() != null;
    }

    static List<String> ids(PlanetryItems plugin, String prefix) { return ids(new PluginContext(plugin), prefix); }

    static List<String> ids(Context context, String prefix) {
        if (!available(context) || prefix == null || !prefix.matches("[a-z0-9_-]{0,64}")) return List.of();
        return context.catalog().definitions().keySet().stream().filter(id -> id.startsWith(prefix)).sorted().limit(100).toList();
    }

    private static boolean current(Player player) {
        return player != null && player.isOnline() && player.isValid() && !player.isDead()
                && Bukkit.getPlayer(player.getUniqueId()) == player;
    }

    static Result give(PlanetryItems plugin, Player player, String id, int amount) {
        return give(new PluginContext(plugin), player, id, amount);
    }

    static Result give(Context context, Player player, String id, int amount) {
        if (!available(context)) return Result.UNAVAILABLE;
        ItemCatalog catalog = context.catalog();
        if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,63}") || catalog.get(id) == null)
            return Result.UNKNOWN_ITEM;
        if (amount < 1 || amount > 2304) return Result.INVALID_AMOUNT;
        PlayerInventory inventory;
        ItemStack[] granted;
        try {
            if (!current(player)) return Result.OFFLINE;
            if (!context.inventoryReady(player)) return Result.INVENTORY_UNAVAILABLE;
            inventory = player.getInventory();
            ItemStack[] original = copy(inventory.getStorageContents());
            if (original.length != 36) return Result.UNAVAILABLE;
            ItemStack prototype = context.create(id, player);
            var staging = Bukkit.createInventory(null, 36);
            staging.setMaxStackSize(inventory.getMaxStackSize());
            staging.setStorageContents(copy(original));
            int limit = Math.min(prototype.getMaxStackSize(), staging.getMaxStackSize());
            if (limit < 1) return Result.UNAVAILABLE;
            for (int remaining = amount; remaining > 0;) {
                int count = Math.min(remaining, limit);
                ItemStack portion = prototype.clone();
                portion.setAmount(count);
                if (!staging.addItem(portion).isEmpty()) return Result.INVENTORY_FULL;
                remaining -= count;
            }
            granted = copy(staging.getStorageContents());
            // Optional hooks can call other plugins synchronously. Recheck at the actual write,
            // including session identity and intervening changes to the inventory snapshot.
            if (!current(player)) return Result.OFFLINE;
            if (!context.inventoryReady(player)) return Result.INVENTORY_UNAVAILABLE;
            if (!current(player)) return Result.OFFLINE;
            if (!available(context) || context.catalog() != catalog) return Result.UNAVAILABLE;
            if (player.getInventory() != inventory || !Arrays.equals(original, inventory.getStorageContents()))
                return Result.INVENTORY_UNAVAILABLE;
        } catch (RuntimeException | LinkageError error) {
            warn(context.logger(), id, "preparation", error);
            return Result.UNAVAILABLE;
        }
        // Once the write starts, an exception can mean partial/complete delivery. Propagate it
        // so the bridge preserves its uncertain reservation instead of acknowledging rejection.
        inventory.setStorageContents(granted);
        // Storage has committed. Never report a failed grant or retry it because a later
        // refresh/notification failed. The existing guard will also reconcile periodically.
        try { context.reconcile(player); }
        catch (RuntimeException | LinkageError error) { warn(context.logger(), id, "reconciliation after grant", error); }
        return Result.SUCCESS;
    }

    static void notify(PlanetryItems plugin, CommandSender sender, Player player, String id, int amount) {
        Map<String, String> values;
        try { values = Map.of("player", player.getName(), "item", id, "amount", Integer.toString(amount)); }
        catch (RuntimeException | LinkageError error) {
            warn(plugin, id, "notification preparation after grant", error);
            return;
        }
        if (sender != null) {
            try { plugin.message(sender, "messages.given", values); }
            catch (RuntimeException | LinkageError error) { warn(plugin, id, "sender notification after grant", error); }
        }
        if (sender != player) {
            try { plugin.message(player, "messages.received", values); }
            catch (RuntimeException | LinkageError error) { warn(plugin, id, "recipient notification after grant", error); }
        }
    }

    private static ItemStack[] copy(ItemStack[] source) {
        return Arrays.stream(source).map(stack -> stack == null ? null : stack.clone()).toArray(ItemStack[]::new);
    }

    private static void warn(PlanetryItems plugin, String id, String phase, Throwable error) {
        warn(plugin.getLogger(), id, phase, error);
    }

    private static void warn(Logger logger, String id, String phase, Throwable error) {
        logger.warning("Item " + id + " " + phase + " failed: " + error.getMessage());
    }
}
