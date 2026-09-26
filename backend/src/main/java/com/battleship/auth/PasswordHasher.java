package com.battleship.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * PBKDF2-HMAC-SHA256 password hashing.
 *
 * Stored format: {@code pbkdf2_sha256$<iterations>$<saltB64>$<hashB64>} — the
 * cost parameter travels with the hash, so iteration counts can be raised
 * later without invalidating existing accounts.
 */
public final class PasswordHasher {

    private static final String ALGORITHM  = "PBKDF2WithHmacSHA256";
    private static final String PREFIX     = "pbkdf2_sha256";
    private static final int    ITERATIONS = 210_000;
    private static final int    SALT_BYTES = 16;
    private static final int    KEY_BITS   = 256;

    private final SecureRandom random = new SecureRandom();

    public String hash(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] key = derive(password, salt, ITERATIONS);
        Base64.Encoder enc = Base64.getEncoder().withoutPadding();
        return PREFIX + "$" + ITERATIONS + "$" + enc.encodeToString(salt)
                + "$" + enc.encodeToString(key);
    }

    /**
     * Constant-time verification against a stored hash. Returns false rather
     * than throwing on a malformed stored value.
     */
    public boolean verify(char[] password, String stored) {
        if (stored == null) return false;
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) return false;
        try {
            int iterations = Integer.parseInt(parts[1]);
            Base64.Decoder dec = Base64.getDecoder();
            byte[] salt     = dec.decode(parts[2]);
            byte[] expected = dec.decode(parts[3]);
            byte[] actual   = derive(password, salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }
}
