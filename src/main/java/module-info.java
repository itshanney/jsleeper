/**
 * jsleeper, a Java client for the read-only Sleeper Fantasy API.
 */
module dev.brickfolio.jsleeper {
    requires transitive java.net.http;
    requires transitive com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.datatype.jsr310;

    exports dev.brickfolio.jsleeper;
    exports dev.brickfolio.jsleeper.api;
    exports dev.brickfolio.jsleeper.error;
    exports dev.brickfolio.jsleeper.players;
    exports dev.brickfolio.jsleeper.model;
    exports dev.brickfolio.jsleeper.model.bracket;
    exports dev.brickfolio.jsleeper.util;

    // Jackson reflects into model records; each new model package needs its own opens line.
    opens dev.brickfolio.jsleeper.model to com.fasterxml.jackson.databind;
    opens dev.brickfolio.jsleeper.model.bracket to com.fasterxml.jackson.databind;
}
