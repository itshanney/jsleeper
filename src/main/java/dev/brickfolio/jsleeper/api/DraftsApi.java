package dev.brickfolio.jsleeper.api;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import dev.brickfolio.jsleeper.internal.EmptyKind;
import dev.brickfolio.jsleeper.internal.Requester;
import dev.brickfolio.jsleeper.model.Draft;
import dev.brickfolio.jsleeper.model.DraftPick;
import dev.brickfolio.jsleeper.model.TradedPick;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Draft endpoints. Stateless and thread-safe. */
public final class DraftsApi {

    private static final TypeFactory TYPES = TypeFactory.defaultInstance();
    private static final JavaType DRAFT_TYPE = TYPES.constructType(Draft.class);
    private static final JavaType PICK_LIST_TYPE = TYPES.constructCollectionType(List.class, DraftPick.class);
    private static final JavaType TRADED_PICK_LIST_TYPE =
            TYPES.constructCollectionType(List.class, TradedPick.class);

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

    /**
     * Returns all picks of a draft ({@code /draft/{draftId}/picks}), or an empty list.
     *
     * @throws IllegalArgumentException if {@code draftId} is null or blank
     */
    public List<DraftPick> picks(String draftId) {
        Args.requireId("draftId", draftId);
        return requester.get(List.of("draft", draftId, "picks"), Map.of(), PICK_LIST_TYPE,
                EmptyKind.LIST, null);
    }

    /**
     * Returns the traded picks of a draft ({@code /draft/{draftId}/traded_picks}), or an empty
     * list.
     *
     * @throws IllegalArgumentException if {@code draftId} is null or blank
     */
    public List<TradedPick> tradedPicks(String draftId) {
        Args.requireId("draftId", draftId);
        return requester.get(List.of("draft", draftId, "traded_picks"), Map.of(),
                TRADED_PICK_LIST_TYPE, EmptyKind.LIST, null);
    }
}
