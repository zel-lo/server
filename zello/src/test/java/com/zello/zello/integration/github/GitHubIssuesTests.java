package com.zello.zello.integration.github;

import com.zello.zello.api.ApiException;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GitHubIssuesTests {
    MockRestServiceServer server;
    GitHubIssues github;
    static final String REMOTE="""
        {"id":123,"number":7,"title":"作業","body":"본문","state":"open","html_url":"https://github.com/team/repo/issues/7",
        "updated_at":"2026-10-31T10:00:00Z","user":{"id":1,"login":"author","type":"User"},"assignees":[],"labels":[{"name":"bug"}]}
        """;
    @BeforeEach void setup() {
        var builder=RestClient.builder().baseUrl("https://api.github.com");server=MockRestServiceServer.bindTo(builder).build();github=new GitHubIssues(builder.build());
    }
    @Test void issueCollectionExcludesPullRequestsAndKeepsPagination() {
        server.expect(requestTo("https://api.github.com/repos/team/repo/issues?state=all&sort=updated&direction=desc&per_page=100&page=1"))
                .andExpect(header("Authorization","Bearer session-token"))
                .andRespond(withSuccess("["+REMOTE+","+REMOTE.replace("\"id\":123", "\"id\":124,\"pull_request\":{\"url\":\"https://api.github.com/pulls/1\"}")+"]",MediaType.APPLICATION_JSON)
                        .header("Link","<https://api.github.com/repos/team/repo/issues?page=2>; rel=\"next\""));
        var page=github.page("session-token","team","repo",1);assertThat(page.items()).hasSize(1);assertThat(page.hasNext()).isTrue();server.verify();
    }
    @Test void createsThePriorityLabelOnlyWhenMissingThenCreatesIssueWithAssignees() {
        server.expect(requestTo("https://api.github.com/repos/team/repo/labels/priority%3Ahigh")).andRespond(withResourceNotFound());
        server.expect(requestTo("https://api.github.com/repos/team/repo/labels")).andExpect(content().json("{\"name\":\"priority:high\",\"color\":\"d73a4a\"}"))
                .andRespond(withSuccess("{\"name\":\"priority:high\"}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/team/repo/issues"))
                .andExpect(content().json("{\"title\":\"새 작업\",\"body\":\"설명\",\"assignees\":[\"author\"],\"labels\":[\"priority:high\"]}"))
                .andRespond(withSuccess(REMOTE,MediaType.APPLICATION_JSON));
        assertThat(github.create("token","team","repo","새 작업","설명",List.of("author"),"priority:high").number()).isEqualTo(7);server.verify();
    }
    @Test void unavailablePostHasAnUnknownOutcomeButKnownRejectionCanBeRetried() {
        server.expect(requestTo("https://api.github.com/repos/team/repo/labels/priority%3Ahigh")).andRespond(withSuccess("{\"name\":\"priority:high\"}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/team/repo/issues")).andRespond(withServerError().body("private raw upstream error"));
        assertThatThrownBy(() -> github.create("token","team","repo","title","body",List.of(),"priority:high"))
                .isInstanceOfSatisfying(ApiException.class,e -> {assertThat(e.code()).isEqualTo("GITHUB_RESULT_UNKNOWN");assertThat(e.getMessage()).doesNotContain("private raw");});server.verify();
    }
    @Test void validationFailureAfterPostAlsoHasUnknownOutcome() {
        server.expect(requestTo("https://api.github.com/repos/team/repo/labels/priority%3Ahigh")).andRespond(withSuccess("{\"name\":\"priority:high\"}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/team/repo/issues")).andRespond(withSuccess("{}",MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> github.create("token","team","repo","title","body",List.of(),"priority:high"))
                .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo("GITHUB_RESULT_UNKNOWN"));server.verify();
    }
    @Test void assigneePermissionIsCheckedAndPagesStayAvailableAfterLocalFiltering() {
        server.expect(requestTo("https://api.github.com/repos/team/repo/assignees/author")).andRespond(withNoContent());
        server.expect(requestTo("https://api.github.com/repos/team/repo/assignees?per_page=100&page=1"))
                .andRespond(withSuccess("[]",MediaType.APPLICATION_JSON).header("Link","<https://api.github.com/assignees?page=2>; rel=\"next\""));
        github.requireAssignee("token","team","repo","author");assertThat(github.assignees("token","team","repo",1).hasNext()).isTrue();server.verify();
    }
    @Test void noLongerAccessibleRepositoriesAndExpiredTokensReturnSafeErrors() {
        server.expect(requestTo("https://api.github.com/repos/team/repo/issues/7")).andRespond(withUnauthorizedRequest());
        assertThatThrownBy(() -> github.detail("token","team","repo",7)).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo("GITHUB_REAUTH_REQUIRED"));server.verify();
    }
}
