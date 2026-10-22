package dev.flint.planetry.items.probe;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import io.papermc.paper.event.block.PlayerShearBlockEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import io.papermc.paper.event.player.PlayerSwapWithEquipmentSlotEvent;
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
import org.bukkit.entity.FishHook;
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
            preloadPackagedClasses();
            items = call(plugin, "items"); guard = call(plugin, "guard"); hooks = call(plugin, "hooks");
            integrations = Bukkit.getPluginManager().isPluginEnabled("Nexo");
            check(Bukkit.getPluginManager().isPluginEnabled("AuraSkills") == integrations, "Unexpected fixture combination");
            player = new Fake("ItemsProbeUser", false);
            metadata();
            requirements();
            durability();
            nativeDurabilityBar();
            eventDurability();
            specialToolDurability();
            nativeControlStickBreak();
            completedToolActions();
            completedFishing();
            foodDurability();
            projectileDurability();
            entityAndShieldDurability();
            glidingDurability();
            equipment();
            equipmentTypes();
            commands();
            networkGrants();
            policies();
            glyphTemplates();
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

    private void preloadPackagedClasses() throws Exception {
        // Paper's lazy class remapper requires the actual CraftServer. Resolve every plugin
        // class before the controlled command/network probes substitute Bukkit's lookup.
        var path = java.nio.file.Path.of(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        try (var jar = new java.util.jar.JarFile(path.toFile())) {
            for (var entry : jar.stream().filter(value -> !value.isDirectory()
                    && value.getName().startsWith("dev/flint/") && value.getName().endsWith(".class")).toList()) {
                String name = entry.getName().substring(0, entry.getName().length() - 6).replace('/', '.');
                Class.forName(name, true, plugin.getClass().getClassLoader());
            }
        }
    }

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
        nativeBar(tool, 3, 3);
        check(!damage(tool) && durability(tool) == 2, "First use did not consume exactly one");
        nativeBar(tool, 3, 2);
        ItemStack saved = ItemStack.deserializeBytes(tool.serializeAsBytes());
        check(durability(saved) == 2, "Durability was not persistent");
        nativeBar(saved, 3, 2);
        check(!damage(tool) && durability(tool) == 1, "Second use differs");
        nativeBar(tool, 3, 1);
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

    private void nativeDurabilityBar() {
        stage = "native durability components, early red lore and persistent refresh";
        ItemStack sword = create("durability_sword"); player.items[0] = sword;
        nativeBar(sword, 100, 100);
        check(!damage(sword), "First sword use unexpectedly broke the item");
        nativeBar(sword, 100, 99);
        setDurability(sword, 31);
        call(items, "refresh", sword, player.player);
        nativeBar(sword, 100, 31);
        check(!durabilityLineRed(sword), "Durability lore turned red above thirty percent");
        setDurability(sword, 30);
        call(items, "refresh", sword, player.player);
        nativeBar(sword, 100, 30);
        check(durabilityLineRed(sword), "Durability lore did not turn red at thirty percent");
        ItemStack saved = ItemStack.deserializeBytes(sword.serializeAsBytes());
        nativeBar(saved, 100, 30);
        check(durability(saved) == 30 && durabilityLineRed(saved), "Serialized sword lost counter or early warning lore");
        saved.setData(DataComponentTypes.MAX_DAMAGE, 300);
        saved.setData(DataComponentTypes.DAMAGE, 2);
        call(items, "refresh", saved, player.player);
        nativeBar(saved, 100, 30);
        ItemStack refreshed = saved.clone();
        for (int n = 0; n < 3; n++) call(items, "refresh", saved, player.player);
        check(saved.equals(refreshed), "Repeated refresh repaired or changed the remaining durability");
        saved.setType(Material.STICK);
        saved.unsetData(DataComponentTypes.MAX_DAMAGE);
        saved.unsetData(DataComponentTypes.DAMAGE);
        ItemStack migrated = (ItemStack) call(items, "migrate", saved, player.player);
        check(migrated.getType() == Material.DIAMOND_SWORD && durability(migrated) == 30,
                "Migration lost native material or repaired custom durability");
        nativeBar(migrated, 100, 30);
        nativeBar(ItemStack.deserializeBytes(migrated.serializeAsBytes()), 100, 30);
        for (String id : List.of("tool", "hat", "food_durable", "trident", "shield", "glider", "single_glider", "huge_trident")) {
            ItemStack item = create(id);
            int max = durability(item);
            nativeBar(item, max, max);
            if (max > 1) {
                damage(item);
                nativeBar(item, max, max - 1);
                setDurability(item, 1); call(items, "refresh", item, player.player);
                nativeBar(item, max, 1);
            }
        }
        org.bukkit.entity.ExperienceOrb orb = Bukkit.getWorlds().getFirst().spawn(
                Bukkit.getWorlds().getFirst().getSpawnLocation(), org.bukkit.entity.ExperienceOrb.class);
        try {
            PlayerItemMendEvent mend = new PlayerItemMendEvent(player.player, sword, EquipmentSlot.HAND, orb, 2);
            denied(mend, "mending only the native bar");
            nativeBar(sword, 100, 30);
            check(durability(sword) == 30, "Mending changed the custom counter");
            PlayerItemMendEvent vanilla = new PlayerItemMendEvent(player.player, ItemStack.of(Material.DIAMOND_SWORD),
                    EquipmentSlot.HAND, orb, 2);
            fire(vanilla); check(!vanilla.isCancelled(), "Managed mending guard affected vanilla items");
        } finally { orb.remove(); }
        player.clear(); passed();
    }

    private void eventDurability() {
        stage = "input clicks do not wear items; completion and cancellation charge once";
        for (String id : List.of("tool", "durability_sword", "hat", "food_durable", "bow", "trident", "shield")) {
            if (id.equals("bow") && !integrations) continue;
            levels(20);
            ItemStack item = create(id); int remaining = durability(item);
            for (EquipmentSlot hand : List.of(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)) {
                player.items[0] = null; player.items[40] = null;
                player.items[hand == EquipmentSlot.HAND ? 0 : 40] = item;
                for (Action action : List.of(Action.LEFT_CLICK_AIR, Action.LEFT_CLICK_BLOCK, Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK)) {
                    boolean block = action == Action.LEFT_CLICK_BLOCK || action == Action.RIGHT_CLICK_BLOCK;
                    PlayerInteractEvent click = new PlayerInteractEvent(player.player, action, item,
                            block ? Bukkit.getWorlds().getFirst().getSpawnLocation().getBlock() : null,
                            block ? BlockFace.UP : BlockFace.SELF, hand);
                    fire(click);
                    check(click.useItemInHand() != Event.Result.DENY && durability(item) == remaining,
                            "Input click was blocked or spent durability before completion: " + id + "/" + action + "/" + hand);
                    PlayerInteractEvent cancelled = new PlayerInteractEvent(player.player, action, item,
                            block ? Bukkit.getWorlds().getFirst().getSpawnLocation().getBlock() : null,
                            block ? BlockFace.UP : BlockFace.SELF, hand);
                    cancelled.setCancelled(true); fire(cancelled);
                    check(durability(item) == remaining, "Cancelled input click spent durability: " + id + "/" + action);
                }
            }
        }
        player.clear();
        ItemStack tool = create("tool"); player.items[0] = tool;
        PlayerItemDamageEvent nativeDamage = new PlayerItemDamageEvent(player.player, tool, 7, 7);
        denied(nativeDamage, "vanilla double damage");
        check(durability(tool) == 3, "Native material damage consumed custom durability");
        BlockBreakEvent cancelled = new BlockBreakEvent(Bukkit.getWorlds().getFirst().getSpawnLocation().getBlock(), player.player);
        cancelled.setCancelled(true); fire(cancelled);
        check(durability(tool) == 3, "Cancelled protected break consumed durability");
        EntityDamageByEntityEvent cancelledAttack = new EntityDamageByEntityEvent(player.player, entity(),
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, source(), 4);
        cancelledAttack.setCancelled(true); fire(cancelledAttack);
        fire(new EntityDamageByEntityEvent(player.player, entity(), EntityDamageEvent.DamageCause.ENTITY_ATTACK, source(), 0));
        check(durability(tool) == 3, "Cancelled or zero-damage melee spent durability");
        fire(new BlockBreakEvent(Bukkit.getWorlds().getFirst().getSpawnLocation().getBlock(), player.player));
        check(durability(tool) == 2, "Successful mining did not consume one point");
        fire(new EntityDamageByEntityEvent(player.player, entity(), EntityDamageEvent.DamageCause.ENTITY_ATTACK, source(), 4));
        check(durability(tool) == 1, "Successful melee did not consume one point");
        nativeBar(tool, 3, 1);
        player.clear();
        ItemStack hat = create("hat"); player.items[39] = hat;
        EntityDamageEvent cancelledWear = new EntityDamageEvent(player.player, EntityDamageEvent.DamageCause.FALL, source(), 2);
        cancelledWear.setCancelled(true); fire(cancelledWear);
        fire(new EntityDamageEvent(player.player, EntityDamageEvent.DamageCause.FALL, source(), 0));
        check(durability(hat) == 10, "Cancelled or zero damage spent worn item durability");
        fire(new EntityDamageEvent(player.player, EntityDamageEvent.DamageCause.FALL, source(), 2));
        check(durability(hat) == 9, "Worn item damage did not consume one point");
        nativeBar(hat, 10, 9);
        player.clear(); passed();
    }

    private void specialToolDurability() {
        stage = "special native tool wear confirms actual use without charging input clicks";
        for (String id : List.of("brush", "pig_control", "strider_control")) {
            for (EquipmentSlot hand : List.of(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)) {
                player.clear();
                ItemStack item = create(id); player.items[hand == EquipmentSlot.HAND ? 0 : 40] = item;
                fire(new PlayerInteractEvent(player.player, Action.RIGHT_CLICK_AIR, item, null, BlockFace.SELF, hand));
                check(durability(item) == 3, "Special tool input spent durability: " + id);
                PlayerItemDamageEvent cancelled = new PlayerItemDamageEvent(player.player, item, 1, 1);
                cancelled.setCancelled(true); fire(cancelled);
                fire(new PlayerItemDamageEvent(player.player, item, 0, 1));
                check(durability(item) == 3, "Cancelled/zero native tool wear spent durability: " + id);
                PlayerItemDamageEvent used = new PlayerItemDamageEvent(player.player, item, 7, 7);
                denied(used, "automatic special-tool damage");
                check(durability(item) == 2, "Confirmed special-tool wear did not spend exactly one point: " + id);
                nativeBar(item, 3, 2);
            }
        }
        player.clear(); passed();
    }

    private void nativeControlStickBreak() throws Exception {
        stage = "native exhausted control-stick conversion cannot revive custom identity";
        Class<?> craft = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack");
        Class<?> nativeType = Class.forName("net.minecraft.world.item.ItemStack");
        Class<?> itemLike = Class.forName("net.minecraft.world.level.ItemLike");
        Object fishingRod = Class.forName("net.minecraft.world.item.Items").getField("FISHING_ROD").get(null);
        for (String id : List.of("pig_control", "strider_control")) {
            for (EquipmentSlot hand : List.of(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)) {
                player.clear();
                // Retain the exact native source BEFORE it empties. Copying an empty Bukkit
                // item afterwards would erase the broken patch and conceal a resurrection bug.
                Object nativeSource = craft.getMethod("asNMSCopy", ItemStack.class).invoke(null, create(id));
                ItemStack held = (ItemStack) craft.getMethod("asCraftMirror", nativeType).invoke(null, nativeSource);
                player.items[hand == EquipmentSlot.HAND ? 0 : 40] = held;
                damage(held); damage(held);
                check(durability(held) == 1, "Control-stick final-use fixture is not at one point");
                denied(new PlayerItemDamageEvent(player.player, held, 7, 7), "native last-point control-stick wear");
                check(empty(held), "Final control-stick use did not exhaust the exact native stack");
                // These are the pinned native hurtAndConvertOnBreak post-event operations.
                Method transmute = nativeType.getDeclaredMethod("transmuteCopyIgnoreEmpty", itemLike, int.class);
                transmute.setAccessible(true);
                Object replacement = transmute.invoke(nativeSource, fishingRod, 1);
                nativeType.getMethod("setDamageValue", int.class).invoke(replacement, 0);
                ItemStack converted = (ItemStack) craft.getMethod("asCraftMirror", nativeType).invoke(null, replacement);
                check(converted.equals(ItemStack.of(Material.FISHING_ROD)) && definition(converted) == null,
                        "Native control-stick conversion revived custom metadata/identity instead of an ordinary rod");
            }
        }
        player.clear(); passed();
    }

    private void completedToolActions() {
        stage = "confirmed shearing and block tool actions charge their actual hand once";
        var block = Bukkit.getWorlds().getFirst().getSpawnLocation().getBlock();
        for (EquipmentSlot hand : List.of(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)) {
            for (boolean entityShear : List.of(true, false)) {
                player.clear(); ItemStack shears = create("shears");
                player.items[hand == EquipmentSlot.HAND ? 0 : 40] = shears;
                fire(new PlayerInteractEvent(player.player, Action.RIGHT_CLICK_BLOCK, shears, block, BlockFace.UP, hand));
                fire(new PlayerInteractEntityEvent(player.player, entity(), hand));
                check(durability(shears) == 3, "Shears input spent durability before confirmed shearing");
                Event cancelled = entityShear
                        ? new PlayerShearEntityEvent(player.player, entity(), shears.clone(), hand, new ArrayList<>())
                        : new PlayerShearBlockEvent(player.player, block, shears.clone(), hand, new ArrayList<>());
                ((Cancellable) cancelled).setCancelled(true); fire(cancelled);
                check(durability(shears) == 3, "Cancelled shearing spent durability");
                Event completed = entityShear
                        ? new PlayerShearEntityEvent(player.player, entity(), shears.clone(), hand, new ArrayList<>())
                        : new PlayerShearBlockEvent(player.player, block, shears.clone(), hand, new ArrayList<>());
                fire(completed);
                check(!((Cancellable) completed).isCancelled() && durability(shears) == 2,
                        "Confirmed shearing did not charge exactly one point on actual held stack: " + entityShear + "/" + hand);
                nativeBar(shears, 3, 2);
            }
            for (String id : List.of("axe", "hoe", "shovel", "flint")) {
                player.clear(); ItemStack tool = create(id);
                player.items[hand == EquipmentSlot.HAND ? 0 : 40] = tool;
                fire(new PlayerInteractEvent(player.player, Action.RIGHT_CLICK_BLOCK, tool, block, BlockFace.UP, hand));
                check(durability(tool) == 3, "Block tool input spent durability before confirmation: " + id);
                BlockPlaceEvent cancelled = new BlockPlaceEvent(block, block.getState(), block, tool.clone(), player.player, true, hand);
                cancelled.setCancelled(true); fire(cancelled);
                BlockPlaceEvent protectedBlock = new BlockPlaceEvent(block, block.getState(), block, tool.clone(), player.player, false, hand);
                fire(protectedBlock);
                check(durability(tool) == 3, "Cancelled or protected block tool action spent durability: " + id);
                BlockPlaceEvent completed = new BlockPlaceEvent(block, block.getState(), block, tool.clone(), player.player, true, hand);
                fire(completed);
                check(!completed.isCancelled() && durability(tool) == 2,
                        "Confirmed block tool action did not spend exactly one actual-hand point: " + id + "/" + hand);
                nativeBar(tool, 3, 2);
            }
        }
        player.clear(); passed();
    }

    private void completedFishing() {
        stage = "fishing completion, cancellation and same-reel duplicate protection";
        for (EquipmentSlot hand : List.of(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)) {
            player.clear(); ItemStack rod = create("fishing_rod");
            player.items[hand == EquipmentSlot.HAND ? 0 : 40] = rod;
            for (PlayerFishEvent.State state : List.of(PlayerFishEvent.State.FISHING, PlayerFishEvent.State.BITE,
                    PlayerFishEvent.State.LURED, PlayerFishEvent.State.REEL_IN, PlayerFishEvent.State.FAILED_ATTEMPT)) {
                fire(new PlayerFishEvent(player.player, null, fishHook(), hand, state));
                check(durability(rod) == 10, "Fishing input/noncatch state spent durability: " + state);
            }
            FishHook hook = fishHook();
            PlayerFishEvent cancelled = new PlayerFishEvent(player.player, entity(), hook, hand, PlayerFishEvent.State.CAUGHT_FISH);
            cancelled.setCancelled(true); fire(cancelled);
            check(durability(rod) == 10, "Cancelled catch spent durability");
            fire(new PlayerFishEvent(player.player, entity(), hook, hand, PlayerFishEvent.State.CAUGHT_FISH));
            check(durability(rod) == 9, "Completed catch did not spend one actual-hand point");
            fire(new PlayerFishEvent(player.player, entity(), hook, hand, PlayerFishEvent.State.CAUGHT_FISH));
            fire(new PlayerFishEvent(player.player, entity(), hook, hand, PlayerFishEvent.State.CAUGHT_ENTITY));
            check(durability(rod) == 9, "Same hook/tick catch completion spent more than one point");
            fire(new PlayerFishEvent(player.player, entity(), fishHook(), hand, PlayerFishEvent.State.CAUGHT_ENTITY));
            check(durability(rod) == 8, "Completed entity catch did not spend one point");
            fire(new PlayerFishEvent(player.player, null, fishHook(), hand, PlayerFishEvent.State.IN_GROUND));
            check(durability(rod) == 7, "Completed in-ground reel did not spend one point");
            nativeBar(rod, 10, 7);
            levels(1); ItemStack restricted = create("restricted_fishing_rod");
            player.items[hand == EquipmentSlot.HAND ? 0 : 40] = restricted;
            denied(new PlayerFishEvent(player.player, entity(), fishHook(), hand, PlayerFishEvent.State.CAUGHT_FISH),
                    "restricted fishing catch");
            check(durability(restricted) == 10, "Restricted fishing consumed durability");
        }
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

    private void equipmentTypes() {
        stage = "only wearable definitions equip; native defaults and restored components are removed";
        var dispenser = Bukkit.getWorlds().getFirst().getSpawnLocation().getBlock();
        for (String id : List.of("ordinary_head", "ordinary_pumpkin", "ordinary_helmet")) {
            player.clear();
            ItemStack item = create(id);
            check(!item.hasData(DataComponentTypes.EQUIPPABLE), "Native material made a nonwearable definition equippable: " + id);
            ItemStack saved = ItemStack.deserializeBytes(item.serializeAsBytes());
            check(!saved.hasData(DataComponentTypes.EQUIPPABLE) && definition(saved) != null,
                    "Serialization restored native equipment defaults: " + id);
            saved.setData(DataComponentTypes.EQUIPPABLE, Equippable.equippable(EquipmentSlot.HEAD).build());
            call(items, "refresh", saved, player.player);
            check(!saved.hasData(DataComponentTypes.EQUIPPABLE), "Refresh retained stale equippable component: " + id);
            saved.setData(DataComponentTypes.EQUIPPABLE, Equippable.equippable(EquipmentSlot.HEAD).build());
            ItemStack migrated = (ItemStack) call(items, "migrate", saved, player.player);
            check(!migrated.hasData(DataComponentTypes.EQUIPPABLE), "Migration retained stale/native equipment defaults: " + id);
            ItemStack vanilla = ItemStack.of(item.getType()), original = vanilla.clone();
            check(vanilla.hasData(DataComponentTypes.EQUIPPABLE), "Native equipment positive control has no component: " + id);
            call(items, "refresh", vanilla, player.player);
            check(vanilla.equals(original), "Managed-item refresh modified vanilla equipment: " + id);

            item.setData(DataComponentTypes.EQUIPPABLE, Equippable.equippable(EquipmentSlot.HEAD).build());
            player.cursor = item;
            denied(new InventoryClickEvent(player.view, InventoryType.SlotType.ARMOR, 5, ClickType.LEFT, InventoryAction.PLACE_ALL),
                    "nonwearable armor click: " + id);
            denied(new InventoryDragEvent(player.view, null, item, false, Map.of(5, item)), "nonwearable armor drag: " + id);
            player.cursor = null; player.items[1] = item;
            denied(new InventoryClickEvent(player.view, InventoryType.SlotType.ARMOR, 5, ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, 1),
                    "nonwearable number-key armor: " + id);
            item.setData(DataComponentTypes.EQUIPPABLE, Equippable.equippable(EquipmentSlot.HEAD).build());
            denied(new InventoryClickEvent(player.view, InventoryType.SlotType.QUICKBAR, 37, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY),
                    "nonwearable native shift-equip: " + id);
            player.items[40] = item;
            denied(new InventoryClickEvent(player.view, InventoryType.SlotType.ARMOR, 5, ClickType.SWAP_OFFHAND, InventoryAction.HOTBAR_SWAP),
                    "nonwearable offhand-key armor: " + id);
            denied(new PlayerSwapWithEquipmentSlotEvent(player.player, item, EquipmentSlot.HEAD, ItemStack.of(Material.AIR)),
                    "nonwearable interact-equip swap: " + id);
            item.setData(DataComponentTypes.EQUIPPABLE, Equippable.equippable(EquipmentSlot.HEAD).build());
            denied(new BlockDispenseArmorEvent(dispenser, item, player.player), "nonwearable dispenser equip: " + id);

            player.clear(); player.cursor = create(id);
            InventoryClickEvent hold = new InventoryClickEvent(player.view, InventoryType.SlotType.QUICKBAR,
                    36, ClickType.LEFT, InventoryAction.PLACE_ALL);
            fire(hold); check(!hold.isCancelled(), "Nonwearable item could not move to the hand: " + id);
            InventoryDragEvent offhand = new InventoryDragEvent(player.view, null, player.cursor, false, Map.of(45, player.cursor));
            fire(offhand); check(!offhand.isCancelled(), "Nonwearable item could not move to offhand: " + id);
            PlayerSwapWithEquipmentSlotEvent handSwap = new PlayerSwapWithEquipmentSlotEvent(player.player, player.cursor,
                    EquipmentSlot.OFF_HAND, ItemStack.of(Material.AIR));
            fire(handSwap); check(!handSwap.isCancelled(), "Nonwearable item could not swap to offhand: " + id);
            player.clear(); ItemStack restored = create(id);
            restored.setData(DataComponentTypes.EQUIPPABLE, Equippable.equippable(EquipmentSlot.HEAD).build());
            player.items[39] = restored;
            call(guard, "reconcile", player.player);
            check(empty(player.items[39]) && player.count(id) == 1,
                    "Restored nonwearable equipment was not safely returned to storage: " + id);
            ItemStack returned = Arrays.stream(player.items).filter(value -> !empty(value)).findFirst().orElseThrow();
            check(!returned.hasData(DataComponentTypes.EQUIPPABLE), "Returned nonwearable retained stale equip component: " + id);
        }
        for (String id : List.of("hat", "aura", "skull", "glider")) {
            player.clear(); ItemStack wearable = create(id);
            EquipmentSlot slot = id.equals("glider") ? EquipmentSlot.CHEST : EquipmentSlot.HEAD;
            int raw = slot == EquipmentSlot.CHEST ? 6 : 5;
            check(wearable.getData(DataComponentTypes.EQUIPPABLE).slot() == slot, "Wearable lost configured slot: " + id);
            player.cursor = wearable;
            InventoryClickEvent click = new InventoryClickEvent(player.view, InventoryType.SlotType.ARMOR, raw,
                    ClickType.LEFT, InventoryAction.PLACE_ALL);
            fire(click); check(!click.isCancelled(), "Valid wearable armor click was denied: " + id);
            InventoryDragEvent drag = new InventoryDragEvent(player.view, null, wearable, false, Map.of(raw, wearable));
            fire(drag); check(!drag.isCancelled(), "Valid wearable armor drag was denied: " + id);
            PlayerSwapWithEquipmentSlotEvent swap = new PlayerSwapWithEquipmentSlotEvent(player.player, wearable, slot, ItemStack.of(Material.AIR));
            fire(swap); check(!swap.isCancelled(), "Valid wearable interact-equip was denied: " + id);
            BlockDispenseArmorEvent dispense = new BlockDispenseArmorEvent(dispenser, wearable, player.player);
            fire(dispense); check(!dispense.isCancelled(), "Valid wearable dispenser equip was denied: " + id);
            denied(new InventoryClickEvent(player.view, InventoryType.SlotType.ARMOR, slot == EquipmentSlot.CHEST ? 5 : 6,
                    ClickType.LEFT, InventoryAction.PLACE_ALL), "wearable in wrong configured slot: " + id);
            player.cursor = null; player.items[slot == EquipmentSlot.CHEST ? 38 : 39] = wearable;
            call(guard, "reconcile", player.player);
            check(!empty(player.items[slot == EquipmentSlot.CHEST ? 38 : 39]), "Reconcile removed valid wearable: " + id);
        }
        player.clear(); player.cursor = ItemStack.of(Material.DIAMOND_HELMET);
        InventoryClickEvent vanillaClick = new InventoryClickEvent(player.view, InventoryType.SlotType.ARMOR,
                5, ClickType.LEFT, InventoryAction.PLACE_ALL);
        fire(vanillaClick); check(!vanillaClick.isCancelled(), "Ordinary vanilla helmet equip was denied");
        Fake chest = new Fake("EquipmentChestProbe", false); chest.viewType = InventoryType.CHEST;
        chest.items[0] = create("ordinary_head");
        chest.items[0].setData(DataComponentTypes.EQUIPPABLE, Equippable.equippable(EquipmentSlot.HEAD).build());
        InventoryClickEvent externalMove = new InventoryClickEvent(chest.view, InventoryType.SlotType.QUICKBAR,
                36, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        fire(externalMove); check(!externalMove.isCancelled(), "Nonwearable external-inventory shift move was mistaken for equipment");
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
        nativeBar(first.getReplacement(), 2, 1);
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
            nativeBar(trident, 3, 2);
            check(durability(projectile.getItemStack()) == 2 && definition(projectile.getItemStack()) != null,
                    "Real Paper projectile pickup stack lost durability/identity");
            nativeBar(projectile.getItemStack(), 3, 2);
            // Pinned Paper's native throw path assigns its pre-event copy after dispatch.
            // Reproduce that boundary; the plugin's next-tick repair must restore the spent point.
            projectile.setItemStack(oldCarry);
            check(durability(projectile.getItemStack()) == 3, "Invalid native trident carry overwrite fixture");
            pendingProjectile = projectile;
            ItemStack riptide = create("trident"); riptide.addUnsafeEnchantment(Enchantment.RIPTIDE, 1); player.items[0] = riptide;
            PlayerRiptideEvent spin = new PlayerRiptideEvent(player.player, riptide, new org.bukkit.util.Vector(0, 1, 0));
            fire(spin);
            check(!spin.isCancelled() && durability(riptide) == 2, "Riptide did not consume exactly one point");
            nativeBar(riptide, 3, 2);
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
            nativeBar(source, 3, 2);
            ItemStack ordinary = create("throwable_plain"); player.items[0] = ordinary;
            PlayerLaunchProjectileEvent plain = new PlayerLaunchProjectileEvent(player.player, ordinary, snowball);
            fire(plain);
            check(!plain.isCancelled() && plain.shouldConsume(), "Durability-disabled throwable lost normal consumption");
        } finally { snowball.remove(); player.clear(); }
        passed();
    }

    private void entityAndShieldDurability() {
        stage = "entity input keeps durability and fully blocked shields charge once";
        ItemStack tool = create("tool"); player.items[0] = tool;
        PlayerInteractEntityEvent use = new PlayerInteractEntityEvent(player.player, entity(), EquipmentSlot.HAND);
        fire(use);
        check(!use.isCancelled() && durability(tool) == 3, "Entity interaction spent durability before actual use");
        PlayerInteractEntityEvent cancelled = new PlayerInteractEntityEvent(player.player, entity(), EquipmentSlot.HAND);
        cancelled.setCancelled(true); fire(cancelled);
        check(durability(tool) == 3, "Cancelled entity interaction consumed durability");
        ItemStack targeted = create("tool"); player.items[0] = targeted; Entity target = entity();
        fire(new PlayerInteractAtEntityEvent(player.player, target, new org.bukkit.util.Vector(0, 1, 0), EquipmentSlot.HAND));
        fire(new PlayerInteractEntityEvent(player.player, target, EquipmentSlot.HAND));
        check(durability(targeted) == 3, "Entity-at/general input pair spent durability");
        player.clear(); ItemStack shield = create("shield"); player.items[40] = shield; player.blocking = true;
        com.google.common.base.Function<Double, Double> zero = ignored -> 0d;
        EntityDamageEvent blocked = new EntityDamageEvent(player.player, EntityDamageEvent.DamageCause.ENTITY_ATTACK, source(),
                new EnumMap<>(Map.of(EntityDamageEvent.DamageModifier.BASE, 4d, EntityDamageEvent.DamageModifier.BLOCKING, -4d)),
                new EnumMap<>(Map.of(EntityDamageEvent.DamageModifier.BASE, zero, EntityDamageEvent.DamageModifier.BLOCKING, zero)));
        check(blocked.getDamage() == 4 && blocked.getFinalDamage() == 0, "Invalid fully blocked damage fixture");
        blocked.setCancelled(true); fire(blocked);
        check(durability(shield) == 3, "Cancelled shield block spent durability");
        blocked.setCancelled(false);
        fire(blocked);
        check(durability(shield) == 2, "Fully blocked damage did not wear the active shield once");
        nativeBar(shield, 3, 2);
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
        nativeBar(glider, 3, 2);
        call(guard, "flightTick", player.player);
        check(durability(glider) == 1, "Second gliding interval differs");
        nativeBar(glider, 3, 1);
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

    private void policies() throws Exception {
        var file = plugin.getDataFolder().toPath().resolve("items/policy.yml");
        String definitions = """
                items:
                  policy_bound:
                    material: STONE
                    max-durability: 0
                    max-stack-size: 64
                    soulbound: true
                  policy_noplace:
                    material: STONE
                    max-durability: 5
                    canplace: false
                    requirements: {planetry-gamelevel: 999999}
                  policy_hat:
                    material: PAPER
                    type: COSMETIC
                    soulbound: true
                    requirements: {planetry-gamelevel: 999999}
                  policy_arrow:
                    material: ARROW
                    max-durability: 0
                    max-stack-size: 64
                    soulbound: true
                  policy_crossbow:
                    material: CROSSBOW
                    max-durability: 0
                    soulbound: true
                """;
        Files.writeString(file, definitions, StandardCharsets.UTF_8);
        var command = plugin.getCommand("planetryitems");
        player.admin = true;
        command.execute(player.player, "pitems", new String[]{"reload"});
        Object policy = call(plugin, "policy");
        ItemStack bound = create("policy_bound"), noPlace = create("policy_noplace");
        stage = "policy flags on new, old, serialized, migrated and reloaded items";
        var boundKey = new NamespacedKey(plugin, "soulbound");
        var placeKey = new NamespacedKey(plugin, "can_place");
        var foreign = new NamespacedKey("foreign", "policy-probe");
        String common = integrations ? "GLYPH[tag_common]" : "COMMON";
        check(lore(bound).equals(List.of("ɪᴛᴇᴍ • ѕᴏᴜʟʙᴏᴜɴᴅ", "", common)),
                "Soulbound suffix changed type-line spacing or added a separate lore section: " + lore(bound));
        check(coloredText(bound.getItemMeta().lore().getFirst(), -1, "ѕᴏᴜʟʙᴏᴜɴᴅ", 0xB589D6),
                "Soulbound label did not use #B589D6");
        check(lore(noPlace).getFirst().equals("ɪᴛᴇᴍ"), "Placement-only item gained a soulbound suffix");
        check(bound.getPersistentDataContainer().get(boundKey, PersistentDataType.BYTE) == 1, "Soulbound PDC missing");
        check(noPlace.getPersistentDataContainer().get(placeKey, PersistentDataType.BYTE) == 0, "Can-place PDC missing");
        ItemStack serializedBound = ItemStack.deserializeBytes(bound.serializeAsBytes());
        check((boolean) call(items, "soulbound", serializedBound), "Serialized flag lost");
        check(lore(serializedBound).equals(lore(bound))
                && coloredText(serializedBound.getItemMeta().lore().getFirst(), -1, "ѕᴏᴜʟʙᴏᴜɴᴅ", 0xB589D6),
                "Serialization lost the soulbound type label or colour");
        bound.editPersistentDataContainer(data -> { data.remove(boundKey); data.set(foreign, PersistentDataType.STRING, "keep"); });
        bound.editMeta(meta -> meta.lore(List.of(Component.text("Legacy tooltip"))));
        check((boolean) call(items, "soulbound", bound), "Old item without flag ignored current definition");
        ItemStack migrated = (ItemStack) call(items, "migrate", bound, player.player);
        check(migrated.getPersistentDataContainer().get(boundKey, PersistentDataType.BYTE) == 1
                && "keep".equals(migrated.getPersistentDataContainer().get(foreign, PersistentDataType.STRING)), "Flag migration lost data");
        check(lore(migrated).equals(List.of("ɪᴛᴇᴍ • ѕᴏᴜʟʙᴏᴜɴᴅ", "", common)), "Old item migration missed soulbound label");
        call(items, "refresh", migrated, player.player);
        call(items, "refresh", migrated, player.player);
        check(lore(migrated).equals(lore(serializedBound)), "Repeated refresh duplicated the soulbound suffix");
        // Existing catalogs must preserve protections through the spelling-only upgrade.
        Object canonicalCatalog = call(plugin, "catalog");
        Files.writeString(file, definitions.replace("soulbound:", "planetry-soulbound:")
                .replace("canplace:", "planetry-canplace:"), StandardCharsets.UTF_8);
        command.execute(player.player, "pitems", new String[]{"reload"});
        check(call(plugin, "catalog") != canonicalCatalog, "Legacy flag catalog failed to activate on reload");
        check((boolean) call(items, "soulbound", create("policy_bound"))
                && !(boolean) call(items, "canPlace", create("policy_noplace")), "Legacy flag aliases lost protections");
        Object legacyCatalog = call(plugin, "catalog");
        Files.writeString(file, definitions.replace("    soulbound: true", "    soulbound: true\n    planetry-soulbound: false"),
                StandardCharsets.UTF_8);
        command.execute(player.player, "pitems", new String[]{"reload"});
        check(call(plugin, "catalog") == legacyCatalog && (boolean) call(items, "soulbound", migrated)
                && !(boolean) call(items, "canPlace", noPlace), "Conflicting aliases replaced the active catalog");
        Files.writeString(file, definitions.replace("soulbound: true", "soulbound: false")
                .replace("canplace: false", "canplace: true"), StandardCharsets.UTF_8);
        command.execute(player.player, "pitems", new String[]{"reload"});
        check(!(boolean) call(items, "soulbound", migrated) && (boolean) call(items, "canPlace", noPlace), "Reload did not override saved flags");
        call(items, "refresh", migrated, player.player);
        check(lore(migrated).equals(List.of("ɪᴛᴇᴍ", "", common)), "Disabling soulbound left its label or separator");
        Files.writeString(file, definitions, StandardCharsets.UTF_8);
        command.execute(player.player, "pitems", new String[]{"reload"});
        check((boolean) call(items, "soulbound", bound) && !(boolean) call(items, "canPlace", noPlace), "Reload flags not immediately enforced");
        bound = migrated;
        call(items, "refresh", bound, player.player);
        check(lore(bound).equals(lore(serializedBound)), "Enabling soulbound did not restore its type label");

        var languageFile = plugin.getDataFolder().toPath().resolve("lang.yml");
        String originalLanguage = Files.readString(languageFile, StandardCharsets.UTF_8);
        try {
            Files.writeString(languageFile, originalLanguage + "\ntypes:\n  item: '<gold>Relic'\nlore:\n  soulbound: '<gray> / <light_purple>Bound'\n", StandardCharsets.UTF_8);
            command.execute(player.player, "pitems", new String[]{"reload"});
            call(items, "refresh", bound, player.player);
            check(lore(bound).getFirst().equals("Relic / Bound"), "Custom type/soulbound translations not applied");
            check(lore(create("policy_noplace")).getFirst().equals("Relic"), "Custom suffix appeared on an ordinary item");
            Files.writeString(languageFile, "messages:\n  info: 'flags {canplace}/{soulbound}; legacy {planetry-canplace}/{planetry-soulbound}'\n",
                    StandardCharsets.UTF_8);
            command.execute(player.player, "pitems", new String[]{"reload"});
            player.items[0] = bound.clone(); player.messages.clear();
            command.execute(player.player, "pitems", new String[]{"info"});
            check(player.messages.contains("flags true/true; legacy true/true")
                    && player.messages.contains("canplace: true") && player.messages.contains("soulbound: true"),
                    "Info omitted canonical flags or broke old header substitutions: " + player.messages);
            check(player.messages.stream().noneMatch(message -> message.startsWith("planetry-canplace:")
                    || message.startsWith("planetry-soulbound:")), "Info detail labels still use the retired prefix");
        } finally {
            Files.writeString(languageFile, originalLanguage, StandardCharsets.UTF_8);
            command.execute(player.player, "pitems", new String[]{"reload"});
            call(items, "refresh", bound, player.player);
        }
        passed();

        stage = "silent placement veto in both hands with no durability cost";
        var block = player.player.getWorld().getBlockAt(player.player.getLocation().add(18, 0, 0));
        block.setType(Material.STONE);
        for (EquipmentSlot hand : List.of(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)) {
            player.inventory.setItem(hand, noPlace); player.messages.clear();
            var interact = new PlayerInteractEvent(player.player, Action.RIGHT_CLICK_BLOCK, noPlace, block, BlockFace.UP, hand);
            fire(interact);
            check(interact.useItemInHand() == Event.Result.DENY && interact.useInteractedBlock() != Event.Result.DENY
                    && player.messages.isEmpty() && durability(noPlace) == 5, "Placement interaction messaged, charged or blocked chest use");
            var place = new BlockPlaceEvent(block, block.getState(), block.getRelative(BlockFace.DOWN), noPlace, player.player, true, hand);
            fire(place); check(place.isCancelled() && player.messages.isEmpty(), "Block placement not silently denied");
            var bucket = new PlayerBucketEmptyEvent(player.player, block, block, BlockFace.UP, Material.WATER_BUCKET, new ItemStack(Material.BUCKET), hand);
            fire(bucket); check(bucket.isCancelled(), "Bucket checked replacement instead of source hand");
        }
        player.clear(); passed();

        stage = "external transfers, bundle writes, number/offhand swaps and positive inventory controls";
        var p = new Fake("PolicyInventory", true);
        p.viewType = InventoryType.CHEST; p.top = Bukkit.createInventory(null, 9); p.cursor = bound.clone();
        for (InventoryAction action : List.of(InventoryAction.PLACE_ALL, InventoryAction.PLACE_ONE, InventoryAction.PLACE_SOME,
                InventoryAction.SWAP_WITH_CURSOR, InventoryAction.PLACE_ALL_INTO_BUNDLE, InventoryAction.PLACE_SOME_INTO_BUNDLE,
                InventoryAction.PLACE_FROM_BUNDLE)) {
            var click = new InventoryClickEvent(p.view, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, action);
            fire(click); check(click.isCancelled(), "External transfer allowed: " + action);
        }
        for (InventoryAction action : List.of(InventoryAction.PICKUP_ALL, InventoryAction.COLLECT_TO_CURSOR,
                InventoryAction.PICKUP_ALL_INTO_BUNDLE, InventoryAction.PICKUP_SOME_INTO_BUNDLE, InventoryAction.PICKUP_FROM_BUNDLE)) {
            var click = new InventoryClickEvent(p.view, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, action);
            fire(click); check(!click.isCancelled(), "Retrieval was blocked: " + action);
        }
        p.cursor = null; p.items[0] = bound.clone(); p.items[40] = bound.clone();
        for (ClickType clickType : List.of(ClickType.NUMBER_KEY, ClickType.SWAP_OFFHAND)) {
            var click = new InventoryClickEvent(p.view, InventoryType.SlotType.CONTAINER, 0, clickType, InventoryAction.HOTBAR_SWAP, clickType == ClickType.NUMBER_KEY ? 0 : -1);
            fire(click); check(click.isCancelled(), "Hotbar/offhand external swap allowed");
        }
        var shift = new InventoryClickEvent(p.view, InventoryType.SlotType.QUICKBAR, p.top.getSize() + 27, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        fire(shift); check(shift.isCancelled(), "Shift-click into chest allowed");
        p.top.setItem(0, bound.clone());
        var retrieve = new InventoryClickEvent(p.view, InventoryType.SlotType.CONTAINER, 0, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        fire(retrieve); check(!retrieve.isCancelled(), "Shift retrieval blocked");
        p.cursor = bound.clone();
        var drag = new InventoryDragEvent(p.view, bound.clone(), bound.clone(), false, Map.of(0, bound.clone(), 9, bound.clone()));
        fire(drag); check(drag.isCancelled(), "Mixed external drag allowed");
        p.top = p.enderChest; p.viewType = InventoryType.ENDER_CHEST;
        var ender = new InventoryClickEvent(p.view, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PLACE_ALL);
        fire(ender); check(!ender.isCancelled(), "Own ender chest rejected");
        var enderDrag = new InventoryDragEvent(p.view, bound.clone(), bound.clone(), false, Map.of(0, bound.clone(), 27, bound.clone()));
        fire(enderDrag); check(!enderDrag.isCancelled(), "Own ender chest drag rejected");
        p.top = Bukkit.createInventory(null, InventoryType.ENDER_CHEST);
        var foreignEnder = new InventoryClickEvent(p.view, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PLACE_ALL);
        fire(foreignEnder); check(foreignEnder.isCancelled(), "Inventory type alone granted ender exemption");
        p.top = Bukkit.createInventory(null, 9); p.viewType = InventoryType.CRAFTING;
        var ownShift = new InventoryClickEvent(p.view, InventoryType.SlotType.QUICKBAR, 36, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        fire(ownShift); check(!ownShift.isCancelled(), "Personal shift movement blocked");
        var ownPlace = new InventoryClickEvent(p.view, InventoryType.SlotType.CONTAINER, 9, ClickType.LEFT, InventoryAction.PLACE_ALL);
        fire(ownPlace); check(!ownPlace.isCancelled(), "Own inventory placement blocked");
        passed();

        stage = "portable bundle and shulker carriers retain soulbound policy";
        ItemStack bundle = new ItemStack(Material.BUNDLE);
        BundleMeta bundleMeta = (BundleMeta) bundle.getItemMeta(); bundleMeta.addItem(bound.asOne()); bundle.setItemMeta(bundleMeta);
        ItemStack shulker = new ItemStack(Material.SHULKER_BOX);
        BlockStateMeta meta = (BlockStateMeta) shulker.getItemMeta();
        var box = (org.bukkit.block.ShulkerBox) meta.getBlockState(); box.getInventory().setItem(0, bundle);
        meta.setBlockState(box); shulker.setItemMeta(meta);
        check((boolean) call(policy, "soulbound", bundle) && (boolean) call(policy, "soulbound", shulker)
                && (boolean) call(policy, "placementBlocked", shulker), "Nested carrier escaped protection");
        for (Action action : List.of(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK)) {
            var unload = new PlayerInteractEvent(player.player, action, bundle, action == Action.RIGHT_CLICK_BLOCK ? block : null, BlockFace.UP, EquipmentSlot.HAND);
            fire(unload);
            check(unload.useItemInHand() == Event.Result.DENY
                    && ((BundleMeta) bundle.getItemMeta()).getItems().getFirst().equals(bound.asOne()), "Bundle could unload protected contents before drop veto");
        }
        ItemStack charged = new ItemStack(Material.CROSSBOW);
        charged.setData(DataComponentTypes.CHARGED_PROJECTILES, io.papermc.paper.datacomponent.item.ChargedProjectiles.chargedProjectiles(List.of(bound.asOne())));
        check((boolean) call(policy, "soulbound", charged), "Charged carrier hid protected item");
        ItemStack arrow = create("policy_arrow"), boundCrossbow = create("policy_crossbow");
        player.clear(); player.items[0] = boundCrossbow; player.items[40] = arrow;
        var load = new io.papermc.paper.event.entity.EntityLoadCrossbowEvent(player.player, boundCrossbow, EquipmentSlot.HAND);
        fire(load); check(load.isCancelled() && arrow.getAmount() == 1, "Crossbow loaded protected recoverable ammo");
        player.items[40] = new ItemStack(Material.ARROW);
        var ordinaryLoad = new io.papermc.paper.event.entity.EntityLoadCrossbowEvent(player.player, boundCrossbow, EquipmentSlot.HAND);
        fire(ordinaryLoad); check(!ordinaryLoad.isCancelled(), "Soulbound crossbow with ordinary ammo was blocked");
        var shoot = new EntityShootBowEvent(player.player, new ItemStack(Material.BOW), arrow, entity(), EquipmentSlot.HAND, 1f, true);
        fire(shoot); check(shoot.isCancelled() && arrow.getAmount() == 1, "Bow fired protected recoverable ammo");
        boundCrossbow.setData(DataComponentTypes.CHARGED_PROJECTILES, io.papermc.paper.datacomponent.item.ChargedProjectiles.chargedProjectiles(List.of(arrow)));
        var chargedBefore = boundCrossbow.getData(DataComponentTypes.CHARGED_PROJECTILES);
        var shootCharged = new PlayerInteractEvent(player.player, Action.RIGHT_CLICK_AIR, boundCrossbow, null, BlockFace.UP, EquipmentSlot.HAND);
        fire(shootCharged);
        check(shootCharged.useItemInHand() == Event.Result.DENY && chargedBefore.equals(boundCrossbow.getData(DataComponentTypes.CHARGED_PROJECTILES)), "Loaded protected ammo was cleared before veto");
        boundCrossbow.setData(DataComponentTypes.CHARGED_PROJECTILES, io.papermc.paper.datacomponent.item.ChargedProjectiles.chargedProjectiles(List.of(new ItemStack(Material.ARROW))));
        var ordinaryCharged = new PlayerInteractEvent(player.player, Action.RIGHT_CLICK_AIR, boundCrossbow, null, BlockFace.UP, EquipmentSlot.HAND);
        fire(ordinaryCharged); check(ordinaryCharged.useItemInHand() != Event.Result.DENY, "Soulbound crossbow with ordinary loaded ammo could not fire");
        player.clear();
        passed();

        stage = "drop toggle, exact warning, one confirmation per gesture and expiry/session fencing";
        p.clear(); p.gameMode = GameMode.CREATIVE;
        p.cursor = bound.clone();
        var blockedCursor = new InventoryClickEvent(p.view, InventoryType.SlotType.OUTSIDE, -999, ClickType.LEFT, InventoryAction.DROP_ALL_CURSOR);
        fire(blockedCursor); check(blockedCursor.isCancelled() && p.cursor.equals(bound), "Cursor drop was not stopped before mutation");
        p.admin = false;
        command.execute(p.player, "pitems", new String[]{"soulboundbypass"});
        check(drop(p, bound).isCancelled(), "Nonadmin enabled bypass");
        p.admin = true; p.messages.clear();
        command.execute(p.player, "pitems", new String[]{"soulboundbypass"});
        check(((List<?>) call(plugin, "onTabComplete", p.player, command, "pitems", new String[]{"soul"})).contains("soulboundbypass"), "Bypass completion absent");
        var first = new InventoryCreativeEvent(p.view, InventoryType.SlotType.OUTSIDE, -999, bound.clone());
        fire(first); check(first.isCancelled(), "Creative first gesture bypassed confirmation");
        check(contains(p.messages, "This item is Soulbound, are you sure you want to drop it? Toggle this bypass with /planetryitems soulboundbypass"), "Exact warning absent");
        var second = new InventoryCreativeEvent(p.view, InventoryType.SlotType.OUTSIDE, -999, bound.clone());
        fire(second); check(!second.isCancelled() && !drop(p, bound).isCancelled(), "Second creative gesture did not receive one native drop permit");
        check(drop(p, bound).isCancelled(), "Confirmation allowed repeated drops");
        ItemStack changed = bound.clone(); changed.editPersistentDataContainer(data -> data.set(foreign, PersistentDataType.STRING, "different"));
        check(drop(p, changed).isCancelled(), "Different metadata confirmed previous item");
        check(drop(p, bound.asQuantity(2)).isCancelled(), "Different quantity confirmed previous item");
        p.admin = false; check(drop(p, bound.asQuantity(2)).isCancelled(), "Permission loss retained bypass");
        p.admin = true; check(drop(p, bound.asQuantity(2)).isCancelled(), "Regained permission silently reenabled bypass");
        command.execute(p.player, "pitems", new String[]{"soulboundbypass"});
        check(drop(p, bound).isCancelled(), "First held drop allowed");
        fire(new PlayerQuitEvent(p.player, Component.empty()));
        check(drop(p, bound).isCancelled(), "Quit retained bypass");
        long[] now = {0};
        var constructor = policy.getClass().getDeclaredConstructor(plugin.getClass(), java.util.function.LongSupplier.class);
        constructor.setAccessible(true);
        Object timed = constructor.newInstance(plugin, (java.util.function.LongSupplier) () -> now[0]);
        call(timed, "toggleBypass", p.player);
        var expiredFirst = dropEvent(p, bound); call(timed, "drop", expiredFirst);
        now[0] = 2_000_000_001L;
        var expiredSecond = dropEvent(p, bound); call(timed, "drop", expiredSecond);
        check(expiredFirst.isCancelled() && expiredSecond.isCancelled(), "Expired confirmation allowed drop");
        var confirmed = dropEvent(p, bound); call(timed, "drop", confirmed); check(!confirmed.isCancelled(), "Fresh second drop rejected");
        var replacement = new Fake(p.id, p.name, true);
        var otherSession = dropEvent(replacement, bound); call(timed, "drop", otherSession);
        check(otherSession.isCancelled(), "Replacement Player inherited bypass");
        passed();

        stage = "durable cursor recovery on close, quit, death, readiness and capacity";
        Object recovery = call(plugin, "recovery");
        var r = new Fake("CursorRecovery", false);
        var recoveryKey = new NamespacedKey(plugin, "soulbound_recovery");
        for (int slot = 0; slot < 36; slot++) r.items[slot] = new ItemStack(Material.DIRT, 64);
        r.cursor = bound.asQuantity(2);
        ItemStack expectedCursor = r.cursor.clone();
        ItemStack[] beforeRecovery = snapshot(r);
        fire(new InventoryCloseEvent(r.view));
        check(empty(r.cursor) && r.data.has(recoveryKey, PersistentDataType.BYTE_ARRAY), "Close did not preserve full cursor before native cleanup");
        byte[] pendingBytes = r.data.get(recoveryKey, PersistentDataType.BYTE_ARRAY);
        check(Arrays.equals(ItemStack.deserializeItemsFromBytes(pendingBytes), new ItemStack[]{expectedCursor}), "Escrow changed metadata or quantity");
        call(recovery, "recover", r.player);
        check(Arrays.equals(beforeRecovery, snapshot(r)) && Arrays.equals(pendingBytes, r.data.get(recoveryKey, PersistentDataType.BYTE_ARRAY)), "Full recovery mutated items");
        r.items[2] = null;
        if (integrations) {
            setField("NetworkInventorySync", "ready", false);
            call(recovery, "recover", r.player);
            check(empty(r.items[2]) && r.data.has(recoveryKey), "Unready recovery mutated storage");
            setField("NetworkInventorySync", "ready", true);
        }
        // Recreate the service to model plugin restart with only persisted player data.
        Object restartedRecovery = recovery.getClass().getConstructor(plugin.getClass()).newInstance(plugin);
        call(restartedRecovery, "recover", r.player);
        check(expectedCursor.equals(r.items[2]) && !r.data.has(recoveryKey), "Recovery after restart lost or duplicated cursor");
        call(restartedRecovery, "recover", r.player);
        check(expectedCursor.equals(r.items[2]) && r.count("policy_bound") == 2, "Repeated recovery duplicated items");
        r.cursor = bound.clone(); fire(new PlayerQuitEvent(r.player, Component.empty()));
        check(empty(r.cursor) && r.data.has(recoveryKey), "Quit cursor lost");
        r.items[3] = null; call(recovery, "recover", r.player);
        check(r.count("policy_bound") == 3 && !r.data.has(recoveryKey), "Quit recovery lost quantity");
        r.cursor = bound.clone();
        var cursorDeath = new PlayerDeathEvent(r.player, DamageSource.builder(DamageType.GENERIC).build(), new ArrayList<>(), 0, Component.empty(), false);
        cursorDeath.setKeepInventory(true); fire(cursorDeath);
        check(empty(r.cursor) && r.data.has(recoveryKey), "Keep-inventory death lost protected cursor");
        r.items[4] = null; call(recovery, "recover", r.player);
        check(r.count("policy_bound") == 4 && !r.data.has(recoveryKey), "Death cursor recovery lost quantity");
        // Recovery can merge prior stacks, leaving the freed slots empty. Start a fully
        // occupied fixture with no matching merge capacity for the corruption scenario.
        for (int slot = 0; slot < 36; slot++) r.items[slot] = new ItemStack(Material.DIRT, 64);
        byte[] corrupt = {1, 127, 127, 127, 127};
        r.data.set(recoveryKey, PersistentDataType.BYTE_ARRAY, corrupt); r.cursor = bound.clone();
        fire(new InventoryCloseEvent(r.view));
        var extraKey = r.data.getKeys().stream().filter(key -> key.getKey().startsWith("soulbound_recovery_extra/")).findFirst().orElseThrow();
        check(empty(r.cursor) && Arrays.equals(corrupt, r.data.get(recoveryKey, PersistentDataType.BYTE_ARRAY))
                && Arrays.equals(new ItemStack[]{bound}, ItemStack.deserializeItemsFromBytes(r.data.get(extraKey, PersistentDataType.BYTE_ARRAY))), "Corruption prevented preserving new cursor independently");
        r.cursor = null; // Native cleanup now has no carried item to discard or drop.
        call(recovery, "recover", r.player);
        check(r.data.has(extraKey) && Arrays.equals(corrupt, r.data.get(recoveryKey, PersistentDataType.BYTE_ARRAY)), "Full recovery discarded isolated queue");
        r.items[5] = null; call(recovery, "recover", r.player);
        check(bound.equals(r.items[5]) && !r.data.has(extraKey) && Arrays.equals(corrupt, r.data.get(recoveryKey, PersistentDataType.BYTE_ARRAY)), "Isolated recovery did not preserve both old bytes and new item");
        r.data.remove(recoveryKey); r.clear();
        for (int index = 0; index < 37; index++) {
            ItemStack distinct = bound.asOne();
            final int itemIndex = index;
            distinct.editPersistentDataContainer(data -> data.set(foreign, PersistentDataType.STRING, "unique-" + itemIndex));
            r.cursor = distinct; call(recovery, "captureCursor", r.player);
        }
        check(ItemStack.deserializeItemsFromBytes(r.data.get(recoveryKey, PersistentDataType.BYTE_ARRAY)).length == 37, "Large pending queue lost entries");
        call(recovery, "recover", r.player);
        var last = ItemStack.deserializeItemsFromBytes(r.data.get(recoveryKey, PersistentDataType.BYTE_ARRAY));
        check(r.count("policy_bound") == 36 && last.length == 1
                && "unique-36".equals(last[0].getPersistentDataContainer().get(foreign, PersistentDataType.STRING)), "Queue larger than storage failed to make partial progress");
        r.items[0] = null; call(recovery, "recover", r.player);
        check(last[0].equals(r.items[0]) && !r.data.has(recoveryKey), "Incremental capacity did not finish remainder recovery");
        passed();

        stage = "death quantity retention and full-inventory equipment preservation";
        var drops = new ArrayList<ItemStack>(List.of(bound.clone(), bound.clone(), new ItemStack(Material.DIRT)));
        var death = new PlayerDeathEvent(p.player, DamageSource.builder(DamageType.GENERIC).build(), drops, 0, Component.empty(), false);
        fire(death);
        check(death.getDrops().size() == 1 && death.getItemsToKeep().size() == 2
                && death.getItemsToKeep().stream().mapToInt(ItemStack::getAmount).sum() == 2, "Death retention lost/duplicated exact stacks");
        player.clear(); for (int slot = 0; slot < 36; slot++) player.items[slot] = new ItemStack(Material.STONE, 64);
        player.items[39] = create("policy_hat");
        call(guard, "reconcile", player.player);
        check(!empty(player.items[39]) && "policy_hat".equals(call(items, "id", player.items[39]))
                && player.items[39].getData(DataComponentTypes.ATTRIBUTE_MODIFIERS).modifiers().isEmpty(), "Full storage discarded soulbound equipment");
        player.clear(); player.admin = false; p.clear(); call(policy, "clear"); passed();
    }

    private PlayerDropItemEvent dropEvent(Fake target, ItemStack stack) {
        var entity = proxy(org.bukkit.entity.Item.class, (object, method, args) -> method.getName().equals("getItemStack") ? stack.clone() : zero(method.getReturnType()));
        return new PlayerDropItemEvent(target.player, entity);
    }
    private PlayerDropItemEvent drop(Fake target, ItemStack stack) { var event = dropEvent(target, stack); fire(event); return event; }

    private void commandInfo() {
        var command = plugin.getCommand("planetryitems");
        for (String id : List.of("described", "restricted", "hat", "skull", "nexo")) {
            if (id.equals("nexo") && !integrations) continue;
            player.items[0] = create(id); player.messages.clear();
            command.execute(player.player, "pitems", new String[]{"info"});
            String text = String.join("\n", player.messages);
            for (String field : List.of("name", "material", "configured-material", "lore", "model", "nexo",
                    "base64", "rarity", "type", "amount", "stack", "unbreakable", "equip", "usable",
                    "bonuses", "requirements", "attributes", "durability", "canplace: true", "soulbound: false"))
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
            if (method.getName().equals("getPlayer") && player.id.equals(args[0])) return player.player;
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

    private String networkGrant(UUID target, String id, int amount) throws Exception {
        return (String) plugin.getClass().getMethod("giveNetworkItem", UUID.class, String.class, int.class)
                .invoke(plugin, target, id, amount);
    }

    @SuppressWarnings("unchecked")
    private List<String> networkIds(String prefix) { return (List<String>) call(plugin, "networkItemIds", prefix); }

    private void networkGrants() throws Exception {
        stage = "network item API signatures and bounded sorted catalog snapshots";
        check(plugin.getClass().getMethod("giveNetworkItem", UUID.class, String.class, int.class).getReturnType() == String.class,
                "Network grant method signature changed");
        check(plugin.getClass().getMethod("networkItemIds", String.class).getReturnType() == List.class,
                "Network catalog method signature changed");
        var file = plugin.getDataFolder().toPath().resolve("items/network-api-probe.yml");
        String longest = "n".repeat(64);
        var yaml = new StringBuilder("items:\n  network_stack:\n    material: AMETHYST_SHARD\n    max-durability: 0\n    max-stack-size: 64\n");
        yaml.append("  network_soulbound:\n    material: PAPER\n    max-durability: 0\n    soulbound: true\n    canplace: false\n");
        yaml.append("  ").append(longest).append(":\n    material: PAPER\n    max-durability: 0\n");
        for (int n = 109; n >= 0; n--)
            yaml.append("  network_probe_%03d:\n    material: PAPER\n    max-durability: 0\n".formatted(n));
        Field field = Bukkit.class.getDeclaredField("server"); field.setAccessible(true);
        Server actual = Bukkit.getServer();
        Fake recipient = new Fake("NetworkItemsProbe", false);
        var current = new java.util.concurrent.atomic.AtomicReference<Player>(recipient.player);
        var lookups = new java.util.concurrent.atomic.AtomicInteger();
        var replaceAt = new java.util.concurrent.atomic.AtomicInteger(Integer.MAX_VALUE);
        Fake replacement = new Fake(recipient.id, recipient.name, false);
        Server lookup = proxy(Server.class, (object, method, args) -> {
            if (method.getName().equals("getPlayer") && recipient.id.equals(args[0])) {
                if (lookups.incrementAndGet() == replaceAt.get()) current.set(replacement.player);
                return current.get();
            }
            try { return method.invoke(actual, args); }
            catch (InvocationTargetException error) { throw error.getCause(); }
        });
        try {
            Files.writeString(file, yaml, StandardCharsets.UTF_8);
            plugin.getCommand("planetryitems").execute(Bukkit.getConsoleSender(), "pitems", new String[]{"reload"});
            List<String> all = networkIds("");
            check(all.size() == 100 && all.equals(all.stream().sorted().toList()), "Network IDs are not bounded/sorted");
            List<String> prefixed = networkIds("network_probe_");
            check(prefixed.size() == 100 && prefixed.getFirst().equals("network_probe_000")
                    && prefixed.getLast().equals("network_probe_099"), "Network completion did not sort before limiting");
            check(networkIds("network_probe_10").size() == 10 && networkIds(longest).equals(List.of(longest)),
                    "Network prefix filtering lost IDs outside the first page or the 64-character boundary");
            for (String bad : Arrays.asList(null, "Network", "network ", "network:", "n".repeat(65)))
                check(networkIds(bad).isEmpty(), "Malformed network prefix accepted: " + bad);
            boolean immutable = false;
            try { all.add("mutated"); } catch (UnsupportedOperationException expected) { immutable = true; }
            check(immutable, "Network IDs must be an immutable snapshot");
            passed();

            field.set(null, lookup);
            stage = "network grants validate arguments, current session and primary thread";
            for (String bad : Arrays.asList(null, "", "Described", "described ", "-described", "_described", "a".repeat(65), "unknown_probe_id"))
                check(networkGrant(recipient.id, bad, 1).equals("UNKNOWN_ITEM"), "Bad network ID accepted: " + bad);
            for (int amount : new int[]{Integer.MIN_VALUE, 0, -1, 2305, Integer.MAX_VALUE})
                check(networkGrant(recipient.id, "described", amount).equals("INVALID_AMOUNT"), "Bad network amount accepted");
            check(networkGrant(null, "described", 1).equals("OFFLINE")
                    && networkGrant(UUID.randomUUID(), "described", 1).equals("OFFLINE"), "Missing network target accepted");
            recipient.online = false;
            check(networkGrant(recipient.id, "described", 1).equals("OFFLINE"), "Offline target accepted");
            recipient.online = true; recipient.valid = false;
            check(networkGrant(recipient.id, "described", 1).equals("OFFLINE"), "Invalid target accepted");
            recipient.valid = true; recipient.dead = true;
            check(networkGrant(recipient.id, "described", 1).equals("OFFLINE"), "Dead target accepted");
            recipient.dead = false;
            var async = new java.util.concurrent.atomic.AtomicReference<Object>();
            Thread thread = new Thread(() -> {
                try { async.set(List.of(networkGrant(recipient.id, "described", 1), networkIds(""))); }
                catch (Throwable error) { async.set(error); }
            }, "items-network-api-off-thread-probe");
            thread.start(); thread.join(5000);
            check(!thread.isAlive() && List.of("UNAVAILABLE", List.of()).equals(async.get()),
                    "Off-thread network API touched player state or did not refuse: " + async.get());
            check(recipient.inventoryCount() == 0, "Rejected network inputs changed inventory");
            passed();

            stage = "real ItemStack network grant quantity, notifications and full-inventory atomicity";
            recipient.messages.clear();
            check(networkGrant(recipient.id, "described", 17).equals("SUCCESS") && recipient.count("described") == 17,
                    "Network grant did not deliver the exact quantity without staff permission");
            check(recipient.messages.size() == 1 && contains(recipient.messages, "received"), "Network recipient notification missing/duplicated");
            check(Arrays.stream(recipient.items).filter(item -> !empty(item)).allMatch(item -> item.getAmount() <= 16),
                    "Network grant exceeded item stack limit");
            recipient.clear();
            check(networkGrant(recipient.id, "network_stack", 2304).equals("SUCCESS") && recipient.count("network_stack") == 2304,
                    "Maximum network grant failed in exactly 36 stackable slots");
            recipient.clear();
            check(networkGrant(recipient.id, longest, 1).equals("SUCCESS") && recipient.count(longest) == 1,
                    "Valid 64-character item ID refused");
            recipient.clear();
            check(networkGrant(recipient.id, "network_soulbound", 1).equals("SUCCESS")
                    && Boolean.TRUE.equals(call(call(plugin, "policy"), "soulbound", recipient.items[0]))
                    && recipient.items[0].getItemMeta().getPersistentDataContainer()
                            .get(new NamespacedKey(plugin, "can_place"), PersistentDataType.BYTE) == 0,
                    "Network grant discarded soulbound/placement policies");
            recipient.clear();
            levels(1);
            check(networkGrant(recipient.id, "restricted_hand", 1).equals("SUCCESS")
                    && !((Set<?>) call(items, "unmet", recipient.player, definition(recipient.items[0]))).isEmpty()
                    && recipient.items[0].getData(DataComponentTypes.ATTRIBUTE_MODIFIERS).modifiers().isEmpty(),
                    "Network grant bypassed positive requirements or activated denied bonuses");
            recipient.clear();
            ItemStack partial = (ItemStack) call(items, "create", "described", recipient.player); partial.setAmount(15);
            recipient.items[0] = partial;
            for (int slot = 1; slot < 36; slot++) recipient.items[slot] = ItemStack.of(Material.COBBLESTONE, 64);
            recipient.items[39] = ItemStack.of(Material.LEATHER_HELMET);
            recipient.items[40] = ItemStack.of(Material.SHIELD);
            recipient.cursor = ItemStack.of(Material.BOOK);
            ItemStack cursor = recipient.cursor.clone();
            ItemStack[] before = snapshot(recipient);
            check(networkGrant(recipient.id, "described", 2).equals("INVENTORY_FULL")
                    && Arrays.equals(before, recipient.items) && cursor.equals(recipient.cursor)
                    && partial.getAmount() == 15 && recipient.items[0] == partial,
                    "Failed partial merge changed live stacks, equipment or cursor");
            check(networkGrant(recipient.id, "described", 1).equals("SUCCESS") && recipient.count("described") == 16
                    && partial.getAmount() == 15, "Successful partial merge changed the original reference or wrong quantity");
            passed();

            recipient.clear();
            stage = "network grants reject session replacement and unavailable item sources";
            current.set(recipient.player); lookups.set(0); replaceAt.set(3);
            check(networkGrant(recipient.id, "described", 1).equals("OFFLINE") && recipient.inventoryCount() == 0
                    && replacement.inventoryCount() == 0, "Preparation granted to a replaced UUID session");
            replaceAt.set(Integer.MAX_VALUE); current.set(recipient.player);
            if (integrations) setField("Nexo", "available", false);
            check(networkGrant(recipient.id, "nexo", 1).equals("UNAVAILABLE") && recipient.inventoryCount() == 0,
                    "Unavailable Nexo grant changed inventory or silently used vanilla material");
            if (integrations) setField("Nexo", "available", true);
            passed();

            if (integrations) {
                stage = "network readiness and session fencing at the actual storage write";
                setField("NetworkInventorySync", "ready", false);
                check(networkGrant(recipient.id, "described", 1).equals("INVENTORY_UNAVAILABLE"), "Unready network inventory accepted");
                setField("NetworkInventorySync", "ready", true);
                setField("NetworkInventorySync", "readinessChecks", 0);
                // Grant preflight and ItemService.create/refresh each check readiness;
                // the third check is the final gate immediately before storage mutation.
                setField("NetworkInventorySync", "readyForChecks", 2);
                check(networkGrant(recipient.id, "described", 1).equals("INVENTORY_UNAVAILABLE") && recipient.inventoryCount() == 0,
                        "Readiness revoked during preparation still committed");
                setField("NetworkInventorySync", "readyForChecks", Integer.MAX_VALUE);
                var readiness = new java.util.concurrent.atomic.AtomicInteger();
                setField("NetworkInventorySync", "beforeReadiness", (Runnable) () -> {
                    if (readiness.incrementAndGet() == 3) current.set(replacement.player);
                });
                check(networkGrant(recipient.id, "described", 1).equals("OFFLINE") && recipient.inventoryCount() == 0
                        && replacement.inventoryCount() == 0, "Readiness callback replaced the session but grant committed");
                current.set(recipient.player); readiness.set(0);
                ItemStack foreign = ItemStack.of(Material.EMERALD, 3);
                setField("NetworkInventorySync", "beforeReadiness", (Runnable) () -> {
                    if (readiness.incrementAndGet() == 3) recipient.items[5] = foreign;
                });
                check(networkGrant(recipient.id, "described", 1).equals("INVENTORY_UNAVAILABLE")
                        && recipient.items[5] == foreign && recipient.count("described") == 0,
                        "Reentrant inventory change was overwritten");
                setField("NetworkInventorySync", "beforeReadiness", null);
                passed();
            }

            stage = "committed network grant remains successful after reconciliation or notification failure";
            recipient.clear(); recipient.storageWrites = 0; recipient.failReconcileAfterGrant = true; recipient.failNotifications = true;
            check(networkGrant(recipient.id, "described", 1).equals("SUCCESS") && recipient.count("described") == 1
                    && recipient.storageWrites == 1, "Late failure reported rejection or repeated the grant");
            recipient.failReconcileAfterGrant = false; recipient.failNotifications = false;
            recipient.clear();
            passed();

            stage = "inventory write uncertainty propagates without a definite rejection or repeated grant";
            recipient.storageWrites = 0; recipient.failWriteAfterGrant = true;
            boolean uncertain = false;
            try { networkGrant(recipient.id, "described", 1); }
            catch (InvocationTargetException expected) {
                uncertain = expected.getCause() instanceof IllegalStateException
                        && expected.getCause().getMessage().contains("write uncertainty");
            }
            check(uncertain && recipient.count("described") == 1 && recipient.storageWrites == 1,
                    "Partially applied write returned a definite result or repeated delivery");
            recipient.failWriteAfterGrant = false; recipient.clear();
            passed();
        } finally {
            field.set(null, actual);
            if (integrations) {
                setField("Nexo", "available", true);
                setField("NetworkInventorySync", "ready", true);
                setField("NetworkInventorySync", "readyForChecks", Integer.MAX_VALUE);
                setField("NetworkInventorySync", "beforeReadiness", null);
            }
            Files.deleteIfExists(file);
            plugin.getCommand("planetryitems").execute(Bukkit.getConsoleSender(), "pitems", new String[]{"reload"});
        }
        check(networkIds("network_probe_").isEmpty(), "Network completion retained a retired catalog snapshot");
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

    private void glyphTemplates() throws Exception {
        stage = "native glyph arguments/fonts, viewer, language templates and safe fallback";
        ItemStack stack = create("glyph_probe");
        String glyph = integrations ? "GLYPH[probe_heart:c]" : "[HEART]";
        check(plain(stack.getItemMeta().displayName()).equals(glyph + " Probe"), "Configured name glyph did not resolve");
        var lines = lore(stack);
        check(contains(lines, integrations ? "GLYPH[probe_heart:2..3:colorable:shadow:#80ffffff]SHIFT[-8]" : "[HEART]"),
                "Native glyph options or shift arguments were lost: " + lines);
        for (String fallback : List.of("[MISSING]", "[EMPTY]", "[BROKEN]"))
            check(contains(lines, fallback), "Missing/empty/broken glyph omitted fallback: " + lines);
        if (integrations) {
            check(glyphFont(stack.getItemMeta().displayName()), "Native font lost from display name");
            check(stack.getItemMeta().lore().stream().anyMatch(ItemsProbe::glyphFont), "Native font lost from lore");
            Class<?> type = Class.forName("com.nexomc.nexo.glyphs.GlyphTag", true,
                    Bukkit.getPluginManager().getPlugin("Nexo").getClass().getClassLoader());
            check(player.id.equals(type.getField("lastViewer").get(null)), "Nexo did not receive the actual item viewer");
        }
        check(ItemStack.deserializeBytes(stack.serializeAsBytes()).equals(stack), "Serialized glyph components changed");
        player.messages.clear();
        call(plugin, "message", player.player, "messages.glyph-probe", Map.of("value", "<glyph:unsafe><shift:99><red>literal"));
        String prefix = integrations ? "GLYPH[probe_heart]SHIFT[8]" : "[HEART]";
        check(contains(player.messages, prefix + " <glyph:unsafe><shift:99><red>literal"),
                "Language glyph failed or dynamic text expanded: " + player.messages);
        if (integrations) {
            var denied = new Fake("GlyphDenied", false);
            denied.glyphAllowed = false;
            call(plugin, "message", denied.player, "messages.glyph-probe", Map.of("value", "denied"));
            check(contains(denied.messages, "<glyph:probe_heart><shift:8> denied")
                    && !contains(denied.messages, "GLYPH[") && !contains(denied.messages, "SHIFT["),
                    "Current native resolver bypassed recipient permissions: " + denied.messages);
            call(plugin, "message", player.player, "messages.glyph-probe", Map.of("value", "allowed"));
            check(contains(player.messages, "GLYPH[probe_heart]SHIFT[8] allowed"),
                    "One recipient's denied tags leaked into another recipient's message");
        }
        passed();
    }

    private static boolean glyphFont(Component value) {
        return value.font() != null && value.font().asString().equals("nexo:probe_glyph")
                || value.children().stream().anyMatch(ItemsProbe::glyphFont);
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
        check(!nexo.hasData(DataComponentTypes.EQUIPPABLE), "Nonwearable Planetry item retained copied Nexo equipment component");
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
        try {
            Field field = Bukkit.class.getDeclaredField("server"); field.setAccessible(true);
            Server actual = Bukkit.getServer();
            Server lookup = proxy(Server.class, (object, method, args) -> {
                if (method.getName().equals("getPlayer") && player.id.equals(args[0])) return player.player;
                try { return method.invoke(actual, args); }
                catch (InvocationTargetException error) { throw error.getCause(); }
            });
            ItemStack[] before = snapshot();
            try {
                field.set(null, lookup);
                check(networkGrant(player.id, "described", 1).equals("INVENTORY_UNAVAILABLE") && Arrays.equals(before, player.items),
                        "Network API treated installed disabled Inventory Sync as absent");
            } finally { field.set(null, actual); }
        } catch (Exception error) { throw new IllegalStateException(error); }
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
    private void setDurability(ItemStack item, int remaining) {
        item.editMeta(meta -> meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "durability"),
                PersistentDataType.INTEGER, remaining));
    }
    private void nativeBar(ItemStack item, int maximum, int remaining) {
        boolean reserved = item.getType() == Material.ELYTRA || item.getType() == Material.TRIDENT;
        int nativeMax = Objects.requireNonNull(item.getData(DataComponentTypes.MAX_DAMAGE));
        int nativeSpent = Objects.requireNonNull(item.getData(DataComponentTypes.DAMAGE));
        check((reserved ? nativeMax >= maximum : nativeMax == maximum)
                && (reserved ? Math.abs(nativeSpent / (double) nativeMax - (maximum - remaining) / (double) maximum) <= 2d / nativeMax
                             : nativeSpent == maximum - remaining),
                "Native durability components differ from counter: " + item.getType() + "/" + maximum + "/" + remaining
                        + " actual=" + item.getData(DataComponentTypes.MAX_DAMAGE) + "/" + item.getData(DataComponentTypes.DAMAGE));
        if (reserved) check(nativeMax - nativeSpent >= 2, "Native last-point reserve refused a usable custom item");
        check(!item.getItemMeta().isUnbreakable() && item.getItemMeta().hasItemFlag(ItemFlag.HIDE_UNBREAKABLE),
                "Breakable native durability bar retained an unbreakable component or visible label");
        // Exercise the actual native bar implementation rather than inferring visibility from components.
        // This is server item logic, not a connected-client rendering assertion.
        try {
            Object nativeStack = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack")
                    .getMethod("asNMSCopy", ItemStack.class).invoke(null, item);
            boolean visible = (Boolean) nativeStack.getClass().getMethod("isBarVisible").invoke(nativeStack);
            int width = (Integer) nativeStack.getClass().getMethod("getBarWidth").invoke(nativeStack);
            int expectedWidth = Math.round(13f - (maximum - remaining) * 13f / maximum);
            check(visible == (remaining < maximum) && width == expectedWidth,
                    "Native durability bar visibility/width disagrees with counter: " + item.getType() + "/" + remaining
                            + " visible=" + visible + " width=" + width + " expected=" + expectedWidth);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot inspect pinned Paper native durability bar", error);
        }
    }
    private static boolean durabilityLineRed(ItemStack item) {
        return item.getItemMeta().lore().stream().filter(component -> plain(component).contains("Durability:"))
                .anyMatch(ItemsProbe::red);
    }
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
    private static boolean coloredText(Component component, int inherited, String text, int expected) {
        int color = component.color() == null ? inherited : component.color().value();
        return component instanceof net.kyori.adventure.text.TextComponent literal
                && literal.content().equals(text) && color == expected
                || component.children().stream().anyMatch(child -> coloredText(child, color, text, expected));
    }
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
    private FishHook fishHook() { UUID id = UUID.randomUUID(); return proxy(FishHook.class, (object, method, args) -> switch (method.getName()) {
        case "getUniqueId" -> id; case "getEntityId" -> id.hashCode(); case "getShooter" -> player.player;
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
        Player player; PlayerInventory inventory; InventoryView view; Inventory top, enderChest;
        InventoryType viewType = InventoryType.CRAFTING;
        GameMode gameMode = GameMode.SURVIVAL;
        ItemStack cursor; boolean admin, legacyAdmin, blocking, gliding; boolean glyphAllowed = true; int breaks, sounds;
        boolean online = true, valid = true, dead, failReconcileAfterGrant, failNotifications, failSizeOnce, failWriteAfterGrant;
        int storageWrites;
        Fake(String name, boolean admin) {
            this(UUID.randomUUID(), name, admin);
        }
        Fake(UUID id, String name, boolean admin) {
            this.id = id; this.name = name; this.admin = admin;
            inventory = proxy(PlayerInventory.class, (object, method, args) -> {
                check(Bukkit.isPrimaryThread(), "Inventory accessed off main thread");
                return switch (method.getName()) {
                    case "getSize" -> {
                        if (failSizeOnce) { failSizeOnce = false; throw new IllegalStateException("Controlled post-grant reconciliation failure"); }
                        yield items.length;
                    }
                    case "getMaxStackSize" -> 64; case "getType" -> InventoryType.PLAYER;
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
                    case "setStorageContents" -> {
                        Arrays.fill(items, 0, 36, null); System.arraycopy(args[0], 0, items, 0, ((ItemStack[]) args[0]).length);
                        storageWrites++; if (failReconcileAfterGrant) failSizeOnce = true;
                        if (failWriteAfterGrant) throw new IllegalStateException("Controlled inventory write uncertainty");
                        yield null;
                    }
                    case "iterator" -> Arrays.asList(items.clone()).listIterator(args == null || args.length == 0 ? 0 : (int) args[0]);
                    case "getLocation" -> Bukkit.getWorlds().getFirst().getSpawnLocation();
                    case "hashCode" -> System.identityHashCode(object); case "equals" -> object == args[0];
                    default -> throw new UnsupportedOperationException("Unimplemented probe inventory API: " + method);
                };
            });
            player = proxy(Player.class, (object, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id; case "getName", "getDisplayName" -> name; case "name", "displayName" -> Component.text(name);
                case "getServer" -> Bukkit.getServer(); case "getPlayer" -> object;
                case "isOnline", "isConnected" -> online; case "isValid" -> valid; case "isDead" -> dead;
                case "hasPlayedBefore" -> true; case "isSneaking", "isOp" -> false;
                case "hasPermission", "isPermissionSet" -> args[0].equals("nexo.glyph.probe") || args[0].equals("nexo.shift")
                        ? glyphAllowed : permission(args[0]);
                case "get" -> args[0].equals(net.kyori.adventure.identity.Identity.UUID) ? Optional.of(id) : Optional.empty();
                case "pointers" -> net.kyori.adventure.pointer.Pointers.builder()
                        .withStatic(net.kyori.adventure.identity.Identity.UUID, id).build();
                case "getPersistentDataContainer" -> data;
                case "isBlocking" -> blocking;
                case "isGliding" -> gliding;
                case "setGliding" -> { gliding = (boolean) args[0]; yield null; }
                case "getGameMode" -> gameMode; case "getInventory" -> inventory; case "getOpenInventory" -> view;
                case "getEnderChest" -> enderChest;
                case "getWorld" -> Bukkit.getWorlds().getFirst(); case "getLocation" -> Bukkit.getWorlds().getFirst().getSpawnLocation();
                case "getItemOnCursor" -> cursor == null ? ItemStack.of(Material.AIR) : cursor;
                case "setItemOnCursor" -> { cursor = (ItemStack) args[0]; yield null; }
                case "getLevel" -> 999; // Requirements must read Core, not Minecraft's display level.
                case "broadcastSlotBreak" -> { breaks++; yield null; }
                case "playSound" -> { sounds++; yield null; }
                case "sendMessage" -> {
                    if (failNotifications) throw new IllegalStateException("Controlled post-grant notification failure");
                    for (Object value : args) { if (value instanceof Component component) messages.add(plain(component)); else if (value instanceof String text) messages.add(text); else if (value instanceof String[] texts) messages.addAll(List.of(texts)); } yield null;
                }
                case "updateInventory", "closeInventory", "sendExperienceChange" -> null;
                case "getEffectivePermissions" -> Set.of();
                case "hashCode" -> System.identityHashCode(object); case "equals" -> object == args[0]; case "toString" -> name;
                default -> zero(method.getReturnType());
            });
            top = Bukkit.createInventory(null, 9);
            enderChest = Bukkit.createInventory(null, InventoryType.ENDER_CHEST);
            view = proxy(InventoryView.class, (object, method, args) -> switch (method.getName()) {
                case "getPlayer" -> player; case "getTopInventory" -> top; case "getBottomInventory" -> inventory;
                case "getType" -> viewType; case "countSlots" -> viewType == InventoryType.CRAFTING ? 46 : top.getSize() + 36;
                case "convertSlot" -> convert((int) args[0]); case "getSlotType" -> slotType((int) args[0]);
                case "getInventory" -> (int) args[0] < 0 ? null : (int) args[0] < topSlots() ? top : inventory;
                case "getItem" -> (int) args[0] < 0 ? null : (int) args[0] < topSlots() ? top.getItem((int) args[0]) : items[convert((int) args[0])];
                case "setItem" -> { int raw = (int) args[0]; if (raw < topSlots()) top.setItem(raw, (ItemStack) args[1]); else items[convert(raw)] = (ItemStack) args[1]; yield null; }
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
        int topSlots() { return viewType == InventoryType.CRAFTING ? 5 : top.getSize(); }
        int convert(int raw) { if (viewType != InventoryType.CRAFTING) { if (raw < top.getSize()) return raw; int bottom = raw - top.getSize(); return bottom < 27 ? bottom + 9 : bottom - 27; } if (raw < 5) return raw; if (raw < 9) return 44 - raw; if (raw < 36) return raw; if (raw < 45) return raw - 36; return 40; }
        InventoryType.SlotType slotType(int raw) { if (viewType != InventoryType.CRAFTING) return raw < 0 ? InventoryType.SlotType.OUTSIDE : raw < top.getSize() + 27 ? InventoryType.SlotType.CONTAINER : InventoryType.SlotType.QUICKBAR; return raw < 0 ? InventoryType.SlotType.OUTSIDE : raw == 0 ? InventoryType.SlotType.RESULT : raw < 5 ? InventoryType.SlotType.CRAFTING : raw < 9 ? InventoryType.SlotType.ARMOR : raw < 36 ? InventoryType.SlotType.CONTAINER : InventoryType.SlotType.QUICKBAR; }
        ItemStack hand(int slot) { return items[slot] == null ? ItemStack.of(Material.AIR) : items[slot]; }
        int firstEmpty() { for (int slot = 0; slot < 36; slot++) if (empty(items[slot])) return slot; return -1; }
        Map<Integer, ItemStack> add(ItemStack[] incoming) { Map<Integer, ItemStack> remaining = new HashMap<>(); for (int n = 0; n < incoming.length; n++) { int slot = firstEmpty(); if (slot < 0) remaining.put(n, incoming[n].clone()); else items[slot] = incoming[n].clone(); } return remaining; }
        void clear() { Arrays.fill(items, null); cursor = null; blocking = false; gliding = false; }
        int inventoryCount() { return Arrays.stream(items).filter(item -> !empty(item)).mapToInt(ItemStack::getAmount).sum(); }
        int count(String id) { return Arrays.stream(items).filter(item -> !empty(item)).filter(item -> id.equals(call(ItemsProbe.this.items, "id", item))).mapToInt(ItemStack::getAmount).sum(); }
    }
}
