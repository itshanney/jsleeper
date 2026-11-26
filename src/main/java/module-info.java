module dev.brickfolio.jsleeper {
    requires java.net.http;
    requires transitive com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.datatype.jsr310;

    // No exports yet: javac rejects exporting a package that has no classes.
    // Export dev.brickfolio.jsleeper (and the api/model/util/error/players packages) as they gain classes.
}
