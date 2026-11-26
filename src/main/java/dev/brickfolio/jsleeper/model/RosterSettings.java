package dev.brickfolio.jsleeper.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/**
 * A roster's record and points. Sleeper sends points as a whole part plus a separate decimal part
 * holding hundredths (D6); use {@link #pointsFor()}, {@link #pointsAgainst()} and
 * {@link #potentialPoints()} instead of combining them by hand.
 *
 * @param wins wins, may be null
 * @param losses losses, may be null
 * @param ties ties, may be null
 * @param totalMoves number of roster moves
 * @param waiverPosition waiver priority
 * @param waiverBudgetUsed FAAB spent
 * @param fpts whole part of points for
 * @param fptsDecimal hundredths part of points for
 * @param fptsAgainst whole part of points against
 * @param fptsAgainstDecimal hundredths part of points against
 * @param division division number
 * @param ppts whole part of potential (best possible lineup) points
 * @param pptsDecimal hundredths part of potential points
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RosterSettings(
        Integer wins,
        Integer losses,
        Integer ties,
        Integer totalMoves,
        Integer waiverPosition,
        Integer waiverBudgetUsed,
        Integer fpts,
        Integer fptsDecimal,
        Integer fptsAgainst,
        Integer fptsAgainstDecimal,
        Integer division,
        Integer ppts,
        Integer pptsDecimal) {

    private static final int POINTS_SCALE = 2;

    /** Settings with every field null; used when Sleeper sends none. */
    static RosterSettings empty() {
        return new RosterSettings(null, null, null, null, null, null, null, null, null, null,
                null, null, null);
    }

    /**
     * Points scored, as {@code fpts + fptsDecimal / 100} with scale 2; null parts count as 0.
     *
     * @return points scored
     */
    public BigDecimal pointsFor() {
        return combine(fpts, fptsDecimal);
    }

    /**
     * Points conceded, as {@code fptsAgainst + fptsAgainstDecimal / 100} with scale 2.
     *
     * @return points conceded
     */
    public BigDecimal pointsAgainst() {
        return combine(fptsAgainst, fptsAgainstDecimal);
    }

    /**
     * Best-possible-lineup points, as {@code ppts + pptsDecimal / 100} with scale 2.
     *
     * @return best-possible-lineup points
     */
    public BigDecimal potentialPoints() {
        return combine(ppts, pptsDecimal);
    }

    private static BigDecimal combine(Integer whole, Integer hundredths) {
        return BigDecimal.valueOf(whole == null ? 0 : whole)
                .add(BigDecimal.valueOf(hundredths == null ? 0 : hundredths, POINTS_SCALE))
                .setScale(POINTS_SCALE);
    }
}
