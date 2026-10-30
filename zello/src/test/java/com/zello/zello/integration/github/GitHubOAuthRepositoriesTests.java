package com.zello.zello.integration.github;

import com.zello.zello.api.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GitHubOAuthRepositoriesTests {
    private MockRestServiceServer server;
    private GitHubOAuthRepositories github;
    private static final String REPO = """
            {"id":123,"owner":{"id":45,"login":"team","type":"Organization"},"name":"server",
             "full_name":"team/server","html_url":"https://github.com/team/server","default_branch":"main",
             "private":true,"archived":false,"language":"Java","extra_api_field":true}
            """;

    @BeforeEach
    void setup() {
        var builder = RestClient.builder().baseUrl("https://api.github.com");
        server = MockRestServiceServer.bindTo(builder).build();
        github = new GitHubOAuthRepositories(builder.build());
    }

    @Test
    void listsUserAndCollaboratorRepositoriesWithoutInstallationRequestsAndPreservesPagination() {
        server.expect(requestTo("https://api.github.com/user/repos?visibility=all&affiliation=owner,collaborator,organization_member&sort=updated&direction=desc&per_page=100&page=1"))
                .andExpect(header("Authorization", "Bearer oauth-test-token"))
                .andRespond(withSuccess("[" + REPO + "]", MediaType.APPLICATION_JSON)
                        .header("Link", "<https://api.github.com/user/repos?page=2>; rel=\"next\""));
        var page = github.repositories("oauth-test-token", "all", null, 1);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().privateRepository()).isTrue();
        server.verify();
    }

    @Test
    void personalFilterUsesAuthenticatedOwnershipInsteadOfAClientSuppliedUsername() {
        server.expect(requestTo("https://api.github.com/user/repos?visibility=all&affiliation=owner&sort=updated&direction=desc&per_page=100&page=1"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        assertThat(github.repositories("token", "personal", "ignored-username", 1).items()).isEmpty();
        server.verify();
    }

    @Test
    void organizationListRequiresActiveMembershipAndRespectsUserTokenAccess() {
        server.expect(requestTo("https://api.github.com/user/memberships/orgs/team"))
                .andRespond(withSuccess("{\"state\":\"active\",\"role\":\"member\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/orgs/team/repos?type=all&sort=updated&direction=desc&per_page=100&page=1"))
                .andRespond(withSuccess("[" + REPO + "]", MediaType.APPLICATION_JSON));
        assertThat(github.repositories("token", "organization", "team", 1).items()).hasSize(1);
        server.verify();
    }

    @Test
    void pendingMembershipAndMalformedOrganizationCannotListRepositories() {
        server.expect(requestTo("https://api.github.com/user/memberships/orgs/team"))
                .andRespond(withSuccess("{\"state\":\"pending\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> github.repositories("token", "organization", "team", 1)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.status()).isEqualTo(404));
        assertThatThrownBy(() -> github.repositories("token", "organization", "team/../../user", 1)).isInstanceOf(ApiException.class);
        server.verify();
    }

    @Test
    void validatesTheAuthoritativeRepositoryIdAndReturnsSafeErrorsForRevokedAccess() {
        server.expect(requestTo("https://api.github.com/repositories/123"))
                .andRespond(withSuccess(REPO.replace("\"id\":123", "\"id\":999"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repositories/123"))
                .andRespond(withResourceNotFound().body("private upstream token details"));
        assertThatThrownBy(() -> github.repository("token", 123)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo("INVALID_GITHUB_RESPONSE"));
        assertThatThrownBy(() -> github.repository("token", 123)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.getMessage()).doesNotContain("private upstream", "token details"));
        server.verify();
    }

    @Test
    void organizationDiscoveryUsesOAuthMembershipApi() {
        server.expect(requestTo("https://api.github.com/user/orgs?per_page=100&page=1"))
                .andRespond(withSuccess("[{\"id\":45,\"login\":\"team\",\"extra\":true}]", MediaType.APPLICATION_JSON));
        assertThat(github.organizations("token", 1).items().getFirst().login()).isEqualTo("team");
        server.verify();
    }
}
