package com.zello.zello.integration.github;

import com.zello.zello.api.ApiException;
import com.zello.zello.domain.pullrequest.PullRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GitHubPullRequestsTests {
    private static final String BASE = "https://api.github.com/repos/team/server/pulls";
    private MockRestServiceServer server;
    private GitHubPullRequests github;

    @BeforeEach
    void setup() {
        var builder = RestClient.builder().baseUrl("https://api.github.com");
        server = MockRestServiceServer.bindTo(builder).build();
        github = new GitHubPullRequests(builder.build());
    }

    @Test
    void collectsPullRequestsAcrossPagesAndComputesReviewDecisionOnlyForOpenOnes() {
        server.expect(requestTo(BASE + "?state=all&sort=updated&direction=desc&per_page=100&page=1"))
                .andExpect(header("Authorization", "Bearer token"))
                .andRespond(withSuccess("[" + pull(1, 10, "open", "null") + "]", MediaType.APPLICATION_JSON)
                        .header("Link", "<https://api.github.com/next>; rel=\"next\""));
        server.expect(requestTo(BASE + "?state=all&sort=updated&direction=desc&per_page=100&page=2"))
                .andRespond(withSuccess("[" + pull(2, 11, "closed", "\"2026-10-30T01:00:00Z\"") + "]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/10/reviews?per_page=100")).andRespond(withSuccess("""
                [{"user":{"id":7},"state":"CHANGES_REQUESTED"},{"user":{"id":7},"state":"APPROVED"},
                 {"user":{"id":8},"state":"COMMENTED"}]
                """, MediaType.APPLICATION_JSON));
        var result = github.fetch("token", "team", "server");
        assertThat(result).hasSize(2);
        assertThat(result.get(0).reviewDecision()).isEqualTo(PullRequest.ReviewDecision.APPROVED);
        assertThat(result.get(1).reviewDecision()).isEqualTo(PullRequest.ReviewDecision.UNKNOWN);
        assertThat(result.get(1).pull().mergedAt()).isNotNull();
        server.verify();
    }

    @Test
    void readsRequestedReviewersAndTreatsAMissingListAsEmpty() {
        server.expect(requestTo(BASE + "?state=all&sort=updated&direction=desc&per_page=100&page=1"))
                .andRespond(withSuccess("[" + pull(1, 10, "closed", "null").replace("\"title\"", "\"requested_reviewers\":[{\"id\":7,\"login\":\"dev\"}],\"title\"")
                        + "," + pull(2, 11, "closed", "null") + "]", MediaType.APPLICATION_JSON));
        var result = github.fetch("token", "team", "server");
        assertThat(result.get(0).pull().requestedReviewers()).extracting(GitHubPullRequests.User::login).containsExactly("dev");
        assertThat(result.get(1).pull().requestedReviewers()).isEmpty();
        server.verify();
    }

    @Test
    void changesRequestedWinsAndDismissedReviewsDoNotCount() {
        server.expect(requestTo(BASE + "?state=all&sort=updated&direction=desc&per_page=100&page=1"))
                .andRespond(withSuccess("[" + pull(1, 10, "open", "null") + "," + pull(2, 11, "open", "null") + "]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/10/reviews?per_page=100")).andRespond(withSuccess("""
                [{"user":{"id":7},"state":"APPROVED"},{"user":{"id":8},"state":"CHANGES_REQUESTED"}]
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/11/reviews?per_page=100")).andRespond(withSuccess("""
                [{"user":{"id":7},"state":"CHANGES_REQUESTED"},{"user":{"id":7},"state":"DISMISSED"}]
                """, MediaType.APPLICATION_JSON));
        var result = github.fetch("token", "team", "server");
        assertThat(result.get(0).reviewDecision()).isEqualTo(PullRequest.ReviewDecision.CHANGES_REQUESTED);
        assertThat(result.get(1).reviewDecision()).isEqualTo(PullRequest.ReviewDecision.PENDING);
        server.verify();
    }

    @Test
    void rejectsMalformedRepositoryNamesAndInvalidResponsesWithSafeErrors() {
        server.expect(requestTo(BASE + "?state=all&sort=updated&direction=desc&per_page=100&page=1"))
                .andRespond(withSuccess("[" + pull(1, 10, "open", "null").replace("\"title\":\"PR 10\"", "\"title\":\"\"") + "]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "?state=all&sort=updated&direction=desc&per_page=100&page=1"))
                .andRespond(withResourceNotFound().body("private upstream details"));
        assertThatThrownBy(() -> github.fetch("token", "team", "../user")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> github.fetch("token", "team", "server")).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo("INVALID_GITHUB_RESPONSE"));
        assertThatThrownBy(() -> github.fetch("token", "team", "server")).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.getMessage()).doesNotContain("private upstream"));
    }

    @Test
    void stopsAtTheConfiguredPageLimit() {
        for (int page = 1; page <= GitHubPullRequests.MAX_PAGES; page++) {
            server.expect(requestTo(BASE + "?state=all&sort=updated&direction=desc&per_page=100&page=" + page))
                    .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON).header("Link", "<https://api.github.com/next>; rel=\"next\""));
        }
        assertThat(github.fetch("token", "team", "server")).isEmpty();
        server.verify();
    }

    @Test
    void listsBranchesAndReportsWhenThereAreMorePages() {
        for (int page = 1; page <= GitHubPullRequests.MAX_BRANCH_PAGES; page++) {
            server.expect(requestTo("https://api.github.com/repos/team/server/branches?per_page=100&page=" + page))
                    .andRespond(withSuccess("[{\"name\":\"main\",\"protected\":true},{\"name\":\"feat/a\"}]", MediaType.APPLICATION_JSON)
                            .header("Link", "<https://api.github.com/next>; rel=\"next\""));
        }
        var branches = github.branches("token", "team", "server");
        assertThat(branches.names()).hasSize(GitHubPullRequests.MAX_BRANCH_PAGES * 2);
        assertThat(branches.hasMore()).isTrue();
        server.verify();
    }

    @Test
    void createsAPullRequestFromSameRepositoryBranchesAndSendsTheBody() {
        server.expect(requestTo("https://api.github.com/repos/team/server/pulls")).andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer token"))
                .andExpect(jsonPath("$.head").value("feat/a")).andExpect(jsonPath("$.base").value("main"))
                .andExpect(jsonPath("$.body").value("팀 요약")).andExpect(jsonPath("$.draft").value(true))
                .andRespond(withStatus(org.springframework.http.HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(pull(5, 12, "open", "null")));
        var created = github.create("token", "team", "server", new GitHubPullRequests.NewPull("제목", "팀 요약", "feat/a", "main", true));
        assertThat(created.number()).isEqualTo(12);
        server.verify();
    }

    @Test
    void mapsRejectionsToSafeMessagesAndTreatsLostResponsesAsUnknown() {
        var url = "https://api.github.com/repos/team/server/pulls";
        server.expect(requestTo(url)).andRespond(withStatus(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT).body("{\"message\":\"Validation Failed\",\"errors\":[{\"message\":\"No commits between main and feat/a\"}]}"));
        server.expect(requestTo(url)).andRespond(withStatus(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT).body("A pull request already exists for team:feat/a"));
        server.expect(requestTo(url)).andRespond(withStatus(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT).body("private upstream details"));
        server.expect(requestTo(url)).andRespond(withStatus(org.springframework.http.HttpStatus.FORBIDDEN).body("secret"));
        server.expect(requestTo(url)).andRespond(withServerError().body("gateway detail"));
        var input = new GitHubPullRequests.NewPull("제목", "요약", "feat/a", "main", false);
        assertThatThrownBy(() -> github.create("token", "team", "server", input)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo("PULL_REQUEST_NO_CHANGES"));
        assertThatThrownBy(() -> github.create("token", "team", "server", input)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.status()).isEqualTo(409));
        assertThatThrownBy(() -> github.create("token", "team", "server", input)).isInstanceOfSatisfying(ApiException.class,
                error -> { assertThat(error.code()).isEqualTo("PULL_REQUEST_REJECTED"); assertThat(error.getMessage()).doesNotContain("private upstream"); });
        assertThatThrownBy(() -> github.create("token", "team", "server", input)).isInstanceOfSatisfying(ApiException.class,
                error -> { assertThat(error.code()).isEqualTo("GITHUB_ACCESS_RESTRICTED"); assertThat(error.getMessage()).doesNotContain("secret"); });
        assertThatThrownBy(() -> github.create("token", "team", "server", input)).isInstanceOfSatisfying(ApiException.class,
                error -> { assertThat(error.code()).isEqualTo("GITHUB_RESULT_UNKNOWN"); assertThat(error.getMessage()).doesNotContain("gateway detail"); });
        server.verify();
    }

    @Test
    void collectsPeopleWhoReviewedExceptTheAuthorAndUnsubmittedReviews() {
        server.expect(requestTo(BASE + "?state=all&sort=updated&direction=desc&per_page=100&page=1"))
                .andRespond(withSuccess("[" + pull(1, 10, "open", "null") + "]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/10/reviews?per_page=100")).andRespond(withSuccess("""
                [{"user":{"id":8,"login":"dev-a"},"state":"COMMENTED"},{"user":{"id":8,"login":"dev-a"},"state":"APPROVED"},
                 {"user":{"id":7,"login":"writer"},"state":"COMMENTED"},{"user":{"id":9,"login":"dev-b"},"state":"PENDING"}]
                """, MediaType.APPLICATION_JSON));
        var result = github.fetch("token", "team", "server");
        assertThat(result.get(0).reviewed()).extracting(GitHubPullRequests.User::login).containsExactly("dev-a");
        server.verify();
    }

    @Test
    void readsReviewsOfAClosedPullRequestOnlyWhenAsked() {
        server.expect(requestTo(BASE + "?state=all&sort=updated&direction=desc&per_page=100&page=1"))
                .andRespond(withSuccess("[" + pull(1, 10, "closed", "\"2026-10-30T01:00:00Z\"") + "," + pull(2, 11, "closed", "null") + "]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/10/reviews?per_page=100")).andRespond(withSuccess("""
                [{"user":{"id":8,"login":"dev-a"},"state":"APPROVED"}]
                """, MediaType.APPLICATION_JSON));
        var result = github.fetch("token", "team", "server", java.util.Set.of(1L));
        assertThat(result.get(0).reviewsFetched()).isTrue();
        assertThat(result.get(0).reviewDecision()).isEqualTo(PullRequest.ReviewDecision.APPROVED);
        assertThat(result.get(1).reviewsFetched()).isFalse();
        server.verify();
    }

    @Test
    void requestsReviewersAndMapsRejectionsSafely() {
        var url = "https://api.github.com/repos/team/server/pulls/12/requested_reviewers";
        server.expect(requestTo(url)).andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(jsonPath("$.reviewers[0]").value("dev-a"))
                .andRespond(withSuccess(pull(5, 12, "open", "null").replace("\"title\"", "\"requested_reviewers\":[{\"id\":8,\"login\":\"dev-a\"}],\"title\""),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(url)).andRespond(withStatus(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT).body("private detail"));
        assertThat(github.requestReviewers("token", "team", "server", 12, java.util.List.of("dev-a")).requestedReviewers())
                .extracting(GitHubPullRequests.User::login).containsExactly("dev-a");
        assertThatThrownBy(() -> github.requestReviewers("token", "team", "server", 12, java.util.List.of("writer")))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.code()).isEqualTo("REVIEWER_REJECTED"); assertThat(error.getMessage()).doesNotContain("private detail"); });
        server.verify();
    }

    @Test
    void readsChangeStatsFromThePullRequestDetail() {
        server.expect(requestTo(BASE + "/12")).andRespond(withSuccess(pull(5, 12, "open", "null")
                .replace("\"title\"", "\"changed_files\":4,\"additions\":120,\"deletions\":30,\"commits\":3,\"title\""), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/13")).andRespond(withSuccess("{\"changed_files\":-1,\"additions\":0,\"deletions\":0,\"commits\":0}", MediaType.APPLICATION_JSON));
        var stats = github.stats("token", "team", "server", 12);
        assertThat(stats).isEqualTo(new GitHubPullRequests.Stats(4, 120, 30, 3));
        assertThatThrownBy(() -> github.stats("token", "team", "server", 13)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo("INVALID_GITHUB_RESPONSE"));
        server.verify();
    }

    @Test
    void listsReviewerCandidatesFromAssignees() {
        server.expect(requestTo("https://api.github.com/repos/team/server/assignees?per_page=100&page=1"))
                .andRespond(withSuccess("[{\"id\":7,\"login\":\"writer\",\"type\":\"User\"},{\"id\":8,\"login\":\"dev-a\",\"type\":\"User\"}]",
                        MediaType.APPLICATION_JSON));
        var candidates = github.reviewerCandidates("token", "team", "server");
        assertThat(candidates.users()).extracting(GitHubPullRequests.User::login).containsExactly("writer", "dev-a");
        assertThat(candidates.hasMore()).isFalse();
        server.verify();
    }

    @Test
    void findsAnOpenPullRequestForTheSameBranchPair() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.github.com/repos/team/server/pulls?state=open")))
                .andExpect(queryParam("head", "team%3Afeat%2Fa")).andExpect(queryParam("base", "main"))
                .andRespond(withSuccess("[" + pull(5, 12, "open", "null") + "]", MediaType.APPLICATION_JSON));
        assertThat(github.findOpen("token", "team", "server", "feat/a", "main")).isPresent();
        server.verify();
    }

    private static String pull(long id, int number, String state, String mergedAt) {
        return """
                {"id":%d,"number":%d,"html_url":"https://github.com/team/server/pull/%d","title":"PR %d","body":"본문","state":"%s",
                 "draft":false,"user":{"id":7,"login":"writer","type":"User","avatar_url":null},"head":{"ref":"feat/a","sha":"abc"},
                 "base":{"ref":"main"},"merge_commit_sha":null,"created_at":"2026-10-29T01:00:00Z","updated_at":"2026-10-30T01:00:00Z",
                 "closed_at":null,"merged_at":%s,"unknown_field":true}
                """.formatted(id, number, number, number, state, mergedAt);
    }
}
