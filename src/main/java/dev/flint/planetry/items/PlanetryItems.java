package dev.flint.planetry.items;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Public entry point for Planetry's optional custom-item service. */
public final class PlanetryItems extends JavaPlugin implements TabCompleter {
    private ItemCatalog catalog;
    private ItemLanguage language;
    private IntegrationHooks hooks;
    private ItemService items;
    private ItemGuard guard;
    private int refreshTicks;

    @Override public void onEnable() {
        hooks = new IntegrationHooks(this);
        items = new ItemService(this);
        try {
            for (String file : List.of("config.yml", "items.yml", "lang.yml")) {
                if (!getDataFolder().toPath().resolve(file).toFile().exists()) saveResource(file, false);
            }
            loadDefinitions();
            guard = new ItemGuard(this);
            Bukkit.getPluginManager().registerEvents(guard, this);
            Objects.requireNonNull(getCommand("planetryitems")).setExecutor(this);
            getCommand("planetryitems").setTabCompleter(this);
            // Fixed scheduler supports atomic interval reload without replacing running tasks.
            Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
                int elapsed;
                @Override public void run() {
                    if (Bukkit.getCurrentTick() % 20 == 0)
                        for (Player player : Bukkit.getOnlinePlayers()) guard.flightTick(player);
                    if (++elapsed < refreshTicks) return;
                    elapsed = 0;
                    for (Player player : Bukkit.getOnlinePlayers()) guard.reconcile(player);
                }
            }, 1, 1);
            Bukkit.getOnlinePlayers().forEach(guard::later);
            getLogger().info("PlanetryItems enabled with " + catalog.definitions().size() + " item definitions.");
        } catch (Exception error) {
            getLogger().severe("Cannot load PlanetryItems: " + error.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    /** Commit all parsed files together; invalid reloads retain the active catalog and language. */
    private void loadDefinitions() throws Exception {
        var data = getDataFolder().toPath();
        var configuration = new YamlConfiguration();
        configuration.load(data.resolve("config.yml").toFile());
        Object rawTicks = configuration.get("refresh-interval-ticks", 20);
        if (!(rawTicks instanceof Integer ticks) || ticks < 1 || ticks > 1200)
            throw new IllegalArgumentException("refresh-interval-ticks must be an integer from 1 to 1200");
        ItemCatalog nextCatalog = ItemCatalog.load(data.resolve("items.yml"));
        items.validate(nextCatalog);
        ItemLanguage nextLanguage = ItemLanguage.load(data.resolve("lang.yml"));
        for (var definition : nextCatalog.definitions().values()) {
            nextLanguage.render(definition.displayName(), Map.of(), hooks::glyph);
            nextLanguage.lore(definition, definition.maxDurability(), definition.requirements().keySet(), hooks::glyph);
        }
        catalog = nextCatalog; language = nextLanguage; refreshTicks = ticks;
    }

    @Override public void onDisable() { if (hooks != null) hooks.close(); }
    public ItemCatalog catalog() { return catalog; }
    public ItemLanguage language() { return language; }
    public IntegrationHooks hooks() { return hooks; }
    public ItemService items() { return items; }
    public ItemGuard guard() { return guard; }

    public void message(CommandSender recipient, String key, Map<String, String> values) {
        recipient.sendMessage(language.text(key, values, hooks::glyph));
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("planetryitems.admin")) { message(sender, "messages.no-permission", Map.of()); return true; }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            try {
                loadDefinitions();
                Bukkit.getOnlinePlayers().forEach(guard::reconcile);
                message(sender, "messages.reloaded", Map.of());
            } catch (Exception error) {
                getLogger().warning("Reload rejected: " + error.getMessage());
                message(sender, "messages.reload-failed", Map.of("error", "Invalid configuration. See the server console."));
            }
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
            message(sender, "messages.list", Map.of("items", String.join(", ", catalog.definitions().keySet()))); return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("info")) {
            if (!(sender instanceof Player player)) { message(sender, "messages.player-only", Map.of()); return true; }
            ItemStack stack = player.getInventory().getItemInMainHand();
            var def = items.definition(stack);
            if (def == null) { message(sender, "messages.no-item", Map.of()); return true; }
            message(sender, "messages.info", Map.of("id", def.id(), "current", Integer.toString(items.currentDurability(stack)),
                    "max", Integer.toString(def.maxDurability()))); return true;
        }
        if (args.length >= 3 && args.length <= 4 && args[0].equalsIgnoreCase("give")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) { message(sender, "messages.player-not-found", Map.of("player", args[1])); return true; }
            if (catalog.get(args[2]) == null) { message(sender, "messages.unknown-item", Map.of("item", args[2])); return true; }
            int amount;
            try { amount = args.length == 4 ? Integer.parseInt(args[3]) : 1; if (amount < 1 || amount > 2304) throw new NumberFormatException(); }
            catch (NumberFormatException e) { message(sender, "messages.invalid-amount", Map.of()); return true; }
            if (!hooks.inventoryReady(target)) { message(sender, "messages.integration-unavailable", Map.of()); return true; }
            try {
                ItemStack prototype = items.create(args[2], target);
                // Stage all stacks in a private inventory first. A full inventory never gets a partial grant.
                var staging = Bukkit.createInventory(null, 36);
                staging.setStorageContents(target.getInventory().getStorageContents());
                for (int remaining = amount; remaining > 0;) {
                    int count = Math.min(remaining, prototype.getMaxStackSize());
                    ItemStack stack = prototype.clone(); stack.setAmount(count);
                    if (!staging.addItem(stack).isEmpty()) { message(sender, "messages.inventory-full", Map.of("player", target.getName())); return true; }
                    remaining -= count;
                }
                target.getInventory().setStorageContents(staging.getStorageContents());
                guard.reconcile(target);
                var values = Map.of("player", target.getName(), "item", args[2], "amount", Integer.toString(amount));
                message(sender, "messages.given", values);
                if (sender != target) message(target, "messages.received", values);
            } catch (IllegalArgumentException | IllegalStateException error) {
                getLogger().warning("Could not give item " + args[2] + ": " + error.getMessage());
                message(sender, "messages.integration-unavailable", Map.of());
            }
            return true;
        }
        message(sender, "messages.usage", Map.of()); return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("planetryitems.admin")) return List.of();
        List<String> options = args.length == 1 ? List.of("give", "list", "reload", "info")
                : args.length == 2 && args[0].equalsIgnoreCase("give") ? Bukkit.getOnlinePlayers().stream().map(Player::getName).toList()
                : args.length == 3 && args[0].equalsIgnoreCase("give") ? new ArrayList<>(catalog.definitions().keySet()) : List.of();
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
