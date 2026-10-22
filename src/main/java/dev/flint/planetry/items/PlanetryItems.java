package dev.flint.planetry.items;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
    private ItemPolicy policy;
    private SoulboundRecovery recovery;
    private int refreshTicks;
    private static final long MIGRATE_COOLDOWN = 2 * 60 * 60 * 1000L;

    @Override public void onEnable() {
        hooks = new IntegrationHooks(this);
        items = new ItemService(this);
        try {
            for (String file : List.of("config.yml", "lang.yml")) {
                if (!getDataFolder().toPath().resolve(file).toFile().exists()) saveResource(file, false);
            }
            ItemFiles.initialize(getDataFolder().toPath(), getResource("items.yml"));
            loadDefinitions();
            policy = new ItemPolicy(this);
            recovery = new SoulboundRecovery(this);
            guard = new ItemGuard(this);
            Bukkit.getPluginManager().registerEvents(guard, this);
            Bukkit.getPluginManager().registerEvents(policy, this);
            Objects.requireNonNull(getCommand("planetryitems")).setExecutor(this);
            getCommand("planetryitems").setTabCompleter(this);
            Objects.requireNonNull(getCommand("migrate")).setExecutor(this);
            getCommand("migrate").setTabCompleter(this);
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
            Bukkit.getOnlinePlayers().forEach(guard::beginSession);
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
        ItemCatalog nextCatalog = ItemCatalog.load(data.resolve("items"));
        items.validate(nextCatalog);
        ItemLanguage nextLanguage = ItemLanguage.load(data.resolve("lang.yml"));
        for (var definition : nextCatalog.definitions().values()) {
            nextLanguage.render(definition.displayName(), Map.of(), hooks.glyphs(null), null);
            nextLanguage.lore(definition, definition.maxDurability(), definition.requirements().keySet(), hooks.glyphs(null), null);
        }
        catalog = nextCatalog; language = nextLanguage; refreshTicks = ticks;
    }

    @Override public void onDisable() {
        if (recovery != null) Bukkit.getOnlinePlayers().forEach(recovery::captureCursor);
        if (hooks != null) hooks.close(); if (policy != null) policy.clear();
    }
    public ItemCatalog catalog() { return catalog; }
    public ItemLanguage language() { return language; }
    public IntegrationHooks hooks() { return hooks; }
    public ItemService items() { return items; }
    public ItemGuard guard() { return guard; }
    public ItemPolicy policy() { return policy; }
    public SoulboundRecovery recovery() { return recovery; }

    public void message(CommandSender recipient, String key, Map<String, String> values) {
        recipient.sendMessage(language.text(key, values, hooks.glyphs(recipient instanceof Player player ? player : null), recipient));
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("migrate")) return migrate(sender, args);
        if (!sender.hasPermission("planetry.admin.items")) { message(sender, "messages.no-permission", Map.of()); return true; }
        if (args.length == 1 && args[0].equalsIgnoreCase("soulboundbypass")) {
            if (!(sender instanceof Player player)) { message(sender, "messages.player-only", Map.of()); return true; }
            boolean enabled = policy().toggleBypass(player);
            message(player, enabled ? "messages.soulbound-bypass-enabled" : "messages.soulbound-bypass-disabled", Map.of());
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            try {
                loadDefinitions();
                policy.clear();
                Bukkit.getOnlinePlayers().forEach(guard::beginSession);
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
            info(player, stack, def); return true;
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

    private boolean migrate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { message(sender, "messages.player-only", Map.of()); return true; }
        if (args.length != 0) { message(sender, "messages.migrate-usage", Map.of()); return true; }
        if (!hooks.inventoryReady(player)) { message(sender, "messages.integration-unavailable", Map.of()); return true; }
        var key = new NamespacedKey(this, "last_migrate");
        var data = player.getPersistentDataContainer();
        long now = System.currentTimeMillis();
        long last = data.getOrDefault(key, PersistentDataType.LONG, 0L);
        long elapsed = now >= last ? now - last : 0;
        if (last > 0 && elapsed < MIGRATE_COOLDOWN) {
            long minutes = Math.max(1, (MIGRATE_COOLDOWN - elapsed + 59_999) / 60_000);
            message(sender, "messages.migrate-cooldown", Map.of("remaining", minutes / 60 + "h " + minutes % 60 + "m"));
            return true;
        }
        var result = guard.migrate(player);
        if (result.failed() == 0) data.set(key, PersistentDataType.LONG, now);
        message(sender, result.failed() == 0 ? "messages.migrate-success" : "messages.migrate-partial",
                Map.of("updated", Integer.toString(result.updated()), "removed", Integer.toString(result.removed()),
                        "failed", Integer.toString(result.failed())));
        return true;
    }

    private void info(Player player, ItemStack stack, ItemDefinition def) {
        var plain = PlainTextComponentSerializer.plainText();
        var attributes = new ArrayList<String>();
        def.attributes().forEach((key, value) -> attributes.add(key + "=" + value.amount()
                + " (" + value.operation() + ", " + value.slot() + ")"));
        var requirements = new ArrayList<String>();
        def.requirements().forEach((key, value) -> requirements.add(language.requirementLabel(key, value, hooks.glyphs(player), player) + " (" + key + ")"));
        var values = new LinkedHashMap<String, String>();
        values.put("id", def.id()); values.put("name", plain.serialize(language.displayName(def, hooks.glyphs(player), player)));
        values.put("material", stack.getType().name()); values.put("configured-material", def.material());
        values.put("rarity", def.rarity()); values.put("type", def.type());
        values.put("model", stack.getItemMeta().getCustomModelDataComponent().getFloats().toString());
        values.put("nexo", def.nexoId().isEmpty() ? "none" : def.nexoId());
        values.put("base64", def.base64().isEmpty() ? "none" : def.base64());
        values.put("equip", def.equipSlot() == null ? "none" : def.equipSlot());
        values.put("amount", Integer.toString(stack.getAmount())); values.put("stack", Integer.toString(def.maxStackSize()));
        values.put("current", Integer.toString(items.currentDurability(stack))); values.put("max", Integer.toString(def.maxDurability()));
        values.put("unbreakable", Boolean.toString(def.unbreakable()));
        values.put("canplace", Boolean.toString(def.canPlace()));
        values.put("soulbound", Boolean.toString(def.soulbound()));
        // Keep existing custom info-header templates working after the YAML rename.
        values.put("planetry-canplace", values.get("canplace"));
        values.put("planetry-soulbound", values.get("soulbound"));
        values.put("attributes", attributes.isEmpty() ? "none" : String.join("; ", attributes));
        values.put("bonuses", def.bonuses().isEmpty() ? "none" : def.bonuses().toString());
        values.put("requirements", requirements.isEmpty() ? "none" : String.join(", ", requirements));
        values.put("lore", String.join(" | ", def.lore().stream().map(line -> plain.serialize(language.render(line, Map.of(), hooks.glyphs(player), player))).toList()));
        values.put("usable", Boolean.toString(items.usable(player, stack)));
        message(player, "messages.info", values);
        // Detail output remains complete even when an administrator retains the old customizable header.
        for (String field : List.of("name", "material", "configured-material", "rarity", "type", "model", "nexo", "base64", "equip",
                "amount", "stack", "unbreakable", "canplace", "soulbound", "attributes", "bonuses", "requirements", "lore", "usable"))
            player.sendMessage(net.kyori.adventure.text.Component.text(field + ": " + values.get(field)));
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("migrate") || !sender.hasPermission("planetry.admin.items")) return List.of();
        List<String> options = args.length == 1 ? List.of("give", "list", "reload", "info", "soulboundbypass")
                : args.length == 2 && args[0].equalsIgnoreCase("give") ? Bukkit.getOnlinePlayers().stream().map(Player::getName).toList()
                : args.length == 3 && args[0].equalsIgnoreCase("give") ? new ArrayList<>(catalog.definitions().keySet()) : List.of();
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
