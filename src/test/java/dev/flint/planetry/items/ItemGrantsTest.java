package dev.flint.planetry.items;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.ArgumentCaptor;

class ItemGrantsTest {
    private final PlanetryItems plugin = mock(PlanetryItems.class);
    private final ItemCatalog catalog = mock(ItemCatalog.class);
    private final ItemGrants.Context context = mock(ItemGrants.Context.class);
    private final Player player = mock(Player.class);
    private final PlayerInventory inventory = mock(PlayerInventory.class);
    private final Inventory staging = mock(Inventory.class);
    private final UUID id = UUID.randomUUID();
    private final AtomicReference<ItemStack[]> prepared = new AtomicReference<>();
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach void setup() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
        bukkit.when(() -> Bukkit.createInventory(null, 36)).thenReturn(staging);
        when(context.available()).thenReturn(true);
        when(context.catalog()).thenReturn(catalog);
        when(context.logger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(catalog.get("crystal")).thenReturn(mock(ItemDefinition.class));
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn("Recipient");
        when(player.isOnline()).thenReturn(true);
        when(player.isValid()).thenReturn(true);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[36]);
        when(inventory.getMaxStackSize()).thenReturn(64);
        when(context.inventoryReady(player)).thenReturn(true);
        ItemStack prototype = stack(1, 16);
        when(context.create("crystal", player)).thenReturn(prototype);
        when(staging.getMaxStackSize()).thenReturn(64);
        doAnswer(call -> { prepared.set(call.getArgument(0)); return null; })
                .when(staging).setStorageContents(any(ItemStack[].class));
        when(staging.getStorageContents()).thenAnswer(call -> prepared.get());
        when(staging.addItem(any(ItemStack[].class))).thenReturn(new HashMap<>());
    }

    @AfterEach void cleanup() { bukkit.close(); }

    @Test void exactPublicReflectionSignaturesRemainAvailable() throws Exception {
        assertEquals(String.class, PlanetryItems.class.getMethod("giveNetworkItem", UUID.class, String.class, int.class).getReturnType());
        assertEquals(List.class, PlanetryItems.class.getMethod("networkItemIds", String.class).getReturnType());
    }

    @Test void listingFiltersThenSortsThenLimitsAndReturnsImmutableSnapshot() {
        var definitions = new LinkedHashMap<String, ItemDefinition>();
        for (int n = 109; n >= 0; n--) definitions.put("crystal_%03d".formatted(n), null);
        definitions.put("another", null);
        when(catalog.definitions()).thenReturn(definitions);
        List<String> result = ids("");
        assertEquals(100, result.size());
        assertEquals("another", result.getFirst());
        assertEquals("crystal_098", result.getLast());
        assertEquals(List.of("crystal_100", "crystal_101", "crystal_102", "crystal_103", "crystal_104",
                "crystal_105", "crystal_106", "crystal_107", "crystal_108", "crystal_109"), ids("crystal_10"));
        assertThrows(UnsupportedOperationException.class, () -> result.add("extra"));
        when(catalog.definitions()).thenReturn(Map.of("new_catalog", mock(ItemDefinition.class)));
        assertEquals(List.of("new_catalog"), ids(""));
        assertEquals(100, result.size());
    }

    @Test void prefixesAcceptOnlyTheBoundedLowercaseAlphabet() {
        String longest = "a".repeat(64);
        when(catalog.definitions()).thenReturn(Map.of(longest, mock(ItemDefinition.class), "a-_1", mock(ItemDefinition.class)));
        assertEquals(List.of(longest), ids(longest));
        assertEquals(List.of("a-_1"), ids("a-_"));
        assertEquals(List.of(), ids("_"));
        assertEquals(List.of(), ids("-"));
        for (String prefix : Arrays.asList(null, "A", "a ", "a:b", "a\n", "a".repeat(65)))
            assertEquals(List.of(), ids(prefix));
    }

    @Test void offThreadCallsFailBeforeLookupOrInventoryAccess() {
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
        assertEquals("UNAVAILABLE", grant(id, "crystal", 1));
        assertEquals(List.of(), ids(""));
        bukkit.verify(() -> Bukkit.getPlayer(id), never());
        verifyNoInteractions(player, inventory, staging);
        verify(context, never()).inventoryReady(any());
        verify(context, never()).create(anyString(), any());
    }

    @Test void disabledOrUninitializedPluginIsUnavailable() {
        when(context.available()).thenReturn(false);
        assertEquals("UNAVAILABLE", grant(id, "crystal", 1));
        assertEquals(List.of(), ids(""));
        when(context.available()).thenReturn(true);
        when(context.catalog()).thenReturn(null);
        assertEquals("UNAVAILABLE", grant(id, "crystal", 1));
        verifyNoInteractions(inventory);
        verify(context, never()).create(anyString(), any());
    }

    @Test void rejectsMalformedUnknownIdsAndAmountsWithoutTouchingStorage() {
        for (String item : Arrays.asList(null, "", "Crystal", "crystal ", "crystal:other", "a".repeat(65), "-crystal", "_crystal", "unknown"))
            assertEquals("UNKNOWN_ITEM", grant(id, item, 1));
        for (int amount : new int[]{Integer.MIN_VALUE, -1, 0, 2305, Integer.MAX_VALUE})
            assertEquals("INVALID_AMOUNT", grant(id, "crystal", amount));
        verifyNoInteractions(inventory, staging);
        verify(context, never()).create(anyString(), any());
    }

    @Test void requiresCurrentOnlineLivingValidPlayer() {
        assertEquals("OFFLINE", grant(null, "crystal", 1));
        assertEquals("OFFLINE", grant(UUID.randomUUID(), "crystal", 1));
        when(player.isOnline()).thenReturn(false);
        assertEquals("OFFLINE", grant(id, "crystal", 1));
        when(player.isOnline()).thenReturn(true);
        when(player.isValid()).thenReturn(false);
        assertEquals("OFFLINE", grant(id, "crystal", 1));
        when(player.isValid()).thenReturn(true);
        when(player.isDead()).thenReturn(true);
        assertEquals("OFFLINE", grant(id, "crystal", 1));
        verifyNoInteractions(inventory);
        verify(context, never()).create(anyString(), any());
    }

    @Test void installedUnreadyInventoryDeniesBeforeCreationAndAtCommit() {
        when(context.inventoryReady(player)).thenReturn(false);
        assertEquals("INVENTORY_UNAVAILABLE", grant(id, "crystal", 1));
        verifyNoInteractions(inventory);
        verify(context, never()).create(anyString(), any());
        when(context.inventoryReady(player)).thenReturn(true, false);
        assertEquals("INVENTORY_UNAVAILABLE", grant(id, "crystal", 1));
        verify(inventory, never()).setStorageContents(any());
    }

    @Test void readinessCallbackCannotSwapTheSessionBeforeWrite() {
        Player replacement = mock(Player.class);
        AtomicReference<Player> current = new AtomicReference<>(player);
        bukkit.when(() -> Bukkit.getPlayer(id)).thenAnswer(call -> current.get());
        AtomicInteger checks = new AtomicInteger();
        when(context.inventoryReady(player)).thenAnswer(call -> {
            if (checks.incrementAndGet() == 2) current.set(replacement);
            return true;
        });
        assertEquals("OFFLINE", grant(id, "crystal", 1));
        verify(inventory, never()).setStorageContents(any());
        verifyNoInteractions(replacement);
    }

    @Test void externalInventoryChangesDuringCreationArePreserved() {
        var contents = new ItemStack[36];
        when(inventory.getStorageContents()).thenReturn(contents);
        ItemStack foreign = stack(3, 64);
        when(context.create("crystal", player)).thenAnswer(call -> { contents[5] = foreign; return stack(1, 16); });
        assertEquals("INVENTORY_UNAVAILABLE", grant(id, "crystal", 1));
        assertSame(foreign, contents[5]);
        verify(inventory, never()).setStorageContents(any());
    }

    @Test void partialMergeThenOverflowNeverMutatesOriginalStacks() {
        ItemStack existing = stack(15, 16);
        var contents = new ItemStack[36]; contents[0] = existing;
        when(inventory.getStorageContents()).thenReturn(contents);
        when(staging.addItem(any(ItemStack[].class))).thenAnswer(call -> {
            assertNotSame(existing, prepared.get()[0]);
            prepared.get()[0].setAmount(16);
            return new HashMap<>(Map.of(0, stack(1, 16)));
        });
        assertEquals("INVENTORY_FULL", grant(id, "crystal", 2));
        assertEquals(15, existing.getAmount());
        assertSame(existing, contents[0]);
        verify(inventory, never()).setStorageContents(any());
        verify(context, never()).reconcile(any());
    }

    @Test void amountBoundsHonorStackLimitsAndCommitOnlyOnce() {
        assertEquals("SUCCESS", grant(id, "crystal", 1));
        verify(staging).setMaxStackSize(64);
        verify(inventory).setStorageContents(any(ItemStack[].class));
        clearInvocations(staging, inventory);
        when(staging.getMaxStackSize()).thenReturn(8);
        assertEquals("SUCCESS", grant(id, "crystal", 2304));
        var portions = ArgumentCaptor.forClass(ItemStack[].class);
        verify(staging, times(288)).addItem(portions.capture());
        for (ItemStack[] portion : portions.getAllValues()) {
            assertEquals(1, portion.length);
            assertEquals(8, portion[0].getAmount());
        }
        verify(inventory).setStorageContents(any(ItemStack[].class));
    }

    @Test void unavailableCreationDoesNotGrantOrNotify() {
        when(context.create("crystal", player)).thenThrow(new IllegalStateException("Nexo loading"));
        assertEquals("UNAVAILABLE", grant(id, "crystal", 1));
        verify(inventory, never()).setStorageContents(any());
        verify(plugin, never()).message(any(), anyString(), anyMap());
    }

    @Test void reentrantDisableOrCatalogReloadCannotCommitAStaleDefinition() {
        doAnswer(call -> {
            when(context.catalog()).thenReturn(mock(ItemCatalog.class));
            return stack(1, 16);
        }).when(context).create("crystal", player);
        assertEquals("UNAVAILABLE", grant(id, "crystal", 1));
        when(context.catalog()).thenReturn(catalog);
        doAnswer(call -> {
            when(context.available()).thenReturn(false);
            return stack(1, 16);
        }).when(context).create("crystal", player);
        assertEquals("UNAVAILABLE", grant(id, "crystal", 1));
        verify(inventory, never()).setStorageContents(any());
    }

    @Test void postCommitReconciliationAndNotificationFailuresStillReturnSuccess() {
        doThrow(new IllegalStateException("refresh failed")).when(context).reconcile(player);
        doThrow(new IllegalStateException("recipient disconnected")).when(plugin).message(eq(player), anyString(), anyMap());
        assertEquals("SUCCESS", grant(id, "crystal", 1));
        assertDoesNotThrow(() -> ItemGrants.notify(plugin, null, player, "crystal", 1));
        verify(inventory).setStorageContents(any(ItemStack[].class));
        verify(context).reconcile(player);
        verify(context).create("crystal", player);
    }

    @Test void inventoryWriteThatMutatesThenThrowsPropagatesAnUncertainOutcome() {
        AtomicReference<ItemStack[]> written = new AtomicReference<>();
        doAnswer(call -> {
            written.set(call.getArgument(0));
            throw new IllegalStateException("write applied before failure");
        }).when(inventory).setStorageContents(any(ItemStack[].class));
        var error = assertThrows(IllegalStateException.class, () -> grant(id, "crystal", 1));
        assertEquals("write applied before failure", error.getMessage());
        assertNotNull(written.get());
        verify(inventory).setStorageContents(any(ItemStack[].class));
        verify(context).create("crystal", player);
        verify(context, never()).reconcile(any());
        verify(plugin, never()).message(any(), anyString(), anyMap());
    }

    @Test void selfAndOtherCommandNotificationsKeepTheirExistingSemantics() {
        ItemGrants.notify(plugin, player, player, "crystal", 1);
        verify(plugin).message(eq(player), eq("messages.given"), anyMap());
        verify(plugin, never()).message(eq(player), eq("messages.received"), anyMap());
        clearInvocations(plugin);
        CommandSender sender = mock(CommandSender.class);
        doThrow(new IllegalStateException("sender gone")).when(plugin).message(eq(sender), eq("messages.given"), anyMap());
        ItemGrants.notify(plugin, sender, player, "crystal", 1);
        verify(plugin).message(eq(player), eq("messages.received"), anyMap());
    }

    private String grant(UUID target, String item, int amount) {
        Player recipient = target != null && target.equals(id) ? player : null;
        return ItemGrants.give(context, recipient, item, amount).name();
    }

    private List<String> ids(String prefix) { return ItemGrants.ids(context, prefix); }

    private static ItemStack stack(int quantity, int max) {
        ItemStack result = mock(ItemStack.class);
        AtomicInteger amount = new AtomicInteger(quantity);
        when(result.getAmount()).thenAnswer(call -> amount.get());
        doAnswer(call -> { amount.set(call.getArgument(0)); return null; }).when(result).setAmount(anyInt());
        when(result.getMaxStackSize()).thenReturn(max);
        when(result.clone()).thenAnswer(call -> stack(amount.get(), max));
        return result;
    }
}
