package dev.flint.planetry.items;

import com.destroystokyo.paper.event.player.PlayerElytraBoostEvent;
import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import io.papermc.paper.event.player.PlayerSwapWithEquipmentSlotEvent;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Enforces use before effects; reconciles externally restored inventories on the main thread. */
public final class ItemGuard implements Listener {
    private final PlanetryItems plugin;
    private final Map<UUID, Long> lastMessage = new HashMap<>();
    private final Map<UUID, EntityUse> entityUses = new HashMap<>();
    private final Set<UUID> queued = new HashSet<>();
    private final Set<UUID> pendingMigration = new HashSet<>();
    private final Map<UUID, Long> nextMigration = new HashMap<>();
    private final Set<String> migrationWarnings = new HashSet<>();
    private record Session(Player player) { }
    private final Map<UUID, Session> sessions = new HashMap<>();
    private static final List<EquipmentSlot> SLOTS = List.of(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);
    private static final Set<Material> PROJECTILE_ITEMS = Set.of(Material.BOW, Material.CROSSBOW, Material.TRIDENT,
            Material.SNOWBALL, Material.EGG, Material.ENDER_PEARL, Material.EXPERIENCE_BOTTLE,
            Material.SPLASH_POTION, Material.LINGERING_POTION, Material.FIREWORK_ROCKET, Material.WIND_CHARGE);
    private record EntityUse(int tick, UUID entity, EquipmentSlot hand, String item, boolean precise) { }

    public ItemGuard(PlanetryItems plugin) { this.plugin = plugin; }
    private ItemService items() { return plugin.items(); }

    public boolean deny(Player player, ItemStack stack) {
        if (items().usable(player, stack)) return false;
        long now = System.currentTimeMillis();
        if (now - lastMessage.getOrDefault(player.getUniqueId(), 0L) > 1000) {
            var def = items().definition(stack);
            Set<String> unmet = def == null ? Set.of() : items().unmet(player, def);
            if (!unmet.isEmpty()) {
                plugin.message(player, "messages.requirements-not-met", Map.of("requirements",
                        plugin.language().requirementSummary(def, unmet, plugin.hooks().glyphs(player), player), "item", def.id()));
            } else plugin.message(player, !plugin.hooks().inventoryReady(player)
                    ? "messages.integration-unavailable" : "messages.disabled-item", Map.of());
            lastMessage.put(player.getUniqueId(), now);
        }
        if (items().definition(stack) != null && plugin.hooks().inventoryReady(player)) items().refresh(stack, player);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void interact(PlayerInteractEvent e) {
        if (plugin.policy().placementInteraction(e)) return;
        if (deny(e.getPlayer(), e.getItem())) { e.setCancelled(true); return; }
        if (e.getItem() != null && items().definition(e.getItem()) != null) items().refresh(e.getItem(), e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void interactUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        // Air interactions deny the nonexistent block by default, so isCancelled alone is not an item-use veto.
        if (e.useItemInHand() == Event.Result.DENY) return;
        var def = items().definition(e.getItem());
        if (def == null || !items().usable(e.getPlayer(), e.getItem())) return;
        // Food and ranged weapons pay on completion, equipment pays when it protects its wearer.
        if (def.equipSlot() != null || def.type().equals("FOOD") || e.getItem().getType().isEdible()
                || PROJECTILE_ITEMS.contains(e.getItem().getType()) || e.getItem().getType() == Material.SHIELD) return;
        items().damage(e.getPlayer(), e.getItem(), hand(e.getHand()));
        later(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void preAttack(PrePlayerAttackEntityEvent e) {
        if (deny(e.getPlayer(), e.getPlayer().getInventory().getItemInMainHand())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void attack(EntityDamageByEntityEvent e) {
        if ((e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK || e.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK)
                && e.getDamager() instanceof Player p && deny(p, p.getInventory().getItemInMainHand())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void attackUse(EntityDamageByEntityEvent e) {
        if (e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && e.getDamager() instanceof Player p && e.getFinalDamage() > 0) {
            items().damage(p, p.getInventory().getItemInMainHand(), EquipmentSlot.HAND); later(p);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent e) {
        if (deny(e.getPlayer(), e.getPlayer().getInventory().getItemInMainHand())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void blockUse(BlockBreakEvent e) {
        items().damage(e.getPlayer(), e.getPlayer().getInventory().getItemInMainHand(), EquipmentSlot.HAND); later(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void consume(PlayerItemConsumeEvent e) { if (deny(e.getPlayer(), e.getItem())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void consumeUse(PlayerItemConsumeEvent e) {
        ItemStack consumed = e.getItem();
        var def = items().definition(consumed);
        if (def == null || !items().usable(e.getPlayer(), consumed)) return;
        // Vanilla applies the food/potion effects, then installs this replacement. Never change its input effects.
        // max-durability 0 remains an ordinary one-shot consumable; unbreakable food is reusable.
        if (def.unbreakable() || def.maxDurability() > 0) {
            boolean broken = items().damage(e.getPlayer(), consumed, e.getHand());
            e.setReplacement(broken ? new ItemStack(Material.AIR) : consumed);
        }
        later(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void launch(PlayerLaunchProjectileEvent e) {
        if (e.getProjectile() instanceof Trident && plugin.policy().soulbound(e.getItemStack())) { e.setCancelled(true); return; }
        // Recheck at release, since requirements can change while a trident is being charged.
        if (deny(e.getPlayer(), e.getItemStack())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void launchUse(PlayerLaunchProjectileEvent e) {
        ItemStack source = e.getItemStack();
        var def = items().definition(source);
        if (def == null) return;
        if (!items().usable(e.getPlayer(), source)) { e.setCancelled(true); return; }
        EquipmentSlot slot = sourceHand(e.getPlayer(), source);
        boolean broken = items().damage(e.getPlayer(), source, slot);
        if (e.getProjectile() instanceof Trident trident) {
            if (broken || source.getAmount() == 0) {
                e.setCancelled(true);
                e.setShouldConsume(false);
            } else {
                // A trident transfers ownership to its recoverable projectile, so preserve vanilla consumption.
                ItemStack carried = source.asOne();
                int expected = items().currentDurability(carried);
                trident.setItem(carried);
                // Paper snapshots the thrown item before this event and reinstalls that older snapshot after it.
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!e.isCancelled() && trident.isValid() && Objects.equals(items().id(trident.getItem()), def.id())
                            && items().currentDurability(trident.getItem()) > expected) trident.setItem(carried);
                });
            }
        } else if (def.unbreakable() || def.maxDurability() > 0) {
            // Reusable snowballs/potions/etc spend custom durability instead of consuming the source stack.
            e.setShouldConsume(false);
        }
        later(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void riptide(PlayerRiptideEvent e) {
        if (deny(e.getPlayer(), e.getItem())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void riptideUse(PlayerRiptideEvent e) {
        if (items().usable(e.getPlayer(), e.getItem())) {
            items().damage(e.getPlayer(), e.getItem(), sourceHand(e.getPlayer(), e.getItem()));
            later(e.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void boost(PlayerElytraBoostEvent e) {
        if (deny(e.getPlayer(), e.getItemStack())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void boostUse(PlayerElytraBoostEvent e) {
        var def = items().definition(e.getItemStack());
        if (def == null || !items().usable(e.getPlayer(), e.getItemStack())) return;
        items().damage(e.getPlayer(), e.getItemStack(), e.getHand());
        if (def.unbreakable() || def.maxDurability() > 0) e.setShouldConsume(false);
        later(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void shoot(EntityShootBowEvent e) {
        if (e.getEntity() instanceof Player p && (plugin.policy().soulbound(e.getConsumable())
                || deny(p, e.getBow()) || deny(p, e.getConsumable()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void shootUse(EntityShootBowEvent e) {
        if (e.getEntity() instanceof Player p) { items().damage(p, e.getBow(), hand(e.getHand())); later(p); }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void entityInteract(PlayerInteractEntityEvent e) {
        if (plugin.policy().entityDeposit(e) || deny(e.getPlayer(), e.getPlayer().getInventory().getItem(e.getHand()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void entityInteractUse(PlayerInteractEntityEvent e) {
        ItemStack stack = e.getPlayer().getInventory().getItem(e.getHand());
        var def = items().definition(stack);
        if (def == null || !items().usable(e.getPlayer(), stack)) return;
        // InteractAt and ordinary interaction can describe the same click, and share a Bukkit handler list.
        EntityUse current = new EntityUse(Bukkit.getCurrentTick(), e.getRightClicked().getUniqueId(), e.getHand(),
                def.id(), e instanceof PlayerInteractAtEntityEvent);
        EntityUse previous = entityUses.put(e.getPlayer().getUniqueId(), current);
        if (previous != null && previous.tick() == current.tick() && previous.entity().equals(current.entity())
                && previous.hand() == current.hand() && previous.item().equals(current.item())
                && previous.precise() != current.precise()) return;
        items().damage(e.getPlayer(), stack, e.getHand());
        later(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void place(BlockPlaceEvent e) {
        if (plugin.policy().placementBlocked(e.getItemInHand()) || deny(e.getPlayer(), e.getItemInHand())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void damageItem(PlayerItemDamageEvent e) {
        // All managed items use the custom one-point path, including vanilla tools.
        if (items().id(e.getItem()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void glide(EntityToggleGlideEvent e) {
        if (e.isGliding() && e.getEntity() instanceof Player p
                && deny(p, p.getInventory().getChestplate())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void resurrect(EntityResurrectEvent e) {
        if (e.getEntity() instanceof Player p && e.getHand() != null
                && deny(p, p.getInventory().getItem(e.getHand()))) e.setCancelled(true);
    }

    /** Once per second of active flight, independently of the configurable lore refresh interval. */
    public void flightTick(Player p) {
        if (!p.isGliding()) return;
        ItemStack chest = p.getInventory().getChestplate();
        if (items().id(chest) == null) return;
        if (deny(p, chest)) { p.setGliding(false); return; }
        if (items().damage(p, chest, EquipmentSlot.CHEST)) p.setGliding(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void blockUnavailableShield(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !p.isBlocking()
                || !e.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)
                || e.getDamage(EntityDamageEvent.DamageModifier.BLOCKING) >= 0) return;
        EquipmentSlot hand = p.getActiveItemHand();
        if (hand != EquipmentSlot.HAND && hand != EquipmentSlot.OFF_HAND) hand = EquipmentSlot.OFF_HAND;
        ItemStack shield = p.getInventory().getItem(hand);
        if (shield == null || shield.getType() != Material.SHIELD) shield = p.getInventory().getItem(
                hand == EquipmentSlot.HAND ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND);
        if (shield != null && shield.getType() == Material.SHIELD && deny(p, shield)) {
            p.clearActiveItem();
            e.setDamage(EntityDamageEvent.DamageModifier.BLOCKING, 0);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void hurt(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !plugin.hooks().inventoryReady(p)) return;
        // A fully blocked hit has zero final damage, but still used the active shield.
        boolean blocked = p.isBlocking() && e.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)
                && e.getDamage(EntityDamageEvent.DamageModifier.BLOCKING) < 0;
        if (blocked) {
            EquipmentSlot slot = p.getActiveItemHand();
            if (slot != EquipmentSlot.HAND && slot != EquipmentSlot.OFF_HAND) slot = EquipmentSlot.OFF_HAND;
            ItemStack shield = p.getInventory().getItem(slot);
            if (shield == null || shield.getType() != Material.SHIELD) {
                slot = slot == EquipmentSlot.HAND ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND;
                shield = p.getInventory().getItem(slot);
            }
            if (shield != null && shield.getType() == Material.SHIELD && items().usable(p, shield))
                items().damage(p, shield, slot);
        }
        if (e.getFinalDamage() > 0) {
            for (EquipmentSlot slot : SLOTS) {
                if (slot == EquipmentSlot.HAND || slot == EquipmentSlot.OFF_HAND) continue;
                ItemStack stack = p.getInventory().getItem(slot);
                if (items().usable(p, stack)) items().damage(p, stack, slot);
            }
        }
        if (blocked || e.getFinalDamage() > 0) later(p);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void equipmentSwap(PlayerSwapWithEquipmentSlotEvent e) {
        if (e.getSlot() != EquipmentSlot.OFF_HAND && deny(e.getPlayer(), e.getItemInHand())) e.setCancelled(true);
        else later(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void held(PlayerItemHeldEvent e) {
        refreshHeld(e.getPlayer(), e.getPlayer().getInventory().getItem(e.getNewSlot()));
        later(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void swap(PlayerSwapHandItemsEvent e) {
        refreshHeld(e.getPlayer(), e.getMainHandItem());
        refreshHeld(e.getPlayer(), e.getOffHandItem());
        later(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void dispense(BlockDispenseArmorEvent e) {
        if (e.getTargetEntity() instanceof Player p && deny(p, e.getItem())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void click(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack incoming = e.getCursor();
        if (e.getClick() == ClickType.NUMBER_KEY && e.getHotbarButton() >= 0) incoming = p.getInventory().getItem(e.getHotbarButton());
        if (e.getClick() == ClickType.SWAP_OFFHAND) incoming = p.getInventory().getItemInOffHand();
        boolean equipmentDestination = e.getSlotType() == InventoryType.SlotType.ARMOR;
        var shifted = items().definition(e.getCurrentItem());
        if ((equipmentDestination && deny(p, incoming)) ||
                (e.isShiftClick() && e.getClickedInventory() == p.getInventory() && shifted != null
                        && shifted.equipSlot() != null && deny(p, e.getCurrentItem()))) {
            e.setCancelled(true); return;
        }
        // Block vanilla transformations that could destroy custom identity or bypass durability.
        if (e.getSlotType() == InventoryType.SlotType.RESULT && workstation(e.getInventory().getType())) {
            for (ItemStack input : e.getInventory().getContents()) if (items().id(input) != null) {
                e.setCancelled(true); plugin.message(p, "messages.workstation-blocked", Map.of()); return;
            }
        }
        if (plugin.hooks().inventoryReady(p)) {
            if (items().definition(incoming) != null) items().refresh(incoming, p);
            if (items().definition(e.getCurrentItem()) != null) items().refresh(e.getCurrentItem(), p);
        }
        later(p);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void drag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        for (int raw : e.getRawSlots()) {
            int slot = e.getView().convertSlot(raw);
            if (e.getView().getSlotType(raw) == InventoryType.SlotType.ARMOR) {
                if (deny(p, e.getOldCursor())) { e.setCancelled(true); return; }
            }
        }
        ItemStack oldCursor = e.getOldCursor();
        var attributes = oldCursor.getData(io.papermc.paper.datacomponent.DataComponentTypes.ATTRIBUTE_MODIFIERS);
        if (items().id(oldCursor) != null && !items().usable(p, oldCursor)
                && attributes != null && !attributes.modifiers().isEmpty()) {
            // Paper installs private pre-event copies, not getNewItems(). Refuse this stale
            // transfer, repair the restored cursor, then an ordinary retry can hold the item.
            e.setCancelled(true);
            Session session = sessions.computeIfAbsent(p.getUniqueId(), id -> new Session(p));
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (sessions.get(p.getUniqueId()) != session || session.player() != p
                        || !p.isOnline() || !plugin.hooks().inventoryReady(p)) return;
                ItemStack current = p.getItemOnCursor();
                if (!oldCursor.equals(current)) return;
                refreshHeld(p, current); p.setItemOnCursor(current);
            });
            return;
        }
        later(p);
    }

    private void refreshHeld(Player player, ItemStack stack) {
        if (plugin.hooks().inventoryReady(player) && items().definition(stack) != null) items().refresh(stack, player);
    }

    public void beginSession(Player player) {
        sessions.put(player.getUniqueId(), new Session(player)); queued.remove(player.getUniqueId());
        pendingMigration.add(player.getUniqueId()); nextMigration.remove(player.getUniqueId()); later(player);
    }
    @EventHandler(priority = EventPriority.MONITOR) public void join(PlayerJoinEvent e) { beginSession(e.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR) public void changed(PlayerInventorySlotChangeEvent e) { later(e.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR) public void respawn(PlayerRespawnEvent e) { later(e.getPlayer()); }
    @EventHandler public void quit(PlayerQuitEvent e) {
        var current = sessions.get(e.getPlayer().getUniqueId());
        if (current != null && current.player() != e.getPlayer()) return;
        sessions.remove(e.getPlayer().getUniqueId());
        plugin.hooks().clearBonuses(e.getPlayer()); lastMessage.remove(e.getPlayer().getUniqueId()); queued.remove(e.getPlayer().getUniqueId());
        entityUses.remove(e.getPlayer().getUniqueId()); pendingMigration.remove(e.getPlayer().getUniqueId()); nextMigration.remove(e.getPlayer().getUniqueId());
    }

    public void later(Player p) {
        Session session = sessions.computeIfAbsent(p.getUniqueId(), id -> new Session(p));
        if (session.player() != p || !queued.add(p.getUniqueId())) return;
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() {
                if (sessions.get(p.getUniqueId()) != session) return;
                if (!p.isOnline()) { queued.remove(p.getUniqueId()); return; }
                if (pendingMigration.contains(p.getUniqueId()) && !plugin.hooks().inventoryReady(p)) {
                    Bukkit.getScheduler().runTaskLater(plugin, this, 20); return;
                }
                queued.remove(p.getUniqueId());
                reconcile(p);
                if (pendingMigration.contains(p.getUniqueId())) {
                    queued.add(p.getUniqueId()); Bukkit.getScheduler().runTaskLater(plugin, this, 20);
                }
            }
        });
    }

    public void reconcile(Player p) {
        Session session = sessions.get(p.getUniqueId());
        if (session != null && session.player() != p) return;
        if (!plugin.hooks().inventoryReady(p)) { plugin.hooks().clearBonuses(p); return; }
        plugin.recovery().recover(p);
        if (pendingMigration.contains(p.getUniqueId())
                && System.currentTimeMillis() >= nextMigration.getOrDefault(p.getUniqueId(), 0L)) {
            boolean firstAttempt = !nextMigration.containsKey(p.getUniqueId());
            var result = migrate(p);
            if (result.failed() > 0) {
                nextMigration.put(p.getUniqueId(), System.currentTimeMillis() + 5000);
                if (firstAttempt) plugin.message(p, "messages.migrate-partial", Map.of("updated", Integer.toString(result.updated()),
                        "removed", Integer.toString(result.removed()), "failed", Integer.toString(result.failed())));
            }
        }
        reconcileReady(p);
    }

    private void reconcileReady(Player p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack original = inv.getItem(i);
            if (items().id(original) == null) continue;
            ItemStack refreshed = original.clone();
            if (items().definition(refreshed) == null) {
                inv.setItem(i, null);
                removed(p, original);
                continue;
            } else items().refresh(refreshed, p);
            if (!refreshed.equals(original)) inv.setItem(i, refreshed);
        }
        ItemStack cursor = p.getItemOnCursor();
        if (items().id(cursor) != null) {
            if (items().definition(cursor) == null) { p.setItemOnCursor(null); removed(p, cursor); }
            else {
                ItemStack refreshed = cursor.clone(); items().refresh(refreshed, p);
                if (!refreshed.equals(cursor)) p.setItemOnCursor(refreshed);
            }
        }
        Map<String, Double> totals = new LinkedHashMap<>();
        for (var slot : SLOTS) {
            ItemStack stack = inv.getItem(slot);
            var def = items().definition(stack);
            boolean hand = slot == EquipmentSlot.HAND || slot == EquipmentSlot.OFF_HAND;
            if (items().id(stack) != null && !items().usable(p, stack)) {
                if (!hand) {
                    // Retain unusable soulbound equipment when storage is full. Refresh has
                    // already removed native bonuses; this branch also excludes AuraSkills bonuses.
                    if (plugin.policy().soulbound(stack) && inv.firstEmpty() < 0) continue;
                    inv.setItem(slot, null);
                    for (var leftover : inv.addItem(stack).values()) p.getWorld().dropItemNaturally(p.getLocation(), leftover);
                    deny(p, stack);
                }
                continue;
            }
            if (def == null || (def.equipSlot() == null ? !hand : !slot.name().equals(def.equipSlot()))) continue;
            def.bonuses().forEach((key, amount) -> {
                if (key.startsWith("auraskills-additional-")) totals.merge(key, amount, Double::sum);
            });
        }
        plugin.hooks().updateBonuses(p, totals);
    }

    public record MigrationResult(int updated, int removed, int failed) { }

    /** Safe to repeat; failed integrations or insufficient split space retain the original stack. */
    public MigrationResult migrate(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null && session.player() != player) throw new IllegalStateException("Player session changed");
        if (!plugin.hooks().inventoryReady(player)) throw new IllegalStateException("Inventory is not ready");
        int updated = 0, removed = 0, failed = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack original = inventory.getItem(slot);
            if (items().id(original) == null) continue;
            if (items().definition(original) == null) {
                inventory.setItem(slot, null); removed(player, original); removed++; continue;
            }
            try {
                ItemStack replacement = items().migrate(original, player);
                if (replacement.equals(original) && replacement.getAmount() <= replacement.getMaxStackSize()) continue;
                int amount = replacement.getAmount();
                int maximum = replacement.getMaxStackSize();
                replacement.setAmount(Math.min(amount, maximum));
                if (amount > maximum) {
                    var staging = Bukkit.createInventory(null, 36);
                    staging.setStorageContents(inventory.getStorageContents());
                    if (slot < 36) staging.setItem(slot, replacement);
                    boolean fits = true;
                    for (int remaining = amount - maximum; remaining > 0;) {
                        int count = Math.min(remaining, maximum);
                        var extra = replacement.clone(); extra.setAmount(count);
                        if (!staging.addItem(extra).isEmpty()) { fits = false; break; }
                        // addItem may mutate its argument while merging partial stacks.
                        remaining -= count;
                    }
                    if (!fits) { failed++; continue; }
                    inventory.setStorageContents(staging.getStorageContents());
                }
                inventory.setItem(slot, replacement); updated++;
                migrationWarnings.remove(items().id(original));
            } catch (IllegalArgumentException | IllegalStateException error) {
                failed++;
                if (migrationWarnings.add(items().id(original)))
                    plugin.getLogger().warning("Could not migrate item " + items().id(original) + ": " + error.getMessage());
            }
        }
        ItemStack cursor = player.getItemOnCursor();
        if (items().id(cursor) != null) {
            if (items().definition(cursor) == null) { player.setItemOnCursor(null); removed(player, cursor); removed++; }
            else try {
                var replacement = items().migrate(cursor, player);
                if (replacement.getAmount() > replacement.getMaxStackSize()) failed++;
                else if (!replacement.equals(cursor)) { player.setItemOnCursor(replacement); updated++; }
            } catch (IllegalArgumentException | IllegalStateException error) { failed++; }
        }
        reconcileReady(player);
        if (failed == 0) { pendingMigration.remove(player.getUniqueId()); nextMigration.remove(player.getUniqueId()); }
        return new MigrationResult(updated, removed, failed);
    }

    private void removed(Player player, ItemStack stack) {
        var meta = stack.getItemMeta();
        String name = meta.hasDisplayName() ? PlainTextComponentSerializer.plainText().serialize(meta.displayName()) : items().id(stack);
        plugin.message(player, "messages.removed-item", Map.of("item", name));
    }

    private static boolean workstation(InventoryType type) {
        return Set.of(InventoryType.CRAFTING, InventoryType.WORKBENCH, InventoryType.ANVIL,
                InventoryType.SMITHING, InventoryType.GRINDSTONE, InventoryType.STONECUTTER).contains(type);
    }
    private static EquipmentSlot hand(EquipmentSlot slot) { return slot == null ? EquipmentSlot.HAND : slot; }

    private static EquipmentSlot sourceHand(Player player, ItemStack source) {
        EquipmentSlot active = player.getActiveItemHand();
        if ((active == EquipmentSlot.HAND || active == EquipmentSlot.OFF_HAND)
                && source.isSimilar(player.getInventory().getItem(active))) return active;
        ItemStack offhand = player.getInventory().getItemInOffHand();
        ItemStack mainhand = player.getInventory().getItemInMainHand();
        return source.isSimilar(offhand) && !source.isSimilar(mainhand) ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND;
    }
}
