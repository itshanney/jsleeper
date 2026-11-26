package dev.brickfolio.jsleeper.api;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import dev.brickfolio.jsleeper.internal.EmptyKind;
import dev.brickfolio.jsleeper.internal.Requester;
import dev.brickfolio.jsleeper.model.Draft;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Draft endpoints. Stateless and thread-safe. */
public final class DraftsApi {

    private static final JavaType DRAFT_TYPE = TypeFactory.defaultInstance().constructType(Draft.class);

    private final Requester requester;

    /** For use by {@link dev.brickfolio.jsleeper.SleeperClient} only. */
    @SuppressWarnings("exports")
    public DraftsApi(Requester requester) {
        this.requester = Objects.requireNonNull(requester, "requester");
    }

    /**
     * Returns a draft ({@code /draft/{draftId}}), or empty if it does not exist. Unlike the list
     * endpoints, this response fills {@code slotToRosterId}.
     *
     * @throws IllegalArgumentException if {@code draftId} is null or blank
     */
    public Optional<Draft> get(String draftId) {
        Args.requireId("draftId", draftId);
        return requester.get(List.of("draft", draftId), Map.of(), DRAFT_TYPE, EmptyKind.OPTIONAL, null);
    }
}
