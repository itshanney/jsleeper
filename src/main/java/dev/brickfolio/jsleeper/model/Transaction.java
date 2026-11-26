package dev.brickfolio.jsleeper.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * A trade, free-agent move or waiver claim. Collections are never null.
 *
 * @param transactionId transaction ID
 * @param type kind of transaction
 * @param typeRaw wire value of {@code type}
 * @param status outcome
 * @param statusRaw wire value of {@code status}
 * @param leg week the transaction belongs to
 * @param creator user ID who initiated it
 * @param created creation time
 * @param statusUpdated time the status last changed
 * @param rosterIds rosters involved
 * @param consenterIds rosters that agreed
 * @param adds player ID to the roster that added them
 * @param drops player ID to the roster that dropped them
 * @param draftPicks picks that moved (trades)
 * @param waiverBudget FAAB moved between rosters (trades)
 * @param settings untyped settings; waiver claims carry {@code waiver_bid}
 * @param metadata untyped metadata, such as notes on why a waiver failed
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Transaction(
        String transactionId,
        TransactionType type,
        String typeRaw,
        TransactionStatus status,
        String statusRaw,
        Integer leg,
        String creator,
        Instant created,
        Instant statusUpdated,
        List<Integer> rosterIds,
        List<Integer> consenterIds,
        Map<String, Integer> adds,
        Map<String, Integer> drops,
        List<TradedPick> draftPicks,
        List<FaabTransfer> waiverBudget,
        Map<String, Object> settings,
        Map<String, Object> metadata) {

    private static final String WAIVER_BID_KEY = "waiver_bid";

    /** Normalises null collections to empty immutable ones. */
    public Transaction {
        rosterIds = Immutables.list(rosterIds);
        consenterIds = Immutables.list(consenterIds);
        adds = Immutables.map(adds);
        drops = Immutables.map(drops);
        draftPicks = Immutables.list(draftPicks);
        waiverBudget = Immutables.list(waiverBudget);
        settings = Immutables.map(settings);
        metadata = Immutables.map(metadata);
    }

    /** Returns the FAAB bid of a waiver claim from {@code settings.waiver_bid}, if present. */
    public OptionalInt waiverBid() {
        return settings.get(WAIVER_BID_KEY) instanceof Number bid
                ? OptionalInt.of(bid.intValue()) : OptionalInt.empty();
    }

    /** JSON entry point: derives each enum from its raw wire string. */
    @JsonCreator
    static Transaction fromJson(
            @JsonProperty("transaction_id") String transactionId,
            @JsonProperty("type") String typeRaw,
            @JsonProperty("status") String statusRaw,
            @JsonProperty("leg") Integer leg,
            @JsonProperty("creator") String creator,
            @JsonProperty("created") Instant created,
            @JsonProperty("status_updated") Instant statusUpdated,
            @JsonProperty("roster_ids") List<Integer> rosterIds,
            @JsonProperty("consenter_ids") List<Integer> consenterIds,
            @JsonProperty("adds") Map<String, Integer> adds,
            @JsonProperty("drops") Map<String, Integer> drops,
            @JsonProperty("draft_picks") List<TradedPick> draftPicks,
            @JsonProperty("waiver_budget") List<FaabTransfer> waiverBudget,
            @JsonProperty("settings") Map<String, Object> settings,
            @JsonProperty("metadata") Map<String, Object> metadata) {
        return new Transaction(transactionId,
                TransactionType.fromWire(typeRaw), typeRaw,
                TransactionStatus.fromWire(statusRaw), statusRaw,
                leg, creator, created, statusUpdated, rosterIds, consenterIds, adds, drops,
                draftPicks, waiverBudget, settings, metadata);
    }
}
