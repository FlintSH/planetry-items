package dev.flint.planetry.items.sync;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class ProxyDefinitionsTest {
    private static final String SECRET = "sync-test-secret-32-characters-minimum";
    @TempDir Path data;
    private Plugin plugin;
    private Server server;
    private Player carrier;
    private Messenger messenger;
    private BukkitScheduler scheduler;
    private BukkitTask task;
    private Logger logger;
    private final AtomicLong now = new AtomicLong();
    private final AtomicInteger validations = new AtomicInteger();
    private final AtomicInteger activations = new AtomicInteger();
    private final AtomicReference<SyncProtocol.Snapshot> active = new AtomicReference<>();
    private final List<SyncProtocol.Packet> outgoing = new ArrayList<>();
    private Path cache;

    @BeforeEach void setup() {
        plugin = mock(Plugin.class);
        server = mock(Server.class);
        carrier = mock(Player.class);
        messenger = mock(Messenger.class);
        scheduler = mock(BukkitScheduler.class);
        task = mock(BukkitTask.class);
        logger = mock(Logger.class);
        cache = data.resolve(SnapshotCache.FILE_NAME);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getLogger()).thenReturn(logger);
        when(server.getMessenger()).thenReturn(messenger);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.isPrimaryThread()).thenReturn(true);
        doReturn(List.of(carrier)).when(server).getOnlinePlayers();
        when(carrier.isOnline()).thenReturn(true);
        when(carrier.getUniqueId()).thenReturn(UUID.randomUUID());
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(100L))).thenReturn(task);
        doAnswer(invocation -> {
            outgoing.add(SyncProtocol.decode(invocation.getArgument(2, byte[].class), SECRET));
            return null;
        }).when(carrier).sendPluginMessage(eq(plugin), eq(SyncProtocol.CHANNEL), any(byte[].class));
    }

    private ProxyDefinitions client() { return client(SnapshotCache::store); }
    private ProxyDefinitions client(ProxyDefinitions.CacheWriter writer) {
        return new ProxyDefinitions(plugin, SECRET, snapshot -> {
            validations.incrementAndGet();
            if (text(snapshot.language()).contains("invalid")) throw new IllegalArgumentException("private data " + SECRET);
            return () -> {
                try { assertSnapshot(snapshot, SnapshotCache.load(cache).orElseThrow()); }
                catch (IOException error) { throw new AssertionError(error); }
                active.set(snapshot);
                activations.incrementAndGet();
            };
        }, cache, now::get, writer);
    }

    private static SyncProtocol.Snapshot snapshot(String id) {
        return snapshot(1, id);
    }
    private static SyncProtocol.Snapshot snapshot(long generation, String id) {
        return new SyncProtocol.Snapshot(generation, bytes("items: " + id + "\n"), bytes("language: " + id + "\n"));
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value, StandardCharsets.UTF_8); }
    private void deliver(ProxyDefinitions client, SyncProtocol.Packet packet) {
        client.onPluginMessageReceived(SyncProtocol.CHANNEL, carrier, SyncProtocol.encode(packet, SECRET));
    }
    private SyncProtocol.Packet latestRequest() {
        return outgoing.stream().filter(packet -> packet.kind() == SyncProtocol.Kind.REQUEST).reduce((a, b) -> b).orElseThrow();
    }
    private long requests() { return outgoing.stream().filter(packet -> packet.kind() == SyncProtocol.Kind.REQUEST).count(); }
    private SyncProtocol.Packet current(UUID request, String digest) {
        return new SyncProtocol.Packet(SyncProtocol.Kind.CURRENT, request, digest, 0, 0, new byte[0]);
    }
    private void install(ProxyDefinitions client, SyncProtocol.Snapshot snapshot) {
        for (var chunk : SyncProtocol.chunks(latestRequest().request(), snapshot)) deliver(client, chunk);
    }
    private static void assertSnapshot(SyncProtocol.Snapshot expected, SyncProtocol.Snapshot actual) {
        assertEquals(expected.generation(), actual.generation());
        assertArrayEquals(expected.items(), actual.items());
        assertArrayEquals(expected.language(), actual.language());
    }

    @Test void explicitStartSchedulesFiveSecondPollAndCloseUnregistersOnlyOwnedChannel() {
        var client = client();
        verifyNoInteractions(messenger);
        client.start();
        client.start();
        verify(messenger).registerOutgoingPluginChannel(plugin, SyncProtocol.CHANNEL);
        verify(messenger).registerIncomingPluginChannel(plugin, SyncProtocol.CHANNEL, client);
        var poll = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runTaskTimer(eq(plugin), poll.capture(), eq(1L), eq(100L));
        poll.getValue().run();
        assertEquals(1, requests());
        assertEquals("", latestRequest().digest());
        client.close();
        client.close();
        verify(task).cancel();
        verify(messenger).unregisterIncomingPluginChannel(plugin, SyncProtocol.CHANNEL, client);
        verify(messenger).unregisterOutgoingPluginChannel(plugin, SyncProtocol.CHANNEL);
        assertFalse(client.requestPending());
        install(client, snapshot("late"));
        assertFalse(client.ready());
        assertEquals(0, client.currentGeneration());
        client.requestNow();
        assertEquals(1, requests());
    }

    @Test void noPlayersMeansNoRequestAndPendingNonceIsNotReplacedBeforeTenSeconds() {
        var client = client();
        client.start();
        doReturn(List.of()).when(server).getOnlinePlayers();
        client.requestNow();
        assertFalse(client.requestPending());
        assertEquals(0, requests());
        doReturn(List.of(carrier)).when(server).getOnlinePlayers();
        client.requestNow();
        UUID first = latestRequest().request();
        now.set(TimeUnit.SECONDS.toNanos(5));
        client.requestNow();
        assertEquals(1, requests());
        now.set(TimeUnit.SECONDS.toNanos(10) - 1);
        client.requestNow();
        assertEquals(1, requests());
        now.incrementAndGet();
        client.requestNow();
        assertEquals(2, requests());
        assertNotEquals(first, latestRequest().request());
        deliver(client, SyncProtocol.chunks(first, snapshot("old")).get(0));
        assertTrue(client.requestPending());
        assertEquals(0, validations.get());
    }

    @Test void failedRequestSendRetriesAtFiveSecondsAndChannelRegistrationFailureCleansUp() {
        doThrow(new IllegalStateException("connection closed")).when(carrier)
                .sendPluginMessage(eq(plugin), eq(SyncProtocol.CHANNEL), any(byte[].class));
        var client = client();
        client.start();
        client.requestNow();
        assertFalse(client.requestPending());
        verify(carrier).sendPluginMessage(eq(plugin), eq(SyncProtocol.CHANNEL), any(byte[].class));
        now.set(TimeUnit.SECONDS.toNanos(4));
        client.requestNow();
        verify(carrier).sendPluginMessage(eq(plugin), eq(SyncProtocol.CHANNEL), any(byte[].class));
        now.set(TimeUnit.SECONDS.toNanos(5));
        client.requestNow();
        verify(carrier, times(2)).sendPluginMessage(eq(plugin), eq(SyncProtocol.CHANNEL), any(byte[].class));
        client.close();
        var failed = client();
        when(messenger.registerIncomingPluginChannel(plugin, SyncProtocol.CHANNEL, failed))
                .thenThrow(new IllegalStateException("registration failed"));
        assertThrows(IllegalStateException.class, failed::start);
        verify(messenger).unregisterIncomingPluginChannel(plugin, SyncProtocol.CHANNEL, failed);
        verify(messenger, times(2)).unregisterOutgoingPluginChannel(plugin, SyncProtocol.CHANNEL);
        failed.requestNow();
        assertFalse(failed.requestPending());
    }

    @Test void authenticChunksValidateThenPersistBothDocumentsBeforeOneActivationAndConsumeNonce() throws Exception {
        var client = client();
        client.start();
        client.requestNow();
        var snapshot = new SyncProtocol.Snapshot(bytes("items: " + "x".repeat(50_000)), bytes("language: value"));
        var packets = SyncProtocol.chunks(latestRequest().request(), snapshot);
        deliver(client, packets.get(2));
        deliver(client, packets.get(0));
        deliver(client, packets.get(0));
        assertEquals(0, validations.get());
        assertFalse(Files.exists(cache));
        deliver(client, packets.get(1));
        assertEquals(1, validations.get());
        assertEquals(1, activations.get());
        assertTrue(client.ready());
        assertFalse(client.requestPending());
        assertEquals(SyncProtocol.digest(snapshot), client.currentDigest());
        assertEquals(snapshot.generation(), client.currentGeneration());
        assertSnapshot(snapshot, active.get());
        assertSnapshot(snapshot, SnapshotCache.load(cache).orElseThrow());
        var result = outgoing.get(outgoing.size() - 1);
        assertEquals(SyncProtocol.Kind.RESULT, result.kind());
        assertEquals("OK", text(result.payload()));
        for (var packet : packets) deliver(client, packet);
        assertEquals(1, activations.get());
        now.set(TimeUnit.SECONDS.toNanos(4));
        client.requestNow();
        assertEquals(1, requests());
        now.set(TimeUnit.SECONDS.toNanos(5));
        client.requestNow();
        assertEquals(2, requests());
        assertEquals(client.currentDigest(), latestRequest().digest());
    }

    @Test void wrongHmacNonceChannelKindAndCarrierCannotAffectPendingRequest() {
        var client = client();
        client.start();
        client.requestNow();
        var chunk = SyncProtocol.chunks(latestRequest().request(), snapshot("valid")).get(0);
        byte[] encoded = SyncProtocol.encode(chunk, SECRET);
        client.onPluginMessageReceived("another:channel", carrier, encoded);
        var impostor = mock(Player.class);
        when(impostor.isOnline()).thenReturn(true);
        UUID carrierId = carrier.getUniqueId();
        when(impostor.getUniqueId()).thenReturn(carrierId);
        client.onPluginMessageReceived(SyncProtocol.CHANNEL, impostor, encoded);
        byte[] forged = encoded.clone();
        forged[forged.length - 1] ^= 1;
        client.onPluginMessageReceived(SyncProtocol.CHANNEL, carrier, forged);
        deliver(client, SyncProtocol.chunks(UUID.randomUUID(), snapshot("wrong")).get(0));
        deliver(client, new SyncProtocol.Packet(SyncProtocol.Kind.REQUEST, chunk.request(), "", 0, 0, new byte[0]));
        client.onPluginMessageReceived(SyncProtocol.CHANNEL, carrier, new byte[25_001]);
        assertTrue(client.requestPending());
        assertEquals(0, validations.get());
        deliver(client, chunk);
        assertEquals(1, activations.get());
    }

    @Test void everyReplyChecksDeadlineAndPartialChunksDoNotExtendIt() {
        var client = client();
        client.start();
        client.requestNow();
        var packets = SyncProtocol.chunks(latestRequest().request(), new SyncProtocol.Snapshot(bytes("x".repeat(30_000)), bytes("lang")));
        now.set(TimeUnit.SECONDS.toNanos(9));
        deliver(client, packets.get(0));
        now.set(TimeUnit.SECONDS.toNanos(10));
        deliver(client, packets.get(1));
        assertFalse(client.requestPending());
        assertFalse(client.ready());
        assertEquals(0, validations.get());
    }

    @Test void disconnectedCarrierCannotFinishAndReplacementSessionCannotReuseNonce() {
        var client = client();
        client.start();
        client.requestNow();
        when(carrier.isOnline()).thenReturn(false);
        install(client, snapshot("disconnected"));
        assertEquals(0, validations.get());
        assertTrue(client.requestPending());
    }

    @Test void invalidSecondDocumentRetainsActivePairAndExactLastGoodCacheWithoutLoggingSecret() throws Exception {
        var old = snapshot("old");
        SnapshotCache.store(cache, old);
        var client = client();
        assertTrue(client.initializeFromCache());
        byte[] oldCache = Files.readAllBytes(cache);
        client.start();
        client.requestNow();
        var invalid = new SyncProtocol.Snapshot(2, bytes("items: valid"), bytes("invalid language " + SECRET));
        install(client, invalid);
        assertEquals(2, validations.get());
        assertEquals(1, activations.get());
        assertEquals(SyncProtocol.digest(old), client.currentDigest());
        assertEquals(old.generation(), client.currentGeneration());
        assertSnapshot(old, active.get());
        assertArrayEquals(oldCache, Files.readAllBytes(cache));
        assertEquals("REJECTED", text(outgoing.get(outgoing.size() - 1).payload()));
        assertFalse(client.requestPending());
        var warning = ArgumentCaptor.forClass(String.class);
        verify(logger).warning(warning.capture());
        assertFalse(warning.getValue().contains(SECRET));
        assertFalse(warning.getValue().contains("invalid language"));
    }

    @Test void rollbackAndSameGenerationForkNeverReachValidationOrStorage() throws Exception {
        var previous = snapshot(100, "active");
        SnapshotCache.store(cache, previous);
        byte[] original = Files.readAllBytes(cache);
        var writes = new AtomicInteger();
        var client = client((file, snapshot) -> {
            writes.incrementAndGet();
            SnapshotCache.store(file, snapshot);
        });
        assertTrue(client.initializeFromCache());
        client.start();
        for (var candidate : List.of(snapshot(0, "active"), snapshot(99, "rollback"), snapshot(100, "fork"))) {
            client.requestNow();
            install(client, candidate);
            assertFalse(client.requestPending());
            assertTrue(client.ready());
            assertEquals(100, client.currentGeneration());
            assertEquals(SyncProtocol.digest(previous), client.currentDigest());
            assertSnapshot(previous, active.get());
            assertArrayEquals(original, Files.readAllBytes(cache));
            assertEquals("REJECTED", text(outgoing.get(outgoing.size() - 1).payload()));
            now.addAndGet(TimeUnit.SECONDS.toNanos(5));
        }
        assertEquals(1, validations.get());
        assertEquals(1, activations.get());
        assertEquals(0, writes.get());
        client.requestNow();
        install(client, snapshot(101, "accepted"));
        assertEquals(101, client.currentGeneration());
        assertEquals(2, validations.get());
        assertEquals(2, activations.get());
        assertEquals(1, writes.get());
    }

    @Test void firstGenerationZeroIsAcceptedButConflictingZeroIsRejectedOnceReady() throws Exception {
        var client = client();
        assertFalse(client.ready());
        assertEquals(0, client.currentGeneration());
        client.start();
        client.requestNow();
        var fixture = new SyncProtocol.Snapshot(bytes("items: fixture"), bytes("language: fixture"));
        install(client, fixture);
        assertTrue(client.ready());
        assertEquals(0, client.currentGeneration());
        assertSnapshot(fixture, active.get());
        byte[] original = Files.readAllBytes(cache);
        now.set(TimeUnit.SECONDS.toNanos(5));
        client.requestNow();
        install(client, new SyncProtocol.Snapshot(bytes("items: fork"), bytes("language: fork")));
        assertEquals("REJECTED", text(outgoing.get(outgoing.size() - 1).payload()));
        assertEquals(1, validations.get());
        assertEquals(1, activations.get());
        assertEquals(0, client.currentGeneration());
        assertEquals(SyncProtocol.digest(fixture), client.currentDigest());
        assertArrayEquals(original, Files.readAllBytes(cache));
    }

    @Test void identicalGenerationAndDigestSucceedWithoutRevalidatingOrRewritingCache() throws Exception {
        var previous = snapshot(100, "active");
        SnapshotCache.store(cache, previous);
        byte[] original = Files.readAllBytes(cache);
        var writes = new AtomicInteger();
        var client = client((file, snapshot) -> {
            writes.incrementAndGet();
            throw new IOException("cache cannot be rewritten");
        });
        assertTrue(client.initializeFromCache());
        client.start();
        client.requestNow();
        install(client, previous);
        assertFalse(client.requestPending());
        assertTrue(client.ready());
        assertEquals(100, client.currentGeneration());
        assertEquals(SyncProtocol.digest(previous), client.currentDigest());
        assertArrayEquals(original, Files.readAllBytes(cache));
        assertEquals(1, validations.get());
        assertEquals(1, activations.get());
        assertEquals(0, writes.get());
        assertEquals("OK", text(outgoing.get(outgoing.size() - 1).payload()));
    }

    @Test void generationChangesOnlyAfterCacheValidationOrPersistedActivation() throws Exception {
        var previous = snapshot(100, "active");
        // Identical documents still constitute a new authority generation.
        var next = new SyncProtocol.Snapshot(200, previous.items(), previous.language());
        SnapshotCache.store(cache, previous);
        var reference = new AtomicReference<ProxyDefinitions>();
        var client = new ProxyDefinitions(plugin, SECRET, snapshot -> {
            long expectedGeneration = snapshot.generation() == previous.generation() ? 0 : previous.generation();
            assertEquals(expectedGeneration, reference.get().currentGeneration());
            validations.incrementAndGet();
            return () -> {
                assertEquals(expectedGeneration, reference.get().currentGeneration());
                try { assertSnapshot(snapshot, SnapshotCache.load(cache).orElseThrow()); }
                catch (IOException error) { throw new AssertionError(error); }
                active.set(snapshot);
                activations.incrementAndGet();
            };
        }, cache, now::get, (file, snapshot) -> {
            assertEquals(previous.generation(), reference.get().currentGeneration());
            assertEquals(SyncProtocol.digest(previous), reference.get().currentDigest());
            assertSnapshot(previous, SnapshotCache.load(file).orElseThrow());
            SnapshotCache.store(file, snapshot);
        });
        reference.set(client);
        assertTrue(client.initializeFromCache());
        assertEquals(previous.generation(), client.currentGeneration());
        client.start();
        client.requestNow();
        install(client, next);
        assertEquals(next.generation(), client.currentGeneration());
        assertEquals(SyncProtocol.digest(next), client.currentDigest());
        assertSnapshot(next, active.get());
        assertSnapshot(next, SnapshotCache.load(cache).orElseThrow());
        assertEquals(2, validations.get());
        assertEquals(2, activations.get());
        assertEquals("OK", text(outgoing.get(outgoing.size() - 1).payload()));
    }

    @Test void invalidHighGenerationDoesNotPreventLaterValidLowerCandidateAboveActiveGeneration() throws Exception {
        var previous = snapshot(100, "active");
        SnapshotCache.store(cache, previous);
        byte[] original = Files.readAllBytes(cache);
        var client = client();
        assertTrue(client.initializeFromCache());
        client.start();
        client.requestNow();
        install(client, new SyncProtocol.Snapshot(Long.MAX_VALUE, bytes("items"), bytes("invalid language")));
        assertEquals(100, client.currentGeneration());
        assertEquals(SyncProtocol.digest(previous), client.currentDigest());
        assertSnapshot(previous, active.get());
        assertArrayEquals(original, Files.readAllBytes(cache));
        assertEquals("REJECTED", text(outgoing.get(outgoing.size() - 1).payload()));
        now.set(TimeUnit.SECONDS.toNanos(5));
        client.requestNow();
        var valid = snapshot(101, "valid");
        install(client, valid);
        assertTrue(client.ready());
        assertEquals(101, client.currentGeneration());
        assertSnapshot(valid, active.get());
        assertSnapshot(valid, SnapshotCache.load(cache).orElseThrow());
        assertEquals("OK", text(outgoing.get(outgoing.size() - 1).payload()));
    }

    @Test void cacheFailureDoesNotActivateOrReplaceLastGoodAndReturnsGenericRejection() throws Exception {
        var old = snapshot("old");
        SnapshotCache.store(cache, old);
        byte[] original = Files.readAllBytes(cache);
        var client = client((file, snapshot) -> { throw new IOException("secret " + SECRET); });
        assertTrue(client.initializeFromCache());
        client.start();
        client.requestNow();
        install(client, snapshot(2, "new"));
        assertEquals(2, validations.get());
        assertEquals(1, activations.get());
        assertSnapshot(old, active.get());
        assertSnapshot(old, SnapshotCache.load(cache).orElseThrow());
        assertEquals(SyncProtocol.digest(old), client.currentDigest());
        assertEquals(old.generation(), client.currentGeneration());
        assertTrue(client.ready());
        assertArrayEquals(original, Files.readAllBytes(cache));
        assertEquals("REJECTED", text(outgoing.get(outgoing.size() - 1).payload()));
    }

    @Test void matchingCurrentRequiresReadinessAndMismatchesCannotEstablishReadiness() throws Exception {
        var client = client();
        client.start();
        client.requestNow();
        deliver(client, current(latestRequest().request(), SyncProtocol.digest(snapshot("unknown"))));
        assertFalse(client.ready());
        assertEquals(0, client.currentGeneration());
        assertFalse(client.requestPending());
        assertEquals("REJECTED", text(outgoing.get(outgoing.size() - 1).payload()));
        client.close();
        SnapshotCache.store(cache, snapshot("known"));
        var readyClient = client();
        assertTrue(readyClient.initializeFromCache());
        readyClient.start();
        readyClient.requestNow();
        deliver(readyClient, current(latestRequest().request(), readyClient.currentDigest()));
        assertTrue(readyClient.ready());
        assertEquals(snapshot("known").generation(), readyClient.currentGeneration());
        assertFalse(readyClient.requestPending());
        assertEquals("OK", text(outgoing.get(outgoing.size() - 1).payload()));
        assertEquals(1, validations.get());
        now.set(TimeUnit.SECONDS.toNanos(5));
        readyClient.requestNow();
        deliver(readyClient, current(latestRequest().request(), SyncProtocol.digest(snapshot("different"))));
        assertEquals(SyncProtocol.digest(snapshot("known")), readyClient.currentDigest());
        assertEquals(snapshot("known").generation(), readyClient.currentGeneration());
        assertEquals("REJECTED", text(outgoing.get(outgoing.size() - 1).payload()));
    }

    @Test void restartValidatesPersistedPairAndCorruptOrInvalidCachesLeaveClientUnready() throws Exception {
        var empty = client();
        assertFalse(empty.initializeFromCache());
        assertEquals(0, empty.currentGeneration());
        Files.writeString(cache, "broken");
        var corrupt = client();
        assertFalse(corrupt.initializeFromCache());
        assertEquals(0, corrupt.currentGeneration());
        SnapshotCache.store(cache, new SyncProtocol.Snapshot(Long.MAX_VALUE, bytes("items"), bytes("invalid")));
        var invalid = client();
        assertFalse(invalid.initializeFromCache());
        assertFalse(invalid.ready());
        assertEquals(0, invalid.currentGeneration());
        SnapshotCache.store(cache, snapshot(101, "restart"));
        var client = client();
        assertTrue(client.initializeFromCache());
        assertTrue(client.ready());
        assertEquals(101, client.currentGeneration());
        assertEquals(SyncProtocol.digest(snapshot(101, "restart")), client.currentDigest());
        assertThrows(IllegalStateException.class, client::initializeFromCache);
    }

    @Test void failedAcknowledgementDoesNotRejectAnAlreadyCommittedSnapshot() {
        doAnswer(invocation -> {
            var packet = SyncProtocol.decode(invocation.getArgument(2, byte[].class), SECRET);
            outgoing.add(packet);
            if (packet.kind() == SyncProtocol.Kind.RESULT) throw new IllegalStateException("carrier lost");
            return null;
        }).when(carrier).sendPluginMessage(eq(plugin), eq(SyncProtocol.CHANNEL), any(byte[].class));
        var client = client();
        client.start();
        client.requestNow();
        install(client, snapshot("committed"));
        assertTrue(client.ready());
        assertEquals(snapshot("committed").generation(), client.currentGeneration());
        assertFalse(client.requestPending());
        assertEquals(1, activations.get());
        assertEquals(1, outgoing.stream().filter(packet -> packet.kind() == SyncProtocol.Kind.RESULT).count());
        assertEquals("OK", text(outgoing.get(outgoing.size() - 1).payload()));
    }

    @Test void resultSendRetainsRequestUntilAttemptCompletesButFencesReentrantReplay() {
        var client = client();
        var replay = new AtomicReference<SyncProtocol.Packet>();
        doAnswer(invocation -> {
            var packet = SyncProtocol.decode(invocation.getArgument(2, byte[].class), SECRET);
            outgoing.add(packet);
            if (packet.kind() == SyncProtocol.Kind.RESULT) {
                assertTrue(client.requestPending());
                deliver(client, replay.get());
                client.requestNow();
                assertEquals(1, activations.get());
            }
            return null;
        }).when(carrier).sendPluginMessage(eq(plugin), eq(SyncProtocol.CHANNEL), any(byte[].class));
        client.start();
        client.requestNow();
        replay.set(SyncProtocol.chunks(latestRequest().request(), snapshot("reentrant")).get(0));
        deliver(client, replay.get());
        assertFalse(client.requestPending());
        assertEquals(1, outgoing.stream().filter(packet -> packet.kind() == SyncProtocol.Kind.RESULT).count());
    }

    @Test void offThreadListenerQueuesBoundedCopyAndOnlyMainThreadCanMutateClient() {
        var client = client();
        client.start();
        client.requestNow();
        var packet = SyncProtocol.chunks(latestRequest().request(), snapshot("queued")).get(0);
        byte[] encoded = SyncProtocol.encode(packet, SECRET);
        when(server.isPrimaryThread()).thenReturn(false);
        client.onPluginMessageReceived(SyncProtocol.CHANNEL, carrier, encoded);
        encoded[0] ^= 1;
        var queued = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runTask(eq(plugin), queued.capture());
        assertEquals(0, validations.get());
        assertThrows(IllegalStateException.class, client::requestNow);
        assertThrows(IllegalStateException.class, client::close);
        when(server.isPrimaryThread()).thenReturn(true);
        queued.getValue().run();
        assertEquals(1, activations.get());
    }

    @Test void queuedMessageCannotActivateAfterClose() {
        var client = client();
        client.start();
        client.requestNow();
        var packet = SyncProtocol.chunks(latestRequest().request(), snapshot("queued" )).get(0);
        when(server.isPrimaryThread()).thenReturn(false);
        deliver(client, packet);
        var queued = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runTask(eq(plugin), queued.capture());
        when(server.isPrimaryThread()).thenReturn(true);
        client.close();
        queued.getValue().run();
        assertFalse(client.ready());
        assertFalse(Files.exists(cache));
        assertEquals(0, validations.get());
    }

    @Test void mixedChunksRejectWholeUpdateAndCannotBeReplayed() {
        var client = client();
        client.start();
        client.requestNow();
        var snapshot = new SyncProtocol.Snapshot(bytes("x".repeat(30_000)), bytes("lang"));
        var packets = SyncProtocol.chunks(latestRequest().request(), snapshot);
        deliver(client, packets.get(0));
        byte[] changed = packets.get(0).payload();
        changed[0] ^= 1;
        deliver(client, new SyncProtocol.Packet(SyncProtocol.Kind.CHUNK, packets.get(0).request(), packets.get(0).digest(), 0, 2, changed));
        assertFalse(client.requestPending());
        assertEquals("REJECTED", text(outgoing.get(outgoing.size() - 1).payload()));
        for (var packet : packets) deliver(client, packet);
        assertEquals(0, validations.get());
        assertFalse(client.ready());
    }
}
