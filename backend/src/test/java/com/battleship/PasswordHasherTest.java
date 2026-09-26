package com.battleship;

import com.battleship.auth.PasswordHasher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void verifiesACorrectPassword() {
        String stored = hasher.hash("correct horse battery".toCharArray());
        assertTrue(hasher.verify("correct horse battery".toCharArray(), stored));
    }

    @Test
    void rejectsAWrongPassword() {
        String stored = hasher.hash("correct horse battery".toCharArray());
        assertFalse(hasher.verify("correct horse batteries".toCharArray(), stored));
    }

    @Test
    void saltsEveryHashSeparately() {
        String a = hasher.hash("same-password".toCharArray());
        String b = hasher.hash("same-password".toCharArray());
        assertNotEquals(a, b, "identical passwords must not produce identical hashes");
        assertTrue(hasher.verify("same-password".toCharArray(), a));
        assertTrue(hasher.verify("same-password".toCharArray(), b));
    }

    @Test
    void rejectsMalformedStoredValuesInsteadOfThrowing() {
        assertFalse(hasher.verify("x".toCharArray(), null));
        assertFalse(hasher.verify("x".toCharArray(), ""));
        assertFalse(hasher.verify("x".toCharArray(), "not-a-hash"));
        assertFalse(hasher.verify("x".toCharArray(), "pbkdf2_sha256$abc$def$ghi"));
    }
}
