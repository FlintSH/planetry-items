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
import org.bukkit.inventory.meta.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.permissions.Permission;
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
                migrationInstanceData();
                migrationCommands();
                deniedDragRecovery();
            });
        });
        after(1200, () -> { throw new IllegalStateException("Probe timeout"); });
    }

    @Override public void onDisable() { spawned.forEach(Entity::remove); }

    private void metadata() {
        stage = "metadata and lore";
        ItemStack bare = create("bare");
        check(bare.getType() == Material.DIAMOND_SWORD && plain(bare.getItemMeta().displayName()).equals("Probe Blade"), "Display name/material differs");
        Map<String, Integer> rarityColors = Map.of("bare", 0xaaaaaa, "tool", 0x55ff55, "described", 0x55ffff,
                "hat", 0x9554ea, "eternal", 0xffbf00, "restricted", 0xff5555);
        rarityColors.forEach((id, color) -> check(nameColor(create(id).getItemMeta().displayName(), -1, color),
                "Rarity name color differs for " + id));
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
        check(restricted.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS).modifiers().isEmpty(),
                "Unmet requirements retained native attribute perks");
        // Model an item restored from a different level/session with now-ineligible perks.
        restricted.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, create("hat").getData(DataComponentTypes.ATTRIBUTE_MODIFIERS));
        player.items[0] = restricted;
        call(guard, "reconcile", player.player);
        restricted = player.items[0];
        check(!empty(restricted) && restricted.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS).modifiers().isEmpty(),
                "Held restricted item was removed or retained restored native attributes");
        player.messages.clear();
        PlayerInteractEvent interact = new PlayerInteractEvent(player.player, Action.RIGHT_CLICK_AIR, restricted, null, BlockFace.SELF, EquipmentSlot.HAND);
        fire(interact);
        check(interact.useItemInHand() == Event.Result.DENY, "Restricted right-click allowed");
        check(!player.messages.isEmpty(), "Denied use did not message the player");
        String denial = player.messages.getLast();
        check(denial.startsWith("You cannot use this item: ") && denial.endsWith("!")
                && denial.contains("Game Level 5") && denial.contains("Mining Level 3")
                && !denial.contains("☆") && !denial.contains("[") && !denial.contains("Requires"),
                "Requirement denial retained decorative lore or wrong wording: " + denial);
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
        PlayerItemHeldEvent held = new PlayerItemHeldEvent(player.player, 0, 1);
        fire(held); check(!held.isCancelled(), "Restricted item cannot be selected in the hotbar");
        PlayerSwapHandItemsEvent swap = new PlayerSwapHandItemsEvent(player.player, restricted, ItemStack.of(Material.AIR));
        fire(swap); check(!swap.isCancelled(), "Restricted item cannot be held in the other hand");
        player.cursor = restricted;
        InventoryClickEvent handClick = new InventoryClickEvent(player.view, InventoryType.SlotType.QUICKBAR,
                36, ClickType.LEFT, InventoryAction.PLACE_ALL);
        fire(handClick); check(!handClick.isCancelled(), "Restricted inventory click into the held slot was blocked");
        InventoryDragEvent handDrag = new InventoryDragEvent(player.view, null, restricted, false, Map.of(45, restricted));
        fire(handDrag); check(!handDrag.isCancelled(), "Restricted inventory drag into the offhand was blocked");
        player.cursor = null;
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
            check(!restricted.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS).modifiers().isEmpty(),
                    "Meeting requirements did not restore configured native attributes");
            levels(1); call(items, "refresh", restricted, player.player);
            check(unmet(restricted).size() == 2 && contains(lore(restricted), "Requires"), "Level loss did not restore requirements");
            check(restricted.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS).modifiers().isEmpty(),
                    "Level loss did not remove native attribute perks");
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
        for (String id : List.of("eternal", "eternal_tool", "food_eternal")) {
            String text = String.join("|", lore(create(id))).toLowerCase(Locale.ROOT);
            check(!text.contains("durability") && !text.contains("unbreakable"),
                    "Unbreakable item retained durability/unbreakable lore: " + id + ": " + text);
        }
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
        Object deniedCatalog = call(plugin, "catalog");
        player.legacyAdmin = true;
        for (String[] args : List.of(new String[]{"list"}, new String[]{"info"}, new String[]{"reload"},
                new String[]{"give", player.name, "described"})) {
            player.messages.clear();
            command.execute(player.player, "pitems", args);
            check(contains(player.messages, "permission") && !contains(player.messages, "bare")
                    && player.inventoryCount() == 0 && call(plugin, "catalog") == deniedCatalog,
                    "Legacy planetryitems.admin granted access to " + args[0]);
        }
        check(((List<?>) call(plugin, "onTabComplete", player.player, command, "pitems", new String[]{""})).isEmpty(),
                "Legacy admin permission exposed command completions");
        player.legacyAdmin = false;
        player.admin = true; player.messages.clear();
        command.execute(player.player, "planetryitems", new String[]{"list"});
        check(contains(player.messages, "bare"), "Admin list does not include configured item");
        commandInfo();
        commandGrants();
        var file = plugin.getDataFolder().toPath().resolve("items/imported.yml");
        check(Files.isRegularFile(file), "Legacy items.yml was not imported into items/imported.yml");
        var nested = plugin.getDataFolder().toPath().resolve("items/probe/deep/extra.yml");
        Files.createDirectories(nested.getParent());
        Files.copy(plugin.getDataFolder().toPath().resolve("nested-fixture.yml"), nested);
        command.execute(player.player, "pitems", new String[]{"reload"});
        check(create("nested_probe").getType() == Material.AMETHYST_SHARD, "Recursive catalog did not load nested fixture");
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
        String originalNested = Files.readString(nested, StandardCharsets.UTF_8);
        originalCatalog = call(plugin, "catalog"); originalLanguage = call(plugin, "language");
        try {
            Files.writeString(nested, "items:\n  nested_probe:\n    material: INVALID_PROBE_MATERIAL\n", StandardCharsets.UTF_8);
            command.execute(player.player, "pitems", new String[]{"reload"});
            check(call(plugin, "catalog") == originalCatalog && call(plugin, "language") == originalLanguage
                    && create("nested_probe").getType() == Material.AMETHYST_SHARD,
                    "Malformed nested catalog did not retain the complete active catalog/language");
            Files.writeString(nested, originalNested + "  bare:\n    material: STICK\n    max-durability: 0\n", StandardCharsets.UTF_8);
            command.execute(player.player, "pitems", new String[]{"reload"});
            check(call(plugin, "catalog") == originalCatalog && create("bare").getType() == Material.DIAMOND_SWORD,
                    "Duplicate IDs across catalog files replaced the active definitions");
            Files.writeString(nested, originalNested.replace("AMETHYST_SHARD", "EMERALD"), StandardCharsets.UTF_8);
            command.execute(player.player, "pitems", new String[]{"reload"});
            check(create("nested_probe").getType() == Material.EMERALD, "Valid nested file changes were not reloaded");
        } finally {
            Files.writeString(nested, originalNested, StandardCharsets.UTF_8);
            command.execute(player.player, "pitems", new String[]{"reload"});
        }
        player.admin = false; player.clear(); passed();
    }

    private void commandInfo() {
        var command = plugin.getCommand("planetryitems");
        for (String id : List.of("described", "restricted", "hat", "skull", "nexo")) {
            if (id.equals("nexo") && !integrations) continue;
            player.items[0] = create(id); player.messages.clear();
            command.execute(player.player, "pitems", new String[]{"info"});
            String text = String.join("\n", player.messages);
            for (String field : List.of("name", "material", "configured-material", "lore", "model", "nexo",
                    "base64", "rarity", "type", "amount", "stack", "unbreakable", "equip", "usable",
                    "bonuses", "requirements", "attributes", "durability"))
                check(text.toLowerCase(Locale.ROOT).contains(field), "Info omits field " + field + " for " + id + ": " + text);
            check(text.contains(id), "Info does not identify the held item");
            if (id.equals("described")) check(text.contains("812") && text.contains("First probe line")
                    && text.contains("Second probe line") && text.contains("16"), "Info omits model, lore or stack values");
            if (id.equals("restricted")) check(text.contains("planetry-gamelevel") && text.contains("auraskills-required-mining")
                    && text.contains("minecraft:attack_damage") && text.contains("auraskills-additional-strength"),
                    "Info omits configured requirements, native attributes or bonuses");
            if (id.equals("hat")) check(text.contains("planetry-oxygen") && text.contains("minecraft:scale"),
                    "Info omits oxygen or registry attributes");
            if (id.equals("skull")) check(text.contains((String) call(definition(player.items[0]), "base64")),
                    "Info omits the actual base64 texture value");
            if (id.equals("nexo")) check(text.contains("probe_texture"), "Info omits the Nexo ID");
        }
        player.clear();
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

    private void migrationCommands() {
        stage = "public migration, retained counts and two-hour cooldown";
        var command = plugin.getCommand("migrate");
        check(command != null && (command.getPermission() == null || command.getPermission().isBlank()),
                "Public /migrate is missing or requires a permission");
        player.admin = false; player.legacyAdmin = false; player.clear();
        var cooldown = new NamespacedKey(plugin, "last_migrate");
        player.data.remove(cooldown);
        if (integrations) {
            player.items[0] = outdated("tool");
            player.items[1] = deleted("Retired Before Readiness");
            ItemStack[] before = snapshot();
            setField("NetworkInventorySync", "ready", false);
            command.execute(player.player, "migrate", new String[0]);
            check(Arrays.equals(before, player.items) && !player.data.has(cooldown, PersistentDataType.LONG),
                    "Unready public migration mutated items or spent the cooldown");
            setField("NetworkInventorySync", "ready", true);
        }
        player.clear(); player.messages.clear();
        player.items[0] = outdated("tool");
        player.items[0].setType(Material.STICK);
        player.items[0].editMeta(meta -> meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "durability"),
                PersistentDataType.INTEGER, 1));
        player.items[1] = legacyStack("described", 17, 32);
        ItemStack ordinary = ItemStack.of(Material.APPLE, 7); player.items[2] = ordinary.clone();
        player.items[3] = deleted("Retired Probe Item");
        player.cursor = outdated("described"); player.cursor.setAmount(2);
        command.execute(player.player, "migrate", new String[0]);
        check(player.items[0].getType() == Material.PAPER && durability(player.items[0]) == 1
                && !plain(player.items[0].getItemMeta().displayName()).contains("OUTDATED"),
                "Public migration did not rebuild the current material/name while preserving remaining durability");
        check(player.count("described") == 17 && Arrays.stream(player.items).filter(item -> !empty(item))
                .allMatch(item -> item.getAmount() <= item.getMaxStackSize()),
                "Migration lost items or failed to split the updated stack limit");
        check(ordinary.equals(player.items[2]), "Migration changed an ordinary item");
        check(player.count("removed_probe_definition") == 0 && contains(player.messages, "Retired Probe Item")
                && contains(player.messages.stream().map(value -> value.toLowerCase(Locale.ROOT)).toList(), "contact staff"),
                "Deleted item was not removed with its name and contact-staff notice: " + player.messages);
        check(player.cursor != null && player.cursor.getAmount() == 2
                && !plain(player.cursor.getItemMeta().displayName()).contains("OUTDATED"), "Managed cursor was not migrated");
        Long first = player.data.get(cooldown, PersistentDataType.LONG);
        check(first != null && first > 0, "Successful public migration did not persist its cooldown");
        player.items[0] = outdated("tool");
        ItemStack denied = player.items[0].clone(); player.messages.clear();
        command.execute(player.player, "migrate", new String[0]);
        check(denied.equals(player.items[0]) && first.equals(player.data.get(cooldown, PersistentDataType.LONG))
                && contains(player.messages, "2h"), "Immediate migration did not retain items and report the two-hour cooldown");
        long expired = System.currentTimeMillis() - 7_200_001L;
        player.data.set(cooldown, PersistentDataType.LONG, expired);
        command.execute(player.player, "migrate", new String[0]);
        check(!plain(player.items[0].getItemMeta().displayName()).contains("OUTDATED")
                && player.data.get(cooldown, PersistentDataType.LONG) > expired, "Migration stayed blocked after two hours");
        player.clear(); player.data.remove(cooldown);
        command.execute(player.player, "migrate", new String[0]);
        check(player.data.has(cooldown, PersistentDataType.LONG), "Successful empty migration did not consume cooldown");

        // A new lower stack limit must never discard overflow when storage is full.
        player.clear(); player.data.remove(cooldown);
        for (int slot = 0; slot < 36; slot++) player.items[slot] = ItemStack.of(Material.COBBLESTONE, 64);
        player.items[0] = legacyStack("described", 17, 32);
        check(player.items[0].getMaxStackSize() >= player.items[0].getAmount(),
                "Legacy full-storage fixture starts with an invalid stack");
        command.execute(player.player, "migrate", new String[0]);
        check(player.count("described") == 17 && !player.data.has(cooldown, PersistentDataType.LONG)
                && player.inventoryCount() == 35 * 64 + 17, "Full storage lost migration overflow or spent the cooldown");
        check(player.items[0].getAmount() == 17 && player.items[0].getMaxStackSize() >= 17,
                "Blocked migration lowered the retained stack's maximum below its amount");
        call(guard, "reconcile", player.player);
        check(player.items[0].getAmount() == 17 && player.items[0].getMaxStackSize() >= 17
                && player.inventoryCount() == 35 * 64 + 17,
                "Reconciliation made a blocked legacy stack invalid or lost its overflow");
        player.items[35] = null;
        command.execute(player.player, "migrate", new String[0]);
        check(player.count("described") == 17 && player.data.has(cooldown, PersistentDataType.LONG)
                && Arrays.stream(player.items).filter(item -> !empty(item))
                .allMatch(item -> item.getAmount() <= item.getMaxStackSize()), "Migration did not recover after storage was freed");
        // Bukkit may mutate an addItem argument while partly merging it into an existing stack.
        player.clear(); player.data.remove(cooldown);
        player.items[0] = legacyStack("described", 32, 32);
        player.items[1] = create("described"); player.items[1].setAmount(15);
        check(((ItemStack) call(items, "migrate", player.items[0], player.player)).isSimilar(player.items[1]),
                "Partial-merge fixture does not match the rebuilt item definition");
        command.execute(player.player, "migrate", new String[0]);
        check(player.count("described") == 47 && player.inventoryCount() == 47
                && player.data.has(cooldown, PersistentDataType.LONG)
                && Arrays.stream(player.items).filter(item -> !empty(item))
                .allMatch(item -> item.getAmount() <= 16 && item.getAmount() <= item.getMaxStackSize()),
                "Migration partial merge duplicated/lost items or exceeded the new stack limit");
        player.data.remove(cooldown);
        player.clear(); passed();
    }

    private ItemStack legacyStack(String id, int amount, int maximum) {
        ItemStack item = outdated(id);
        item.editMeta(meta -> meta.setMaxStackSize(maximum));
        item.setAmount(amount);
        return item;
    }

    private void migrationInstanceData() throws Exception {
        stage = "service and public migration preserve containers, bundles, books and foreign PDC";
        var state = plugin.getDataFolder().toPath().resolve("items/probe/state.yml");
        check(!Files.exists(state), "Unexpected existing instance-data fixture catalog");
        Files.createDirectories(state.getParent());
        Files.writeString(state, """
                items:
                  state_shulker:
                    material: SHULKER_BOX
                    max-stack-size: 1
                    max-durability: 0
                  state_bundle:
                    material: BUNDLE
                    max-stack-size: 1
                    max-durability: 0
                  state_written:
                    material: WRITTEN_BOOK
                    max-stack-size: 1
                    max-durability: 0
                  state_enchants:
                    material: ENCHANTED_BOOK
                    max-stack-size: 1
                    max-durability: 0
                """, StandardCharsets.UTF_8);
        var reload = plugin.getCommand("planetryitems");
        var cooldown = new NamespacedKey(plugin, "last_migrate");
        try {
            player.admin = true;
            reload.execute(player.player, "pitems", new String[]{"reload"});
            player.admin = false; player.clear(); player.data.remove(cooldown);
            List<String> ids = List.of("state_shulker", "state_bundle", "state_written", "state_enchants");
            for (int slot = 0; slot < ids.size(); slot++) {
                String id = ids.get(slot);
                ItemStack original = instanceData(id), before = original.clone();
                ItemStack migrated = (ItemStack) call(items, "migrate", original, player.player);
                check(original.equals(before), "Service migration mutated its input: " + id);
                assertInstanceData(migrated, id);
                assertInstanceData(ItemStack.deserializeBytes(migrated.serializeAsBytes()), id);
                player.items[slot] = original;
            }
            player.cursor = instanceData("state_bundle");
            plugin.getCommand("migrate").execute(player.player, "migrate", new String[0]);
            check(player.data.has(cooldown, PersistentDataType.LONG), "Instance-data public migration failed");
            for (int slot = 0; slot < ids.size(); slot++) {
                assertInstanceData(player.items[slot], ids.get(slot));
                assertInstanceData(ItemStack.deserializeBytes(player.items[slot].serializeAsBytes()), ids.get(slot));
            }
            assertInstanceData(player.cursor, "state_bundle");
            passed();
        } finally {
            player.clear(); player.data.remove(cooldown);
            Files.deleteIfExists(state);
            player.admin = true;
            reload.execute(player.player, "pitems", new String[]{"reload"});
            player.admin = false;
        }
    }

    private ItemStack instanceData(String id) {
        ItemStack item = outdated(id);
        switch (id) {
            case "state_shulker" -> {
                // A catalog material change must also preserve the block-entity inventory.
                item = item.withType(Material.WHITE_SHULKER_BOX);
                BlockStateMeta meta = (BlockStateMeta) item.getItemMeta();
                var box = (org.bukkit.block.ShulkerBox) meta.getBlockState();
                box.getInventory().setItem(0, ItemStack.of(Material.DIAMOND, 23));
                meta.setBlockState(box); item.setItemMeta(meta);
            }
            case "state_bundle" -> {
                BundleMeta meta = (BundleMeta) item.getItemMeta();
                meta.setItems(List.of(ItemStack.of(Material.DIAMOND, 7), ItemStack.of(Material.EMERALD, 3)));
                item.setItemMeta(meta);
            }
            case "state_written" -> {
                BookMeta meta = (BookMeta) item.getItemMeta();
                meta.setTitle("Instance Journal"); meta.setAuthor("Probe Author");
                meta.setPages("First saved page\nDiamonds: 23", "Second saved page");
                item.setItemMeta(meta);
            }
            case "state_enchants" -> {
                EnchantmentStorageMeta meta = (EnchantmentStorageMeta) item.getItemMeta();
                meta.addStoredEnchant(Enchantment.MENDING, 1, false);
                meta.addStoredEnchant(Enchantment.UNBREAKING, 3, false);
                item.setItemMeta(meta);
            }
            default -> throw new IllegalArgumentException(id);
        }
        item.editMeta(meta -> {
            meta.getPersistentDataContainer().set(new NamespacedKey("probe_foreign", "instance"),
                    PersistentDataType.STRING, "saved:" + id);
            var model = meta.getCustomModelDataComponent();
            model.setFloats(List.of(456f)); meta.setCustomModelDataComponent(model);
        });
        return item;
    }

    private void assertInstanceData(ItemStack item, String id) {
        check(!empty(item) && id.equals(call(items, "id", item)) && item.getAmount() == 1,
                "Migration lost managed identity/count: " + id);
        check(("saved:" + id).equals(item.getItemMeta().getPersistentDataContainer().get(
                new NamespacedKey("probe_foreign", "instance"), PersistentDataType.STRING)),
                "Migration lost foreign instance PDC: " + id);
        check(!plain(item.getItemMeta().displayName()).contains("OUTDATED")
                && item.getData(DataComponentTypes.CUSTOM_MODEL_DATA) == null,
                "Migration retained stale catalog name/model components: " + id);
        switch (id) {
            case "state_shulker" -> {
                check(item.getType() == Material.SHULKER_BOX, "Migration did not apply the current shulker material");
                var box = (org.bukkit.block.ShulkerBox) ((BlockStateMeta) item.getItemMeta()).getBlockState();
                check(ItemStack.of(Material.DIAMOND, 23).equals(box.getInventory().getItem(0)),
                        "Migration lost the shulker's diamonds");
            }
            case "state_bundle" -> check(((BundleMeta) item.getItemMeta()).getItems().equals(
                    List.of(ItemStack.of(Material.DIAMOND, 7), ItemStack.of(Material.EMERALD, 3))),
                    "Migration lost bundle contents");
            case "state_written" -> {
                BookMeta meta = (BookMeta) item.getItemMeta();
                check("Instance Journal".equals(meta.getTitle()) && "Probe Author".equals(meta.getAuthor())
                        && meta.getPages().equals(List.of("First saved page\nDiamonds: 23", "Second saved page")),
                        "Migration lost written book title, author or pages");
            }
            case "state_enchants" -> check(((EnchantmentStorageMeta) item.getItemMeta()).getStoredEnchants().equals(
                    Map.of(Enchantment.MENDING, 1, Enchantment.UNBREAKING, 3)),
                    "Migration lost stored enchanted-book enchantments");
            default -> throw new IllegalArgumentException(id);
        }
    }

    private void deniedDragRecovery() {
        stage = "stale denied drag cancels, repairs the restored cursor and permits a clean retry";
        player.clear(); levels(20);
        ItemStack stale = create("restricted_hand");
        if (!integrations) {
            // Model native perks persisted by an earlier eligible server/session.
            stale.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, create("hat").getData(DataComponentTypes.ATTRIBUTE_MODIFIERS));
        }
        check(!stale.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS).modifiers().isEmpty(),
                "Stale drag fixture has no formerly eligible native attributes");
        levels(1); player.cursor = stale.clone();
        InventoryDragEvent drag = new InventoryDragEvent(player.view, null, stale, false, Map.of(45, stale.asOne()));
        fire(drag);
        check(drag.isCancelled(), "Drag carrying stale denied native perks was not cancelled immediately");
        // Native drag cancellation restores the old cursor after event dispatch. The event's
        // new-item map contains copies and cannot prove which stacks Paper will install.
        player.cursor = stale.clone();
        after(2, () -> {
            check(!empty(player.cursor) && player.cursor.getAmount() == 1
                    && player.cursor.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS).modifiers().isEmpty(),
                    "Cancelled drag did not repair native perks on the actual restored cursor");
            ItemStack clean = player.cursor.clone();
            InventoryDragEvent retry = new InventoryDragEvent(player.view, null, clean, false, Map.of(45, clean.asOne()));
            fire(retry);
            check(!retry.isCancelled(), "Sanitized restricted item cannot be dragged into the offhand");
            player.clear(); passed(); automaticMigration();
        });
    }

    private void automaticMigration() {
        stage = "automatic join migration waits for Inventory Sync readiness";
        player.clear(); player.messages.clear();
        player.items[0] = outdated("tool"); player.items[1] = deleted("Retired Join Item");
        ItemStack[] before = snapshot();
        if (integrations) setField("NetworkInventorySync", "ready", false);
        fire(new PlayerJoinEvent(player.player, Component.empty()));
        after(5, () -> {
            if (integrations) {
                check(Arrays.equals(before, player.items), "Join changed inventory before Inventory Sync readiness");
                setField("NetworkInventorySync", "ready", true);
            }
            after(30, () -> {
                check(!plain(player.items[0].getItemMeta().displayName()).contains("OUTDATED")
                        && player.count("removed_probe_definition") == 0 && contains(player.messages, "Retired Join Item"),
                        "Join did not automatically refresh/remove items after readiness");
                int notices = (int) player.messages.stream().filter(message -> message.contains("Retired Join Item")).count();
                call(guard, "reconcile", player.player);
                check(notices == 1 && player.messages.stream().filter(message -> message.contains("Retired Join Item")).count() == 1,
                        "Deleted item removal notice was repeated");
                passed();
                // A backend switch reaches the target server through another join lifecycle.
                fire(new PlayerQuitEvent(player.player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
                player.clear(); player.messages.clear();
                player.items[0] = outdated("described"); player.items[1] = deleted("Retired Switch Item");
                ItemStack[] switched = snapshot();
                if (integrations) setField("NetworkInventorySync", "ready", false);
                stage = "automatic migration on the next server session";
                fire(new PlayerJoinEvent(player.player, Component.empty()));
                after(5, () -> {
                    if (integrations) {
                        check(Arrays.equals(switched, player.items), "Server switch changed inventory before readiness");
                        setField("NetworkInventorySync", "ready", true);
                    }
                    after(30, () -> {
                        check(!plain(player.items[0].getItemMeta().displayName()).contains("OUTDATED")
                                && player.count("removed_probe_definition") == 0 && contains(player.messages, "Retired Switch Item"),
                                "Next server session did not migrate despite the public cooldown");
                        passed(); rapidReconnect();
                    });
                });
            });
        });
    }

    private void rapidReconnect() throws Exception {
        stage = "rapid reconnect fences an old online Player and retains the new session migration";
        Fake old = new Fake("ReconnectProbe", false);
        old.items[0] = outdated("tool"); old.items[0].setType(Material.STICK);
        ItemStack[] original = snapshot(old);
        if (integrations) setField("NetworkInventorySync", "ready", false);
        fire(new PlayerJoinEvent(old.player, Component.empty()));
        Checked reconnect = () -> {
            check(old.player.isOnline(), "Reconnect fixture must model an old UUID-backed online Player");
            fire(new PlayerQuitEvent(old.player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
            Fake current = new Fake(old.id, old.name, false);
            check(current.player != old.player && current.player.getUniqueId().equals(old.player.getUniqueId()),
                    "Reconnect fixture did not create distinct Player instances with the same UUID");
            current.items[0] = outdated("tool"); current.items[0].setType(Material.STICK);
            fire(new PlayerJoinEvent(current.player, Component.empty()));
            if (integrations) {
                // Old task retries at tick 21; the new session's first ready retry is tick 26.
                after(15, () -> {
                    setField("NetworkInventorySync", "ready", true);
                    after(4, () -> {
                        check(Arrays.equals(original, old.items), "Old delayed task mutated a superseded inventory");
                        check(current.items[0].getType() == Material.STICK && pendingMigration(current),
                                "Old delayed task consumed or prematurely ran the new pending migration");
                        // A late event from the superseded Player must not clear the new record either.
                        fire(new PlayerQuitEvent(old.player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
                        check(pendingMigration(current), "Late old-session quit cleared the current pending migration");
                        after(8, () -> finishReconnect(old, original, current));
                    });
                });
            } else {
                // Both next-tick tasks are queued; the old task must yield to the new session.
                after(2, () -> finishReconnect(old, original, current));
            }
        };
        if (integrations) after(5, reconnect);
        else reconnect.run();
    }

    private void finishReconnect(Fake old, ItemStack[] original, Fake current) {
        check(Arrays.equals(original, old.items) && old.player.isOnline(),
                "Superseded Player inventory changed while the UUID still reports online");
        check(current.items[0].getType() == Material.PAPER && !pendingMigration(current),
                "New same-UUID session did not finish its own migration");
        fire(new PlayerQuitEvent(current.player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        passed();
        if (integrations) retryMissingSource();
        else finish();
    }

    private void retryMissingSource() {
        stage = "first-ready missing Nexo source retries automatically after five seconds";
        Fake retry = new Fake("RetryProbe", false);
        retry.items[0] = outdated("nexo"); retry.items[0].setType(Material.STICK);
        ItemStack[] original = snapshot(retry);
        setField("Nexo", "available", false);
        setField("NetworkInventorySync", "ready", false);
        fire(new PlayerJoinEvent(retry.player, Component.empty()));
        after(5, () -> {
            check(Arrays.equals(original, retry.items), "Missing-source join mutated inventory before readiness");
            long readyAt = System.currentTimeMillis();
            setField("NetworkInventorySync", "ready", true);
            awaitFirstSourceFailure(retry, readyAt, 60);
        });
    }

    private void awaitFirstSourceFailure(Fake retry, long readyAt, int remaining) {
        after(1, () -> {
            long retryAt = nextMigrationAt(retry);
            if (retryAt == 0) {
                check(remaining > 0, "First ready migration never attempted the unavailable Nexo source");
                awaitFirstSourceFailure(retry, readyAt, remaining - 1);
                return;
            }
            check(retry.items[0].getType() == Material.STICK && pendingMigration(retry)
                    && sourceFailures(retry) == 1 && !retry.data.has(new NamespacedKey(plugin, "last_migrate")),
                    "Failed first-ready migration lost the original, retry, notice or public cooldown separation");
            check(retryAt >= readyAt + 5_000,
                    "Failed migration did not schedule a five-second backoff");
            setField("Nexo", "available", true);
            if (System.currentTimeMillis() < retryAt) {
                call(guard, "reconcile", retry.player);
                check(retry.items[0].getType() == Material.STICK && pendingMigration(retry)
                        && nextMigrationAt(retry) == retryAt && sourceFailures(retry) == 1,
                        "Migration ignored its backoff or repeated the failure notice");
            }
            awaitSourceRecovery(retry, retryAt, System.currentTimeMillis() + 12_000, 240);
        });
    }

    private void awaitSourceRecovery(Fake retry, long retryAt, long deadline, int remaining) {
        after(10, () -> {
            if (retry.items[0].getType() == Material.AMETHYST_SHARD) {
                check(System.currentTimeMillis() >= retryAt && !pendingMigration(retry)
                        && nextMigrationAt(retry) == 0 && sourceFailures(retry) == 1,
                        "Recovered source retried early, retained pending state or repeated its notice");
                check(retry.items[0].getData(DataComponentTypes.ITEM_MODEL).asString().equals("nexo:probe_texture")
                        && !retry.data.has(new NamespacedKey(plugin, "last_migrate")),
                        "Automatic Nexo retry omitted source appearance or spent the public cooldown");
                fire(new PlayerQuitEvent(retry.player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
                passed(); finish();
                return;
            }
            check(retry.items[0].getType() == Material.STICK && pendingMigration(retry) && sourceFailures(retry) == 1,
                    "Waiting retry lost its original item, pending state or single failure notice");
            check(System.currentTimeMillis() < deadline && remaining > 0,
                    "Ready migration did not recover automatically after the Nexo source returned");
            awaitSourceRecovery(retry, retryAt, deadline, remaining - 10);
        });
    }

    private int sourceFailures(Fake target) {
        return (int) target.messages.stream().filter(message -> message.contains("could not update")).count();
    }

    private boolean pendingMigration(Fake target) {
        return ((Set<?>) guardField("pendingMigration")).contains(target.id);
    }

    private long nextMigrationAt(Fake target) {
        Object next = ((Map<?, ?>) guardField("nextMigration")).get(target.id);
        return next == null ? 0 : ((Number) next).longValue();
    }

    private Object guardField(String name) {
        try {
            Field field = guard.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(guard);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    private ItemStack outdated(String id) {
        ItemStack item = create(id);
        item.editMeta(meta -> { meta.displayName(Component.text("OUTDATED " + id)); meta.lore(List.of(Component.text("OUTDATED lore"))); });
        return item;
    }

    private ItemStack deleted(String name) {
        ItemStack item = create("bare");
        item.editMeta(meta -> {
            meta.displayName(Component.text(name));
            meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "item_id"), PersistentDataType.STRING, "removed_probe_definition");
        });
        return item;
    }

    private ItemStack[] snapshot() {
        return snapshot(player);
    }

    private ItemStack[] snapshot(Fake target) {
        return Arrays.stream(target.items).map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);
    }

    private void finish() {
        if (integrations) disabledSync();
        check(plugin.isEnabled(), "Plugin disabled during acceptance");
        finished = true;
        getLogger().info("ITEMS_PROBE_OK: " + groups + " groups; real Paper metadata/serialization/events/projectiles/migration; controlled players; "
                + (integrations ? "controlled Core/AuraSkills/Nexo/readiness API fixtures" : "optional plugins absent"));
        Bukkit.shutdown();
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

        stage = "held requirement loss disables native and AuraSkills perks";
        levels(20); player.items[0] = create("restricted_hand"); call(guard, "reconcile", player.player);
        check(modifiers().size() == 1 && modifiers().containsKey("planetryitems:strength")
                && !player.items[0].getData(DataComponentTypes.ATTRIBUTE_MODIFIERS).modifiers().isEmpty(),
                "Eligible held item did not grant its configured native/AuraSkills perks");
        levels(1); call(guard, "reconcile", player.player);
        check(player.count("restricted_hand") == 1 && !empty(player.items[0]) && modifiers().isEmpty()
                && player.items[0].getData(DataComponentTypes.ATTRIBUTE_MODIFIERS).modifiers().isEmpty(),
                "Requirement loss removed the held item or retained native/AuraSkills perks");
        player.clear(); call(guard, "reconcile", player.player); passed();

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
    private static boolean nameColor(Component component, int inherited, int expected) {
        int color = component.color() == null ? inherited : component.color().value();
        if (component instanceof net.kyori.adventure.text.TextComponent text && !text.content().isEmpty()
                && color != expected) return false;
        return component.children().stream().allMatch(child -> nameColor(child, color, expected));
    }
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
        final UUID id; final String name;
        final ItemStack[] items = new ItemStack[43]; final List<String> messages = new ArrayList<>();
        final PersistentDataContainer data = new ItemStack(Material.PAPER).getItemMeta().getPersistentDataContainer();
        Player player; PlayerInventory inventory; InventoryView view; Inventory top;
        ItemStack cursor; boolean admin, legacyAdmin, blocking, gliding; int breaks, sounds;
        Fake(String name, boolean admin) {
            this(UUID.randomUUID(), name, admin);
        }
        Fake(UUID id, String name, boolean admin) {
            this.id = id; this.name = name; this.admin = admin;
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
                case "isDead", "isSneaking", "isOp" -> false;
                case "hasPermission", "isPermissionSet" -> permission(args[0]);
                case "getPersistentDataContainer" -> data;
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
        boolean permission(Object value) {
            String name = value instanceof Permission permission ? permission.getName() : String.valueOf(value);
            return name.equals("planetry.admin.items") ? admin : name.equals("planetryitems.admin") && legacyAdmin;
        }
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
