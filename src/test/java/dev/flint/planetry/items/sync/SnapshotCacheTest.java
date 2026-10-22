package dev.flint.planetry.items.sync;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotCacheTest {
    private static final int CACHE_HEADER_BYTES = 41;
    private static final int CACHE_LENGTH_OFFSET = 37;
    private static final int BUNDLE_HEADER_BYTES = 21;

    @TempDir Path data;

    @Test void missingCacheIsEmptyWithoutCreatingDirectories() throws Exception {
        Path file = data.resolve("missing").resolve(SnapshotCache.FILE_NAME);
        assertEquals(Optional.empty(), SnapshotCache.load(file));
        assertFalse(Files.exists(file.getParent()));
    }

    @Test void roundTripsBothRawDocumentsAndCreatesParentDirectory() throws Exception {
        Path file = data.resolve("nested/cache").resolve(SnapshotCache.FILE_NAME);
        var expected = snapshot("first");
        SnapshotCache.store(file, expected);

        assertSnapshot(expected, SnapshotCache.load(file).orElseThrow());
        assertArrayEquals(envelope(SyncProtocol.encodeSnapshot(expected)), Files.readAllBytes(file));
        assertOnlyEntries(file.getParent(), file);
    }

    @Test void preservesAuthorityGenerationAndRejectsChecksummedNegativeGenerationOrLegacySnapshot() throws Exception {
        Path file = data.resolve(SnapshotCache.FILE_NAME);
        var fixture = snapshot("authority");
        var expected = new SyncProtocol.Snapshot(Long.MAX_VALUE, fixture.items(), fixture.language());
        SnapshotCache.store(file, expected);
        assertSnapshot(expected, SnapshotCache.load(file).orElseThrow());
        for (long generation : new long[] {-1, Long.MIN_VALUE}) {
            byte[] invalid = SyncProtocol.encodeSnapshot(expected);
            ByteBuffer.wrap(invalid).putLong(5, generation);
            assertRejected(file, envelope(invalid), "negative generation " + generation);
        }
        byte[] legacy = ByteBuffer.allocate(13 + fixture.items().length + fixture.language().length)
                .putInt(0x5049534e).put((byte) 1).putInt(fixture.items().length).put(fixture.items())
                .putInt(fixture.language().length).put(fixture.language()).array();
        assertRejected(file, envelope(legacy), "legacy snapshot version");
    }

    @Test void atomicReplacementSwitchesBothDocumentsToTheSameGeneration() throws Exception {
        Path file = data.resolve(SnapshotCache.FILE_NAME);
        var previous = snapshot("old");
        var next = snapshot("new");
        SnapshotCache.store(file, previous);
        byte[] original = Files.readAllBytes(file);
        var replacements = new AtomicInteger();

        SnapshotCache.store(file, next, (source, target) -> {
            replacements.incrementAndGet();
            assertEquals(file.toAbsolutePath(), target);
            assertEquals(target.getParent(), source.getParent());
            assertNotEquals(target, source);
            assertArrayEquals(original, Files.readAllBytes(target));
            assertSnapshot(previous, SnapshotCache.load(target).orElseThrow());
            assertSnapshot(next, SnapshotCache.load(source).orElseThrow());
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            assertSnapshot(next, SnapshotCache.load(target).orElseThrow());
        });

        assertEquals(1, replacements.get());
        assertSnapshot(next, SnapshotCache.load(file).orElseThrow());
        assertArrayEquals(envelope(SyncProtocol.encodeSnapshot(next)), Files.readAllBytes(file));
        assertOnlyEntries(data, file);
    }

    @Test void failedAtomicReplacementPreservesExactPreviousCacheAndRemovesTemporaryFile() throws Exception {
        Path file = data.resolve(SnapshotCache.FILE_NAME);
        var previous = snapshot("old");
        var next = snapshot("new");
        SnapshotCache.store(file, previous);
        byte[] original = Files.readAllBytes(file);
        var temporary = new AtomicReference<Path>();
        var failure = new IOException("injected replacement failure");

        var thrown = assertThrows(IOException.class, () -> SnapshotCache.store(file, next, (source, target) -> {
            temporary.set(source);
            assertArrayEquals(original, Files.readAllBytes(target));
            assertSnapshot(next, SnapshotCache.load(source).orElseThrow());
            throw failure;
        }));

        assertSame(failure, thrown);
        assertNotNull(temporary.get(), "replacement hook must run");
        assertFalse(Files.exists(temporary.get(), LinkOption.NOFOLLOW_LINKS));
        assertArrayEquals(original, Files.readAllBytes(file));
        assertSnapshot(previous, SnapshotCache.load(file).orElseThrow());
        assertOnlyEntries(data, file);
    }

    @Test void failedFirstStoreLeavesNoCacheOrTemporaryFiles() throws Exception {
        Path file = data.resolve(SnapshotCache.FILE_NAME);
        var temporary = new AtomicReference<Path>();

        assertThrows(IOException.class, () -> SnapshotCache.store(file, snapshot("first"), (source, target) -> {
            temporary.set(source);
            assertTrue(Files.isRegularFile(source));
            assertFalse(Files.exists(target));
            throw new IOException("injected first-store failure");
        }));

        assertNotNull(temporary.get(), "replacement hook must run");
        assertFalse(Files.exists(temporary.get(), LinkOption.NOFOLLOW_LINKS));
        assertEquals(Optional.empty(), SnapshotCache.load(file));
        assertOnlyEntries(data);
    }

    @Test void unsupportedAtomicMovePreservesPreviousCacheAndDoesNotFallback() throws Exception {
        Path file = data.resolve(SnapshotCache.FILE_NAME);
        var previous = snapshot("old");
        SnapshotCache.store(file, previous);
        byte[] original = Files.readAllBytes(file);
        var temporary = new AtomicReference<Path>();
        var replacements = new AtomicInteger();

        assertThrows(AtomicMoveNotSupportedException.class,
                () -> SnapshotCache.store(file, snapshot("new"), (source, target) -> {
                    replacements.incrementAndGet();
                    temporary.set(source);
                    throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "injected");
                }));

        assertEquals(1, replacements.get());
        assertNotNull(temporary.get());
        assertFalse(Files.exists(temporary.get(), LinkOption.NOFOLLOW_LINKS));
        assertArrayEquals(original, Files.readAllBytes(file));
        assertSnapshot(previous, SnapshotCache.load(file).orElseThrow());
        assertOnlyEntries(data, file);
    }

    @Test void rejectsEveryTruncatedCache() throws Exception {
        Path file = data.resolve(SnapshotCache.FILE_NAME);
        byte[] valid = envelope(SyncProtocol.encodeSnapshot(snapshot("complete")));
        for (int length = 0; length < valid.length; length++)
            assertRejected(file, Arrays.copyOf(valid, length), "truncated cache length " + length);
    }

    @Test void rejectsBadCacheMagicAndVersion() throws Exception {
        Path file = data.resolve(SnapshotCache.FILE_NAME);
        byte[] valid = envelope(SyncProtocol.encodeSnapshot(snapshot("valid")));
        for (int offset : new int[] {0, 1, 2, 3, 4}) {
            byte[] invalid = valid.clone();
            invalid[offset] ^= (byte) 0xff;
            assertRejected(file, invalid, "invalid cache header byte " + offset);
        }
    }

    @Test void rejectsChecksumOrPayloadTampering() throws Exception {
        Path file = data.resolve(SnapshotCache.FILE_NAME);
        byte[] valid = envelope(SyncProtocol.encodeSnapshot(snapshot("valid")));
        for (int offset : new int[] {5, 36, valid.length - 1}) {
            byte[] invalid = valid.clone();
            invalid[offset] ^= 1;
            assertRejected(file, invalid, "tampered cache byte " + offset);
        }
    }

    @Test void rejectsAppendedCacheBytes() throws Exception {
        Path file = data.resolve(SnapshotCache.FILE_NAME);
        byte[] valid = envelope(SyncProtocol.encodeSnapshot(snapshot("valid")));
        for (int extra : new int[] {1, 8, CACHE_HEADER_BYTES})
            assertRejected(file, Arrays.copyOf(valid, valid.length + extra), "appended cache bytes " + extra);
    }

    @Test void rejectsInvalidDeclaredBundleLengths() throws Exception {
        Path file = data.resolve(SnapshotCache.FILE_NAME);
        byte[] valid = envelope(SyncProtocol.encodeSnapshot(snapshot("valid")));
        int actual = valid.length - CACHE_HEADER_BYTES;
        for (int length : new int[] {-1, Integer.MIN_VALUE, 0, actual - 1, actual + 1,
                SyncProtocol.MAX_SNAPSHOT_BYTES + 1, Integer.MAX_VALUE}) {
            byte[] invalid = valid.clone();
            ByteBuffer.wrap(invalid).putInt(CACHE_LENGTH_OFFSET, length);
            assertRejected(file, invalid, "declared cache length " + length);
        }
    }

    @Test void rejectsCorrectlyChecksummedMalformedBundleHeadersAndLengths() throws Exception {
        Path file = data.resolve(SnapshotCache.FILE_NAME);
        var snapshot = snapshot("valid");
        byte[] bundle = SyncProtocol.encodeSnapshot(snapshot);
        for (int offset : new int[] {0, 4}) {
            byte[] invalid = bundle.clone();
            invalid[offset] ^= (byte) 0xff;
            assertRejected(file, envelope(invalid), "invalid inner header byte " + offset);
        }
        for (int offset : new int[] {13, 17 + snapshot.items().length}) {
            int actual = ByteBuffer.wrap(bundle).getInt(offset);
            for (int length : new int[] {-1, Integer.MIN_VALUE, 0, actual - 1, actual + 1, Integer.MAX_VALUE}) {
                byte[] invalid = bundle.clone();
                ByteBuffer.wrap(invalid).putInt(offset, length);
                assertRejected(file, envelope(invalid), "inner length " + length + " at " + offset);
            }
        }
    }

    @Test void rejectsCorrectlyChecksummedEmptyDocuments() throws Exception {
        Path file = data.resolve(SnapshotCache.FILE_NAME);
        assertRejected(file, envelope(bundle(new byte[0], bytes("language: {}\n"))), "empty items");
        assertRejected(file, envelope(bundle(bytes("items: {}\n"), new byte[0])), "empty language");
        assertRejected(file, envelope(bundle(new byte[0], new byte[0])), "both documents empty");
    }

    @Test void rejectsCorrectlyChecksummedMalformedUtf8InEitherDocument() throws Exception {
        Path file = data.resolve(SnapshotCache.FILE_NAME);
        for (byte[] malformed : List.of(new byte[] {(byte) 0xff},
                new byte[] {(byte) 0xc0, (byte) 0x80},
                new byte[] {(byte) 0xc3, 0x28},
                new byte[] {(byte) 0xe2, (byte) 0x82},
                new byte[] {(byte) 0xed, (byte) 0xa0, (byte) 0x80})) {
            assertRejected(file, envelope(bundle(malformed, bytes("language: {}\n"))), "malformed UTF-8 items");
            assertRejected(file, envelope(bundle(bytes("items: {}\n"), malformed)), "malformed UTF-8 language");
        }
    }

    @Test void rejectsCorrectlyChecksummedTruncatedOrAppendedBundles() throws Exception {
        Path file = data.resolve(SnapshotCache.FILE_NAME);
        byte[] valid = SyncProtocol.encodeSnapshot(snapshot("complete"));
        for (int length = 0; length < valid.length; length++)
            assertRejected(file, envelope(Arrays.copyOf(valid, length)), "truncated inner bundle length " + length);
        assertRejected(file, envelope(Arrays.copyOf(valid, valid.length + 1)), "appended inner bundle byte");
    }

    @Test void roundTripsExactlyMaximumBundle() throws Exception {
        Path file = data.resolve(SnapshotCache.FILE_NAME);
        var largest = maximumSnapshot();
        assertEquals(SyncProtocol.MAX_SNAPSHOT_BYTES, SyncProtocol.encodeSnapshot(largest).length);

        SnapshotCache.store(file, largest);

        assertEquals((long) SyncProtocol.MAX_SNAPSHOT_BYTES + CACHE_HEADER_BYTES, Files.size(file));
        assertSnapshot(largest, SnapshotCache.load(file).orElseThrow());
        assertOnlyEntries(data, file);
    }

    @Test void rejectsOneByteOverMaximumOnDisk() throws Exception {
        Path file = data.resolve(SnapshotCache.FILE_NAME);
        SnapshotCache.store(file, maximumSnapshot());
        Files.write(file, new byte[] {0}, StandardOpenOption.APPEND);
        assertEquals((long) SyncProtocol.MAX_SNAPSHOT_BYTES + CACHE_HEADER_BYTES + 1, Files.size(file));
        assertThrows(IOException.class, () -> SnapshotCache.load(file));
    }

    @Test void rejectsOversizeDiskInputWithinSmallHeap() throws Exception {
        Path file = data.resolve(SnapshotCache.FILE_NAME);
        long diskSize = 64L * 1024 * 1024;
        // Sparse input exceeds the probe's heap without allocating that much test data.
        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
            channel.position(diskSize - 1);
            channel.write(ByteBuffer.wrap(new byte[] {0}));
        }
        assertEquals(diskSize, Files.size(file));
        Path output = data.resolve("oversize-probe.log");
        String classpath = String.join(java.io.File.pathSeparator,
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Path.of(SnapshotCacheTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                Path.of(SnapshotCache.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx32m", "-cp", classpath, OversizeCacheProbe.class.getName(), file.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "oversize cache probe timed out");
            assertEquals(0, process.exitValue(), Files.readString(output));
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
        assertEquals(diskSize, Files.size(file));
    }

    @Test void refusesSymlinkLoadsIncludingDanglingLinks() throws Exception {
        Path target = data.resolve("target.snapshot");
        SnapshotCache.store(target, snapshot("target"));
        byte[] original = Files.readAllBytes(target);
        Path existing = Files.createSymbolicLink(data.resolve(SnapshotCache.FILE_NAME), target);
        Path missing = data.resolve("missing.snapshot");
        Path dangling = Files.createSymbolicLink(data.resolve("dangling.snapshot"), missing);

        for (Path link : List.of(existing, dangling)) {
            assertThrows(IOException.class, () -> SnapshotCache.load(link));
            assertTrue(Files.isSymbolicLink(link));
        }
        assertArrayEquals(original, Files.readAllBytes(target));
        assertFalse(Files.exists(missing, LinkOption.NOFOLLOW_LINKS));
        assertOnlyEntries(data, target, existing, dangling);
    }

    @Test void refusesSymlinkStoresWithoutTouchingTargets() throws Exception {
        Path target = data.resolve("target.snapshot");
        SnapshotCache.store(target, snapshot("target"));
        byte[] original = Files.readAllBytes(target);
        Path existing = Files.createSymbolicLink(data.resolve(SnapshotCache.FILE_NAME), target);
        Path missing = data.resolve("missing.snapshot");
        Path dangling = Files.createSymbolicLink(data.resolve("dangling.snapshot"), missing);

        for (Path link : List.of(existing, dangling)) {
            assertThrows(IOException.class, () -> SnapshotCache.store(link, snapshot("replacement")));
            assertTrue(Files.isSymbolicLink(link));
        }
        assertEquals(target, Files.readSymbolicLink(existing));
        assertEquals(missing, Files.readSymbolicLink(dangling));
        assertArrayEquals(original, Files.readAllBytes(target));
        assertFalse(Files.exists(missing, LinkOption.NOFOLLOW_LINKS));
        assertOnlyEntries(data, target, existing, dangling);
    }

    @Test void rejectsDirectoryAsCacheSourceOrDestination() throws Exception {
        Path directory = Files.createDirectory(data.resolve(SnapshotCache.FILE_NAME));
        Path marker = Files.writeString(directory.resolve("keep.txt"), "preserve");
        assertThrows(IOException.class, () -> SnapshotCache.load(directory));
        assertThrows(IOException.class, () -> SnapshotCache.store(directory, snapshot("new")));
        assertEquals("preserve", Files.readString(marker));
        assertOnlyEntries(data, directory);
        assertOnlyEntries(directory, marker);
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static SyncProtocol.Snapshot snapshot(String generation) {
        return new SyncProtocol.Snapshot(
                bytes("# " + generation + "\r\nitems:\r\n  star:\r\n    display-name: 'Grüße 🌕'\r\n"),
                bytes("# " + generation + "\nmessages:\n  ready: 'Prêt ✓'\n"));
    }

    private static SyncProtocol.Snapshot maximumSnapshot() {
        return new SyncProtocol.Snapshot(bytes("x".repeat(SyncProtocol.MAX_SNAPSHOT_BYTES - BUNDLE_HEADER_BYTES - 1)),
                bytes("y"));
    }

    private static void assertSnapshot(SyncProtocol.Snapshot expected, SyncProtocol.Snapshot actual) {
        assertEquals(expected.generation(), actual.generation(), "authority generation");
        assertArrayEquals(expected.items(), actual.items(), "items document");
        assertArrayEquals(expected.language(), actual.language(), "language document");
    }

    private static void assertOnlyEntries(Path directory, Path... expected) throws IOException {
        try (var entries = Files.list(directory)) {
            assertEquals(Arrays.stream(expected).sorted().toList(), entries.sorted().toList());
        }
    }

    private static void assertRejected(Path file, byte[] encoded, String reason) throws IOException {
        Files.write(file, encoded);
        assertThrows(IOException.class, () -> SnapshotCache.load(file), reason);
    }

    /** Build malformed bundles without going through the validating Snapshot constructor. */
    private static byte[] bundle(byte[] items, byte[] language) {
        return ByteBuffer.allocate(BUNDLE_HEADER_BYTES + items.length + language.length)
                .putInt(0x5049534e).put((byte) 2).putLong(0).putInt(items.length).put(items)
                .putInt(language.length).put(language).array();
    }

    /** Keep the outer checksum valid so malformed inner bundles reach the decoder. */
    private static byte[] envelope(byte[] bundle) throws Exception {
        return ByteBuffer.allocate(CACHE_HEADER_BYTES + bundle.length)
                .putInt(0x50494348).put((byte) 1).put(MessageDigest.getInstance("SHA-256").digest(bundle))
                .putInt(bundle.length).put(bundle).array();
    }

    public static final class OversizeCacheProbe {
        public static void main(String[] args) throws IOException {
            try {
                SnapshotCache.load(Path.of(args[0]));
            } catch (IOException expected) {
                return;
            }
            throw new AssertionError("Oversize cache was accepted");
        }
    }
}
