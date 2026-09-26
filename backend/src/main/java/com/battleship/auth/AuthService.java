package com.battleship.auth;

import com.battleship.db.User;
import com.battleship.db.UserRepository;
import com.battleship.util.Log;

import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

/** Registration and login. The only place that turns credentials into a JWT. */
public final class AuthService {

    private static final Log log = Log.of(AuthService.class);

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_.-]{3,20}$");
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 128;

    /** A dummy hash used to keep login timing identical for unknown usernames. */
    private final String decoyHash;

    private final UserRepository users;
    private final PasswordHasher hasher;
    private final JwtService jwt;

    public AuthService(UserRepository users, PasswordHasher hasher, JwtService jwt) {
        this.users     = users;
        this.hasher    = hasher;
        this.jwt       = jwt;
        this.decoyHash = hasher.hash("timing-equalizer-not-a-real-password".toCharArray());
    }

    public Session register(String username, char[] password) {
        try {
            validateUsername(username);
            validatePassword(password);
            String hash = hasher.hash(password);
            User user;
            try {
                user = users.create(username, hash);
            } catch (UserRepository.UsernameTakenException e) {
                throw AuthException.conflict("That username is already taken");
            }
            log.info("Registered user " + user.username() + " (id=" + user.id() + ")");
            return session(user);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    public Session login(String username, char[] password) {
        try {
            if (username == null || username.isBlank() || password == null || password.length == 0) {
                throw AuthException.badRequest("Username and password are required");
            }
            Optional<User> found = users.findByUsername(username.trim());

            // Always run the KDF, even for an unknown username, so response
            // time does not reveal which accounts exist.
            String storedHash = found.map(User::passwordHash).orElse(decoyHash);
            boolean ok = hasher.verify(password, storedHash);

            if (found.isEmpty() || !ok) {
                throw AuthException.unauthorized("Invalid username or password");
            }
            User user = found.get();
            users.touchLastSeen(user.id());
            return session(user);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private Session session(User user) {
        JwtService.IssuedToken issued = jwt.issue(user);
        return new Session(user, issued);
    }

    private static void validateUsername(String username) {
        if (username == null || !USERNAME.matcher(username.trim()).matches()) {
            throw AuthException.badRequest(
                    "Username must be 3-20 characters using letters, digits, dot, dash or underscore");
        }
    }

    private static void validatePassword(char[] password) {
        if (password == null || password.length < MIN_PASSWORD_LENGTH) {
            throw AuthException.badRequest(
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (password.length > MAX_PASSWORD_LENGTH) {
            throw AuthException.badRequest("Password must be at most "
                    + MAX_PASSWORD_LENGTH + " characters");
        }
    }

    /** A freshly authenticated user plus the token representing the session. */
    public record Session(User user, JwtService.IssuedToken token) {}
}
