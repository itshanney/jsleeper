package dev.brickfolio.jsleeper.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AvatarsTest {

    @Test
    void full_builds_the_documented_url() {
        assertThat(Avatars.full("cc12ec49965eb7856f84d71cf85306af"))
                .isEqualTo(URI.create("https://sleepercdn.com/avatars/cc12ec49965eb7856f84d71cf85306af"));
    }

    @Test
    void thumb_builds_the_documented_url() {
        assertThat(Avatars.thumb("cc12ec49965eb7856f84d71cf85306af"))
                .isEqualTo(URI.create("https://sleepercdn.com/avatars/thumbs/cc12ec49965eb7856f84d71cf85306af"));
    }

    @Test
    void id_is_encoded_as_a_single_path_segment() {
        assertThat(Avatars.full("a/b c").toString())
                .isEqualTo("https://sleepercdn.com/avatars/a%2Fb%20c");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void blank_or_null_id_is_rejected(String blank) {
        assertThatThrownBy(() -> Avatars.full(blank)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Avatars.thumb(blank)).isInstanceOf(IllegalArgumentException.class);
    }
}
