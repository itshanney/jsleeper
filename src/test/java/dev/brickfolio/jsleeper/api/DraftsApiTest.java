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
import dev.brickfolio.jsleeper.model.DraftStatus;
import dev.brickfolio.jsleeper.model.DraftType;
import dev.brickfolio.jsleeper.model.SeasonType;
import dev.brickfolio.jsleeper.model.Sport;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class DraftsApiTest {

    private static final String DRAFT_ID = "1391586807681945600";

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
    void recorded_single_draft_fixture_deserialises_with_slot_to_roster_id() {
        stub("/v1/draft/" + DRAFT_ID, 200, Fixtures.bytes("draft.json"));

        Draft d = client.drafts().get(DRAFT_ID).orElseThrow();

        assertThat(d.draftId()).isEqualTo(DRAFT_ID);
        assertThat(d.leagueId()).isEqualTo("1391586806968889344");
        assertThat(d.type()).isEqualTo(DraftType.SNAKE);
        assertThat(d.status()).isEqualTo(DraftStatus.COMPLETE);
        assertThat(d.statusRaw()).isEqualTo("complete");
        assertThat(d.sport()).isEqualTo(Sport.NFL);
        assertThat(d.season()).isEqualTo("2026");
        assertThat(d.seasonType()).isEqualTo(SeasonType.REGULAR);
        assertThat(d.startTime()).isEqualTo(Instant.ofEpochMilli(1788477614314L));
        assertThat(d.created()).isEqualTo(Instant.ofEpochMilli(1786142658669L));
        assertThat(d.lastPicked()).isEqualTo(Instant.ofEpochMilli(1788480843696L));
        assertThat(d.lastMessageTime()).isEqualTo(Instant.ofEpochMilli(1788480844534L));
        assertThat(d.lastMessageId()).isEqualTo("1401393869941145600");
        assertThat(d.creators()).containsExactly("1266175725602353152");
        assertThat(d.metadata().name()).isEqualTo("Test Draft");
        assertThat(d.metadata().description()).isEmpty();
        assertThat(d.metadata().scoringType()).isEqualTo("ppr");
        assertThat(d.draftOrder()).hasSize(12).containsEntry("1002321326117376000", 6);
        assertThat(d.slotToRosterId()).hasSize(12).containsEntry(1, 3).containsEntry(10, 1);
    }

    @Test
    void recorded_settings_are_typed_and_other_keys_land_in_extras() {
        stub("/v1/draft/" + DRAFT_ID, 200, Fixtures.bytes("draft.json"));

        var s = client.drafts().get(DRAFT_ID).orElseThrow().settings();

        assertThat(s.teams()).isEqualTo(12);
        assertThat(s.rounds()).isEqualTo(14);
        assertThat(s.pickTimer()).isEqualTo(60);
        assertThat(s.slotsQb()).isEqualTo(1);
        assertThat(s.slotsRb()).isEqualTo(2);
        assertThat(s.slotsWr()).isEqualTo(2);
        assertThat(s.slotsTe()).isEqualTo(1);
        assertThat(s.slotsFlex()).isEqualTo(1);
        assertThat(s.slotsK()).isEqualTo(1);
        assertThat(s.slotsDef()).isEqualTo(1);
        assertThat(s.slotsBn()).isEqualTo(5);
        assertThat(s.extras()).containsEntry("reversal_round", 0).containsEntry("cpu_autopick", 1)
                .containsEntry("autopause_start_time", 300).doesNotContainKeys("teams", "rounds", "slots_bn");
    }

    @Test
    void slots_super_flex_lands_in_extras() {
        stub("/v1/draft/1", 200,
                "{\"draft_id\":\"1\",\"settings\":{\"teams\":10,\"slots_super_flex\":1,\"slots_idp\":\"2\"}}");

        var s = client.drafts().get("1").orElseThrow().settings();

        assertThat(s.teams()).isEqualTo(10);
        assertThat(s.extras()).containsEntry("slots_super_flex", 1).containsEntry("slots_idp", "2");
    }

    @Test
    void numeric_string_settings_are_coerced_but_garbage_stays_in_extras() {
        stub("/v1/draft/1", 200, "{\"draft_id\":\"1\",\"settings\":{\"teams\":\"8\",\"rounds\":\"many\"}}");

        var s = client.drafts().get("1").orElseThrow().settings();

        assertThat(s.teams()).isEqualTo(8);
        assertThat(s.rounds()).isNull();
        assertThat(s.extras()).containsEntry("rounds", "many");
    }

    @Test
    void null_or_missing_settings_metadata_and_collections_become_safe_defaults() {
        stub("/v1/draft/1", 200,
                "{\"draft_id\":\"1\",\"settings\":null,\"metadata\":null,\"draft_order\":null,"
                        + "\"slot_to_roster_id\":null,\"creators\":null}");
        stub("/v1/draft/2", 200, "{\"draft_id\":\"2\"}");

        for (String id : List.of("1", "2")) {
            Draft d = client.drafts().get(id).orElseThrow();
            assertThat(d.settings().teams()).isNull();
            assertThat(d.settings().extras()).isEmpty();
            assertThat(d.metadata().name()).isNull();
            assertThat(d.draftOrder()).isEmpty();
            assertThat(d.slotToRosterId()).isEmpty();
            assertThat(d.creators()).isEmpty();
        }
    }

    @Test
    void league_drafts_fixture_leaves_slot_to_roster_id_empty() {
        stub("/v1/league/L/drafts", 200, Fixtures.bytes("league_drafts.json"));

        List<Draft> drafts = client.leagues().drafts("L");

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).draftId()).isEqualTo(DRAFT_ID);
        assertThat(drafts.get(0).slotToRosterId()).isEmpty();
        assertThat(drafts.get(0).draftOrder()).hasSize(12);
        assertThat(drafts.get(0).settings().rounds()).isEqualTo(14);
    }

    @Test
    void user_drafts_fixture_deserialises_including_null_draft_order() {
        stub("/v1/user/U/drafts/nfl/2026", 200, Fixtures.bytes("user_drafts.json"));

        List<Draft> drafts = client.users().drafts("U", Sport.NFL, 2026);

        assertThat(drafts).hasSize(2);
        assertThat(drafts).allSatisfy(d -> assertThat(d.slotToRosterId()).isEmpty());
        assertThat(drafts).anySatisfy(d -> assertThat(d.draftOrder()).isEmpty());
        wm.verify(getRequestedFor(urlEqualTo("/v1/user/U/drafts/nfl/2026")));
    }

    @Test
    void unknown_type_and_status_map_to_unknown_and_keep_raw_values() {
        stub("/v1/draft/1", 200, "{\"draft_id\":\"1\",\"type\":\"slow_auction\",\"status\":\"exploded\"}");

        Draft d = client.drafts().get("1").orElseThrow();

        assertThat(d.type()).isEqualTo(DraftType.UNKNOWN);
        assertThat(d.typeRaw()).isEqualTo("slow_auction");
        assertThat(d.status()).isEqualTo(DraftStatus.UNKNOWN);
        assertThat(d.statusRaw()).isEqualTo("exploded");
    }

    @Test
    void known_but_undocumented_types_and_paused_status_are_recognised() {
        stub("/v1/draft/1", 200, "{\"draft_id\":\"1\",\"type\":\"auction\",\"status\":\"paused\"}");

        Draft d = client.drafts().get("1").orElseThrow();

        assertThat(d.type()).isEqualTo(DraftType.AUCTION);
        assertThat(d.status()).isEqualTo(DraftStatus.PAUSED);
    }

    @Test
    void json_404_on_get_gives_empty_and_path_is_draft_id() {
        stub("/v1/draft/999", 404, "null");

        Optional<Draft> d = client.drafts().get("999");

        assertThat(d).isEmpty();
        wm.verify(getRequestedFor(urlEqualTo("/v1/draft/999")));
    }

    @Test
    void empty_lists_give_empty_lists() {
        stub("/v1/league/L/drafts", 200, "[]");
        stub("/v1/user/U/drafts/nfl/2025", 200, "[]");

        assertThat(client.leagues().drafts("L")).isEmpty();
        assertThat(client.users().drafts("U", Sport.NFL, 2025)).isEmpty();
    }

    @Test
    void bad_arguments_are_rejected_before_any_request() {
        assertThatThrownBy(() -> client.drafts().get(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.leagues().drafts(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.users().drafts("", Sport.NFL, 2026)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.users().drafts("U", Sport.UNKNOWN, 2026)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.users().drafts("U", Sport.NFL, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(wm.getAllServeEvents()).isEmpty();
    }
}
