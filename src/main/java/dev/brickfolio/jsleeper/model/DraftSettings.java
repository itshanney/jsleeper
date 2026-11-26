package dev.brickfolio.jsleeper.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Draft settings. The documented keys are typed; every other key (for example
 * {@code slots_super_flex} or {@code reversal_round}) is kept in {@code extras} under its wire name.
 *
 * @param teams number of teams
 * @param rounds number of rounds
 * @param pickTimer seconds per pick
 * @param slotsQb quarterback slots
 * @param slotsRb running back slots
 * @param slotsWr wide receiver slots
 * @param slotsTe tight end slots
 * @param slotsFlex flex slots
 * @param slotsK kicker slots
 * @param slotsDef defense slots
 * @param slotsBn bench slots
 * @param extras all other settings, never null
 */
public record DraftSettings(
        Integer teams,
        Integer rounds,
        Integer pickTimer,
        Integer slotsQb,
        Integer slotsRb,
        Integer slotsWr,
        Integer slotsTe,
        Integer slotsFlex,
        Integer slotsK,
        Integer slotsDef,
        Integer slotsBn,
        Map<String, Object> extras) {

    /** Normalises null extras to an empty immutable map. */
    public DraftSettings {
        extras = Immutables.map(extras);
    }

    /** Settings with every field null; used when Sleeper sends none. */
    static DraftSettings empty() {
        return new DraftSettings(null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** JSON entry point: takes the typed keys out of the raw map and keeps the rest as extras. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static DraftSettings fromJson(Map<String, Object> raw) {
        if (raw == null) {
            return empty();
        }
        Map<String, Object> rest = new LinkedHashMap<>(raw);
        return new DraftSettings(
                take(rest, "teams"), take(rest, "rounds"), take(rest, "pick_timer"),
                take(rest, "slots_qb"), take(rest, "slots_rb"), take(rest, "slots_wr"),
                take(rest, "slots_te"), take(rest, "slots_flex"), take(rest, "slots_k"),
                take(rest, "slots_def"), take(rest, "slots_bn"), rest);
    }

    /** Removes {@code key} and returns it as an Integer; a value that is not numeric stays in extras. */
    private static Integer take(Map<String, Object> rest, String key) {
        Object value = rest.get(key);
        Integer result = null;
        if (value instanceof Number n) {
            result = n.intValue();
        } else if (value instanceof String s) {
            try {
                result = Integer.valueOf(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        } else if (value == null) {
            rest.remove(key);
            return null;
        }
        if (result != null) {
            rest.remove(key);
        }
        return result;
    }
}
