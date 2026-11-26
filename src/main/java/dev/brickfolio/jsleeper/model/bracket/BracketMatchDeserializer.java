package dev.brickfolio.jsleeper.model.bracket;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;

/**
 * Reads a bracket row. A slot is {@code t1}/{@code t2} when that is an int (or a {@code {w|l: n}}
 * object), otherwise the {@code t1_from}/{@code t2_from} source, otherwise TBD. A roster ID beats
 * the source, which is kept separately as provenance.
 */
final class BracketMatchDeserializer extends JsonDeserializer<BracketMatch> {

    @Override
    public BracketMatch deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode row = p.readValueAsTree();
        if (!row.isObject()) {
            throw JsonMappingException.from(p, "bracket row must be an object, was " + row.getNodeType());
        }
        JsonNode from1 = row.get("t1_from");
        JsonNode from2 = row.get("t2_from");
        return new BracketMatch(
                requiredInt(row, "r", p),
                requiredInt(row, "m", p),
                slot(row.get("t1"), from1, "t1", p),
                slot(row.get("t2"), from2, "t2", p),
                source(from1, "t1_from", p),
                source(from2, "t2_from", p),
                optionalInt(row, "w", p),
                optionalInt(row, "l", p),
                optionalInt(row, "p", p));
    }

    private static BracketSlot slot(JsonNode direct, JsonNode from, String name, JsonParser p)
            throws JsonMappingException {
        if (isAbsent(direct)) {
            BracketSlot source = source(from, name + "_from", p);
            return source == null ? new BracketSlot.Tbd() : source;
        }
        if (direct.isIntegralNumber()) {
            return new BracketSlot.Team(direct.intValue());
        }
        if (direct.isObject()) {
            return reference(direct, name, p);
        }
        throw JsonMappingException.from(p, name + " must be an int or {w|l: n}, was " + direct.getNodeType());
    }

    private static BracketSlot source(JsonNode from, String name, JsonParser p) throws JsonMappingException {
        if (isAbsent(from)) {
            return null;
        }
        if (!from.isObject()) {
            throw JsonMappingException.from(p, name + " must be an object, was " + from.getNodeType());
        }
        return reference(from, name, p);
    }

    private static BracketSlot reference(JsonNode node, String name, JsonParser p) throws JsonMappingException {
        JsonNode winner = node.get("w");
        JsonNode loser = node.get("l");
        boolean hasWinner = winner != null && winner.isIntegralNumber();
        boolean hasLoser = loser != null && loser.isIntegralNumber();
        if (hasWinner == hasLoser) {
            throw JsonMappingException.from(p, name + " must hold exactly one integer w or l: " + node);
        }
        return hasWinner
                ? new BracketSlot.WinnerOf(winner.intValue())
                : new BracketSlot.LoserOf(loser.intValue());
    }

    private static int requiredInt(JsonNode row, String key, JsonParser p) throws JsonMappingException {
        Integer value = optionalInt(row, key, p);
        if (value == null) {
            throw JsonMappingException.from(p, "bracket row is missing integer field " + key);
        }
        return value;
    }

    private static Integer optionalInt(JsonNode row, String key, JsonParser p) throws JsonMappingException {
        JsonNode node = row.get(key);
        if (isAbsent(node)) {
            return null;
        }
        if (!node.isIntegralNumber()) {
            throw JsonMappingException.from(p, key + " must be an integer, was " + node.getNodeType());
        }
        return node.intValue();
    }

    private static boolean isAbsent(JsonNode node) {
        return node == null || node.isNull();
    }
}
