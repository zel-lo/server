package com.zello.zello.integration.github;

import com.zello.zello.api.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GitHubGatewayTests {
    private MockRestServiceServer server;
    private GitHubGateway gateway;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl("https://api.github.com");
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new GitHubGateway(builder.build());
    }

    @Test
    void verifiesInstallationAndRepositoryAcrossPagesAndFetchesAuthoritativeMetadata() {
        installations("null");
        server.expect(requestTo("https://api.github.com/user/installations/12/repositories?per_page=100&page=1"))
                .andRespond(withSuccess("{\"total_count\":101,\"repositories\":[{\"id\":999,\"private\":false,\"archived\":false}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/user/installations/12/repositories?per_page=100&page=2"))
                .andRespond(withSuccess("{\"total_count\":101,\"repositories\":[{\"id\":34,\"private\":true,\"archived\":false}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repositories/34")).andExpect(header("Authorization", "Bearer secret-session-token"))
                .andRespond(withSuccess("""
                        {"id":34,"owner":{"id":56,"login":"team","type":"Organization"},"name":"server",
                         "full_name":"team/server","html_url":"https://github.com/team/server","default_branch":"main","private":true,"archived":false,"language":"Java","extra_future_field":"ignored"}
                        """, MediaType.APPLICATION_JSON));
        var verified = gateway.verify("secret-session-token", 12, 34);
        assertThat(verified.repository().owner().type()).isEqualTo("Organization");
        assertThat(verified.repository().privateRepository()).isTrue();
        assertThat(verified.repository().language()).isEqualTo("Java");
        server.verify();
    }

    @Test
    void suspendedOrForgedInstallationIsRejectedBeforeFetchingRepositories() {
        installations("\"2026-10-30T00:00:00Z\"");
        assertThatThrownBy(() -> gateway.verify("token", 12, 34)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.status()).isEqualTo(404));
        server.verify();
    }

    @Test
    void repositoryOutsideInstallationIsRejectedWithoutFetchingItsDetails() {
        installations("null");
        server.expect(requestTo("https://api.github.com/user/installations/12/repositories?per_page=100&page=1"))
                .andRespond(withSuccess("{\"total_count\":0,\"repositories\":[]}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> gateway.verify("token", 12, 34)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.status()).isEqualTo(404));
        server.verify();
    }

    @Test
    void remoteUnauthorizedReturnsSafeReauthenticationErrorWithoutRemoteBodyOrToken() {
        server.expect(requestTo("https://api.github.com/user/installations?per_page=100&page=1"))
                .andRespond(withUnauthorizedRequest().body("private upstream diagnostic secret-session-token"));
        assertThatThrownBy(() -> gateway.installations("secret-session-token", 1)).isInstanceOfSatisfying(ApiException.class,
                error -> {
                    assertThat(error.status()).isEqualTo(401);
                    assertThat(error.code()).isEqualTo("GITHUB_REAUTH_REQUIRED");
                    assertThat(error.getMessage()).doesNotContain("secret-session-token", "diagnostic");
                });
        server.verify();
    }

    @Test
    void invalidPageDoesNotCallGithub() {
        assertThatThrownBy(() -> gateway.installations("token", 0)).isInstanceOf(ApiException.class);
        server.verify();
    }

    private void installations(String suspended) {
        server.expect(requestTo("https://api.github.com/user/installations?per_page=100&page=1"))
                .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization", org.hamcrest.Matchers.startsWith("Bearer ")))
                .andRespond(withSuccess("{\"total_count\":1,\"installations\":[{\"id\":12,\"account\":{\"id\":56,\"login\":\"team\",\"type\":\"Organization\"},\"permissions\":{\"metadata\":\"read\"},\"suspended_at\":" + suspended + "}]}", MediaType.APPLICATION_JSON));
    }
}
