package dev.flint.planetry.items;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.event.player.PlayerInsertLecternBookEvent;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import io.papermc.paper.event.entity.EntityLoadCrossbowEvent;
import java.util.*;
import java.util.function.LongSupplier;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;

/** Main-thread placement and outgoing-inventory policy. Never moves or deletes player items. */
public final class ItemPolicy implements Listener {
    private final PlanetryItems plugin;
    private final LongSupplier clock;
    private final Map<UUID, Bypass> bypasses = new HashMap<>();
    private static final long CONFIRM_NANOS = 2_000_000_000L;
    private static final class Bypass {
        final Player player;
        ItemStack pending;
        ItemStack approved;
        int approvedTick;
        long warned;
        Bypass(Player player) { this.player = player; }
    }

    public ItemPolicy(PlanetryItems plugin) { this(plugin, System::nanoTime); }
    ItemPolicy(PlanetryItems plugin, LongSupplier clock) { this.plugin = plugin; this.clock = clock; }

    public boolean toggleBypass(Player player) {
        if (!player.hasPermission("planetry.admin.items")) return false;
        Bypass current = bypasses.get(player.getUniqueId());
        if (current != null && current.player == player) { bypasses.remove(player.getUniqueId()); return false; }
        bypasses.put(player.getUniqueId(), new Bypass(player));
        return true;
    }

    public void clear() { bypasses.clear(); }
    public void forget(Player player) {
        Bypass state = bypasses.get(player.getUniqueId());
        if (state != null && state.player == player) bypasses.remove(player.getUniqueId());
    }

    /** Includes portable containers, so wrapping an item cannot defeat the transfer policy. */
    public boolean soulbound(ItemStack stack) { return soulbound(stack, 0); }
    private boolean soulbound(ItemStack stack, int depth) {
        if (stack == null || stack.isEmpty()) return false;
        if (plugin.items().soulbound(stack)) return true;
        // Native serialized items are depth-limited too. Refuse a pathological carrier safely.
        if (depth >= 32) return true;
        var bundle = stack.getData(DataComponentTypes.BUNDLE_CONTENTS);
        if (bundle != null) for (ItemStack child : bundle.contents()) if (soulbound(child, depth + 1)) return true;
        var container = stack.getData(DataComponentTypes.CONTAINER);
        if (container != null) for (ItemStack child : container.contents()) if (soulbound(child, depth + 1)) return true;
        var charged = stack.getData(DataComponentTypes.CHARGED_PROJECTILES);
        if (charged != null) for (ItemStack child : charged.projectiles()) if (soulbound(child, depth + 1)) return true;
        return false;
    }

    public boolean placementBlocked(ItemStack stack) { return !plugin.items().canPlace(stack) || soulbound(stack); }

    public boolean placementInteraction(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item != null && (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            var charged = item.getData(DataComponentTypes.CHARGED_PROJECTILES);
            if (charged != null && charged.projectiles().stream().anyMatch(this::soulbound)) {
                // Crossbows clear their stored ammunition before the later shooting event.
                event.setUseItemInHand(Event.Result.DENY);
                return true;
            }
        }
        if (item != null && (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)
                && item.getData(DataComponentTypes.BUNDLE_CONTENTS) != null && soulbound(item)) {
            // Native bundle use removes contents before attempting world drops. Cancelling
            // those later drops cannot safely restore them to a full player inventory.
            event.setUseItemInHand(Event.Result.DENY);
            return true;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || item == null) return false;
        if (soulbound(item) && event.getClickedBlock() != null && Set.of(Material.JUKEBOX,
                Material.CHISELED_BOOKSHELF, Material.DECORATED_POT, Material.CAMPFIRE,
                Material.SOUL_CAMPFIRE, Material.FLOWER_POT).contains(event.getClickedBlock().getType())) {
            event.setUseInteractedBlock(Event.Result.DENY); event.setUseItemInHand(Event.Result.DENY);
            return true;
        }
        if (!placementBlocked(item)) return false;
        var definition = plugin.items().definition(item);
        // A block-shaped wearable can equip through the same click. The dedicated
        // equipment event checks requirements; BlockPlaceEvent still vetoes world placement.
        if (item.getType().isBlock() && definition != null && definition.equipSlot() != null) return true;
        String name = item.getType().name();
        if (!item.getType().isBlock() && !name.endsWith("_BOAT") && !name.endsWith("_RAFT")
                && !name.endsWith("MINECART") && !name.endsWith("_SPAWN_EGG")
                && !Set.of("ARMOR_STAND", "END_CRYSTAL", "ITEM_FRAME", "GLOW_ITEM_FRAME", "PAINTING",
                        "WATER_BUCKET", "LAVA_BUCKET", "POWDER_SNOW_BUCKET", "COD_BUCKET", "SALMON_BUCKET",
                        "TROPICAL_FISH_BUCKET", "PUFFERFISH_BUCKET", "AXOLOTL_BUCKET", "TADPOLE_BUCKET").contains(name)
                && !(event.getClickedBlock() != null && event.getClickedBlock().getType() == Material.LECTERN
                        && (item.getType() == Material.WRITTEN_BOOK || item.getType() == Material.WRITABLE_BOOK))) return false;
        // Preserve the clicked block's use (opening a chest, for example), while refusing the item.
        event.setUseItemInHand(Event.Result.DENY);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void place(BlockPlaceEvent event) { if (placementBlocked(event.getItemInHand())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void entityPlace(EntityPlaceEvent event) {
        Player player = event.getPlayer();
        if (player != null && placementBlocked(player.getInventory().getItem(event.getHand()))) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void hanging(HangingPlaceEvent event) {
        ItemStack item = event.getItemStack();
        if (item == null && event.getPlayer() != null) item = event.getPlayer().getInventory().getItem(event.getHand());
        if (placementBlocked(item)) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void bucket(PlayerBucketEmptyEvent event) {
        if (placementBlocked(event.getPlayer().getInventory().getItem(event.getHand()))) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void dispense(BlockDispenseEvent event) { if (placementBlocked(event.getItem())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void hopper(InventoryMoveItemEvent event) { if (soulbound(event.getItem())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void loadCrossbow(EntityLoadCrossbowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        // Native selection prefers either hand (also fireworks), then the first storage arrow.
        for (ItemStack held : List.of(player.getInventory().getItemInOffHand(), player.getInventory().getItemInMainHand())) {
            if (ammunition(held, true)) { if (soulbound(held)) event.setCancelled(true); return; }
        }
        for (ItemStack stored : player.getInventory().getStorageContents()) {
            if (ammunition(stored, false)) { if (soulbound(stored)) event.setCancelled(true); return; }
        }
    }

    private static boolean ammunition(ItemStack item, boolean held) {
        return item != null && !item.isEmpty() && (item.getType() == Material.ARROW || item.getType() == Material.TIPPED_ARROW
                || item.getType() == Material.SPECTRAL_ARROW || held && item.getType() == Material.FIREWORK_ROCKET);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void lectern(PlayerInsertLecternBookEvent event) {
        if (placementBlocked(event.getBook()) || soulbound(event.getBook())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void frame(PlayerItemFrameChangeEvent event) {
        if (event.getAction() == PlayerItemFrameChangeEvent.ItemFrameChangeAction.PLACE
                && (placementBlocked(event.getItemStack()) || soulbound(event.getItemStack()))) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void armorStand(PlayerArmorStandManipulateEvent event) {
        if (soulbound(event.getPlayerItem())) event.setCancelled(true);
    }

    public boolean entityDeposit(PlayerInteractEntityEvent event) {
        ItemStack item = event.getPlayer().getInventory().getItem(event.getHand());
        if (event.getRightClicked() instanceof org.bukkit.entity.ItemFrame frame
                && frame.getItem().isEmpty() && placementBlocked(item)) return true;
        return soulbound(item)
                && (event.getRightClicked() instanceof InventoryHolder
                        || event.getRightClicked() instanceof org.bukkit.entity.Allay
                        || event.getRightClicked() instanceof org.bukkit.entity.ItemFrame
                        || event.getRightClicked() instanceof org.bukkit.entity.ArmorStand);
    }

    private boolean owned(Player player, Inventory inventory) {
        return inventory != null && (inventory.equals(player.getInventory()) || inventory.equals(player.getEnderChest()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void click(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory destination = event.getClickedInventory();
        boolean external = destination != null && !owned(player, destination);
        boolean cursorBound = soulbound(event.getCursor());
        boolean currentBound = soulbound(event.getCurrentItem());
        ItemStack dropped = switch (event.getAction()) {
            case DROP_ALL_CURSOR -> event.getCursor();
            case DROP_ONE_CURSOR -> event.getCursor().asOne();
            case DROP_ALL_SLOT -> event.getCurrentItem();
            case DROP_ONE_SLOT -> event.getCurrentItem() == null ? null : event.getCurrentItem().asOne();
            default -> event instanceof InventoryCreativeEvent && event.getRawSlot() < 0 ? event.getCursor() : null;
        };
        if (soulbound(dropped)) {
            if (!approveDrop(player, dropped)) { event.setCancelled(true); return; }
            Bypass state = bypasses.get(player.getUniqueId());
            state.approved = dropped.clone(); state.approvedTick = org.bukkit.Bukkit.getCurrentTick();
        }
        boolean cancel = switch (event.getAction()) {
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR, PLACE_ALL_INTO_BUNDLE,
                    PLACE_SOME_INTO_BUNDLE, PLACE_FROM_BUNDLE -> external && cursorBound;
            case MOVE_TO_OTHER_INVENTORY -> currentBound && owned(player, destination)
                    && !owned(player, event.getView().getTopInventory())
                    // In the personal view shift-click rearranges storage/equipment, not crafting inputs.
                    && event.getView().getType() != InventoryType.CRAFTING;
            case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> external && soulbound(
                    event.getClick() == ClickType.SWAP_OFFHAND ? player.getInventory().getItemInOffHand()
                            : event.getHotbarButton() < 0 ? null : player.getInventory().getItem(event.getHotbarButton()));
            case UNKNOWN -> external && (cursorBound || currentBound);
            default -> false;
        };
        // Creative packets supply replacement stacks rather than a normal place action.
        if (event instanceof InventoryCreativeEvent && external && cursorBound) cancel = true;
        if (cancel) event.setCancelled(true);
        // A successful pre-mutation confirmation grants the native drop event one same-tick permit.
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void drag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !soulbound(event.getOldCursor())) return;
        for (int raw : event.getRawSlots()) {
            if (!owned(player, event.getView().getInventory(raw))) { event.setCancelled(true); return; }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void drop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        ItemStack stack = event.getItemDrop().getItemStack();
        Bypass state = validBypass(player);
        if (state != null && org.bukkit.Bukkit.getCurrentTick() == state.approvedTick && stack.equals(state.approved)) {
            state.approved = null;
            return;
        }
        if (state != null) state.approved = null;
        if (!approveDrop(player, stack)) event.setCancelled(true);
    }

    private Bypass validBypass(Player player) {
        Bypass state = bypasses.get(player.getUniqueId());
        if (state != null && (state.player != player || !player.hasPermission("planetry.admin.items"))) {
            bypasses.remove(player.getUniqueId()); state = null;
        }
        return state;
    }

    private boolean approveDrop(Player player, ItemStack stack) {
        Bypass state = validBypass(player);
        if (!soulbound(stack)) { if (state != null) state.pending = null; return true; }
        if (state != null && plugin.hooks().inventoryReady(player)) {
            long now = clock.getAsLong();
            if (stack.equals(state.pending) && now - state.warned >= 0 && now - state.warned <= CONFIRM_NANOS) {
                state.pending = null;
                return true;
            }
            state.pending = stack.clone(); state.warned = now;
            plugin.message(player, "messages.soulbound-drop-warning", Map.of());
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void death(PlayerDeathEvent event) {
        plugin.recovery().captureCursor(event.getEntity());
        forget(event.getEntity());
        if (event.getKeepInventory()) return;
        for (var iterator = event.getDrops().iterator(); iterator.hasNext();) {
            ItemStack item = iterator.next();
            if (!soulbound(item)) continue;
            iterator.remove();
            event.getItemsToKeep().add(item);
        }
    }
    @EventHandler public void join(PlayerJoinEvent event) { forget(event.getPlayer()); }
    @EventHandler public void quit(PlayerQuitEvent event) { plugin.recovery().captureCursor(event.getPlayer()); forget(event.getPlayer()); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void close(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        plugin.recovery().captureCursor(player);
        Bypass state = bypasses.get(player.getUniqueId());
        if (state != null && state.player == player) { state.pending = null; state.approved = null; }
        // Native close/death processing must finish before touching storage again.
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.getOpenInventory() != event.getView()) plugin.recovery().recover(player);
        });
    }
}
