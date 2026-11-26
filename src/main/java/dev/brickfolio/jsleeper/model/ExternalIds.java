package dev.brickfolio.jsleeper.model;

/**
 * A player's IDs on other sites, for cross-site joins. Sleeper sends these as a mix of numbers and
 * strings, so all are strings here; a blank value is null.
 *
 * @param espnId ESPN ID
 * @param yahooId Yahoo ID
 * @param sportradarId Sportradar ID
 * @param statsId Stats ID
 * @param fantasyDataId FantasyData ID
 * @param rotowireId Rotowire ID
 * @param rotoworldId Rotoworld ID
 * @param gsisId NFL GSIS ID
 * @param swishId Swish ID
 */
public record ExternalIds(
        String espnId,
        String yahooId,
        String sportradarId,
        String statsId,
        String fantasyDataId,
        String rotowireId,
        String rotoworldId,
        String gsisId,
        String swishId) {

    /** Turns blank values into null. */
    public ExternalIds {
        espnId = blankToNull(espnId);
        yahooId = blankToNull(yahooId);
        sportradarId = blankToNull(sportradarId);
        statsId = blankToNull(statsId);
        fantasyDataId = blankToNull(fantasyDataId);
        rotowireId = blankToNull(rotowireId);
        rotoworldId = blankToNull(rotoworldId);
        gsisId = blankToNull(gsisId);
        swishId = blankToNull(swishId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
