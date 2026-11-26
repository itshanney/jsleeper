package dev.brickfolio.jsleeper.api;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import dev.brickfolio.jsleeper.internal.EmptyKind;
import dev.brickfolio.jsleeper.internal.Requester;
import dev.brickfolio.jsleeper.model.User;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** User endpoints. Stateless and thread-safe. */
public final class UsersApi {

    private static final JavaType USER_TYPE = TypeFactory.defaultInstance().constructType(User.class);

    private final Requester requester;

    /** For use by {@link dev.brickfolio.jsleeper.SleeperClient} only. */
    @SuppressWarnings("exports")
    public UsersApi(Requester requester) {
        this.requester = Objects.requireNonNull(requester, "requester");
    }

    /**
     * Looks up a user by username or user ID ({@code /user/{usernameOrId}}).
     *
     * <p>Usernames can change, so store {@link User#userId()}. An unknown user gives an empty
     * Optional, not an exception.
     *
     * @throws IllegalArgumentException if the argument is null or blank
     */
    public Optional<User> get(String usernameOrId) {
        if (usernameOrId == null || usernameOrId.isBlank()) {
            throw new IllegalArgumentException("usernameOrId must not be blank");
        }
        return requester.get(List.of("user", usernameOrId), Map.of(), USER_TYPE,
                EmptyKind.OPTIONAL, null);
    }
}
