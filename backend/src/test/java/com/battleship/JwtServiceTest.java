package com.battleship;

import com.battleship.auth.AuthenticatedUser;
import com.battleship.auth.JwtService;
import com.battleship.config.AppConfig;
import com.battleship.db.User;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {

    private static final String SECRET = "test-secret-that-is-definitely-long-enough-123";

    private static AppConfig config(String secret) {
        System.setProperty("JWT_SECRET", secret);
        System.setProperty("JWT_ISSUER", "battleship-test");
        return AppConfig.fromEnvironment();
    }

    private static User user() {
        return new User(42L, "admiral", "hash", 3, 1, Instant.now());
    }

    @Test
    void roundTripsAnIdentity() {
        JwtService jwt = new JwtService(config(SECRET));
        String token = jwt.issue(user()).token();

        Optional<AuthenticatedUser> verified = jwt.verify(token);
        assertTrue(verified.isPresent());
        assertEquals(42L, verified.get().userId());
        assertEquals("admiral", verified.get().username());
    }

    @Test
    void rejectsATokenSignedWithAnotherSecret() {
        String token = new JwtService(config(SECRET)).issue(user()).token();
        JwtService other = new JwtService(config("a-completely-different-secret-key-9876"));
        assertTrue(other.verify(token).isEmpty(), "signature must not validate");
    }

    @Test
    void rejectsGarbageWithoutThrowing() {
        JwtService jwt = new JwtService(config(SECRET));
        assertTrue(jwt.verify(null).isEmpty());
        assertTrue(jwt.verify("").isEmpty());
        assertTrue(jwt.verify("not.a.jwt").isEmpty());
    }

    @Test
    void parsesBearerHeadersCaseInsensitively() {
        assertEquals(Optional.of("abc"), JwtService.bearerToken("Bearer abc"));
        assertEquals(Optional.of("abc"), JwtService.bearerToken("bearer abc"));
        assertTrue(JwtService.bearerToken("Basic abc").isEmpty());
        assertTrue(JwtService.bearerToken("Bearer   ").isEmpty());
        assertTrue(JwtService.bearerToken(null).isEmpty());
    }
}
