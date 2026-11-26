package dev.brickfolio.jsleeper.model.bracket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.brickfolio.jsleeper.internal.JsonCodec;
import dev.brickfolio.jsleeper.model.bracket.BracketSlot.LoserOf;
import dev.brickfolio.jsleeper.model.bracket.BracketSlot.Team;
import dev.brickfolio.jsleeper.model.bracket.BracketSlot.Tbd;
import dev.brickfolio.jsleeper.model.bracket.BracketSlot.WinnerOf;
import java.util.List;
import org.junit.jupiter.api.Test;

class BracketMatchTest {

    private static final String DOCS_EXAMPLE = """
            [{"r":1,"m":1,"t1":3,"t2":6,"w":null,"l":null},
             {"r":1,"m":2,"t1":4,"t2":5,"w":null,"l":null},
             {"r":2,"m":3,"t1":1,"t2":null,"t2_from":{"w":1},"w":null,"l":null},
             {"r":2,"m":4,"t1":2,"t2":null,"t2_from":{"w":2},"w":null,"l":null},
             {"r":2,"m":5,"t1":null,"t2":null,"t1_from":{"l":1},"t2_from":{"l":2},"w":null,"l":null,"p":5},
             {"r":3,"m":6,"t1":null,"t2":null,"t1_from":{"w":3},"t2_from":{"w":4},"w":null,"l":null,"p":1},
             {"r":3,"m":7,"t1":null,"t2":null,"t1_from":{"l":3},"t2_from":{"l":4},"w":null,"l":null,"p":3}]
            """;

    private final ObjectMapper mapper = JsonCodec.create();

    private BracketMatch one(String json) throws JsonProcessingException {
        return mapper.readValue(json, BracketMatch.class);
    }

    private List<BracketMatch> many(String json) throws JsonProcessingException {
        JavaType type = mapper.getTypeFactory().constructCollectionType(List.class, BracketMatch.class);
        return mapper.readValue(json, type);
    }

    @Test
    void docs_seven_row_example_deserialises() throws Exception {
        List<BracketMatch> rows = many(DOCS_EXAMPLE);

        assertThat(rows).hasSize(7);
        assertThat(rows.get(0).team1()).isEqualTo(new Team(3));
        assertThat(rows.get(0).team2()).isEqualTo(new Team(6));
        assertThat(rows.get(2).team1()).isEqualTo(new Team(1));
        assertThat(rows.get(2).team2()).isEqualTo(new WinnerOf(1));
        assertThat(rows.get(4).team1()).isEqualTo(new LoserOf(1));
        assertThat(rows.get(4).team2()).isEqualTo(new LoserOf(2));
        assertThat(rows.get(5).team1()).isEqualTo(new WinnerOf(3));
        assertThat(rows.get(6).team2()).isEqualTo(new LoserOf(4));
        assertThat(rows).extracting(BracketMatch::placement).containsExactly(null, null, null, null, 5, 1, 3);
        assertThat(rows).extracting(BracketMatch::round).containsExactly(1, 1, 2, 2, 2, 3, 3);
    }

    @Test
    void int_slots_become_teams_and_scalar_fields_map() throws Exception {
        BracketMatch m = one("{\"r\":1,\"m\":2,\"t1\":3,\"t2\":6,\"w\":3,\"l\":6,\"p\":1}");

        assertThat(m).isEqualTo(new BracketMatch(1, 2, new Team(3), new Team(6), null, null, 3, 6, 1));
    }

    @Test
    void slot_given_as_an_object_is_winner_or_loser_of() throws Exception {
        BracketMatch m = one("{\"r\":2,\"m\":3,\"t1\":{\"w\":1},\"t2\":{\"l\":1}}");

        assertThat(m.team1()).isEqualTo(new WinnerOf(1));
        assertThat(m.team2()).isEqualTo(new LoserOf(1));
        assertThat(m.team1From()).isNull();
    }

    @Test
    void null_slot_without_source_is_tbd() throws Exception {
        BracketMatch m = one("{\"r\":2,\"m\":3,\"t1\":null}");

        assertThat(m.team1()).isEqualTo(new Tbd());
        assertThat(m.team2()).isEqualTo(new Tbd());
    }

    @Test
    void roster_id_beats_source_but_provenance_is_kept() throws Exception {
        BracketMatch m = one("{\"r\":2,\"m\":3,\"t1\":5,\"t1_from\":{\"w\":1},\"t2\":8,\"t2_from\":{\"l\":2}}");

        assertThat(m.team1()).isEqualTo(new Team(5));
        assertThat(m.team1From()).isEqualTo(new WinnerOf(1));
        assertThat(m.team2()).isEqualTo(new Team(8));
        assertThat(m.team2From()).isEqualTo(new LoserOf(2));
    }

    @Test
    void source_is_also_exposed_when_it_supplies_the_slot() throws Exception {
        BracketMatch m = one("{\"r\":2,\"m\":3,\"t1\":null,\"t1_from\":{\"w\":1}}");

        assertThat(m.team1()).isEqualTo(new WinnerOf(1));
        assertThat(m.team1From()).isEqualTo(new WinnerOf(1));
    }

    @Test
    void unknown_extra_fields_are_ignored() throws Exception {
        assertThat(one("{\"r\":1,\"m\":1,\"t1\":1,\"t2\":2,\"surprise\":[1]}").matchId()).isEqualTo(1);
    }

    @Test
    void unexpected_slot_shapes_are_mapping_errors() {
        for (String bad : List.of(
                "{\"r\":1,\"m\":1,\"t1\":{\"x\":1}}",
                "{\"r\":1,\"m\":1,\"t1\":\"abc\"}",
                "{\"r\":1,\"m\":1,\"t1\":[1]}",
                "{\"r\":1,\"m\":1,\"t1\":{\"w\":1,\"l\":2}}",
                "{\"r\":1,\"m\":1,\"t1\":null,\"t1_from\":{\"x\":1}}",
                "{\"r\":1,\"m\":1,\"t1_from\":7}",
                "{\"r\":1,\"m\":1,\"w\":\"x\"}",
                "{\"m\":1}",
                "[1]")) {
            assertThatThrownBy(() -> one(bad)).as(bad).isInstanceOf(JsonMappingException.class);
        }
    }
}
