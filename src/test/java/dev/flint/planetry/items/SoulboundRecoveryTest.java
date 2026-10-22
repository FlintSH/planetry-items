package dev.flint.planetry.items;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class SoulboundRecoveryTest {
    private final PlanetryItems plugin = mock(PlanetryItems.class);
    private final ItemPolicy policy = mock(ItemPolicy.class);
    private final IntegrationHooks hooks = mock(IntegrationHooks.class);
    private final Player player = mock(Player.class);
    private final PlayerInventory inventory = mock(PlayerInventory.class);
    private final Inventory staging = mock(Inventory.class);
    private final PersistentDataContainer data = mock(PersistentDataContainer.class);
    private final Logger logger = mock(Logger.class);
    private final AtomicReference<byte[]> saved = new AtomicReference<>();
    private final AtomicReference<ItemStack[]> staged = new AtomicReference<>();
    private final NamespacedKey key = new NamespacedKey("planetryitems", "soulbound_recovery");
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<ItemStack> stacks;
    private SoulboundRecovery recovery;

    @BeforeEach void setup() {
        bukkit = mockStatic(Bukkit.class);
        stacks = mockStatic(ItemStack.class);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        bukkit.when(() -> Bukkit.createInventory(null, 36)).thenReturn(staging);
        when(plugin.getName()).thenReturn("PlanetryItems");
        when(plugin.namespace()).thenReturn("planetryitems");
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.policy()).thenReturn(policy);
        when(plugin.hooks()).thenReturn(hooks);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getPersistentDataContainer()).thenReturn(data);
        when(player.getInventory()).thenReturn(inventory);
        when(player.isOnline()).thenReturn(true);
        when(hooks.inventoryReady(player)).thenReturn(true);
        when(inventory.getMaxStackSize()).thenReturn(64);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[36]);
        when(staging.getMaxStackSize()).thenReturn(64);
        doAnswer(call -> { staged.set(call.getArgument(0)); return null; })
                .when(staging).setStorageContents(any(ItemStack[].class));
        when(staging.getStorageContents()).thenAnswer(call -> staged.get());
        when(staging.addItem(any(ItemStack[].class))).thenReturn(new HashMap<>());
        when(data.has(key)).thenAnswer(call -> saved.get() != null);
        when(data.getKeys()).thenReturn(java.util.Set.of());
        when(data.get(key, PersistentDataType.BYTE_ARRAY)).thenAnswer(call -> saved.get());
        doAnswer(call -> { saved.set(call.getArgument(2)); return null; })
                .when(data).set(eq(key), eq(PersistentDataType.BYTE_ARRAY), any(byte[].class));
        doAnswer(call -> { saved.set(null); return null; }).when(data).remove(key);
        recovery = new SoulboundRecovery(plugin);
    }

    @AfterEach void cleanup() {
        stacks.close();
        bukkit.close();
    }

    @Test void capturesExactCursorAfterAppendingExistingPendingAndPersistsInOrder() {
        ItemStack previous = stack(5, 64);
        pending(previous);
        ItemStack cursor = stack(7, 64);
        when(player.getItemOnCursor()).thenReturn(cursor);
        when(policy.soulbound(cursor)).thenReturn(true);
        byte[] encoded = frame(2);
        stacks.when(() -> ItemStack.serializeItemsAsBytes(any(ItemStack[].class))).thenAnswer(call -> {
            ItemStack[] appended = (ItemStack[]) call.getRawArguments()[0];
            assertEquals(2, appended.length);
            assertSame(previous, appended[0]);
            assertNotSame(cursor, appended[1]);
            assertEquals(7, appended[1].getAmount());
            stacks.when(() -> ItemStack.deserializeItemsFromBytes(encoded)).thenReturn(appended);
            return encoded;
        });

        recovery.captureCursor(player);

        assertArrayEquals(encoded, saved.get());
        var order = inOrder(data, player);
        order.verify(data).set(key, PersistentDataType.BYTE_ARRAY, encoded);
        order.verify(player).setItemOnCursor(null);
        order.verify(player).saveData();
        assertEquals(7, cursor.getAmount());
    }

    @Test void ordinaryCursorDoesNotEnterEscrow() {
        ItemStack cursor = stack(1, 64);
        when(player.getItemOnCursor()).thenReturn(cursor);
        recovery.captureCursor(player);
        assertNull(saved.get());
        verify(player, never()).setItemOnCursor(any());
        verify(player, never()).saveData();
        stacks.verifyNoInteractions();
    }

    @Test void serializationFailurePreservesCursorAndPending() {
        ItemStack previous = stack(5, 64);
        byte[] encoded = pending(previous);
        ItemStack cursor = stack(7, 64);
        when(player.getItemOnCursor()).thenReturn(cursor);
        when(policy.soulbound(cursor)).thenReturn(true);
        stacks.when(() -> ItemStack.serializeItemsAsBytes(any(ItemStack[].class)))
                .thenThrow(new IllegalArgumentException("codec failure"));

        recovery.captureCursor(player);

        assertArrayEquals(encoded, saved.get());
        verify(player, never()).setItemOnCursor(any());
        verify(player, never()).saveData();
        verify(data, never()).set(eq(key), eq(PersistentDataType.BYTE_ARRAY), any(byte[].class));
    }

    @Test void failedPdcWriteNeverTouchesCursor() {
        ItemStack cursor = stack(7, 64);
        when(player.getItemOnCursor()).thenReturn(cursor);
        when(policy.soulbound(cursor)).thenReturn(true);
        byte[] encoded = frame(1);
        stacks.when(() -> ItemStack.serializeItemsAsBytes(any(ItemStack[].class))).thenAnswer(call -> {
            ItemStack[] appended = (ItemStack[]) call.getRawArguments()[0];
            stacks.when(() -> ItemStack.deserializeItemsFromBytes(encoded)).thenReturn(appended);
            return encoded;
        });
        doThrow(new IllegalStateException("write failure")).when(data)
                .set(key, PersistentDataType.BYTE_ARRAY, encoded);

        recovery.captureCursor(player);

        assertNull(saved.get());
        verify(player, never()).setItemOnCursor(any());
        verify(player, never()).saveData();
    }

    @Test void lossySerializationNeverWritesEscrowOrTouchesCursor() {
        ItemStack cursor = stack(7, 64);
        when(player.getItemOnCursor()).thenReturn(cursor);
        when(policy.soulbound(cursor)).thenReturn(true);
        byte[] encoded = frame(1);
        ItemStack changed = stack(6, 64);
        stacks.when(() -> ItemStack.serializeItemsAsBytes(any(ItemStack[].class))).thenReturn(encoded);
        stacks.when(() -> ItemStack.deserializeItemsFromBytes(encoded))
                .thenReturn(new ItemStack[]{changed});

        recovery.captureCursor(player);

        assertNull(saved.get());
        verify(data, never()).set(eq(key), eq(PersistentDataType.BYTE_ARRAY), any(byte[].class));
        verify(player, never()).setItemOnCursor(any());
        verify(player, never()).saveData();
    }

    @Test void failedRemainderSerializationLeavesAllPendingAndLiveStorageUntouched() {
        ItemStack carried = stack(63, 64);
        ItemStack[] original = new ItemStack[36];
        original[0] = carried;
        when(inventory.getStorageContents()).thenReturn(original);
        byte[] encoded = pending(stack(2, 64), stack(3, 64));
        var leftover = new HashMap<Integer, ItemStack>();
        leftover.put(0, stack(3, 64));
        when(staging.addItem(any(ItemStack[].class))).thenAnswer(call -> {
            staged.get()[0].setAmount(64);
            return new HashMap<Integer, ItemStack>();
        }).thenReturn(leftover);
        stacks.when(() -> ItemStack.serializeItemsAsBytes(any(ItemStack[].class)))
                .thenThrow(new IllegalStateException("cannot encode remainder"));

        recovery.recover(player);

        assertEquals(63, carried.getAmount());
        assertArrayEquals(encoded, saved.get());
        verify(inventory, never()).setStorageContents(any(ItemStack[].class));
        verify(data, never()).remove(key);
        verify(player, never()).saveData();
    }

    @Test void completeStagingCommitsStorageBeforeClearingEscrowAndSaving() {
        pending(stack(1, 64));
        ItemStack restored = stack(19, 64);
        when(staging.addItem(any(ItemStack[].class))).thenAnswer(call -> {
            staged.get()[0] = restored;
            return new HashMap<Integer, ItemStack>();
        });

        recovery.recover(player);

        assertNull(saved.get());
        var contents = ArgumentCaptor.forClass(ItemStack[].class);
        var order = inOrder(inventory, data, player);
        order.verify(inventory).setStorageContents(contents.capture());
        order.verify(data).remove(key);
        order.verify(player).saveData();
        assertNotSame(restored, contents.getValue()[0]);
        assertEquals(19, contents.getValue()[0].getAmount());
        verify(player, never()).getEnderChest();
        verify(player, never()).getWorld();
    }

    @Test void recoverySplitsInputsWithoutChangingPendingQuantity() {
        ItemStack pending = stack(5, 2);
        pending(pending);
        var quantities = new java.util.ArrayList<Integer>();
        when(staging.addItem(any(ItemStack[].class))).thenAnswer(call -> {
            ItemStack[] portions = (ItemStack[]) call.getRawArguments()[0];
            quantities.add(portions[0].getAmount());
            portions[0].setAmount(0); // Paper is allowed to change the supplied stack.
            return new HashMap<Integer, ItemStack>();
        });

        recovery.recover(player);

        assertEquals(java.util.List.of(2, 2, 1), quantities);
        assertEquals(5, pending.getAmount());
        assertNull(saved.get());
    }

    @Test void failedEscrowRemovalRollsBackStorageAndRetainsExactPendingBytes() {
        ItemStack[] original = new ItemStack[36];
        original[0] = stack(23, 64);
        when(inventory.getStorageContents()).thenReturn(original);
        byte[] encoded = pending(stack(1, 64));
        doThrow(new IllegalStateException("remove failure")).when(data).remove(key);

        recovery.recover(player);

        assertArrayEquals(encoded, saved.get());
        var contents = ArgumentCaptor.forClass(ItemStack[].class);
        verify(inventory, times(2)).setStorageContents(contents.capture());
        ItemStack[] rollback = contents.getAllValues().get(1);
        assertNotSame(original[0], rollback[0]);
        assertEquals(23, rollback[0].getAmount());
        verify(player, never()).saveData();
    }

    @Test void offlineDeadAndUnreadyPlayersLeaveEscrowUntouched() {
        byte[] encoded = pending(stack(1, 64));
        when(player.isOnline()).thenReturn(false);
        recovery.recover(player);
        when(player.isOnline()).thenReturn(true);
        when(player.isDead()).thenReturn(true);
        recovery.recover(player);
        when(player.isDead()).thenReturn(false);
        when(hooks.inventoryReady(player)).thenReturn(false);
        recovery.recover(player);

        assertArrayEquals(encoded, saved.get());
        stacks.verifyNoInteractions();
        verify(inventory, never()).setStorageContents(any(ItemStack[].class));
        verify(player, never()).saveData();
    }

    @Test void malformedFramingIsRetainedWithoutNativeDecodeOrCursorMutationAndWarningsDeduplicate() {
        ItemStack cursor = stack(1, 64);
        when(player.getItemOnCursor()).thenReturn(cursor);
        when(policy.soulbound(cursor)).thenReturn(true);
        byte[][] damaged = {
                new byte[0],
                ByteBuffer.allocate(5).put((byte) 1).putInt(Integer.MAX_VALUE).array(),
                ByteBuffer.allocate(9).put((byte) 1).putInt(1).putInt(-1).array(),
                ByteBuffer.allocate(9).put((byte) 1).putInt(1).putInt(20).array(),
                java.util.Arrays.copyOf(frame(1), 11)
        };
        for (byte[] encoded : damaged) {
            saved.set(encoded);
            recovery.captureCursor(player);
            recovery.recover(player);
            assertArrayEquals(encoded, saved.get());
        }

        stacks.verify(() -> ItemStack.deserializeItemsFromBytes(any(byte[].class)), never());
        verify(player, never()).setItemOnCursor(any());
        verify(inventory, never()).setStorageContents(any(ItemStack[].class));
        verify(player, never()).saveData();
        verify(logger, times(3)).warning(anyString());
    }

    @Test void codecFailureAndWrongPdcTypeRetainPending() {
        byte[] encoded = frame(1);
        saved.set(encoded);
        stacks.when(() -> ItemStack.deserializeItemsFromBytes(any(byte[].class)))
                .thenThrow(new IllegalArgumentException("bad native item"));
        recovery.recover(player);
        assertArrayEquals(encoded, saved.get());
        when(data.get(key, PersistentDataType.BYTE_ARRAY)).thenReturn(null);
        recovery.recover(player);
        assertArrayEquals(encoded, saved.get());
        verify(data, never()).remove(key);
        verify(player, never()).saveData();
    }

    @Test void offThreadCallsRefuseAllPlayerMutation() {
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> recovery.captureCursor(player));
        assertThrows(IllegalStateException.class, () -> recovery.recover(player));
        verifyNoInteractions(data, inventory);
        verify(player, never()).setItemOnCursor(any());
        verify(player, never()).saveData();
    }

    private byte[] pending(ItemStack... pending) {
        byte[] encoded = frame(pending.length);
        saved.set(encoded);
        stacks.when(() -> ItemStack.deserializeItemsFromBytes(any(byte[].class))).thenReturn(pending);
        return encoded;
    }

    /** Tiny framed payloads; the native codec is mocked, not represented as real Paper NBT. */
    private static byte[] frame(int count) {
        var bytes = ByteBuffer.allocate(5 + count * 5).put((byte) 1).putInt(count);
        for (int index = 0; index < count; index++) bytes.putInt(1).put((byte) 1);
        return bytes.array();
    }

    private static ItemStack stack(int quantity, int max) {
        ItemStack result = mock(ItemStack.class);
        AtomicInteger amount = new AtomicInteger(quantity);
        when(result.getAmount()).thenAnswer(call -> amount.get());
        when(result.getMaxStackSize()).thenReturn(max);
        when(result.isEmpty()).thenAnswer(call -> amount.get() < 1);
        when(result.clone()).thenAnswer(call -> stack(amount.get(), max));
        doAnswer(call -> { amount.set(call.getArgument(0)); return null; }).when(result).setAmount(anyInt());
        return result;
    }
}
