package com.battleship.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.battleship.config.AppConfig;
import com.battleship.db.User;

import java.time.Instant;
import java.util.Optional;

/**
 * Issues and verifies HS256 session tokens.
 *
 * The same token authenticates both the REST endpoints (Authorization header)
 * and the WebSocket upgrade (?token= query parameter, since browsers cannot
 * set headers on a WebSocket handshake). Verification is stateless, so any
 * server instance can accept a token minted by any other.
 */
public final class JwtService {

    private final Algorithm   algorithm;
    private final JWTVerifier verifier;
    private final String      issuer;
    private final long        ttlSeconds;

    public JwtService(AppConfig config) {
        this.algorithm  = Algorithm.HMAC256(config.jwtSecret());
        this.issuer     = config.jwtIssuer();
        this.ttlSeconds = config.jwtTtlSeconds();
        this.verifier   = JWT.require(algorithm)
                .withIssuer(issuer)
                .acceptLeeway(5)
                .build();
    }

    public IssuedToken issue(User user) {
        Instant now       = Instant.now();
        Instant expiresAt = now.plusSeconds(ttlSeconds);
        String token = JWT.create()
                .withIssuer(issuer)
                .withSubject(Long.toString(user.id()))
                .withClaim("username", user.username())
                .withIssuedAt(now)
                .withExpiresAt(expiresAt)
                .sign(algorithm);
        return new IssuedToken(token, expiresAt, ttlSeconds);
    }

    /** Returns the identity in the token, or empty if it is invalid or expired. */
    public Optional<AuthenticatedUser> verify(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        try {
            DecodedJWT jwt = verifier.verify(token.trim());
            long userId = Long.parseLong(jwt.getSubject());
            String username = jwt.getClaim("username").asString();
            return Optional.of(new AuthenticatedUser(userId, username));
        } catch (JWTVerificationException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Extracts a bearer token from an Authorization header value. */
    public static Optional<String> bearerToken(String authorizationHeader) {
        if (authorizationHeader == null) return Optional.empty();
        String prefix = "Bearer ";
        if (authorizationHeader.regionMatches(true, 0, prefix, 0, prefix.length())) {
            String token = authorizationHeader.substring(prefix.length()).trim();
            return token.isEmpty() ? Optional.empty() : Optional.of(token);
        }
        return Optional.empty();
    }

    public record IssuedToken(String token, Instant expiresAt, long expiresInSeconds) {}
}
