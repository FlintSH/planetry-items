package dev.flint.planetry.items.sync;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/** One checksummed file keeps item definitions and language in the same generation. */
public final class SnapshotCache {
    public static final String FILE_NAME = "proxy-cache.snapshot";
    private static final int MAGIC = 0x50494348; // PICH
    private static final byte VERSION = 1;
    private static final int HEADER_BYTES = 41;

    private SnapshotCache() {}

    /** Missing is normal; malformed, oversized and symbolic-link caches fail closed. */
    public static Optional<SyncProtocol.Snapshot> load(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid snapshot cache file");
        // Bound the actual read as well as the initial size: a growing file cannot exhaust memory.
        try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] encoded = in.readNBytes(SyncProtocol.MAX_SNAPSHOT_BYTES + HEADER_BYTES + 1);
            if (encoded.length <= HEADER_BYTES || encoded.length > SyncProtocol.MAX_SNAPSHOT_BYTES + HEADER_BYTES)
                throw new IOException("Invalid snapshot cache size");
            ByteBuffer buffer = ByteBuffer.wrap(encoded);
            if (buffer.getInt() != MAGIC || buffer.get() != VERSION) throw new IOException("Invalid snapshot cache version");
            byte[] expected = new byte[32];
            buffer.get(expected);
            int size = buffer.getInt();
            if (size != buffer.remaining()) throw new IOException("Invalid snapshot cache length");
            byte[] bundle = new byte[size];
            buffer.get(bundle);
            byte[] actual = HexFormat.of().parseHex(SyncProtocol.digest(bundle));
            if (!MessageDigest.isEqual(expected, actual)) throw new IOException("Invalid snapshot cache checksum");
            try { return Optional.of(SyncProtocol.decodeSnapshot(bundle)); }
            catch (IllegalArgumentException error) { throw new IOException("Invalid snapshot cache bundle"); }
        }
    }

    public static void store(Path file, SyncProtocol.Snapshot snapshot) throws IOException {
        store(file, snapshot, (source, target) -> Files.move(source, target,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }

    @FunctionalInterface interface AtomicReplacement { void replace(Path source, Path target) throws IOException; }

    /** Injectable replacement only for exercising failure before the atomic commit. */
    static void store(Path file, SyncProtocol.Snapshot snapshot, AtomicReplacement replacement) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(replacement, "replacement");
        byte[] bundle = SyncProtocol.encodeSnapshot(snapshot);
        ByteBuffer encoded = ByteBuffer.allocate(HEADER_BYTES + bundle.length);
        encoded.putInt(MAGIC).put(VERSION).put(HexFormat.of().parseHex(SyncProtocol.digest(bundle)))
                .putInt(bundle.length).put(bundle).flip();
        Path absolute = file.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invalid snapshot cache destination");
        Path temporary = Files.createTempFile(absolute.getParent(), ".proxy-cache-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                while (encoded.hasRemaining()) channel.write(encoded);
                channel.force(true);
            }
            // Unsupported atomic replacement rejects this update; never truncate the last good file.
            replacement.replace(temporary, absolute);
        } finally { Files.deleteIfExists(temporary); }
    }
}
