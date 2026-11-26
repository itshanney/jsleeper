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
import dev.brickfolio.jsleeper.error.SleeperDeserializationException;
import dev.brickfolio.jsleeper.model.bracket.BracketMatch;
import dev.brickfolio.jsleeper.model.bracket.BracketSlot;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class BracketsApiTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    private final SleeperClient client = SleeperClient.builder().baseUri(wm.baseUrl() + "/v1").build();

    private void stub(String url, byte[] body) {
        wm.stubFor(get(urlEqualTo(url)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    // Usage Examples section 6, verbatim.
    static String describe(BracketSlot slot) {
        return switch (slot) {
            case BracketSlot.Team(int rosterId)   -> "Roster " + rosterId;
            case BracketSlot.WinnerOf(int match)  -> "Winner of M" + match;
            case BracketSlot.LoserOf(int match)   -> "Loser of M" + match;
            case BracketSlot.Tbd()                -> "TBD";
        };
    }

    @Test
    void recorded_winners_bracket_fixture_deserialises() {
        stub("/v1/league/L/winners_bracket", Fixtures.bytes("league_winners_bracket.json"));

        List<BracketMatch> rows = client.leagues().winnersBracket("L");

        assertThat(rows).hasSize(7);
        assertThat(rows.get(0)).isEqualTo(new BracketMatch(1, 1, new BracketSlot.Team(11),
                new BracketSlot.Team(12), null, null, null, null, null));
        BracketMatch m3 = rows.get(2);
        assertThat(m3.team1()).isEqualTo(new BracketSlot.Team(1));
        assertThat(m3.team2()).isEqualTo(new BracketSlot.WinnerOf(1));
        assertThat(m3.team1From()).isNull();
        BracketMatch m5 = rows.get(4);
        assertThat(m5.team1()).isEqualTo(new BracketSlot.LoserOf(1));
        assertThat(m5.team2()).isEqualTo(new BracketSlot.LoserOf(2));
        assertThat(m5.placement()).isEqualTo(5);
        assertThat(rows.get(5).placement()).isEqualTo(1);
        assertThat(rows.get(6).team1()).isEqualTo(new BracketSlot.LoserOf(3));
    }

    @Test
    void recorded_losers_bracket_fixture_deserialises_via_losers_bracket_path() {
        stub("/v1/league/L/losers_bracket", Fixtures.bytes("league_losers_bracket.json"));

        List<BracketMatch> rows = client.leagues().losersBracket("L");

        assertThat(rows).hasSize(7);
        assertThat(rows.get(0).team1()).isEqualTo(new BracketSlot.Team(5));
        assertThat(rows.get(5).team2()).isEqualTo(new BracketSlot.WinnerOf(4));
        wm.verify(getRequestedFor(urlEqualTo("/v1/league/L/losers_bracket")));
    }

    @Test
    void usage_example_describe_switch_is_exhaustive_over_every_slot() {
        stub("/v1/league/L/winners_bracket", Fixtures.bytes("league_winners_bracket.json"));
        List<String> lines = new ArrayList<>();

        for (BracketMatch m : client.leagues().winnersBracket("L")) {
            lines.add("R%d M%d: %s vs %s%s".formatted(
                    m.round(), m.matchId(), describe(m.team1()), describe(m.team2()),
                    m.winner() == null ? "" : "  -> winner roster " + m.winner()));
        }

        assertThat(lines).first().isEqualTo("R1 M1: Roster 11 vs Roster 12");
        assertThat(lines.get(2)).isEqualTo("R2 M3: Roster 1 vs Winner of M1");
        assertThat(describe(new BracketSlot.Tbd())).isEqualTo("TBD");
    }

    @Test
    void empty_bracket_gives_empty_list() {
        stub("/v1/league/L/winners_bracket", "[]".getBytes(StandardCharsets.UTF_8));

        assertThat(client.leagues().winnersBracket("L")).isEmpty();
    }

    @Test
    void unexpected_slot_shape_surfaces_as_deserialization_exception() {
        stub("/v1/league/L/winners_bracket",
                "[{\"r\":1,\"m\":1,\"t1\":{\"x\":1},\"t2\":2}]".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> client.leagues().winnersBracket("L"))
                .isExactlyInstanceOf(SleeperDeserializationException.class);
    }

    @Test
    void blank_league_id_is_rejected_before_any_request() {
        assertThatThrownBy(() -> client.leagues().winnersBracket(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.leagues().losersBracket(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(wm.getAllServeEvents()).isEmpty();
    }
}
