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

class GitHubBranchesTests {
    private static final String ROOT = "https://api.github.com/repos/team/server/git/";
    private static final String SHA = "a".repeat(40);
    MockRestServiceServer server;
    GitHubBranches branches;
    @BeforeEach void setup() {
        var builder = RestClient.builder().baseUrl("https://api.github.com");
        server = MockRestServiceServer.bindTo(builder).build();
        branches = new GitHubBranches(builder.build());
    }
    String ref(String branch) { return "{\"ref\":\"refs/heads/" + branch + "\",\"object\":{\"type\":\"commit\",\"sha\":\"" + SHA + "\"}}"; }
    void missingTarget() { server.expect(requestTo(ROOT + "ref/heads/feat%2F7")).andRespond(withResourceNotFound()); }
    void base() { server.expect(requestTo(ROOT + "ref/heads/main")).andRespond(withSuccess(ref("main"), MediaType.APPLICATION_JSON)); }

    @Test void createsAnIssueBranchAtTheCurrentDefaultBranchSha() {
        missingTarget(); base();
        server.expect(requestTo(ROOT + "refs")).andExpect(method(HttpMethod.POST)).andExpect(header("Authorization", "Bearer token"))
                .andExpect(content().json("{\"ref\":\"refs/heads/feat/7\",\"sha\":\"" + SHA + "\"}"))
                .andRespond(withSuccess(ref("feat/7"), MediaType.APPLICATION_JSON));
        assertThat(branches.ensure("token", "team", "server", "main", "feat/7")).isEqualTo(new GitHubBranches.Result("feat/7", true));
        server.verify();
    }
    @Test void reusesTheExactExistingBranchWithoutMovingItsHead() {
        server.expect(requestTo(ROOT + "ref/heads/feat%2F7")).andRespond(withSuccess(ref("feat/7"), MediaType.APPLICATION_JSON));
        assertThat(branches.ensure("token", "team", "server", "main", "feat/7").created()).isFalse();
        server.verify();
    }
    @Test void resolvesConcurrentCreationByReadingTheSameBranch() {
        missingTarget(); base();
        server.expect(requestTo(ROOT + "refs")).andRespond(withStatus(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT));
        server.expect(requestTo(ROOT + "ref/heads/feat%2F7")).andRespond(withSuccess(ref("feat/7"), MediaType.APPLICATION_JSON));
        assertThat(branches.ensure("token", "team", "server", "main", "feat/7").created()).isFalse();
        server.verify();
    }
    @Test void recoversALostCreationResponseWithoutCreatingAnotherBranch() {
        missingTarget(); base();
        server.expect(requestTo(ROOT + "refs")).andRespond(withException(new java.net.SocketTimeoutException("private diagnostic")));
        server.expect(requestTo(ROOT + "ref/heads/feat%2F7")).andRespond(withSuccess(ref("feat/7"), MediaType.APPLICATION_JSON));
        assertThat(branches.ensure("token", "team", "server", "main", "feat/7").created()).isFalse();
        server.verify();
    }
    @Test void aMissingBaseDoesNotCreateAReference() {
        missingTarget();
        server.expect(requestTo(ROOT + "ref/heads/main")).andRespond(withResourceNotFound());
        assertThatThrownBy(() -> branches.ensure("token", "team", "server", "main", "feat/7"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("BASE_BRANCH_NOT_FOUND"));
        server.verify();
    }
    @Test void rejectsUnsafeNamesBeforeSendingAnyRequest() {
        assertThatThrownBy(() -> branches.ensure("token", "team", "server", "main", "feat/7; echo token")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> branches.ensure("token", "../team", "server", "main", "feat/7")).isInstanceOf(ApiException.class);
        server.verify();
    }
    @Test void aForbiddenCreateReturnsASafeError() {
        missingTarget(); base();
        server.expect(requestTo(ROOT + "refs")).andRespond(withForbiddenRequest().body("private upstream details"));
        assertThatThrownBy(() -> branches.ensure("token", "team", "server", "main", "feat/7"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("GITHUB_ACCESS_RESTRICTED");
                    assertThat(e.getMessage()).doesNotContain("private upstream details");
                });
        server.verify();
    }
}
