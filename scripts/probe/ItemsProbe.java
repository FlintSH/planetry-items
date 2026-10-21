package dev.flint.planetry.items.probe;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.entity.Snowball;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Real Paper stacks, serialization and Bukkit event dispatch; controlled players and optional API seams. */
public final class ItemsProbe extends JavaPlugin {
    private JavaPlugin plugin;
    private Object items, guard, hooks;
    private Fake player;
    private boolean integrations, finished;
    private String stage = "startup";
    private int groups;
    private Trident pendingProjectile;
    private final List<Entity> spawned = new ArrayList<>();

    @Override public void onEnable() {
        after(1, () -> {
            plugin = (JavaPlugin) Bukkit.getPluginManager().getPlugin("PlanetryItems");
            check(plugin != null && plugin.isEnabled(), "Packaged PlanetryItems did not enable");
            items = call(plugin, "items"); guard = call(plugin, "guard"); hooks = call(plugin, "hooks");
            integrations = Bukkit.getPluginManager().isPluginEnabled("Nexo");
            check(Bukkit.getPluginManager().isPluginEnabled("AuraSkills") == integrations, "Unexpected fixture combination");
            player = new Fake("ItemsProbeUser", false);
            metadata();
            requirements();
            durability();
            eventDurability();
            foodDurability();
            projectileDurability();
            entityAndShieldDurability();
            glidingDurability();
            equipment();
            commands();
            if (integrations) integrationChecks();
            else missingHooks();
            stage = "trident carry after the native post-event overwrite";
            after(2, () -> {
                // The controlled projectile has no network player/shooter and may be discarded
                // by native ticking. Its real carried stack must retain the repaired point.
                check(pendingProjectile != null && durability(pendingProjectile.getItemStack()) == 2,
                        "Native post-event trident carry overwrite lost custom durability");
                passed();
                if (integrations) disabledSync();
                check(plugin.isEnabled(), "Plugin disabled during acceptance");
                finished = true;
                getLogger().info("ITEMS_PROBE_OK: " + groups + " groups; real Paper metadata/serialization/events/projectiles; controlled players; "
                        + (integrations ? "controlled Core/AuraSkills/Nexo/readiness API fixtures" : "optional plugins absent"));
                Bukkit.shutdown();
            });
        });
        after(1200, () -> { throw new IllegalStateException("Probe timeout"); });
    }

    @Override public void onDisable() { spawned.forEach(Entity::remove); }

    private void metadata() {
        stage = "metadata and lore";
        ItemStack bare = create("bare");
        check(bare.getType() == Material.DIAMOND_SWORD && plain(bare.getItemMeta().displayName()).equals("Probe Blade"), "Display name/material differs");
        var defaults = bare.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        check(defaults != null && defaults.modifiers().isEmpty(), "Unconfigured vanilla sword attributes remain active");
        check(lore(bare).size() == 3 && lore(bare).get(1).isEmpty(), "Empty sections did not collapse to one blank: " + lore(bare));
        check(lore(bare).getFirst().equals("ᴡᴇᴀᴘᴏɴ"), "Small-cap item type differs: " + lore(bare));
        ItemStack described = create("described");
        check(described.getMaxStackSize() == 16, "Configured stack size missing");
        check(described.getItemMeta().getCustomModelDataComponent().getFloats().equals(List.of(812f)), "Custom model component differs");
        var lines = lore(described);
        check(lines.indexOf("First probe line") + 1 == lines.indexOf("Second probe line"), "Multiline lore lost order: " + lines);
        check(!String.join("|", lines).contains("Durability"), "Disabled durability still displays a counter");
        ItemStack hat = create("hat");
        var equippable = hat.getData(DataComponentTypes.EQUIPPABLE);
        check(hat.getType() == Material.PAPER && equippable != null && equippable.slot() == EquipmentSlot.HEAD
                && equippable.equipOnInteract(), "Arbitrary paper helmet lacks native equippable component");
        var attributes = hat.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS).modifiers();
        check(attributes.size() == 2 && attributes.stream().anyMatch(entry -> entry.attribute().equals(Attribute.ARMOR)
                && entry.modifier().getAmount() == 2), "Configured armor modifier missing");
        check(attributes.stream().anyMatch(entry -> entry.attribute().equals(Attribute.SCALE)
                && entry.modifier().getAmount() == 0.25), "Registry attribute scale was not applied");
        Double oxygen = hat.getItemMeta().getPersistentDataContainer().get(new NamespacedKey(plugin,"oxygen"), PersistentDataType.DOUBLE);
        check(oxygen != null && oxygen == 100, "Oxygen not persisted in actual item PDC");
        ItemStack roundtrip = ItemStack.deserializeBytes(hat.serializeAsBytes());
        check(roundtrip.equals(hat) && definition(roundtrip) != null, "Paper item serialization lost identity/components");
        ItemStack skull = create("skull");
        check(skull.getItemMeta() instanceof SkullMeta meta && meta.getPlayerProfile() != null
                && meta.getPlayerProfile().getProperties().stream().anyMatch(property -> property.getName().equals("textures")),
                "Base64 head profile property missing");
        for (String id : List.of("stacked_sword", "eternal_tool")) {
            ItemStack stacked = create(id); stacked.setAmount(16);
            check(stacked.getMaxStackSize() == 16 && !stacked.hasData(DataComponentTypes.MAX_DAMAGE)
                    && !stacked.hasData(DataComponentTypes.DAMAGE),
                    "Stacked native tool retained incompatible vanilla damage components: " + id);
            ItemStack restored = ItemStack.deserializeBytes(stacked.serializeAsBytes());
            check(restored.equals(stacked) && restored.getAmount() == 16 && definition(restored) != null,
                    "Native Paper serialization rejected/changed configured tool stacks: " + id);
        }
        passed();
    }

    private void requirements() {
        stage = "requirements and denied actions";
        levels(1);
        ItemStack restricted = create("restricted");
        check(unmet(restricted).size() == 2 && contains(lore(restricted), "Requires"), "Unmet requirement lore missing");
        player.items[0] = restricted;
        PlayerInteractEvent interact = new PlayerInteractEvent(player.player, Action.RIGHT_CLICK_AIR, restricted, null, BlockFace.SELF, EquipmentSlot.HAND);
        fire(interact);
        check(interact.useItemInHand() == Event.Result.DENY, "Restricted right-click allowed");
        check(!player.messages.isEmpty(), "Denied use did not message the player");
        PrePlayerAttackEntityEvent pre = new PrePlayerAttackEntityEvent(player.player, entity(), true);
        fire(pre); check(pre.isCancelled(), "Pre-attack allowed a restricted weapon");
        EntityDamageByEntityEvent attack = new EntityDamageByEntityEvent(player.player, entity(), EntityDamageEvent.DamageCause.ENTITY_ATTACK, source(), 4);
        denied(attack, "restricted melee attack");
        BlockBreakEvent block = new BlockBreakEvent(Bukkit.getWorlds().getFirst().getSpawnLocation().getBlock(), player.player);
        denied(block, "restricted mining");
        denied(new PlayerInteractEntityEvent(player.player, entity(), EquipmentSlot.HAND), "restricted entity interaction");
        denied(new PlayerItemConsumeEvent(player.player, create("food"), EquipmentSlot.HAND), "restricted food");
        ItemStack bow = create("bow");
        denied(new EntityShootBowEvent(player.player, bow, ItemStack.of(Material.ARROW), entity(), EquipmentSlot.HAND, 1f, true), "restricted bow");
        player.items[1] = restricted;
        denied(new PlayerItemHeldEvent(player.player, 0, 1), "restricted hotbar selection");
        denied(new PlayerSwapHandItemsEvent(player.player, restricted, ItemStack.of(Material.AIR)), "restricted hand swap");
        check(durability(restricted) == 10 && durability(bow) == 10, "Denied action consumed durability");
        ItemStack stale = create("bare");
        stale.editMeta(meta -> meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "item_id"),
                PersistentDataType.STRING, "removed_probe_definition"));
        check(definition(stale) == null && Boolean.FALSE.equals(call(items, "usable", player.player, stale)),
                "Removed item definition became usable");
        PlayerInteractEvent staleUse = new PlayerInteractEvent(player.player, Action.RIGHT_CLICK_AIR, stale, null, BlockFace.SELF, EquipmentSlot.HAND);
        fire(staleUse); check(staleUse.useItemInHand() == Event.Result.DENY, "Removed definition bypassed use guard");
        if (integrations) {
            levels(20); call(items, "refresh", restricted, player.player);
            check(unmet(restricted).isEmpty() && !contains(lore(restricted), "Requires"), "Met requirements stayed in lore");
            levels(1); call(items, "refresh", restricted, player.player);
            check(unmet(restricted).size() == 2 && contains(lore(restricted), "Requires"), "Level loss did not restore requirements");
        }
        player.clear();
        passed();
    }

    private void durability() {
        stage = "one-point durability and native break packet";
        ItemStack tool = create("tool"); player.items[0] = tool;
        check(durability(tool) == 3, "Initial custom durability differs");
        check(!damage(tool) && durability(tool) == 2, "First use did not consume exactly one");
        ItemStack saved = ItemStack.deserializeBytes(tool.serializeAsBytes());
        check(durability(saved) == 2, "Durability was not persistent");
        check(!damage(tool) && durability(tool) == 1, "Second use differs");
        check(contains(lore(tool), "Durability: 1/3"), "Current durability absent from lore");
        check(tool.getItemMeta().lore().stream().anyMatch(ItemsProbe::red), "Low durability is not red");
        int breaks = player.breaks, sounds = player.sounds;
        check(damage(tool) && tool.getAmount() == 0 && player.breaks == breaks + 1 && player.sounds == sounds + 1,
                "Exhaustion did not remove stack and issue native slot-break/sound calls");
        ItemStack eternal = create("eternal");
        for (int n = 0; n < 8; n++) check(!damage(eternal), "Unbreakable item broke");
        check(durability(eternal) == 3, "Unbreakable durability decreased");
        player.clear(); passed();
    }

    private void eventDurability() {
        stage = "successful/cancelled use events";
        ItemStack airTool = create("tool"); player.items[0] = airTool;
        PlayerInteractEvent airUse = new PlayerInteractEvent(player.player, Action.RIGHT_CLICK_AIR, airTool, null, BlockFace.SELF, EquipmentSlot.HAND);
        fire(airUse);
        check(airUse.useItemInHand() != Event.Result.DENY && durability(airTool) == 2,
                "Allowed right-click air use was mistaken for a cancelled block interaction");
        PlayerInteractEvent deniedAir = new PlayerInteractEvent(player.player, Action.RIGHT_CLICK_AIR, airTool, null, BlockFace.SELF, EquipmentSlot.HAND);
        deniedAir.setCancelled(true); fire(deniedAir);
        check(durability(airTool) == 2, "Explicitly denied right-click air use consumed durability");
        ItemStack tool = create("tool"); player.items[0] = tool;
        PlayerItemDamageEvent nativeDamage = new PlayerItemDamageEvent(player.player, tool, 7, 7);
        denied(nativeDamage, "vanilla double damage");
        check(durability(tool) == 3, "Native material damage consumed custom durability");
        BlockBreakEvent cancelled = new BlockBreakEvent(Bukkit.getWorlds().getFirst().getSpawnLocation().getBlock(), player.player);
        cancelled.setCancelled(true); fire(cancelled);
        check(durability(tool) == 3, "Cancelled protected break consumed durability");
        fire(new BlockBreakEvent(Bukkit.getWorlds().getFirst().getSpawnLocation().getBlock(), player.player));
        check(durability(tool) == 2, "Successful mining did not consume one point");
        fire(new EntityDamageByEntityEvent(player.player, entity(), EntityDamageEvent.DamageCause.ENTITY_ATTACK, source(), 4));
        check(durability(tool) == 1, "Successful melee did not consume one point");
        player.clear();
        ItemStack hat = create("hat"); player.items[39] = hat;
        fire(new EntityDamageEvent(player.player, EntityDamageEvent.DamageCause.FALL, source(), 2));
        check(durability(hat) == 9, "Worn item damage did not consume one point");
        player.clear(); passed();
    }

    private void equipment() {
        stage = "equipment click/drag/dispenser and restored inventory";
        ItemStack restricted = create("restricted");
        player.cursor = restricted;
        denied(new InventoryClickEvent(player.view, InventoryType.SlotType.ARMOR, 5, ClickType.LEFT, InventoryAction.PLACE_ALL), "restricted armor click");
        denied(new InventoryDragEvent(player.view, null, restricted, false, Map.of(5, restricted)), "restricted armor drag");
        player.cursor = null; player.items[1] = restricted;
        denied(new InventoryClickEvent(player.view, InventoryType.SlotType.ARMOR, 5, ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, 1), "restricted number-key armor");
        denied(new InventoryClickEvent(player.view, InventoryType.SlotType.CONTAINER, 37, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY), "restricted shift equip");
        denied(new BlockDispenseArmorEvent(Bukkit.getWorlds().getFirst().getSpawnLocation().getBlock(), restricted, player.player), "restricted dispenser equip");
        player.items[39] = restricted;
        call(guard, "reconcile", player.player);
        check(empty(player.items[39]) && player.count("restricted") == 2, "Restored restricted armor not safely returned to storage");
        player.clear(); player.cursor = ItemStack.of(Material.APPLE);
        InventoryClickEvent ordinary = new InventoryClickEvent(player.view, InventoryType.SlotType.CONTAINER, 12, ClickType.LEFT, InventoryAction.PLACE_ALL);
        fire(ordinary); check(!ordinary.isCancelled(), "Ordinary item movement blocked");
        player.clear(); passed();
    }

    private void foodDurability() {
        stage = "food completion preserves remaining durability";
        ItemStack food = create("food_durable"); player.items[0] = food;
        PlayerItemConsumeEvent cancelled = new PlayerItemConsumeEvent(player.player, food, EquipmentSlot.HAND);
        cancelled.setCancelled(true); fire(cancelled);
        check(cancelled.getReplacement() == null && durability(food) == 2, "Cancelled food completion changed the item");
        PlayerItemConsumeEvent first = new PlayerItemConsumeEvent(player.player, food, EquipmentSlot.HAND);
        fire(first);
        check(!first.isCancelled() && !empty(first.getReplacement()) && durability(first.getReplacement()) == 1,
                "First food completion did not supply a one-point remaining replacement");
        check(definition(first.getReplacement()) != null, "Food replacement lost custom item identity");
        // Paper applies the replacement after the callback; emulate that documented boundary.
        player.items[0] = first.getReplacement();
        PlayerItemConsumeEvent second = new PlayerItemConsumeEvent(player.player, player.items[0], EquipmentSlot.HAND);
        int breaks = player.breaks;
        fire(second);
        check(!second.isCancelled() && second.getReplacement() != null && empty(second.getReplacement())
                && player.breaks == breaks + 1, "Exhausted food did not replace itself with air and break");
        ItemStack eternal = create("food_eternal"); eternal.setAmount(16); player.items[0] = eternal;
        PlayerItemConsumeEvent infinite = new PlayerItemConsumeEvent(player.player, eternal, EquipmentSlot.HAND);
        fire(infinite);
        check(!infinite.isCancelled() && !empty(infinite.getReplacement()) && infinite.getReplacement().getAmount() == 16
                && durability(infinite.getReplacement()) == 2,
                "Unbreakable food was consumed or lost durability");
        ItemStack regular = create("food_plain"); player.items[0] = regular;
        PlayerItemConsumeEvent plain = new PlayerItemConsumeEvent(player.player, regular, EquipmentSlot.HAND);
        fire(plain);
        check(!plain.isCancelled() && plain.getReplacement() == null, "Durability-disabled food lost normal consumption");
        player.clear(); passed();
    }

    private void projectileDurability() {
        stage = "trident launch, returned stack and riptide durability";
        levels(1);
        World world = Bukkit.getWorlds().getFirst();
        Trident projectile = world.spawn(world.getSpawnLocation().add(0, 3, 0), Trident.class);
        spawned.add(projectile);
        try {
            ItemStack restricted = create("restricted_trident"); player.items[0] = restricted;
            projectile.setItemStack(restricted.clone());
            PlayerLaunchProjectileEvent denied = new PlayerLaunchProjectileEvent(player.player, restricted, projectile);
            denied(denied, "restricted trident launch");
            check(durability(restricted) == 3, "Denied trident launch consumed durability");
            restricted.addUnsafeEnchantment(Enchantment.RIPTIDE, 1);
            PlayerRiptideEvent deniedRiptide = new PlayerRiptideEvent(player.player, restricted, new org.bukkit.util.Vector(0, 1, 0));
            denied(deniedRiptide, "restricted riptide");
            ItemStack trident = create("trident"); player.items[0] = trident;
            ItemStack oldCarry = trident.clone();
            projectile.setItemStack(trident.clone());
            PlayerLaunchProjectileEvent launch = new PlayerLaunchProjectileEvent(player.player, trident, projectile);
            fire(launch);
            check(!launch.isCancelled() && durability(trident) == 2, "Successful trident launch did not consume one point");
            check(durability(projectile.getItemStack()) == 2 && definition(projectile.getItemStack()) != null,
                    "Real Paper projectile pickup stack lost durability/identity");
            // Pinned Paper's native throw path assigns its pre-event copy after dispatch.
            // Reproduce that boundary; the plugin's next-tick repair must restore the spent point.
            projectile.setItemStack(oldCarry);
            check(durability(projectile.getItemStack()) == 3, "Invalid native trident carry overwrite fixture");
            pendingProjectile = projectile;
            ItemStack riptide = create("trident"); riptide.addUnsafeEnchantment(Enchantment.RIPTIDE, 1); player.items[0] = riptide;
            PlayerRiptideEvent spin = new PlayerRiptideEvent(player.player, riptide, new org.bukkit.util.Vector(0, 1, 0));
            fire(spin);
            check(!spin.isCancelled() && durability(riptide) == 2, "Riptide did not consume exactly one point");
            PlayerLaunchProjectileEvent protectedLaunch = new PlayerLaunchProjectileEvent(player.player, riptide, projectile);
            protectedLaunch.setCancelled(true); fire(protectedLaunch);
            check(durability(riptide) == 2, "Cancelled launch consumed durability");
            ItemStack exhausted = create("trident"); damage(exhausted); damage(exhausted); player.items[0] = exhausted;
            Trident finalProjectile = world.spawn(world.getSpawnLocation().add(0, 3, 0), Trident.class);
            spawned.add(finalProjectile);
            finalProjectile.setItemStack(exhausted.clone());
            PlayerLaunchProjectileEvent finalThrow = new PlayerLaunchProjectileEvent(player.player, exhausted, finalProjectile);
            fire(finalThrow);
            check(finalThrow.isCancelled() && !finalThrow.shouldConsume() && empty(exhausted),
                    "Last trident use launched a broken pickup stack");
        } finally { if (pendingProjectile != projectile) projectile.remove(); player.clear(); }
        Snowball snowball = world.spawn(world.getSpawnLocation().add(0, 3, 0), Snowball.class);
        spawned.add(snowball);
        try {
            ItemStack source = create("throwable"); player.items[0] = source;
            PlayerInteractEvent click = new PlayerInteractEvent(player.player, Action.RIGHT_CLICK_BLOCK, source,
                    world.getSpawnLocation().getBlock(), BlockFace.UP, EquipmentSlot.HAND);
            fire(click);
            check(durability(source) == 3, "Throwable right-click charged before projectile completion");
            PlayerLaunchProjectileEvent launch = new PlayerLaunchProjectileEvent(player.player, source, snowball);
            fire(launch);
            check(!launch.isCancelled() && !launch.shouldConsume() && durability(source) == 2,
                    "Durable throwable was consumed or charged more than one point");
            ItemStack ordinary = create("throwable_plain"); player.items[0] = ordinary;
            PlayerLaunchProjectileEvent plain = new PlayerLaunchProjectileEvent(player.player, ordinary, snowball);
            fire(plain);
            check(!plain.isCancelled() && plain.shouldConsume(), "Durability-disabled throwable lost normal consumption");
        } finally { snowball.remove(); player.clear(); }
        passed();
    }

    private void entityAndShieldDurability() {
        stage = "entity interaction and fully blocked shield damage";
        ItemStack tool = create("tool"); player.items[0] = tool;
        PlayerInteractEntityEvent use = new PlayerInteractEntityEvent(player.player, entity(), EquipmentSlot.HAND);
        fire(use);
        check(!use.isCancelled() && durability(tool) == 2, "Entity interaction did not consume one point");
        PlayerInteractEntityEvent cancelled = new PlayerInteractEntityEvent(player.player, entity(), EquipmentSlot.HAND);
        cancelled.setCancelled(true); fire(cancelled);
        check(durability(tool) == 2, "Cancelled entity interaction consumed durability");
        ItemStack targeted = create("tool"); player.items[0] = targeted; Entity target = entity();
        fire(new PlayerInteractAtEntityEvent(player.player, target, new org.bukkit.util.Vector(0, 1, 0), EquipmentSlot.HAND));
        fire(new PlayerInteractEntityEvent(player.player, target, EquipmentSlot.HAND));
        check(durability(targeted) == 2, "Entity-at/general event pair charged twice");
        player.clear(); ItemStack shield = create("shield"); player.items[40] = shield; player.blocking = true;
        com.google.common.base.Function<Double, Double> zero = ignored -> 0d;
        EntityDamageEvent blocked = new EntityDamageEvent(player.player, EntityDamageEvent.DamageCause.ENTITY_ATTACK, source(),
                new EnumMap<>(Map.of(EntityDamageEvent.DamageModifier.BASE, 4d, EntityDamageEvent.DamageModifier.BLOCKING, -4d)),
                new EnumMap<>(Map.of(EntityDamageEvent.DamageModifier.BASE, zero, EntityDamageEvent.DamageModifier.BLOCKING, zero)));
        check(blocked.getDamage() == 4 && blocked.getFinalDamage() == 0, "Invalid fully blocked damage fixture");
        fire(blocked);
        check(durability(shield) == 2, "Fully blocked damage did not wear the active shield once");
        player.blocking = false;
        fire(new EntityDamageEvent(player.player, EntityDamageEvent.DamageCause.ENTITY_ATTACK, source(), 0));
        check(durability(shield) == 2, "Zero damage without blocking wore the shield");
        player.clear(); passed();
    }

    private void glidingDurability() {
        stage = "gliding wear, exhaustion and requirement enforcement";
        levels(1);
        ItemStack restricted = create("restricted_glider"); player.items[38] = restricted;
        denied(new EntityToggleGlideEvent(player.player, true), "restricted gliding start");
        player.gliding = true; call(guard, "flightTick", player.player);
        check(!player.gliding && durability(restricted) == 3, "Restricted active glide did not stop without wear");
        ItemStack glider = create("glider"); player.items[38] = glider;
        call(guard, "flightTick", player.player);
        check(durability(glider) == 3, "Grounded elytra wore down");
        EntityToggleGlideEvent start = new EntityToggleGlideEvent(player.player, true);
        fire(start); check(!start.isCancelled(), "Unrestricted gliding start was blocked");
        player.gliding = true;
        call(guard, "flightTick", player.player);
        check(player.gliding && durability(glider) == 2, "Gliding interval did not spend exactly one point");
        call(guard, "flightTick", player.player);
        check(durability(glider) == 1, "Second gliding interval differs");
        call(guard, "flightTick", player.player);
        check(!player.gliding && empty(glider), "Exhausted elytra did not break and stop gliding");
        player.clear(); passed();
    }

    private void commands() throws Exception {
        stage = "admin permissions and safe reload";
        var command = plugin.getCommand("planetryitems");
        check(command != null, "Admin command missing");
        player.messages.clear();
        command.execute(player.player, "planetryitems", new String[]{"list"});
        check(!player.messages.isEmpty() && player.inventoryCount() == 0, "Permission denial missing");
        player.admin = true; player.messages.clear();
        command.execute(player.player, "planetryitems", new String[]{"list"});
        check(contains(player.messages, "bare"), "Admin list does not include configured item");
        commandGrants();
        var file = plugin.getDataFolder().toPath().resolve("items.yml");
        String original = Files.readString(file, StandardCharsets.UTF_8);
        Object originalCatalog = call(plugin, "catalog"), originalLanguage = call(plugin, "language");
        try {
            Files.writeString(file, "items:\n  broken:\n    material: INVALID_PROBE_MATERIAL\n", StandardCharsets.UTF_8);
            command.execute(player.player, "planetryitems", new String[]{"reload"});
            check(create("bare").getType() == Material.DIAMOND_SWORD, "Invalid reload replaced valid definitions");
            check(call(plugin, "catalog") == originalCatalog && call(plugin, "language") == originalLanguage,
                    "Invalid material reload replaced the active catalog/language");
        } finally { Files.writeString(file, original, StandardCharsets.UTF_8); }
        var lang = plugin.getDataFolder().toPath().resolve("lang.yml");
        String originalLang = Files.readString(lang, StandardCharsets.UTF_8);
        try {
            Files.writeString(lang, "types:\n  item: '<glyph>'\n", StandardCharsets.UTF_8);
            command.execute(player.player, "planetryitems", new String[]{"reload"});
            check(call(plugin, "catalog") == originalCatalog && call(plugin, "language") == originalLanguage,
                    "Malformed glyph language reload replaced active catalog/language");
            check(create("described").getMaxStackSize() == 16, "Malformed glyph language broke existing item creation");
        } finally { Files.writeString(lang, originalLang, StandardCharsets.UTF_8); }
        try {
            Files.writeString(file, "items:\n  bare:\n    material: DIAMOND_SWORD\n    display-name: '<glyph>'\n    max-durability: 0\n", StandardCharsets.UTF_8);
            command.execute(player.player, "planetryitems", new String[]{"reload"});
            check(call(plugin, "catalog") == originalCatalog && call(plugin, "language") == originalLanguage,
                    "Malformed glyph display name replaced active catalog/language");
            check(plain(create("bare").getItemMeta().displayName()).equals("Probe Blade"),
                    "Malformed display name replaced the active valid item");
        } finally { Files.writeString(file, original, StandardCharsets.UTF_8); }
        command.execute(player.player, "planetryitems", new String[]{"reload"});
        check(create("described").getMaxStackSize() == 16, "Valid reload did not preserve definitions");
        player.admin = false; player.clear(); passed();
    }

    private void commandGrants() throws Exception {
        // Player doubles are not in CraftServer's NMS player list. Override only this lookup,
        // synchronously, and forward every server operation to the real Paper instance.
        Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        Server actual = Bukkit.getServer();
        Server lookup = proxy(Server.class, (object, method, args) -> {
            if (method.getName().equals("getPlayerExact") && player.name.equals(args[0])) return player.player;
            try { return method.invoke(actual, args); }
            catch (InvocationTargetException error) { throw error.getCause(); }
        });
        try {
            field.set(null, lookup);
            player.clear();
            var command = plugin.getCommand("planetryitems");
            player.admin = false;
            command.execute(player.player, "planetryitems", new String[]{"give", player.name, "described", "17"});
            check(player.inventoryCount() == 0, "Unprivileged give granted items");
            player.admin = true;
            command.execute(player.player, "planetryitems", new String[]{"give", player.name, "described", "17"});
            check(player.count("described") == 17 && Arrays.stream(player.items).filter(item -> !empty(item))
                    .allMatch(item -> item.getAmount() <= 16), "Give did not honor count and max-stack-size");
            int count = player.inventoryCount();
            for (String amount : List.of("0", "-1", "2305", "x"))
                command.execute(player.player, "planetryitems", new String[]{"give", player.name, "described", amount});
            command.execute(player.player, "planetryitems", new String[]{"give", player.name, "unknown_probe_id"});
            check(player.inventoryCount() == count, "Invalid give arguments changed inventory");
            player.clear();
            for (int slot = 0; slot < 35; slot++) player.items[slot] = ItemStack.of(Material.COBBLESTONE, 64);
            ItemStack[] before = Arrays.stream(player.items).map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);
            command.execute(player.player, "planetryitems", new String[]{"give", player.name, "described", "17"});
            check(Arrays.equals(before, player.items), "Insufficient capacity granted a partial item batch");
            if (integrations) {
                player.clear(); setField("NetworkInventorySync", "ready", false);
                command.execute(player.player, "planetryitems", new String[]{"give", player.name, "described", "1"});
                check(player.inventoryCount() == 0, "Give modified an inventory before readiness");
                setField("NetworkInventorySync", "ready", true);
            }
            player.clear();
        } finally { field.set(null, actual); }
    }

    private void integrationChecks() throws Exception {
        stage = "Nexo items and glyphs";
        ItemStack nexo = create("nexo");
        check(nexo.getType() == Material.AMETHYST_SHARD && !nexo.getItemMeta().getPersistentDataContainer()
                .has(new NamespacedKey("nexo", "probe")), "Nexo gameplay identity leaked into the Planetry item");
        check(nexo.getData(DataComponentTypes.ITEM_MODEL).asString().equals("nexo:probe_texture"), "Nexo item model was discarded");
        check(nexo.getItemMeta().getCustomModelDataComponent().getFloats().equals(List.of(991f)), "Nexo custom model data discarded");
        check(nexo.getEnchantments().isEmpty() && nexo.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS).modifiers().isEmpty(),
                "Unconfigured Nexo enchantments/attributes leaked into Planetry item");
        check(contains(lore(nexo), "GLYPH[tag_rare]"), "Rarity did not use native Nexo resolver");
        setField("Nexo", "available", false);
        expectFailure(() -> create("nexo"), "Missing Nexo item silently became a vanilla fallback");
        setField("Nexo", "available", true); passed();

        stage = "Core and AuraSkills unavailable data fail closed";
        levels(20); ItemStack restricted = create("restricted");
        check(unmet(restricted).isEmpty(), "High fixture levels do not meet requirements");
        setField("PlanetryCore", "loaded", false);
        check(unmet(restricted).contains("planetry-gamelevel"), "Unloaded Core data granted access");
        setField("PlanetryCore", "loaded", true); setField("AuraSkills", "loaded", false);
        check(unmet(restricted).contains("auraskills-required-mining"), "Unloaded AuraSkills data granted access");
        setField("AuraSkills", "loaded", true); levels(1); passed();

        stage = "transient AuraSkills equipment bonuses and cleanup";
        player.clear(); player.items[39] = create("aura"); call(guard, "reconcile", player.player);
        Map<?, ?> modifiers = modifiers();
        check(modifiers.size() == 5, "All five AuraSkills modifiers were not applied: " + modifiers.keySet());
        Object wisdom = modifiers.get("planetryitems:wisdom");
        check(wisdom != null && ((Number) call(wisdom, "getValue")).doubleValue() == 2
                && Boolean.FALSE.equals(call(wisdom, "isPersistent")), "Wisdom amount or nonpersistent flag differs");
        call(guard, "reconcile", player.player);
        check(modifiers().get("planetryitems:wisdom") == wisdom, "Unchanged equipment unnecessarily replaced modifiers");
        setField("NetworkInventorySync", "ready", false); call(guard, "reconcile", player.player);
        check(modifiers().isEmpty(), "Inventory loading retained bonuses");
        setField("NetworkInventorySync", "ready", true); call(guard, "reconcile", player.player);
        check(modifiers().size() == 5, "Bonuses did not recover after readiness");
        player.items[39] = null; call(guard, "reconcile", player.player);
        check(modifiers().isEmpty(), "Unequip retained modifiers");
        player.items[39] = create("aura"); call(guard, "reconcile", player.player);
        fire(new PlayerQuitEvent(player.player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        check(modifiers().isEmpty(), "Quit retained modifiers");
        player.clear(); passed();

    }

    private void disabledSync() {
        stage = "installed disabled Inventory Sync refuses mutation";
        Plugin sync = Bukkit.getPluginManager().getPlugin("NetworkInventorySync");
        Bukkit.getPluginManager().disablePlugin(sync);
        check(Boolean.FALSE.equals(call(hooks, "inventoryReady", player.player)), "Disabled Inventory Sync treated as absent");
        ItemStack tool = create("tool");
        PlayerInteractEvent event = new PlayerInteractEvent(player.player, Action.RIGHT_CLICK_AIR, tool, null, BlockFace.SELF, EquipmentSlot.HAND);
        fire(event); check(event.useItemInHand() == Event.Result.DENY && durability(tool) == 3, "Disabled sync allowed managed use");
        passed();
    }

    private void missingHooks() {
        stage = "missing optional hooks";
        check(unmet(create("restricted")).size() == 2, "Missing plugins bypass requirements");
        expectFailure(() -> create("nexo"), "Missing Nexo silently became a vanilla fallback");
        check(!contains(lore(create("described")), "<glyph:"), "Missing Nexo leaked unresolved glyph syntax");
        check(Boolean.TRUE.equals(call(hooks, "inventoryReady", player.player)), "Absent Inventory Sync blocked ordinary use");
        passed();
    }

    private Map<?, ?> modifiers() throws Exception {
        ClassLoader loader = Bukkit.getPluginManager().getPlugin("AuraSkills").getClass().getClassLoader();
        Class<?> type = Class.forName("dev.flint.planetry.items.probe.AuraFixture", true, loader);
        Map<?, ?> users = (Map<?, ?>) type.getField("users").get(null);
        Object user = users.get(player.id);
        return user == null ? Map.of() : (Map<?, ?>) user.getClass().getField("modifiers").get(user);
    }
    private void levels(int level) {
        if (integrations) { setField("PlanetryCore", "level", level); setField("AuraSkills", "level", level); }
    }
    private void setField(String name, String field, Object value) {
        try { Plugin target = Bukkit.getPluginManager().getPlugin(name); target.getClass().getField(field).set(target, value); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private ItemStack create(String id) { return (ItemStack) call(items, "create", id, player.player); }
    private Object definition(ItemStack item) { return call(items, "definition", item); }
    private int durability(ItemStack item) { return (Integer) call(items, "currentDurability", item); }
    private boolean damage(ItemStack item) { return (Boolean) call(items, "damage", player.player, item, EquipmentSlot.HAND); }
    @SuppressWarnings("unchecked") private Set<String> unmet(ItemStack item) { return (Set<String>) call(items, "unmet", player.player, definition(item)); }
    private static Object call(Object target, String name, Object... args) {
        try {
            for (Method method : target.getClass().getMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
                boolean matches = true;
                for (int i = 0; i < args.length; i++) if (args[i] != null && !method.getParameterTypes()[i].isInstance(args[i])) matches = false;
                if (matches) return method.invoke(target, args);
            }
            throw new NoSuchMethodException(target.getClass() + "." + name);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException cause) throw cause;
            throw new IllegalStateException(error.getCause());
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private static List<String> lore(ItemStack item) { return item.getItemMeta().lore().stream().map(ItemsProbe::plain).toList(); }
    private static String plain(Component value) { return value == null ? "" : PlainTextComponentSerializer.plainText().serialize(value); }
    private static boolean contains(List<String> values, String needle) { return values.stream().anyMatch(value -> value.contains(needle)); }
    private static boolean red(Component component) { return NamedTextColor.RED.equals(component.color()) || component.children().stream().anyMatch(ItemsProbe::red); }
    private static boolean empty(ItemStack item) { return item == null || item.getType().isAir() || item.getAmount() == 0; }
    private static DamageSource source() { return DamageSource.builder(DamageType.GENERIC).build(); }
    private void fire(Event event) { Bukkit.getPluginManager().callEvent(event); }
    private void denied(Event event, String action) { fire(event); check(((Cancellable) event).isCancelled(), "Allowed " + action); }
    private Entity entity() { UUID id = UUID.randomUUID(); return proxy(Entity.class, (object, method, args) -> switch (method.getName()) {
        case "getUniqueId" -> id; case "getEntityId" -> id.hashCode(); case "getType" -> org.bukkit.entity.EntityType.ARMOR_STAND;
        case "getWorld" -> Bukkit.getWorlds().getFirst(); case "getLocation" -> Bukkit.getWorlds().getFirst().getSpawnLocation();
        case "hashCode" -> System.identityHashCode(object); case "equals" -> object == args[0]; default -> zero(method.getReturnType());
    }); }
    private void expectFailure(Runnable action, String message) { boolean failed = false; try { action.run(); } catch (IllegalStateException expected) { failed = true; } check(failed, message); }
    private void passed() { groups++; getLogger().info("PASS: " + stage); }
    private void after(int ticks, Checked action) { Bukkit.getScheduler().runTaskLater(this, () -> { try { if (!finished) action.run(); } catch (Throwable error) { fail(error); } }, ticks); }
    private void fail(Throwable error) { if (finished) return; finished = true; getLogger().severe("ITEMS_PROBE_FAILED: " + stage + ": " + error); error.printStackTrace(); Bukkit.shutdown(); }
    private static void check(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) { return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler)); }
    private static Object zero(Class<?> type) { if (!type.isPrimitive()) return null; if (type == boolean.class) return false; if (type == int.class) return 0; if (type == long.class) return 0L; if (type == double.class) return 0d; if (type == float.class) return 0f; if (type == byte.class) return (byte) 0; if (type == short.class) return (short) 0; if (type == char.class) return (char) 0; return null; }

    private final class Fake {
        final UUID id = UUID.randomUUID(); final String name;
        final ItemStack[] items = new ItemStack[43]; final List<String> messages = new ArrayList<>();
        Player player; PlayerInventory inventory; InventoryView view; Inventory top;
        ItemStack cursor; boolean admin, blocking, gliding; int breaks, sounds;
        Fake(String name, boolean admin) {
            this.name = name; this.admin = admin;
            inventory = proxy(PlayerInventory.class, (object, method, args) -> {
                check(Bukkit.isPrimaryThread(), "Inventory accessed off main thread");
                return switch (method.getName()) {
                    case "getSize" -> items.length; case "getMaxStackSize" -> 64; case "getType" -> InventoryType.PLAYER;
                    case "getHolder" -> player; case "getViewers" -> List.of(player);
                    case "getItem" -> items[index(args[0])];
                    case "setItem" -> { items[index(args[0])] = (ItemStack) args[1]; yield null; }
                    case "getItemInMainHand", "getItemInHand" -> hand(0); case "getItemInOffHand" -> hand(40);
                    case "setItemInMainHand", "setItemInHand" -> { items[0] = (ItemStack) args[0]; yield null; }
                    case "setItemInOffHand" -> { items[40] = (ItemStack) args[0]; yield null; }
                    case "getHeldItemSlot" -> 0; case "getContents" -> items.clone();
                    case "getStorageContents" -> Arrays.copyOf(items, 36); case "getArmorContents" -> Arrays.copyOfRange(items, 36, 40);
                    case "getHelmet" -> hand(39); case "getChestplate" -> hand(38); case "getLeggings" -> hand(37); case "getBoots" -> hand(36);
                    case "setHelmet" -> { items[39] = (ItemStack) args[0]; yield null; }
                    case "setChestplate" -> { items[38] = (ItemStack) args[0]; yield null; }
                    case "setLeggings" -> { items[37] = (ItemStack) args[0]; yield null; }
                    case "setBoots" -> { items[36] = (ItemStack) args[0]; yield null; }
                    case "firstEmpty" -> firstEmpty(); case "addItem" -> add((ItemStack[]) args[0]);
                    case "setStorageContents" -> { Arrays.fill(items, 0, 36, null); System.arraycopy(args[0], 0, items, 0, ((ItemStack[]) args[0]).length); yield null; }
                    case "iterator" -> Arrays.asList(items.clone()).listIterator(args == null || args.length == 0 ? 0 : (int) args[0]);
                    case "getLocation" -> Bukkit.getWorlds().getFirst().getSpawnLocation();
                    case "hashCode" -> System.identityHashCode(object); case "equals" -> object == args[0];
                    default -> throw new UnsupportedOperationException("Unimplemented probe inventory API: " + method);
                };
            });
            player = proxy(Player.class, (object, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id; case "getName", "getDisplayName" -> name; case "name", "displayName" -> Component.text(name);
                case "getServer" -> Bukkit.getServer(); case "getPlayer" -> object;
                case "isOnline", "isValid", "hasPlayedBefore", "isConnected" -> true;
                case "isDead", "isSneaking" -> false; case "hasPermission", "isPermissionSet", "isOp" -> this.admin;
                case "isBlocking" -> blocking;
                case "isGliding" -> gliding;
                case "setGliding" -> { gliding = (boolean) args[0]; yield null; }
                case "getGameMode" -> GameMode.SURVIVAL; case "getInventory" -> inventory; case "getOpenInventory" -> view;
                case "getWorld" -> Bukkit.getWorlds().getFirst(); case "getLocation" -> Bukkit.getWorlds().getFirst().getSpawnLocation();
                case "getItemOnCursor" -> cursor == null ? ItemStack.of(Material.AIR) : cursor;
                case "setItemOnCursor" -> { cursor = (ItemStack) args[0]; yield null; }
                case "getLevel" -> 999; // Requirements must read Core, not Minecraft's display level.
                case "broadcastSlotBreak" -> { breaks++; yield null; }
                case "playSound" -> { sounds++; yield null; }
                case "sendMessage" -> { for (Object value : args) { if (value instanceof Component component) messages.add(plain(component)); else if (value instanceof String text) messages.add(text); else if (value instanceof String[] texts) messages.addAll(List.of(texts)); } yield null; }
                case "updateInventory", "closeInventory", "sendExperienceChange" -> null;
                case "getEffectivePermissions" -> Set.of();
                case "hashCode" -> System.identityHashCode(object); case "equals" -> object == args[0]; case "toString" -> name;
                default -> zero(method.getReturnType());
            });
            top = Bukkit.createInventory(null, 9);
            view = proxy(InventoryView.class, (object, method, args) -> switch (method.getName()) {
                case "getPlayer" -> player; case "getTopInventory" -> top; case "getBottomInventory" -> inventory;
                case "getType" -> InventoryType.CRAFTING; case "countSlots" -> 46;
                case "convertSlot" -> convert((int) args[0]); case "getSlotType" -> slotType((int) args[0]);
                case "getInventory" -> (int) args[0] < 0 ? null : (int) args[0] < 5 ? top : inventory;
                case "getItem" -> (int) args[0] < 5 ? top.getItem((int) args[0]) : items[convert((int) args[0])];
                case "setItem" -> { int raw = (int) args[0]; if (raw < 5) top.setItem(raw, (ItemStack) args[1]); else items[convert(raw)] = (ItemStack) args[1]; yield null; }
                case "getCursor" -> cursor == null ? ItemStack.of(Material.AIR) : cursor;
                case "setCursor" -> { cursor = (ItemStack) args[0]; yield null; }
                case "getTitle", "getOriginalTitle" -> "Probe inventory"; case "title" -> Component.text("Probe inventory");
                case "hashCode" -> System.identityHashCode(object); case "equals" -> object == args[0];
                default -> zero(method.getReturnType());
            });
        }
        int index(Object value) { if (value instanceof Integer slot) return slot; return switch ((EquipmentSlot) value) { case HAND -> 0; case OFF_HAND -> 40; case FEET -> 36; case LEGS -> 37; case CHEST -> 38; case HEAD -> 39; case BODY -> 41; case SADDLE -> 42; }; }
        int convert(int raw) { if (raw < 5) return raw; if (raw < 9) return 44 - raw; if (raw < 36) return raw; if (raw < 45) return raw - 36; return 40; }
        InventoryType.SlotType slotType(int raw) { return raw < 0 ? InventoryType.SlotType.OUTSIDE : raw == 0 ? InventoryType.SlotType.RESULT : raw < 5 ? InventoryType.SlotType.CRAFTING : raw < 9 ? InventoryType.SlotType.ARMOR : raw < 36 ? InventoryType.SlotType.CONTAINER : InventoryType.SlotType.QUICKBAR; }
        ItemStack hand(int slot) { return items[slot] == null ? ItemStack.of(Material.AIR) : items[slot]; }
        int firstEmpty() { for (int slot = 0; slot < 36; slot++) if (empty(items[slot])) return slot; return -1; }
        Map<Integer, ItemStack> add(ItemStack[] incoming) { Map<Integer, ItemStack> remaining = new HashMap<>(); for (int n = 0; n < incoming.length; n++) { int slot = firstEmpty(); if (slot < 0) remaining.put(n, incoming[n].clone()); else items[slot] = incoming[n].clone(); } return remaining; }
        void clear() { Arrays.fill(items, null); cursor = null; blocking = false; gliding = false; }
        int inventoryCount() { return Arrays.stream(items).filter(item -> !empty(item)).mapToInt(ItemStack::getAmount).sum(); }
        int count(String id) { return Arrays.stream(items).filter(item -> !empty(item)).filter(item -> id.equals(call(ItemsProbe.this.items, "id", item))).mapToInt(ItemStack::getAmount).sum(); }
    }
}
