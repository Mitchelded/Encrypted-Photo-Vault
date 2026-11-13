package io.github.epv.vault;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Android/JVM implementation of the EPV1 protocol. No Android-specific code here:
 * this same Java file can be compiled on desktop for cross-language verification.
 * Passwords and plaintext never leave the device through this class.
 */
public final class EpvCore {
    public static final int HEADER_LEN = 76;
    public static final int CHUNK_SIZE = 8 * 1024 * 1024;
    public static final int MAX_METADATA = 4096;
    public static final int ARGON2_MEMORY_KIB = 64 * 1024;
    public static final int ARGON2_ITERATIONS = 3;
    public static final int ARGON2_THREADS = 1;
    private static final SecureRandom RNG = new SecureRandom();
    private EpvCore() {}

    public static final class Header {
        public final byte[] raw, fileId, salt, nonce;
        public final int partIndex, partCount, cipherLength;
        private Header(byte[] raw, byte[] fileId, byte[] salt, byte[] nonce,
                       int partIndex, int partCount, int cipherLength) {
            this.raw = raw;
            this.fileId = fileId;
            this.salt = salt;
            this.nonce = nonce;
            this.partIndex = partIndex;
            this.partCount = partCount;
            this.cipherLength = cipherLength;
        }
        public String id() { return hex(fileId); }
    }

    public static final class Part {
        public final Header header;
        public final String metadataJson;
        public final byte[] data;
        private Part(Header header, String metadataJson, byte[] data) {
            this.header = header;
            this.metadataJson = metadataJson;
            this.data = data;
        }
    }

    public static byte[] random(int n) {
        byte[] result = new byte[n];
        RNG.nextBytes(result);
        return result;
    }

    public static String hex(byte[] bytes) {
        StringBuilder b = new StringBuilder(bytes.length * 2);
        for (byte x : bytes) b.append(String.format(java.util.Locale.ROOT, "%02x", x & 255));
        return b.toString();
    }

    public static Header readHeader(byte[] rgb) {
        if (rgb.length < HEADER_LEN) throw new IllegalArgumentException("Too small for EPV1 header");
        ByteBuffer b = ByteBuffer.wrap(rgb, 0, HEADER_LEN).order(ByteOrder.BIG_ENDIAN);
        byte[] magic = new byte[4]; b.get(magic);
        int version = b.get() & 255;
        int flags = b.get() & 255;
        int headerLength = b.getShort() & 65535;
        if (!Arrays.equals(magic, new byte[]{'E','P','V','1'}) || version != 1 || flags != 0 || headerLength != HEADER_LEN)
            throw new IllegalArgumentException("Not an EPV1 image");
        byte[] fileId = new byte[16]; b.get(fileId);
        int idx = b.getInt();
        int count = b.getInt();
        byte[] salt = new byte[16]; b.get(salt);
        byte[] nonce = new byte[12]; b.get(nonce);
        int cipherLength = b.getInt();
        int memory = b.getInt();
        int iterations = b.getInt();
        int threads = b.getInt();
        if (count < 1 || count > 1_000_000 || idx < 0 || idx >= count ||
            cipherLength < 16 || cipherLength > CHUNK_SIZE + MAX_METADATA + 20 ||
            HEADER_LEN + (long)cipherLength > rgb.length ||
            memory != ARGON2_MEMORY_KIB || iterations != ARGON2_ITERATIONS || threads != ARGON2_THREADS)
            throw new IllegalArgumentException("Invalid EPV1 header values");
        return new Header(Arrays.copyOf(rgb, HEADER_LEN), fileId, salt, nonce, idx, count, cipherLength);
    }

    public static byte[] deriveKey(String password, byte[] salt) {
        if (password == null || password.isEmpty()) throw new IllegalArgumentException("Empty password");
        if (salt.length != 16) throw new IllegalArgumentException("Invalid KDF salt");
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(salt)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(ARGON2_MEMORY_KIB)
                .withIterations(ARGON2_ITERATIONS)
                .withParallelism(ARGON2_THREADS).build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(params);
        byte[] bytes = password.getBytes(StandardCharsets.UTF_8);
        byte[] key = new byte[32];
        try { generator.generateBytes(bytes, key); }
        finally { Arrays.fill(bytes, (byte)0); }
        return key;
    }

    public static byte[] encryptPart(byte[] key, byte[] fileId, byte[] salt, int index,
                                     int count, String metadataJson, byte[] data) throws Exception {
        if (key.length != 32 || fileId.length != 16 || salt.length != 16 ||
            index < 0 || index >= count || count < 1 || count > 1_000_000 || data.length > CHUNK_SIZE)
            throw new IllegalArgumentException("Invalid encryption parameters");
        byte[] metadata = metadataJson.getBytes(StandardCharsets.UTF_8);
        if (metadata.length == 0 || metadata.length > MAX_METADATA)
            throw new IllegalArgumentException("Metadata must be 1..4096 bytes");
        ByteBuffer plaintext = ByteBuffer.allocate(4 + metadata.length + data.length).order(ByteOrder.BIG_ENDIAN);
        plaintext.putInt(metadata.length).put(metadata).put(data);
        byte[] nonce = random(12);
        ByteBuffer h = ByteBuffer.allocate(HEADER_LEN).order(ByteOrder.BIG_ENDIAN);
        h.put(new byte[]{'E','P','V','1'}).put((byte)1).put((byte)0).putShort((short)HEADER_LEN);
        h.put(fileId).putInt(index).putInt(count).put(salt).put(nonce);
        h.putInt(plaintext.array().length + 16);
        h.putInt(ARGON2_MEMORY_KIB).putInt(ARGON2_ITERATIONS).putInt(ARGON2_THREADS);
        byte[] headerBytes = h.array();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(headerBytes);
        byte[] encrypted = cipher.doFinal(plaintext.array());
        byte[] payload = new byte[HEADER_LEN + encrypted.length];
        System.arraycopy(headerBytes, 0, payload, 0, HEADER_LEN);
        System.arraycopy(encrypted, 0, payload, HEADER_LEN, encrypted.length);
        return payload;
    }

    public static Part decryptPart(byte[] key, byte[] rgb) throws Exception {
        Header h = readHeader(rgb);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, h.nonce));
        cipher.updateAAD(h.raw);
        byte[] plain = cipher.doFinal(rgb, HEADER_LEN, h.cipherLength);
        if (plain.length < 4) throw new IllegalArgumentException("Corrupt metadata length");
        ByteBuffer b = ByteBuffer.wrap(plain).order(ByteOrder.BIG_ENDIAN);
        int length = b.getInt();
        if (length <= 0 || length > MAX_METADATA || length > plain.length - 4)
            throw new IllegalArgumentException("Corrupt metadata");
        byte[] metadata = new byte[length]; b.get(metadata);
        byte[] data = new byte[b.remaining()]; b.get(data);
        return new Part(h, new String(metadata, StandardCharsets.UTF_8), data);
    }
}
