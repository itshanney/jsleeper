module dev.brickfolio.jsleeper {
    requires java.net.http;
    requires transitive com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.datatype.jsr310;

    exports dev.brickfolio.jsleeper.error;
}
