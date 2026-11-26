package dev.brickfolio.jsleeper.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Draft metadata. Other keys Sleeper sends are ignored.
 *
 * @param name draft name
 * @param description draft description, often empty
 * @param scoringType raw scoring type such as "ppr", "half_ppr" or "std"
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DraftMetadata(String name, String description, String scoringType) {}
