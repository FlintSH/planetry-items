package dev.flint.planetry.items.sync;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class SyncProtocolTest {
    private static final String SECRET = "sync-test-secret-32-characters-minimum";
    private static final UUID REQUEST = UUID.fromString("a57063b3-b425-4c63-a5d2-83dd35d8476c");
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static SyncProtocol.Snapshot snapshot(int size) {
        return new SyncProtocol.Snapshot(4_294_967_297L, bytes("i".repeat(size)), bytes("language: ä\n"));
    }
    private static SyncProtocol.Packet request() {
        return new SyncProtocol.Packet(SyncProtocol.Kind.REQUEST, REQUEST, "", 0, 0, new byte[0]);
    }

    @Test void roundTripsEveryPacketKindAndRawUtf8Snapshot() {
        var snapshot = snapshot(50_000);
        var packets = new ArrayList<>(SyncProtocol.chunks(REQUEST, snapshot));
        packets.add(request());
        packets.add(new SyncProtocol.Packet(SyncProtocol.Kind.REQUEST, REQUEST, SyncProtocol.digest(snapshot), 0, 0, new byte[0]));
        packets.add(new SyncProtocol.Packet(SyncProtocol.Kind.CURRENT, REQUEST, SyncProtocol.digest(snapshot), 0, 0, new byte[0]));
        packets.add(new SyncProtocol.Packet(SyncProtocol.Kind.RESULT, REQUEST, SyncProtocol.digest(snapshot), 0, 0, bytes("OK")));
        packets.add(new SyncProtocol.Packet(SyncProtocol.Kind.RESULT, REQUEST, "", 0, 0, bytes("REJECTED")));
        for (var packet : packets) {
            byte[] encoded = SyncProtocol.encode(packet, SECRET);
            assertTrue(encoded.length <= SyncProtocol.MAX_PACKET_BYTES);
            var decoded = SyncProtocol.decode(encoded, SECRET);
            assertEquals(packet.kind(), decoded.kind());
            assertEquals(packet.request(), decoded.request());
            assertEquals(packet.digest(), decoded.digest());
            assertEquals(packet.index(), decoded.index());
            assertEquals(packet.total(), decoded.total());
            assertArrayEquals(packet.payload(), decoded.payload());
        }
        var decoded = SyncProtocol.decodeSnapshot(SyncProtocol.encodeSnapshot(snapshot));
        assertEquals(snapshot.generation(), decoded.generation());
        assertArrayEquals(snapshot.items(), decoded.items());
        assertArrayEquals(snapshot.language(), decoded.language());
        assertEquals(SyncProtocol.digest(snapshot), SyncProtocol.digest(SyncProtocol.encodeSnapshot(snapshot)));
    }

    @Test void generationRoundTripsAcrossLongRangeAndChangesDigestWithoutChangingDocuments() {
        byte[] items = bytes("items: {}\n"), language = bytes("language: ä\n");
        var fixture = new SyncProtocol.Snapshot(items, language);
        assertEquals(0, fixture.generation());
        assertEquals(SyncProtocol.digest(new SyncProtocol.Snapshot(0, items, language)), SyncProtocol.digest(fixture));
        assertArrayEquals(SyncProtocol.encodeSnapshot(new SyncProtocol.Snapshot(0, items, language)), SyncProtocol.encodeSnapshot(fixture));
        String previousDigest = "";
        for (long generation : new long[] {0, 1, 4_294_967_297L, Long.MAX_VALUE}) {
            var snapshot = new SyncProtocol.Snapshot(generation, items, language);
            byte[] encoded = SyncProtocol.encodeSnapshot(snapshot);
            assertEquals(21 + items.length + language.length, encoded.length);
            assertEquals(2, encoded[4]);
            assertEquals(generation, ByteBuffer.wrap(encoded).getLong(5));
            var decoded = SyncProtocol.decodeSnapshot(encoded);
            assertEquals(generation, decoded.generation());
            assertArrayEquals(items, decoded.items());
            assertArrayEquals(language, decoded.language());
            String digest = SyncProtocol.digest(snapshot);
            assertNotEquals(previousDigest, digest);
            previousDigest = digest;
        }
    }

    @Test void snapshotEncodingMatchesIndependentVersionTwoWireFixture() {
        byte[] items = bytes("abc"), language = bytes("de");
        long generation = 0x0102030405060708L;
        byte[] expected = ByteBuffer.allocate(26).putInt(0x5049534e).put((byte) 2)
                .putLong(generation).putInt(3).put(items).putInt(2).put(language).array();
        assertArrayEquals(expected, SyncProtocol.encodeSnapshot(new SyncProtocol.Snapshot(generation, items, language)));
        assertEquals(generation, SyncProtocol.decodeSnapshot(expected).generation());
    }

    @Test void rejectsNegativeGenerationsLegacyVersionsAndGenerationTampering() throws Exception {
        for (long generation : new long[] {-1, Long.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new SyncProtocol.Snapshot(generation, bytes("items"), bytes("language")));
            byte[] encoded = SyncProtocol.encodeSnapshot(snapshot(5));
            ByteBuffer.wrap(encoded).putLong(5, generation);
            assertThrows(IllegalArgumentException.class, () -> SyncProtocol.decodeSnapshot(encoded));
        }
        byte[] bundle = SyncProtocol.encodeSnapshot(snapshot(5));
        bundle[4] = 1;
        assertThrows(IllegalArgumentException.class, () -> SyncProtocol.decodeSnapshot(bundle));
        byte[] packetBody = body(SyncProtocol.encode(request(), SECRET));
        assertEquals(2, packetBody[4]);
        packetBody[4] = 1;
        assertThrows(IllegalArgumentException.class, () -> SyncProtocol.decode(sign(packetBody), SECRET));

        var chunk = SyncProtocol.chunks(REQUEST, snapshot(5)).get(0);
        byte[] tampered = chunk.payload();
        ByteBuffer.wrap(tampered).putLong(5, 4_294_967_298L);
        var changedGeneration = new SyncProtocol.Packet(chunk.kind(), REQUEST, chunk.digest(), 0, 1, tampered);
        assertThrows(IllegalArgumentException.class, () -> new SyncProtocol.Assembly(REQUEST).accept(changedGeneration));
    }

    @Test void rejectsForgedHmacWrongSecretsAndOversizeBeforeParsing() {
        byte[] encoded = SyncProtocol.encode(request(), SECRET);
        encoded[7] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> SyncProtocol.decode(encoded, SECRET));
        assertThrows(IllegalArgumentException.class, () -> SyncProtocol.decode(SyncProtocol.encode(request(), SECRET), "a".repeat(32)));
        assertThrows(IllegalArgumentException.class, () -> SyncProtocol.decode(new byte[25_001], SECRET));
        assertThrows(IllegalArgumentException.class, () -> SyncProtocol.decode(new byte[66], SECRET));
        assertThrows(IllegalArgumentException.class, () -> SyncProtocol.decode(null, SECRET));
    }

    @Test void rejectsAuthenticatedUnknownHeadersLengthsAndTrailingBytes() throws Exception {
        byte[] body = body(SyncProtocol.encode(request(), SECRET));
        for (int offset : new int[] {0, 4, 5, 22}) {
            byte[] changed = body.clone();
            changed[offset] = (byte) 127;
            assertThrows(IllegalArgumentException.class, () -> SyncProtocol.decode(sign(changed), SECRET));
        }
        for (int offset : new int[] {23, 27, 31}) {
            byte[] changed = body.clone();
            ByteBuffer.wrap(changed).putInt(offset, -1);
            assertThrows(IllegalArgumentException.class, () -> SyncProtocol.decode(sign(changed), SECRET));
        }
        byte[] trailing = Arrays.copyOf(body, body.length + 1);
        assertThrows(IllegalArgumentException.class, () -> SyncProtocol.decode(sign(trailing), SECRET));
        byte[] truncated = Arrays.copyOf(body, body.length - 1);
        assertThrows(IllegalArgumentException.class, () -> SyncProtocol.decode(sign(truncated), SECRET));
    }

    @Test void validatesTypeSpecificFieldsAndCanonicalChunkBounds() {
        String digest = "a".repeat(64);
        assertThrows(IllegalArgumentException.class, () -> new SyncProtocol.Packet(SyncProtocol.Kind.CURRENT, REQUEST, "", 0, 0, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new SyncProtocol.Packet(SyncProtocol.Kind.REQUEST, REQUEST, digest, 1, 0, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new SyncProtocol.Packet(SyncProtocol.Kind.REQUEST, REQUEST, digest, 0, 0, bytes("bad")));
        assertThrows(IllegalArgumentException.class, () -> new SyncProtocol.Packet(SyncProtocol.Kind.REQUEST, REQUEST, "A".repeat(64), 0, 0, new byte[0]));
        for (String value : List.of("", "a".repeat(129), "Configuration password", "OK\n"))
            assertThrows(IllegalArgumentException.class, () -> new SyncProtocol.Packet(SyncProtocol.Kind.RESULT, REQUEST, digest, 0, 0, bytes(value)));
        assertThrows(IllegalArgumentException.class, () -> new SyncProtocol.Packet(SyncProtocol.Kind.RESULT, REQUEST, digest, 0, 0, new byte[] {(byte) 0xc0}));
        assertThrows(IllegalArgumentException.class, () -> new SyncProtocol.Packet(SyncProtocol.Kind.CHUNK, REQUEST, digest, 0, 0, new byte[1]));
        assertThrows(IllegalArgumentException.class, () -> new SyncProtocol.Packet(SyncProtocol.Kind.CHUNK, REQUEST, digest, 0, 85, new byte[24_000]));
        assertThrows(IllegalArgumentException.class, () -> new SyncProtocol.Packet(SyncProtocol.Kind.CHUNK, REQUEST, digest, 84, 84, new byte[1]));
        assertThrows(IllegalArgumentException.class, () -> new SyncProtocol.Packet(SyncProtocol.Kind.CHUNK, REQUEST, digest, 0, 2, new byte[23_999]));
        assertThrows(IllegalArgumentException.class, () -> new SyncProtocol.Packet(SyncProtocol.Kind.CHUNK, REQUEST, digest, 83, 84, new byte[8_001]));
        assertThrows(IllegalArgumentException.class, () -> new SyncProtocol.Packet(SyncProtocol.Kind.CHUNK, REQUEST, digest, 0, 1, new byte[24_001]));
    }

    @Test void secretBoundsAndEncodingAreStrictWithoutIncludingSecretInErrors() {
        for (String secret : List.of("a".repeat(31), "a".repeat(257), "a".repeat(31) + '\ud800')) {
            var error = assertThrows(IllegalArgumentException.class, () -> SyncProtocol.encode(request(), secret));
            assertFalse(error.getMessage().contains(secret));
        }
        for (String secret : List.of("a".repeat(32), "a".repeat(256), "ü".repeat(32)))
            assertEquals(REQUEST, SyncProtocol.decode(SyncProtocol.encode(request(), secret), secret).request());
    }

    @Test void snapshotRejectsMalformedUtf8EmptyOversizedAndCorruptFraming() {
        assertThrows(IllegalArgumentException.class, () -> new SyncProtocol.Snapshot(new byte[0], bytes("ok")));
        assertThrows(IllegalArgumentException.class, () -> new SyncProtocol.Snapshot(bytes("ok"), new byte[] {(byte) 0xff}));
        assertThrows(IllegalArgumentException.class, () -> new SyncProtocol.Snapshot(bytes("ok"), new byte[] {(byte) 0xc0, (byte) 0x80}));
        assertThrows(IllegalArgumentException.class, () -> new SyncProtocol.Snapshot(new byte[2_000_000], bytes("ok")));
        byte[] encoded = SyncProtocol.encodeSnapshot(snapshot(5));
        byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(IllegalArgumentException.class, () -> SyncProtocol.decodeSnapshot(trailing));
        for (int offset : new int[] {0, 4, 5, 13, 22}) {
            byte[] invalid = encoded.clone();
            invalid[offset] = (byte) 0xff;
            assertThrows(IllegalArgumentException.class, () -> SyncProtocol.decodeSnapshot(invalid));
        }
        for (int length = 0; length < encoded.length; length++) {
            byte[] truncated = Arrays.copyOf(encoded, length);
            assertThrows(IllegalArgumentException.class, () -> SyncProtocol.decodeSnapshot(truncated));
        }
    }

    @Test void acceptsExactlyMaximumBundleAndOutOfOrderLastChunkFirst() {
        var largest = new SyncProtocol.Snapshot(bytes("x".repeat(1_999_978)), bytes("y"));
        byte[] bundle = SyncProtocol.encodeSnapshot(largest);
        assertEquals(2_000_000, bundle.length);
        assertThrows(IllegalArgumentException.class,
                () -> new SyncProtocol.Snapshot(bytes("x".repeat(1_999_979)), bytes("y")));
        var packets = new ArrayList<>(SyncProtocol.chunks(REQUEST, largest));
        assertEquals(84, packets.size());
        Collections.reverse(packets);
        var assembly = new SyncProtocol.Assembly(REQUEST);
        for (int i = 0; i < packets.size() - 1; i++) assertTrue(assembly.accept(packets.get(i)).isEmpty());
        var result = assembly.accept(packets.get(packets.size() - 1)).orElseThrow();
        assertEquals(largest.generation(), result.generation());
        assertArrayEquals(largest.items(), result.items());
        assertArrayEquals(largest.language(), result.language());
        assertThrows(IllegalArgumentException.class, () -> assembly.accept(packets.get(0)));
    }

    @Test void duplicatesAreHarmlessUntilConflictingAndRejectedAssemblyCannotResume() {
        var packets = SyncProtocol.chunks(REQUEST, snapshot(40_000));
        var assembly = new SyncProtocol.Assembly(REQUEST);
        assertTrue(assembly.accept(packets.get(0)).isEmpty());
        assertTrue(assembly.accept(packets.get(0)).isEmpty());
        byte[] conflict = packets.get(0).payload();
        conflict[0] ^= 1;
        var duplicate = new SyncProtocol.Packet(SyncProtocol.Kind.CHUNK, REQUEST, packets.get(0).digest(), 0, 2, conflict);
        assertThrows(IllegalArgumentException.class, () -> assembly.accept(duplicate));
        assertThrows(IllegalArgumentException.class, () -> assembly.accept(packets.get(1)));
    }

    @Test void mixedRequestsDigestsTotalsAndWrongFinalDigestAreRejected() {
        var packets = SyncProtocol.chunks(REQUEST, snapshot(40_000));
        var first = packets.get(0);
        var changed = List.of(
                new SyncProtocol.Packet(SyncProtocol.Kind.CHUNK, UUID.randomUUID(), first.digest(), 0, 2, first.payload()),
                new SyncProtocol.Packet(SyncProtocol.Kind.CHUNK, REQUEST, "a".repeat(64), 0, 2, first.payload()),
                new SyncProtocol.Packet(SyncProtocol.Kind.CHUNK, REQUEST, first.digest(), 0, 3, first.payload()));
        for (var packet : changed) {
            var assembly = new SyncProtocol.Assembly(REQUEST);
            assembly.accept(first);
            assertThrows(IllegalArgumentException.class, () -> assembly.accept(packet));
        }
        var one = SyncProtocol.chunks(REQUEST, snapshot(10)).get(0);
        var wrongDigest = new SyncProtocol.Packet(SyncProtocol.Kind.CHUNK, REQUEST, "a".repeat(64), 0, 1, one.payload());
        assertThrows(IllegalArgumentException.class, () -> new SyncProtocol.Assembly(REQUEST).accept(wrongDigest));
    }

    @Test void recordsAndAssemblyDefendAgainstCallerArrayMutation() {
        byte[] items = bytes("items: {}\n"), language = bytes("messages: {}\n");
        var snapshot = new SyncProtocol.Snapshot(items, language);
        String expected = SyncProtocol.digest(snapshot);
        items[0] = 0;
        language[0] = 0;
        snapshot.items()[0] = 0;
        snapshot.language()[0] = 0;
        assertEquals(expected, SyncProtocol.digest(snapshot));
        var chunks = SyncProtocol.chunks(REQUEST, snapshot);
        var packet = chunks.get(0);
        byte[] payload = packet.payload();
        var copied = new SyncProtocol.Packet(packet.kind(), packet.request(), packet.digest(), packet.index(), packet.total(), payload);
        payload[0] = 0;
        copied.payload()[0] = 0;
        assertTrue(new SyncProtocol.Assembly(REQUEST).accept(copied).isPresent());
    }

    @Test void authenticatedRandomEnvelopesDoNotEscapeParserBounds() throws Exception {
        var random = new Random(2219);
        for (int i = 0; i < 300; i++) {
            byte[] body = new byte[35 + random.nextInt(1000)];
            random.nextBytes(body);
            byte[] signed = sign(body);
            assertThrows(IllegalArgumentException.class, () -> SyncProtocol.decode(signed, SECRET));
        }
    }

    @Test void matchingDigestCannotBypassStrictBundleParserAndNullInputsFailConsistently() {
        byte[] malformed = SyncProtocol.encodeSnapshot(snapshot(10));
        ByteBuffer.wrap(malformed).putInt(13, Integer.MAX_VALUE);
        var packet = new SyncProtocol.Packet(SyncProtocol.Kind.CHUNK, REQUEST, SyncProtocol.digest(malformed), 0, 1, malformed);
        assertThrows(IllegalArgumentException.class, () -> new SyncProtocol.Assembly(REQUEST).accept(packet));
        assertThrows(IllegalArgumentException.class, () -> new SyncProtocol.Packet(null, REQUEST, "", 0, 0, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new SyncProtocol.Packet(SyncProtocol.Kind.REQUEST, null, "", 0, 0, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new SyncProtocol.Packet(SyncProtocol.Kind.REQUEST, REQUEST, null, 0, 0, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new SyncProtocol.Packet(SyncProtocol.Kind.REQUEST, REQUEST, "", 0, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new SyncProtocol.Snapshot(null, bytes("language")));
        assertThrows(IllegalArgumentException.class, () -> SyncProtocol.encode(null, SECRET));
        assertThrows(IllegalArgumentException.class, () -> SyncProtocol.digest((byte[]) null));
        assertThrows(IllegalArgumentException.class, () -> SyncProtocol.encodeSnapshot(null));
        assertThrows(IllegalArgumentException.class, () -> new SyncProtocol.Assembly(null));
        assertThrows(IllegalArgumentException.class, () -> new SyncProtocol.Assembly(REQUEST).accept(null));
    }

    private static byte[] body(byte[] encoded) { return Arrays.copyOf(encoded, encoded.length - 32); }
    private static byte[] sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(bytes(SECRET), "HmacSHA256"));
        byte[] encoded = Arrays.copyOf(body, body.length + 32);
        System.arraycopy(mac.doFinal(body), 0, encoded, body.length, 32);
        return encoded;
    }
}
