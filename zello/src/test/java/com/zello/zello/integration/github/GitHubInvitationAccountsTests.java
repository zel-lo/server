package com.zello.zello.integration.github;

import com.zello.zello.api.ApiException;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GitHubInvitationAccountsTests {
    MockRestServiceServer server;
    GitHubInvitationAccounts accounts;

    @BeforeEach void setup() {
        var builder = RestClient.builder().baseUrl("https://api.github.com");
        server = MockRestServiceServer.bindTo(builder).build();
        accounts = new GitHubInvitationAccounts(builder.build());
    }

    @Test void resolvesTheStableIdAndCurrentLoginWithTheInvitersSessionToken() {
        server.expect(requestTo("https://api.github.com/users/old-login"))
                .andExpect(header("Authorization", "Bearer secret-session-token"))
                .andRespond(withSuccess("{\"id\":123,\"login\":\"new-login\",\"type\":\"User\",\"avatar_url\":null,\"future\":true}", MediaType.APPLICATION_JSON));
        assertThat(accounts.resolve("secret-session-token", "old-login"))
                .isEqualTo(new GitHubInvitationAccounts.Account(123, "new-login", "User", null));
        server.verify();
    }

    @ParameterizedTest @ValueSource(strings = {"Organization", "Bot", "unknown"})
    void rejectsNonPersonalAccounts(String type) {
        server.expect(requestTo("https://api.github.com/users/team"))
                .andRespond(withSuccess("{\"id\":123,\"login\":\"team\",\"type\":\"" + type + "\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> accounts.resolve("token", "team")).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo("INVALID_INVITATION_ACCOUNT"));
        server.verify();
    }

    @ParameterizedTest @ValueSource(ints = {401, 403, 404, 429, 500})
    void mapsRemoteFailuresWithoutExposingTheTokenOrPrivateDiagnostics(int status) {
        server.expect(requestTo("https://api.github.com/users/octocat"))
                .andRespond(withStatus(HttpStatusCode.valueOf(status)).body("secret-session-token private upstream diagnostic"));
        assertThatThrownBy(() -> accounts.resolve("secret-session-token", "octocat"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(status == 401 ? 401 : status == 404 ? 404 : status == 500 ? 502 : 403);
                    assertThat(error.getMessage()).doesNotContain("secret-session-token", "diagnostic");
                });
        server.verify();
    }

    @Test void mapsNetworkFailuresToARetryableSafeError() {
        server.expect(requestTo("https://api.github.com/users/octocat"))
                .andRespond(withException(new IOException("private transport details")));
        assertThatThrownBy(() -> accounts.resolve("token", "octocat"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(502);
                    assertThat(error.getMessage()).doesNotContain("private transport");
                });
        server.verify();
    }
}
