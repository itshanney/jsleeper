package dev.brickfolio.jsleeper.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.brickfolio.jsleeper.Fixtures;
import dev.brickfolio.jsleeper.SleeperClient;
import dev.brickfolio.jsleeper.model.FaabTransfer;
import dev.brickfolio.jsleeper.model.TradedPick;
import dev.brickfolio.jsleeper.model.Transaction;
import dev.brickfolio.jsleeper.model.TransactionStatus;
import dev.brickfolio.jsleeper.model.TransactionType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class TransactionsApiTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    private final SleeperClient client = SleeperClient.builder().baseUri(wm.baseUrl() + "/v1").build();

    private void stub(String url, byte[] body) {
        wm.stubFor(get(urlEqualTo(url)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    private void stub(String url, String body) {
        stub(url, body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void recorded_week_fixture_has_free_agent_and_waiver_types_and_failed_status() {
        stub("/v1/league/L/transactions/1", Fixtures.bytes("league_transactions_w1.json"));

        List<Transaction> txs = client.leagues().transactions("L", 1);

        assertThat(txs).hasSize(16);
        assertThat(txs).extracting(Transaction::type)
                .contains(TransactionType.FREE_AGENT, TransactionType.WAIVER)
                .doesNotContain(TransactionType.UNKNOWN);
        assertThat(txs).extracting(Transaction::status)
                .contains(TransactionStatus.COMPLETE, TransactionStatus.FAILED)
                .doesNotContain(TransactionStatus.UNKNOWN);
        assertThat(txs).allSatisfy(t -> {
            assertThat(t.typeRaw()).isNotBlank();
            assertThat(t.created()).isAfter(Instant.EPOCH);
            assertThat(t.leg()).isEqualTo(1);
        });
    }

    @Test
    void recorded_waiver_claims_expose_bid_and_maps() {
        stub("/v1/league/L/transactions/1", Fixtures.bytes("league_transactions_w1.json"));

        List<Transaction> txs = client.leagues().transactions("L", 1);

        Transaction first = txs.get(0);
        assertThat(first.type()).isEqualTo(TransactionType.WAIVER);
        assertThat(first.status()).isEqualTo(TransactionStatus.FAILED);
        assertThat(first.waiverBid()).hasValue(36);
        assertThat(first.adds()).containsExactly(java.util.Map.entry("12545", 9));
        assertThat(first.drops()).isEmpty();
        assertThat(first.rosterIds()).containsExactly(9);
        assertThat(first.creator()).isNotBlank();
        assertThat(first.metadata()).containsEntry("notes", "redacted");
        assertThat(txs).anySatisfy(t -> assertThat(t.drops()).containsEntry("DET", 9));
        assertThat(txs).anySatisfy(t -> assertThat(t.waiverBid()).hasValue(0));
        assertThat(txs).filteredOn(t -> t.type() == TransactionType.WAIVER)
                .allSatisfy(t -> assertThat(t.waiverBid()).isPresent());
    }

    @Test
    void edge_trade_fixture_has_picks_and_faab_transfer() {
        stub("/v1/league/L/transactions/1", Fixtures.bytes("edge_transaction_trade_faab.json"));

        Transaction trade = client.leagues().transactions("L", 1).get(0);

        assertThat(trade.type()).isEqualTo(TransactionType.TRADE);
        assertThat(trade.status()).isEqualTo(TransactionStatus.COMPLETE);
        assertThat(trade.rosterIds()).containsExactly(4, 9);
        assertThat(trade.consenterIds()).containsExactly(4, 9);
        assertThat(trade.draftPicks()).containsExactly(
                new TradedPick("2027", 1, 4, 4, 9), new TradedPick("2027", 3, 9, 9, 4));
        assertThat(trade.waiverBudget()).containsExactly(new FaabTransfer(4, 9, 55));
        assertThat(trade.adds()).isEmpty();
        assertThat(trade.drops()).isEmpty();
        assertThat(trade.settings()).isEmpty();
        assertThat(trade.metadata()).isEmpty();
        assertThat(trade.waiverBid()).isEmpty();
        assertThat(trade.created()).isEqualTo(Instant.ofEpochMilli(1789599000000L));
        assertThat(trade.statusUpdated()).isEqualTo(Instant.ofEpochMilli(1789600000000L));
    }

    @Test
    void unknown_type_and_status_map_to_unknown_and_keep_raw_values() {
        stub("/v1/league/L/transactions/2",
                "[{\"transaction_id\":\"1\",\"type\":\"commissioner\",\"status\":\"pending\"}]");

        Transaction t = client.leagues().transactions("L", 2).get(0);

        assertThat(t.type()).isEqualTo(TransactionType.UNKNOWN);
        assertThat(t.typeRaw()).isEqualTo("commissioner");
        assertThat(t.status()).isEqualTo(TransactionStatus.UNKNOWN);
        assertThat(t.statusRaw()).isEqualTo("pending");
    }

    @Test
    void waiver_bid_ignores_a_non_numeric_value() {
        stub("/v1/league/L/transactions/3",
                "[{\"transaction_id\":\"1\",\"settings\":{\"waiver_bid\":\"lots\"}}]");

        assertThat(client.leagues().transactions("L", 3).get(0).waiverBid()).isEmpty();
    }

    @Test
    void usage_example_transaction_loop_compiles_and_runs_against_the_recorded_week() {
        stub("/v1/league/L/transactions/1", Fixtures.bytes("league_transactions_w1.json"));
        List<String> out = new ArrayList<>();

        // Usage Examples section 5, as written (System.out replaced by a list).
        for (Transaction tx : client.leagues().transactions("L", 1)) {
            String summary = switch (tx.type()) {
                case TRADE      -> "Trade between rosters " + tx.rosterIds();
                case WAIVER     -> "Waiver claim" + tx.waiverBid().stream()
                                        .mapToObj(bid -> " ($" + bid + " FAAB)").findFirst().orElse("");
                case FREE_AGENT -> "Free-agent move";
                case UNKNOWN    -> "Unrecognised type: " + tx.typeRaw();
            };
            out.add("[%s] %s (%s)".formatted(tx.created(), summary, tx.status()));

            tx.adds().forEach((playerId, rosterId) ->
                    out.add("   + %s -> roster %d".formatted(playerId, rosterId)));
            tx.drops().forEach((playerId, rosterId) ->
                    out.add("   - %s <- roster %d".formatted(playerId, rosterId)));
            tx.draftPicks().forEach(p ->
                    out.add("   pick %s R%d: roster %d -> %d".formatted(
                            p.season(), p.round(), p.previousOwnerId(), p.ownerId())));
        }

        assertThat(out).anyMatch(l -> l.contains("Waiver claim ($36 FAAB) (FAILED)"));
        assertThat(out).anyMatch(l -> l.startsWith("   + "));
    }

    @Test
    void usage_example_loop_prints_pick_lines_for_the_trade() {
        stub("/v1/league/L/transactions/1", Fixtures.bytes("edge_transaction_trade_faab.json"));
        List<String> out = new ArrayList<>();

        for (Transaction tx : client.leagues().transactions("L", 1)) {
            tx.draftPicks().forEach(p ->
                    out.add("pick %s R%d: roster %d -> %d".formatted(
                            p.season(), p.round(), p.previousOwnerId(), p.ownerId())));
        }

        assertThat(out).containsExactly("pick 2027 R1: roster 4 -> 9", "pick 2027 R3: roster 9 -> 4");
    }

    @Test
    void transactions_path_carries_the_week_and_empty_array_gives_empty_list() {
        stub("/v1/league/L/transactions/18", "[]");

        assertThat(client.leagues().transactions("L", 18)).isEmpty();
        wm.verify(getRequestedFor(urlEqualTo("/v1/league/L/transactions/18")));
    }

    @Test
    void transactions_rejects_week_below_one_and_blank_league_before_any_request() {
        assertThatThrownBy(() -> client.leagues().transactions("L", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.leagues().transactions(" ", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.leagues().tradedPicks(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test
    void recorded_traded_picks_fixture_is_an_empty_list() {
        stub("/v1/league/L/traded_picks", Fixtures.bytes("league_traded_picks.json"));

        assertThat(client.leagues().tradedPicks("L")).isEmpty();
    }

    @Test
    void traded_picks_with_two_picks_deserialise() {
        stub("/v1/league/L/traded_picks",
                "[{\"season\":\"2019\",\"round\":5,\"roster_id\":1,\"previous_owner_id\":1,\"owner_id\":2},"
                        + "{\"season\":\"2020\",\"round\":3,\"roster_id\":2,\"previous_owner_id\":2,\"owner_id\":1}]");

        List<TradedPick> picks = client.leagues().tradedPicks("L");

        assertThat(picks).containsExactly(new TradedPick("2019", 5, 1, 1, 2), new TradedPick("2020", 3, 2, 2, 1));
    }
}
