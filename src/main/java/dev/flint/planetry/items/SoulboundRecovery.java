package dev.flint.planetry.items;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Main-thread escrow for soulbound cursors that Paper cannot return to full storage.
 * Player PDC uses normal backend player-data persistence, not a shared database or
 * an Inventory Sync snapshot. Pending items wait on this backend until space opens.
 */
public final class SoulboundRecovery {
    private static final int STORAGE_SIZE = 36;
    private final PlanetryItems plugin;
    private final NamespacedKey key;
    private final Set<String> warnings = new HashSet<>();
    private static final String EXTRA_PREFIX = "soulbound_recovery_extra/";

    public SoulboundRecovery(PlanetryItems plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        key = new NamespacedKey(plugin, "soulbound_recovery");
    }

    /** Capture before native close/death/quit cursor cleanup, including while sync is unready. */
    public void captureCursor(Player player) {
        requireServerThread();
        PersistentDataContainer data = player.getPersistentDataContainer();
        ItemStack cursor;
        byte[] previous;
        byte[] encoded;
        try {
            cursor = player.getItemOnCursor();
            if (!plugin.policy().soulbound(cursor)) return;
            cursor = cursor.clone();
            previous = pendingBytes(data);
            ItemStack[] pending = previous == null ? new ItemStack[0] : decode(previous);
            ItemStack[] appended = Arrays.copyOf(pending, pending.length + 1);
            appended[pending.length] = cursor;
            encoded = ItemStack.serializeItemsAsBytes(appended);
            if (!Arrays.equals(appended, decode(encoded)))
                throw new IllegalStateException("Recovery serialization changed an item");
        } catch (RuntimeException | LinkageError error) {
            warn(player, "capture", error);
            captureSeparate(player);
            return; // Never replace unreadable pending data; preserve the new cursor independently.
        }

        try {
            data.set(key, PersistentDataType.BYTE_ARRAY, encoded);
        } catch (RuntimeException | LinkageError error) {
            try { restorePending(data, previous); }
            catch (RuntimeException | LinkageError rollback) { error.addSuppressed(rollback); }
            warn(player, "capture", error);
            return;
        }

        try {
            // The exact stack is encoded and in PDC before the native cursor is cleared.
            player.setItemOnCursor(null);
            player.saveData();
        } catch (RuntimeException | LinkageError error) {
            // Native setters normally cannot fail; retain the original state if one does.
            try { player.setItemOnCursor(cursor); }
            catch (RuntimeException | LinkageError rollback) { error.addSuppressed(rollback); }
            try { restorePending(data, previous); }
            catch (RuntimeException | LinkageError rollback) { error.addSuppressed(rollback); }
            warn(player, "capture", error);
        }
    }

    /** A corrupt old queue must not expose a new cursor to unsafe native cleanup. */
    private void captureSeparate(Player player) {
        ItemStack cursor = player.getItemOnCursor();
        if (!plugin.policy().soulbound(cursor)) return;
        cursor = cursor.clone();
        NamespacedKey extra = new NamespacedKey(plugin, EXTRA_PREFIX + java.util.UUID.randomUUID());
        boolean written = false;
        try {
            var exact = new ItemStack[]{cursor};
            byte[] encoded = ItemStack.serializeItemsAsBytes(exact);
            if (!Arrays.equals(exact, decode(encoded))) throw new IllegalStateException("Recovery serialization changed an item");
            player.getPersistentDataContainer().set(extra, PersistentDataType.BYTE_ARRAY, encoded);
            written = true;
            player.setItemOnCursor(null);
            player.saveData();
        } catch (RuntimeException | LinkageError error) {
            if (written) {
                try { player.setItemOnCursor(cursor); player.getPersistentDataContainer().remove(extra); }
                catch (RuntimeException | LinkageError rollback) { error.addSuppressed(rollback); }
            }
            warn(player, "isolated capture", error);
        }
    }

    /** Restore whatever fits, keeping every exact remainder in escrow for a later attempt. */
    public void recover(Player player) {
        requireServerThread();
        if (!player.isOnline() || player.isDead() || !plugin.hooks().inventoryReady(player)) return;
        recoverQueue(player, key);
        for (var extra : Set.copyOf(player.getPersistentDataContainer().getKeys())) {
            if (extra.getNamespace().equals(key.getNamespace()) && extra.getKey().startsWith(EXTRA_PREFIX))
                recoverQueue(player, extra);
        }
    }

    private void recoverQueue(Player player, NamespacedKey queueKey) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        byte[] encoded;
        ItemStack[] original;
        ItemStack[] restored;
        byte[] remainder;
        try {
            encoded = pendingBytes(data, queueKey);
            if (encoded == null) return;
            ItemStack[] pending = decode(encoded);
            var inventory = player.getInventory();
            original = copy(inventory.getStorageContents());
            if (original.length != STORAGE_SIZE)
                throw new IllegalStateException("Unexpected player storage size");
            Inventory staging = Bukkit.createInventory(null, STORAGE_SIZE);
            staging.setMaxStackSize(inventory.getMaxStackSize());
            staging.setStorageContents(copy(original));
            var leftovers = new ArrayList<ItemStack>();
            boolean inserted = false;
            for (ItemStack stack : pending) {
                // Bound each input to both stack limits; addItem may mutate its
                // arguments. Neither pending nor live storage is shared here.
                int limit = Math.min(stack.getMaxStackSize(), staging.getMaxStackSize());
                if (limit < 1) throw new IllegalStateException("Invalid recovery stack limit");
                int remaining = stack.getAmount();
                while (remaining > 0) {
                    int count = Math.min(remaining, limit);
                    ItemStack portion = stack.clone();
                    portion.setAmount(count);
                    var rejected = staging.addItem(portion);
                    int rejectedCount = 0;
                    for (var rejectedStack : rejected.values()) {
                        leftovers.add(rejectedStack.clone());
                        rejectedCount += rejectedStack.getAmount();
                    }
                    if (rejectedCount < 0 || rejectedCount > count) throw new IllegalStateException("Invalid recovery remainder");
                    if (rejectedCount < count) inserted = true;
                    remaining -= count;
                }
            }
            if (!inserted) return;
            restored = copy(staging.getStorageContents());
            remainder = leftovers.isEmpty() ? null : ItemStack.serializeItemsAsBytes(leftovers.toArray(ItemStack[]::new));
            if (remainder != null && !Arrays.equals(leftovers.toArray(ItemStack[]::new), decode(remainder)))
                throw new IllegalStateException("Recovery serialization changed a remainder");
        } catch (RuntimeException | LinkageError error) {
            warn(player, "recovery", error);
            return;
        }

        try {
            player.getInventory().setStorageContents(restored);
            restorePending(data, remainder, queueKey);
            player.saveData();
        } catch (RuntimeException | LinkageError error) {
            try { player.getInventory().setStorageContents(original); }
            catch (RuntimeException | LinkageError rollback) { error.addSuppressed(rollback); }
            try { data.set(queueKey, PersistentDataType.BYTE_ARRAY, encoded); }
            catch (RuntimeException | LinkageError rollback) { error.addSuppressed(rollback); }
            warn(player, "recovery", error);
        }
    }

    private byte[] pendingBytes(PersistentDataContainer data) {
        return pendingBytes(data, key);
    }
    private byte[] pendingBytes(PersistentDataContainer data, NamespacedKey queueKey) {
        if (!data.has(queueKey)) return null;
        byte[] value = data.get(queueKey, PersistentDataType.BYTE_ARRAY);
        if (value == null) throw new IllegalStateException("Recovery data is not a byte array");
        return value.clone();
    }

    private static ItemStack[] decode(byte[] encoded) {
        // Paper 26.1.2's version-1 collection codec trusts lengths when allocating
        // and accepts trailing/truncated input. Validate its framing first so
        // damaged player data cannot allocate arbitrary arrays or lose entries.
        ByteBuffer frame = ByteBuffer.wrap(encoded);
        if (frame.remaining() < 5 || frame.get() != 1)
            throw new IllegalStateException("Invalid recovery data version");
        int count = frame.getInt();
        if (count < 1 || count > frame.remaining() / Integer.BYTES)
            throw new IllegalStateException("Invalid recovery item count");
        for (int index = 0; index < count; index++) {
            if (frame.remaining() < Integer.BYTES)
                throw new IllegalStateException("Truncated recovery item length");
            int length = frame.getInt();
            if (length < 1 || length > frame.remaining())
                throw new IllegalStateException("Invalid recovery item length");
            frame.position(frame.position() + length);
        }
        if (frame.hasRemaining()) throw new IllegalStateException("Trailing recovery data");
        ItemStack[] pending = ItemStack.deserializeItemsFromBytes(encoded);
        if (pending.length != count) throw new IllegalStateException("Recovery item count mismatch");
        for (ItemStack stack : pending) {
            if (stack == null || stack.isEmpty() || stack.getAmount() < 1)
                throw new IllegalStateException("Recovery data contains an empty item");
        }
        return pending;
    }

    private void restorePending(PersistentDataContainer data, byte[] previous) {
        restorePending(data, previous, key);
    }
    private void restorePending(PersistentDataContainer data, byte[] previous, NamespacedKey queueKey) {
        if (previous == null) data.remove(queueKey);
        else data.set(queueKey, PersistentDataType.BYTE_ARRAY, previous);
    }

    private static ItemStack[] copy(ItemStack[] contents) {
        ItemStack[] result = new ItemStack[contents.length];
        for (int slot = 0; slot < contents.length; slot++)
            result[slot] = contents[slot] == null ? null : contents[slot].clone();
        return result;
    }

    private static void requireServerThread() {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("Soulbound recovery must run on the server thread");
    }

    private void warn(Player player, String operation, Throwable error) {
        if (warnings.add(player.getUniqueId() + ":" + operation))
            plugin.getLogger().warning("Soulbound cursor " + operation + " failed for " + player.getUniqueId()
                    + " (" + error.getClass().getSimpleName() + "). Pending data is retained;"
                    + " further warnings for this player and operation are suppressed until restart."
                    + (error.getSuppressed().length == 0 ? "" : " State rollback also failed; operator review is required."));
    }
}
