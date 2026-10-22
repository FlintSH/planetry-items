package dev.flint.planetry.items.sync;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Shared Java 17 wire contract. Keep this file identical on proxy and backend. */
public final class SyncProtocol {
    public static final String CHANNEL = "planetry:items_sync";
    public static final int MAX_SNAPSHOT_BYTES = 2_000_000;
    public static final int CHUNK_BYTES = 24_000;
    public static final int MAX_PACKET_BYTES = 25_000;
    public static final int MAX_CHUNKS = (MAX_SNAPSHOT_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES;
    private static final int PACKET_MAGIC = 0x50495359; // PISY
    private static final int SNAPSHOT_MAGIC = 0x5049534e; // PISN
    private static final int VERSION = 2;
    private static final int MAC_BYTES = 32;
    private static final int SNAPSHOT_HEADER_BYTES = 21;

    private SyncProtocol() {}

    public enum Kind { REQUEST, CURRENT, CHUNK, RESULT }

    public record Packet(Kind kind, UUID request, String digest, int index, int total, byte[] payload) {
        public Packet {
            if (kind == null || request == null || digest == null || payload == null) throw invalid("Missing packet field");
            if (payload.length > CHUNK_BYTES) throw invalid("Payload too large");
            payload = payload.clone();
            if (!validDigest(digest, kind == Kind.REQUEST || kind == Kind.RESULT))
                throw invalid("Invalid digest");
            if (kind == Kind.CHUNK) {
                if (total < 1 || total > MAX_CHUNKS || index < 0 || index >= total
                        || payload.length < 1 || payload.length > CHUNK_BYTES
                        || (index < total - 1 && payload.length != CHUNK_BYTES)
                        || (long) (total - 1) * CHUNK_BYTES + (index == total - 1 ? payload.length : 1) > MAX_SNAPSHOT_BYTES)
                    throw invalid("Invalid chunk");
            } else {
                if (index != 0 || total != 0) throw invalid("Unexpected chunk fields");
                if (kind == Kind.RESULT) {
                    if (payload.length > 128) throw invalid("Result too long");
                    String result = utf8(payload);
                    if (!result.equals("OK") && !result.equals("REJECTED")) throw invalid("Invalid result");
                } else if (payload.length != 0) throw invalid("Unexpected payload");
            }
        }
        @Override public byte[] payload() { return payload.clone(); }
    }

    /** Nonnegative authority generation and raw, nonempty, strictly encoded UTF-8 documents. */
    public record Snapshot(long generation, byte[] items, byte[] language) {
        public Snapshot {
            if (generation < 0) throw invalid("Invalid snapshot generation");
            if (items == null || language == null) throw invalid("Missing snapshot document");
            if (items.length == 0 || language.length == 0
                    || (long) items.length + language.length + SNAPSHOT_HEADER_BYTES > MAX_SNAPSHOT_BYTES)
                throw invalid("Invalid snapshot size");
            items = items.clone();
            language = language.clone();
            utf8(items);
            utf8(language);
        }
        public Snapshot(byte[] items, byte[] language) { this(0, items, language); }
        @Override public byte[] items() { return items.clone(); }
        @Override public byte[] language() { return language.clone(); }
    }

    public static void validateSecret(String secret) {
        if (secret == null || secret.length() < 32 || secret.length() > 256)
            throw invalid("Sync secret must contain 32 to 256 characters");
        // Unpaired surrogates must not silently become the same signing key.
        try {
            StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(secret));
        } catch (CharacterCodingException error) { throw invalid("Invalid sync secret encoding"); }
    }

    public static byte[] encode(Packet packet, String secret) {
        if (packet == null) throw invalid("Missing packet");
        validateSecret(secret);
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeInt(PACKET_MAGIC);
            out.writeByte(VERSION);
            out.writeByte(packet.kind.ordinal());
            out.writeLong(packet.request.getMostSignificantBits());
            out.writeLong(packet.request.getLeastSignificantBits());
            out.writeByte(packet.digest.length());
            out.write(packet.digest.getBytes(StandardCharsets.US_ASCII));
            out.writeInt(packet.index);
            out.writeInt(packet.total);
            out.writeInt(packet.payload.length);
            out.write(packet.payload);
            out.flush();
            byte[] body = bytes.toByteArray();
            out.write(mac(body, secret));
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > MAX_PACKET_BYTES) throw invalid("Packet too large");
            return encoded;
        } catch (IOException error) { throw new IllegalStateException(error); }
    }

    /** Authenticate before parsing any attacker-controlled lengths. */
    public static Packet decode(byte[] encoded, String secret) {
        validateSecret(secret);
        if (encoded == null || encoded.length < 35 + MAC_BYTES || encoded.length > MAX_PACKET_BYTES)
            throw invalid("Invalid packet size");
        byte[] body = Arrays.copyOf(encoded, encoded.length - MAC_BYTES);
        byte[] signature = Arrays.copyOfRange(encoded, body.length, encoded.length);
        if (!MessageDigest.isEqual(mac(body, secret), signature)) throw invalid("Invalid authentication");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(body));
            if (in.readInt() != PACKET_MAGIC || in.readUnsignedByte() != VERSION)
                throw invalid("Unknown protocol version");
            int kindId = in.readUnsignedByte();
            if (kindId >= Kind.values().length) throw invalid("Unknown packet kind");
            UUID request = new UUID(in.readLong(), in.readLong());
            int digestLength = in.readUnsignedByte();
            if (digestLength != 0 && digestLength != 64) throw invalid("Invalid digest length");
            String digest = new String(in.readNBytes(digestLength), StandardCharsets.US_ASCII);
            int index = in.readInt();
            int total = in.readInt();
            int length = in.readInt();
            if (length < 0 || length > CHUNK_BYTES || length != in.available())
                throw invalid("Invalid payload length");
            return new Packet(Kind.values()[kindId], request, digest, index, total, in.readNBytes(length));
        } catch (IOException error) { throw invalid("Truncated packet"); }
    }

    /** Versioned, length-prefixed bundle; its entire byte sequence is hashed. */
    public static byte[] encodeSnapshot(Snapshot snapshot) {
        if (snapshot == null) throw invalid("Missing snapshot");
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeInt(SNAPSHOT_MAGIC);
            out.writeByte(VERSION);
            out.writeLong(snapshot.generation);
            out.writeInt(snapshot.items.length);
            out.write(snapshot.items);
            out.writeInt(snapshot.language.length);
            out.write(snapshot.language);
            return bytes.toByteArray();
        } catch (IOException error) { throw new IllegalStateException(error); }
    }

    public static Snapshot decodeSnapshot(byte[] bundle) {
        if (bundle == null || bundle.length < SNAPSHOT_HEADER_BYTES + 2 || bundle.length > MAX_SNAPSHOT_BYTES)
            throw invalid("Invalid snapshot size");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bundle));
            if (in.readInt() != SNAPSHOT_MAGIC || in.readUnsignedByte() != VERSION)
                throw invalid("Unknown snapshot version");
            long generation = in.readLong();
            if (generation < 0) throw invalid("Invalid snapshot generation");
            int itemsLength = in.readInt();
            if (itemsLength < 1 || itemsLength > in.available() - 5) throw invalid("Invalid items length");
            byte[] items = in.readNBytes(itemsLength);
            int languageLength = in.readInt();
            if (languageLength < 1 || languageLength != in.available()) throw invalid("Invalid language length");
            return new Snapshot(generation, items, in.readNBytes(languageLength));
        } catch (IOException error) { throw invalid("Truncated snapshot"); }
    }

    public static String digest(Snapshot snapshot) { return digest(encodeSnapshot(snapshot)); }

    public static String digest(byte[] bytes) {
        if (bytes == null) throw invalid("Missing digest input");
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (GeneralSecurityException error) { throw new IllegalStateException(error); }
    }

    public static List<Packet> chunks(UUID request, Snapshot snapshot) {
        byte[] bundle = encodeSnapshot(snapshot);
        String digest = digest(bundle);
        int total = (bundle.length + CHUNK_BYTES - 1) / CHUNK_BYTES;
        var result = new ArrayList<Packet>(total);
        for (int index = 0; index < total; index++)
            result.add(new Packet(Kind.CHUNK, request, digest, index, total,
                    Arrays.copyOfRange(bundle, index * CHUNK_BYTES, Math.min(bundle.length, (index + 1) * CHUNK_BYTES))));
        return List.copyOf(result);
    }

    /** Bounded to one nonce and one digest. A rejected assembly cannot be reused. */
    public static final class Assembly {
        private final UUID request;
        private String digest;
        private byte[][] parts;
        private int received;
        private int size;
        private boolean finished;

        public Assembly(UUID request) {
            if (request == null) throw invalid("Missing assembly request");
            this.request = request;
        }

        public Optional<Snapshot> accept(Packet packet) {
            if (finished) throw invalid("Assembly is closed");
            try {
                if (packet == null || packet.kind != Kind.CHUNK || !request.equals(packet.request)) throw invalid("Unexpected chunk request");
                if (parts == null) {
                    digest = packet.digest;
                    parts = new byte[packet.total][];
                } else if (!digest.equals(packet.digest) || parts.length != packet.total)
                    throw invalid("Mixed snapshot chunks");
                byte[] previous = parts[packet.index];
                if (previous != null) {
                    if (!Arrays.equals(previous, packet.payload)) throw invalid("Conflicting duplicate chunk");
                    return Optional.empty();
                }
                if ((long) size + packet.payload.length > MAX_SNAPSHOT_BYTES) throw invalid("Snapshot too large");
                parts[packet.index] = packet.payload.clone();
                size += packet.payload.length;
                if (++received != parts.length) return Optional.empty();
                var bytes = new ByteArrayOutputStream(size);
                for (byte[] part : parts) bytes.writeBytes(part);
                byte[] bundle = bytes.toByteArray();
                if (!MessageDigest.isEqual(HexFormat.of().parseHex(digest), HexFormat.of().parseHex(digest(bundle))))
                    throw invalid("Snapshot digest mismatch");
                Snapshot snapshot = decodeSnapshot(bundle);
                finished = true;
                parts = null;
                return Optional.of(snapshot);
            } catch (IllegalArgumentException error) {
                finished = true;
                parts = null;
                throw error;
            }
        }
    }

    private static boolean validDigest(String digest, boolean allowEmpty) {
        if (allowEmpty && digest.isEmpty()) return true;
        if (digest.length() != 64) return false;
        for (int i = 0; i < digest.length(); i++) {
            char c = digest.charAt(i);
            if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) return false;
        }
        return true;
    }

    private static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) { throw invalid("Invalid UTF-8"); }
    }

    private static byte[] mac(byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(body);
        } catch (GeneralSecurityException error) { throw new IllegalStateException(error); }
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
