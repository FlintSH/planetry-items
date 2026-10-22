package dev.flint.planetry.items.sync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;

/** Main-thread pull client. Gameplay/lifecycle integration belongs to the caller. */
public final class ProxyDefinitions implements PluginMessageListener, AutoCloseable {
    private static final long POLL_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
    private final Plugin plugin;
    private final String secret;
    private final Validator validator;
    private final Path cacheFile;
    private final LongSupplier nanoTime;
    private final CacheWriter cacheWriter;
    private volatile String currentDigest = "";
    private volatile long currentGeneration;
    private Pending pending;
    private BukkitTask task;
    private boolean started;
    private boolean closed;
    private boolean sent;
    private long lastSent;

    /** Parse/validate both documents without side effects, returning a no-throw atomic activation. */
    @FunctionalInterface public interface Validator {
        Runnable validate(SyncProtocol.Snapshot snapshot) throws Exception;
    }

    @FunctionalInterface interface CacheWriter {
        void store(Path file, SyncProtocol.Snapshot snapshot) throws IOException;
    }

    public ProxyDefinitions(Plugin plugin, String secret, Validator validator) {
        this(plugin, secret, validator, plugin.getDataFolder().toPath().resolve(SnapshotCache.FILE_NAME));
    }

    public ProxyDefinitions(Plugin plugin, String secret, Validator validator, Path cacheFile) {
        this(plugin, secret, validator, cacheFile, System::nanoTime, SnapshotCache::store);
    }

    ProxyDefinitions(Plugin plugin, String secret, Validator validator, Path cacheFile,
            LongSupplier nanoTime, CacheWriter cacheWriter) {
        SyncProtocol.validateSecret(secret);
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.secret = secret;
        this.validator = Objects.requireNonNull(validator, "validator");
        this.cacheFile = Objects.requireNonNull(cacheFile, "cacheFile");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.cacheWriter = Objects.requireNonNull(cacheWriter, "cacheWriter");
    }

    /** Validate the last good pair before allowing inventory reconciliation on startup. */
    public boolean initializeFromCache() {
        requireMainThread();
        if (closed || started || ready()) throw new IllegalStateException("Cache initialization must precede start");
        try {
            var cached = SnapshotCache.load(cacheFile);
            if (cached.isEmpty()) return false;
            var snapshot = cached.get();
            Runnable activate = Objects.requireNonNull(validator.validate(snapshot), "activation");
            activate.run();
            currentGeneration = snapshot.generation();
            currentDigest = SyncProtocol.digest(snapshot);
            return true;
        } catch (Exception error) {
            plugin.getLogger().warning("Proxy definitions cache rejected; waiting for a valid proxy snapshot.");
            return false;
        }
    }

    /** Explicit registration; calls and the returned validator activation run on the server thread. */
    public void start() {
        requireMainThread();
        if (closed) throw new IllegalStateException("Sync client is closed");
        if (started) return;
        var messenger = plugin.getServer().getMessenger();
        try {
            messenger.registerOutgoingPluginChannel(plugin, SyncProtocol.CHANNEL);
            messenger.registerIncomingPluginChannel(plugin, SyncProtocol.CHANNEL, this);
            started = true;
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::requestNow, 1L, 100L);
        } catch (RuntimeException error) {
            started = false;
            messenger.unregisterIncomingPluginChannel(plugin, SyncProtocol.CHANNEL, this);
            messenger.unregisterOutgoingPluginChannel(plugin, SyncProtocol.CHANNEL);
            throw error;
        }
    }

    /** At most one outstanding request, expiring after ten real seconds without extension by chunks. */
    public void requestNow() {
        requireMainThread();
        if (!started || closed) return;
        long now = nanoTime.getAsLong();
        if (pending != null) {
            if (pending.consumed) return;
            if (!expired(pending, now)) return;
            pending = null;
        }
        if (sent && now - lastSent < POLL_NANOS) return;
        Player carrier = plugin.getServer().getOnlinePlayers().stream().filter(Player::isOnline).findFirst().orElse(null);
        if (carrier == null) return;
        UUID request = UUID.randomUUID();
        Pending next = new Pending(request, carrier, now);
        pending = next;
        sent = true;
        lastSent = now;
        try {
            carrier.sendPluginMessage(plugin, SyncProtocol.CHANNEL, SyncProtocol.encode(
                    new SyncProtocol.Packet(SyncProtocol.Kind.REQUEST, request, currentDigest, 0, 0, new byte[0]), secret));
        } catch (RuntimeException error) {
            if (pending == next) pending = null;
            plugin.getLogger().warning("Proxy definitions request could not be sent; it will be retried.");
        }
    }

    @Override public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!SyncProtocol.CHANNEL.equals(channel) || message == null || message.length > SyncProtocol.MAX_PACKET_BYTES) return;
        if (!plugin.getServer().isPrimaryThread()) {
            byte[] copy = message.clone();
            plugin.getServer().getScheduler().runTask(plugin, () -> receive(player, copy));
        } else receive(player, message);
    }

    private void receive(Player player, byte[] message) {
        requireMainThread();
        if (!started || closed || pending == null || pending.consumed) return;
        if (expired(pending, nanoTime.getAsLong())) { pending = null; return; }
        // Tie the request to this connection, so another carrier cannot complete it.
        if (player != pending.carrier || !player.isOnline()) return;
        final SyncProtocol.Packet packet;
        try { packet = SyncProtocol.decode(message, secret); }
        catch (IllegalArgumentException error) { return; }
        Pending expected = pending;
        if (!expected.request.equals(packet.request())) return;
        if (packet.kind() == SyncProtocol.Kind.CURRENT) {
            if (ready() && currentDigest.equals(packet.digest())) {
                finish(expected, currentDigest, "OK");
            } else reject(expected, packet.digest());
            return;
        }
        if (packet.kind() != SyncProtocol.Kind.CHUNK) return;
        try {
            var assembled = expected.assembly.accept(packet);
            if (assembled.isEmpty()) return;
            expected.consumed = true;
            var snapshot = assembled.get();
            // A signed response can still be stale or fork an already accepted authority generation.
            if (snapshot.generation() < currentGeneration
                    || (ready() && snapshot.generation() == currentGeneration && !currentDigest.equals(packet.digest())))
                throw new IllegalArgumentException("Snapshot generation rejected");
            if (ready() && currentDigest.equals(packet.digest())) {
                finish(expected, currentDigest, "OK");
                return;
            }
            Runnable activate = Objects.requireNonNull(validator.validate(snapshot), "activation");
            // No live definitions change until the validated pair has been forced and atomically persisted.
            cacheWriter.store(cacheFile, snapshot);
            activate.run();
            currentGeneration = snapshot.generation();
            currentDigest = packet.digest();
            finish(expected, currentDigest, "OK");
        } catch (Exception error) {
            reject(expected, packet.digest());
            // Never log YAML, exception text, packet contents or the shared secret.
            plugin.getLogger().warning("Proxy definitions update rejected; keeping the last good definitions.");
        }
    }

    private void reject(Pending expected, String digest) {
        finish(expected, digest, "REJECTED");
    }

    private void finish(Pending expected, String digest, String status) {
        expected.consumed = true; // Fence reentrant messages while retaining the request through the send attempt.
        try { result(expected, digest, status); }
        finally { if (pending == expected) pending = null; }
    }

    private void result(Pending expected, String digest, String status) {
        if (!expected.carrier.isOnline()) return;
        try {
            expected.carrier.sendPluginMessage(plugin, SyncProtocol.CHANNEL, SyncProtocol.encode(
                    new SyncProtocol.Packet(SyncProtocol.Kind.RESULT, expected.request, digest, 0, 0,
                            status.getBytes(StandardCharsets.UTF_8)), secret));
        } catch (RuntimeException error) {
            // A lost acknowledgement must never roll back an already persisted and active snapshot.
            plugin.getLogger().warning("Proxy definitions acknowledgement could not be sent.");
        }
    }

    public boolean ready() { return !currentDigest.isEmpty(); }
    public String currentDigest() { return currentDigest; }
    public long currentGeneration() { return currentGeneration; }
    public boolean requestPending() { return pending != null && !expired(pending, nanoTime.getAsLong()); }

    @Override public void close() {
        requireMainThread();
        if (closed) return;
        closed = true;
        pending = null;
        if (task != null) task.cancel();
        if (started) {
            var messenger = plugin.getServer().getMessenger();
            messenger.unregisterIncomingPluginChannel(plugin, SyncProtocol.CHANNEL, this);
            messenger.unregisterOutgoingPluginChannel(plugin, SyncProtocol.CHANNEL);
            started = false;
        }
    }

    private static boolean expired(Pending pending, long now) {
        long elapsed = now - pending.started;
        return elapsed < 0 || elapsed >= TIMEOUT_NANOS;
    }

    private void requireMainThread() {
        if (!plugin.getServer().isPrimaryThread()) throw new IllegalStateException("Proxy definitions require the server thread");
    }

    private static final class Pending {
        final UUID request;
        final Player carrier;
        final long started;
        final SyncProtocol.Assembly assembly;
        boolean consumed;
        Pending(UUID request, Player carrier, long started) {
            this.request = request;
            this.carrier = carrier;
            this.started = started;
            this.assembly = new SyncProtocol.Assembly(request);
        }
    }
}
