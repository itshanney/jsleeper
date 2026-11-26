package dev.brickfolio.jsleeper.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.brickfolio.jsleeper.Fixtures;
import dev.brickfolio.jsleeper.SleeperClient;
import dev.brickfolio.jsleeper.model.User;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class UsersApiTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    private final SleeperClient client = SleeperClient.builder().baseUri(wm.baseUrl() + "/v1").build();

    private void stubJson(String url, String body) {
        wm.stubFor(get(urlEqualTo(url)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    void recorded_user_fixture_deserialises() {
        wm.stubFor(get(urlEqualTo("/v1/user/user10")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(Fixtures.bytes("user.json"))));

        Optional<User> user = client.users().get("user10");

        assertThat(user).contains(new User("1266175725602353152", "user10", "User 10",
                "00000000000000000000000000000000"));
    }

    @Test
    void unknown_user_with_null_body_gives_empty_optional() {
        stubJson("/v1/user/nobody", "null");

        assertThat(client.users().get("nobody")).isEmpty();
    }

    @Test
    void missing_fields_are_null_and_unknown_fields_ignored() {
        stubJson("/v1/user/42", "{\"user_id\":\"42\",\"display_name\":\"X\",\"surprise\":1}");

        User user = client.users().get("42").orElseThrow();

        assertThat(user.userId()).isEqualTo("42");
        assertThat(user.username()).isNull();
        assertThat(user.avatar()).isNull();
    }

    @Test
    void username_with_a_space_is_percent_encoded() {
        stubJson("/v1/user/some%20user", "null");

        client.users().get("some user");

        wm.verify(getRequestedFor(urlEqualTo("/v1/user/some%20user")));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void blank_argument_is_rejected_before_any_request(String blank) {
        assertThatThrownBy(() -> client.users().get(blank))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(wm.getAllServeEvents()).isEmpty();
    }
}
