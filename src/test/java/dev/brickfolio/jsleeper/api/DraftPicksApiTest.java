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
import dev.brickfolio.jsleeper.model.Draft;
import dev.brickfolio.jsleeper.model.DraftPick;
import dev.brickfolio.jsleeper.model.DraftPickMetadata;
import dev.brickfolio.jsleeper.model.League;
import dev.brickfolio.jsleeper.model.TradedPick;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class DraftPicksApiTest {

    private static final String DRAFT_ID = "1391586807681945600";
    private static final String LEAGUE_ID = "1391586806968889344";

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    private final SleeperClient client = SleeperClient.builder().baseUri(wm.baseUrl() + "/v1").build();

    private void stub(String url, int status, byte[] body) {
        wm.stubFor(get(urlEqualTo(url)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    private void stub(String url, int status, String body) {
        stub(url, status, body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void recorded_picks_fixture_has_168_picks_and_a_fully_populated_first_pick() {
        stub("/v1/draft/D/picks", 200, Fixtures.bytes("draft_picks.json"));

        List<DraftPick> picks = client.drafts().picks("D");

        assertThat(picks).hasSize(168);
        DraftPick first = picks.get(0);
        assertThat(first.draftId()).isEqualTo(DRAFT_ID);
        assertThat(first.pickNo()).isEqualTo(1);
        assertThat(first.round()).isEqualTo(1);
        assertThat(first.draftSlot()).isEqualTo(1);
        assertThat(first.rosterId()).isEqualTo(3);
        assertThat(first.playerId()).isEqualTo("9221");
        assertThat(first.pickedBy()).isEqualTo("1134385218795892736");
        assertThat(first.isKeeper()).isNull();
        assertThat(first.metadata()).isEqualTo(new DraftPickMetadata("Jahmyr", "Gibbs", "RB", "DET", "0",
                "Active", "", "nfl", "9221", "1788366031161"));
        assertThat(picks).extracting(DraftPick::pickNo).isSorted();
    }

    @Test
    void edge_fixture_normal_pick_blank_picked_by_with_string_roster_and_omitted_fields() {
        stub("/v1/draft/D/picks", 200, Fixtures.bytes("edge_draft_picks.json"));

        List<DraftPick> picks = client.drafts().picks("D");

        assertThat(picks).hasSize(3);
        assertThat(picks.get(0).pickedBy()).isNotBlank();
        assertThat(picks.get(0).rosterId()).isNotNull();

        DraftPick blank = picks.get(1);
        assertThat(blank.pickedBy()).isNull();
        assertThat(blank.rosterId()).isNotNull();

        DraftPick sparse = picks.get(2);
        assertThat(sparse.rosterId()).isNull();
        assertThat(sparse.round()).isNull();
        assertThat(sparse.draftSlot()).isNull();
        assertThat(sparse.pickNo()).isEqualTo(3);
        assertThat(sparse.playerId()).isNotBlank();
    }

    @Test
    void string_roster_id_is_coerced_and_numeric_metadata_becomes_strings() {
        stub("/v1/draft/D/picks", 200,
                "[{\"pick_no\":2,\"roster_id\":\"10\",\"picked_by\":\"\","
                        + "\"metadata\":{\"number\":31,\"news_updated\":1513007102037,\"first_name\":\"David\"}}]");

        DraftPick p = client.drafts().picks("D").get(0);

        assertThat(p.rosterId()).isEqualTo(10);
        assertThat(p.pickedBy()).isNull();
        assertThat(p.metadata().number()).isEqualTo("31");
        assertThat(p.metadata().newsUpdated()).isEqualTo("1513007102037");
        assertThat(p.metadata().firstName()).isEqualTo("David");
    }

    @Test
    void is_keeper_json_key_maps_to_is_keeper() {
        stub("/v1/draft/D/picks", 200,
                "[{\"pick_no\":1,\"is_keeper\":true},{\"pick_no\":2,\"is_keeper\":false},{\"pick_no\":3}]");

        assertThat(client.drafts().picks("D")).extracting(DraftPick::isKeeper).containsExactly(true, false, null);
    }

    @Test
    void null_metadata_and_whitespace_picked_by_are_normalised() {
        stub("/v1/draft/D/picks", 200, "[{\"pick_no\":1,\"metadata\":null,\"picked_by\":\"  \"}]");

        DraftPick p = client.drafts().picks("D").get(0);

        assertThat(p.pickedBy()).isNull();
        assertThat(p.metadata().playerId()).isNull();
    }

    @Test
    void json_404_and_empty_array_on_picks_give_empty_list() {
        stub("/v1/draft/A/picks", 404, "null");
        stub("/v1/draft/B/picks", 200, "[]");

        assertThat(client.drafts().picks("A")).isEmpty();
        assertThat(client.drafts().picks("B")).isEmpty();
    }

    @Test
    void recorded_draft_traded_picks_fixture_is_empty() {
        stub("/v1/draft/D/traded_picks", 200, Fixtures.bytes("draft_traded_picks.json"));

        assertThat(client.drafts().tradedPicks("D")).isEmpty();
        wm.verify(getRequestedFor(urlEqualTo("/v1/draft/D/traded_picks")));
    }

    @Test
    void draft_traded_picks_with_an_inline_pick_deserialise_and_404_gives_empty() {
        stub("/v1/draft/D/traded_picks", 200,
                "[{\"season\":\"2019\",\"round\":5,\"roster_id\":1,\"previous_owner_id\":1,\"owner_id\":2}]");
        stub("/v1/draft/X/traded_picks", 404, "null");

        assertThat(client.drafts().tradedPicks("D")).containsExactly(new TradedPick("2019", 5, 1, 1, 2));
        assertThat(client.drafts().tradedPicks("X")).isEmpty();
    }

    @Test
    void blank_draft_id_is_rejected_before_any_request() {
        assertThatThrownBy(() -> client.drafts().picks(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.drafts().tradedPicks(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test
    void usage_example_draft_board_compiles_and_runs_against_recorded_fixtures() {
        stub("/v1/league/" + LEAGUE_ID, 200, Fixtures.bytes("league.json"));
        stub("/v1/draft/" + DRAFT_ID, 200, Fixtures.bytes("draft.json"));
        stub("/v1/draft/" + DRAFT_ID + "/picks", 200, Fixtures.bytes("draft_picks.json"));
        SleeperClient sleeper = client;
        String leagueId = LEAGUE_ID;
        List<String> out = new ArrayList<>();

        // Usage Examples section 7, as written (System.out.printf replaced by out.add(String.format(..))).
        League league = sleeper.leagues().get(leagueId).orElseThrow();
        Draft draft = sleeper.drafts().get(league.draftId()).orElseThrow();  // single GET fills slotToRosterId

        out.add(String.format("%s draft, %d rounds, %s%n",
                draft.type(), draft.settings().rounds(), draft.metadata().scoringType()));

        for (DraftPick pick : sleeper.drafts().picks(draft.draftId())) {
            DraftPickMetadata md = pick.metadata();
            out.add(String.format("%3d. (R%d.%02d) %s %s, %s %s%s%n",
                    pick.pickNo(), pick.round(), pick.draftSlot(),
                    md.firstName(), md.lastName(), md.position(), md.team(),
                    Boolean.TRUE.equals(pick.isKeeper()) ? "  [K]" : ""));
        }

        assertThat(out).hasSize(169);
        assertThat(out.get(0)).startsWith("SNAKE draft, 14 rounds, ppr");
        assertThat(out.get(1)).isEqualTo("  1. (R1.01) Jahmyr Gibbs, RB DET" + System.lineSeparator());
    }
}
