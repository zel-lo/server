package com.zello.zello.pullrequest;

import com.jayway.jsonpath.JsonPath;
import com.zello.zello.api.ApiException;
import com.zello.zello.auth.*;
import com.zello.zello.domain.project.*;
import com.zello.zello.domain.pullrequest.PullRequest;
import com.zello.zello.domain.pullrequest.PullRequestRepository;
import com.zello.zello.domain.user.UserRepository;
import com.zello.zello.integration.github.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "zello.auth.github.app-type=OAUTH")
@AutoConfigureMockMvc
class PullRequestApiTests {
    private static final AtomicLong IDS = new AtomicLong(210_000_000);
    @Autowired MockMvc mvc;
    @Autowired GitHubLoginService login;
    @Autowired PullRequestRepository pullRequests;
    @Autowired ProjectRepository projects;
    @Autowired ProjectMemberRepository members;
    @Autowired UserRepository users;
    @MockitoBean GitHubOAuthRepositories github;
    @MockitoBean GitHubPullRequests pulls;
    @MockitoBean GitHubTokenProvider tokens;
    private ZelloPrincipal owner;
    private long ownerGithubId;
    private long projectId;
    private long repoId;
    private long repositoryDbId;

    @BeforeEach
    void setup() throws Exception {
        ownerGithubId = IDS.incrementAndGet();
        owner = login.login(new GitHubProfile(ownerGithubId, "pr-user-" + ownerGithubId, null, null, null));
        repoId = IDS.incrementAndGet();
        when(tokens.oauthToken(any(), any())).thenReturn("session-token");
        when(github.repository(anyString(), eq(repoId))).thenReturn(new GitHubGateway.RemoteRepository(repoId,
                new GitHubGateway.Owner(45, "team", "Organization", null), "server", "team/server", "https://github.com/team/server",
                null, "Java", "main", true, false));
        var created = mvc.perform(post("/api/projects").with(as(owner)).with(csrf()).contentType("application/json")
                .content("{\"name\":\"PR 프로젝트\"}")).andExpect(status().isCreated()).andReturn();
        projectId = Long.parseLong(JsonPath.read(created.getResponse().getContentAsString(), "$.id"));
        var connected = mvc.perform(post(base("/repositories")).with(as(owner)).with(csrf()).contentType("application/json")
                .content("{\"githubRepositoryId\":\"" + repoId + "\"}")).andExpect(status().isCreated()).andReturn();
        repositoryDbId = Long.parseLong(JsonPath.read(connected.getResponse().getContentAsString(), "$.id"));
    }

    @Test
    void createsAPullRequestOnGithubWithTheTeamSummaryAsBodyAndListsIt() throws Exception {
        when(pulls.create(anyString(), eq("team"), eq("server"), any())).thenAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return remote(9, "feat/new", "main");
        });
        create("key-0001-aaaa", "feat/new", "main", false).andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value(9)).andExpect(jsonPath("$.teamSummary").value("팀 요약"))
                .andExpect(jsonPath("$.body").value("팀 요약")).andExpect(jsonPath("$.author.login").value("pr-user-" + ownerGithubId));
        var sent = org.mockito.ArgumentCaptor.forClass(GitHubPullRequests.NewPull.class);
        verify(pulls).create(eq("session-token"), eq("team"), eq("server"), sent.capture());
        assertThat(sent.getValue().body()).isEqualTo("팀 요약");
        assertThat(sent.getValue().head()).isEqualTo("feat/new");
        list("").andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].title").value("새 PR"));
    }

    @Test
    void replayingTheSameRequestKeyDoesNotCreateASecondPullRequest() throws Exception {
        when(pulls.create(anyString(), anyString(), anyString(), any())).thenReturn(remote(9, "feat/new", "main"));
        create("key-0002-aaaa", "feat/new", "main", false).andExpect(status().isCreated());
        create("key-0002-aaaa", "feat/new", "main", false).andExpect(status().isOk()).andExpect(jsonPath("$.number").value(9));
        verify(pulls, times(1)).create(anyString(), anyString(), anyString(), any());
        list("").andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void githubRejectionIsReturnedAndLeavesNothingInTheListSoANewAttemptWorks() throws Exception {
        when(pulls.create(anyString(), anyString(), anyString(), any())).thenThrow(new ApiException(422, "PULL_REQUEST_NO_CHANGES", "변경된 커밋이 없어요."));
        create("key-0003-aaaa", "feat/empty", "main", false).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PULL_REQUEST_NO_CHANGES"));
        list("").andExpect(jsonPath("$.items.length()").value(0));
        reset(pulls);
        when(pulls.create(anyString(), anyString(), anyString(), any())).thenReturn(remote(10, "feat/empty", "main"));
        create("key-0004-aaaa", "feat/empty", "main", false).andExpect(status().isCreated());
        list("").andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void unknownOutcomeIsReconciledAgainstGithubAndRetriesNeverCreateTwice() throws Exception {
        when(pulls.create(anyString(), anyString(), anyString(), any())).thenThrow(new ApiException(503, "GITHUB_RESULT_UNKNOWN", "확인하지 못했어요."));
        when(pulls.findOpen(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(java.util.Optional.empty());
        create("key-0005-aaaa", "feat/lost", "main", false).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GITHUB_RESULT_UNKNOWN"));
        list("").andExpect(jsonPath("$.items.length()").value(0));

        when(pulls.findOpen(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(java.util.Optional.of(remote(11, "feat/lost", "main")));
        create("key-0005-aaaa", "feat/lost", "main", false).andExpect(status().isCreated()).andExpect(jsonPath("$.number").value(11));
        verify(pulls, times(1)).create(anyString(), anyString(), anyString(), any());
        list("").andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].teamSummary").value("팀 요약"));
    }

    @Test
    void lostResponseThatActuallyCreatedThePullRequestIsRecoveredImmediately() throws Exception {
        when(pulls.create(anyString(), anyString(), anyString(), any())).thenThrow(new ApiException(503, "GITHUB_RESULT_UNKNOWN", "확인하지 못했어요."));
        when(pulls.findOpen(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(java.util.Optional.of(remote(12, "feat/lost", "main")));
        create("key-0006-aaaa", "feat/lost", "main", false).andExpect(status().isCreated()).andExpect(jsonPath("$.number").value(12));
    }

    @Test
    void collectionAdoptsAnInFlightRowInsteadOfDuplicatingItAndKeepsTheTeamSummary() throws Exception {
        when(pulls.create(anyString(), anyString(), anyString(), any())).thenThrow(new ApiException(503, "GITHUB_RESULT_UNKNOWN", "확인하지 못했어요."));
        when(pulls.findOpen(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(java.util.Optional.empty());
        create("key-0007-aaaa", "feat/race", "main", false).andExpect(status().isServiceUnavailable());
        var remote = remote(13, "feat/race", "main");
        stubFetch(new GitHubPullRequests.Fetched(remote, PullRequest.ReviewDecision.PENDING));
        sync().andExpect(status().isOk());
        list("").andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].teamSummary").value("팀 요약"));
    }

    @Test
    void createRejectsDuplicatesSameBranchesViewersOutsidersAndBadInput() throws Exception {
        when(pulls.create(anyString(), anyString(), anyString(), any())).thenReturn(remote(14, "feat/dup", "main"));
        create("key-0008-aaaa", "feat/dup", "main", false).andExpect(status().isCreated());
        create("key-0009-aaaa", "feat/dup", "main", false).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PULL_REQUEST_ALREADY_EXISTS"));
        create("key-0010-aaaa", "main", "main", false).andExpect(status().isBadRequest());
        mvc.perform(post(base("/pull-requests")).with(as(owner)).with(csrf()).contentType("application/json")
                .content("{\"repositoryId\":\"" + repositoryDbId + "\",\"title\":\"\",\"headBranch\":\"a\",\"baseBranch\":\"b\",\"teamSummary\":\"x\",\"requestKey\":\"key-0011-aaaa\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(base("/pull-requests")).with(as(owner)).contentType("application/json").content("{}")).andExpect(status().isForbidden());

        var viewer = login.login(new GitHubProfile(IDS.incrementAndGet(), "pr-writer-viewer", null, null, null));
        members.saveAndFlush(ProjectMember.builder().project(projects.findById(projectId).orElseThrow())
                .user(users.findById(viewer.userId()).orElseThrow()).role(ProjectMember.Role.VIEWER).build());
        createAs(viewer, "key-0012-aaaa", "feat/other", "main").andExpect(status().isForbidden());
        var outsider = login.login(new GitHubProfile(IDS.incrementAndGet(), "pr-outsider", null, null, null));
        createAs(outsider, "key-0013-aaaa", "feat/other", "main").andExpect(status().isNotFound());
        verify(pulls, times(1)).create(anyString(), anyString(), anyString(), any());
    }

    @Test
    void branchListShowsGithubBranchesAndTheDefaultBranchToMembers() throws Exception {
        when(pulls.branches(anyString(), eq("team"), eq("server"))).thenReturn(new GitHubPullRequests.Branches(List.of("main", "feat/a"), false));
        mvc.perform(get(base("/repositories/" + repositoryDbId + "/branches")).with(as(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultBranch").value("main")).andExpect(jsonPath("$.branches[1]").value("feat/a"))
                .andExpect(jsonPath("$.hasMore").value(false));
        var outsider = login.login(new GitHubProfile(IDS.incrementAndGet(), "branch-outsider", null, null, null));
        mvc.perform(get(base("/repositories/" + repositoryDbId + "/branches")).with(as(outsider))).andExpect(status().isNotFound());
        mvc.perform(get(base("/repositories/999999999/branches")).with(as(owner))).andExpect(status().isNotFound());
    }

    @Test
    void syncStoresPullRequestsAndListFiltersByStateAndAuthor() throws Exception {
        long otherId = IDS.incrementAndGet();
        stubFetch(fetched(1, 1, ownerGithubId, "open", false, null, PullRequest.ReviewDecision.PENDING),
                fetched(2, 2, otherId, "open", true, null, PullRequest.ReviewDecision.PENDING),
                fetched(3, 3, ownerGithubId, "closed", false, Instant.parse("2026-10-30T02:00:00Z"), PullRequest.ReviewDecision.UNKNOWN));
        sync().andExpect(status().isOk()).andExpect(jsonPath("$.collected").value(3));

        list("").andExpect(jsonPath("$.items.length()").value(3)).andExpect(jsonPath("$.counts.open").value(2))
                .andExpect(jsonPath("$.counts.merged").value(1));
        list("?state=open").andExpect(jsonPath("$.items.length()").value(2));
        list("?state=merged").andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].state").value("MERGED"));
        list("?author=me").andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.counts.open").value(1));
        list("?state=open&author=me").andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].author.login").value("remote-" + ownerGithubId))
                .andExpect(jsonPath("$.items[0].repositoryName").value("server"));
    }

    @Test
    void syncingAgainUpdatesInPlaceWithoutDuplicatesAndKeepsTeamSummary() throws Exception {
        stubFetch(fetched(1, 1, ownerGithubId, "open", false, null, PullRequest.ReviewDecision.PENDING));
        sync().andExpect(status().isOk());
        var row = pullRequests.findAll().stream().filter(pull -> pull.getGithubPrId() != null && pull.getGithubPrId() == repoId * 100 + 1).findFirst().orElseThrow();
        row.updateTeamSummary("팀 요약");
        pullRequests.saveAndFlush(row);

        stubFetch(fetched(1, 1, ownerGithubId, "closed", false, Instant.parse("2026-10-31T01:00:00Z"), PullRequest.ReviewDecision.UNKNOWN));
        sync().andExpect(status().isOk());
        list("").andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].state").value("MERGED"))
                .andExpect(jsonPath("$.items[0].teamSummary").value("팀 요약"));
    }

    @Test
    void olderRemoteDataDoesNotOverwriteNewerStoredData() throws Exception {
        stubFetch(fetched(1, 1, ownerGithubId, "closed", false, Instant.parse("2026-10-31T01:00:00Z"), PullRequest.ReviewDecision.UNKNOWN));
        sync().andExpect(status().isOk());
        stubFetch(withUpdatedAt(fetched(1, 1, ownerGithubId, "open", false, null, PullRequest.ReviewDecision.PENDING), "2026-10-01T00:00:00Z"));
        sync().andExpect(status().isOk());
        list("").andExpect(jsonPath("$.items[0].state").value("MERGED"));
    }

    @Test
    void lostGithubAccessHidesStoredPullRequests() throws Exception {
        stubFetch(fetched(1, 1, ownerGithubId, "open", false, null, PullRequest.ReviewDecision.PENDING));
        sync().andExpect(status().isOk());
        when(github.repository(anyString(), eq(repoId))).thenThrow(new ApiException(404, "GITHUB_RESOURCE_NOT_FOUND", "접근할 수 없습니다."));
        list("").andExpect(jsonPath("$.items.length()").value(0));
        sync().andExpect(jsonPath("$.skippedRepositories").value(1));
    }

    @Test
    void nonMembersCannotReadOrSyncAndBadFiltersAreRejected() throws Exception {
        var visitor = login.login(new GitHubProfile(IDS.incrementAndGet(), "pr-visitor", null, null, null));
        mvc.perform(get(base("/pull-requests")).with(as(visitor))).andExpect(status().isNotFound());
        mvc.perform(post(base("/pull-requests/sync")).with(as(visitor)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post(base("/pull-requests/sync")).with(as(owner))).andExpect(status().isForbidden());
        list("?state=bogus").andExpect(status().isBadRequest());
        list("?author=someone").andExpect(status().isBadRequest());
        list("?size=1000").andExpect(status().isBadRequest());
        verifyNoInteractions(pulls);
    }

    @Test
    void viewersCanReadAndRefreshButStayInsideTheirProject() throws Exception {
        var viewer = login.login(new GitHubProfile(IDS.incrementAndGet(), "pr-viewer", null, null, null));
        members.saveAndFlush(ProjectMember.builder().project(projects.findById(projectId).orElseThrow())
                .user(users.findById(viewer.userId()).orElseThrow()).role(ProjectMember.Role.VIEWER).build());
        stubFetch(fetched(1, 1, ownerGithubId, "open", false, null, PullRequest.ReviewDecision.PENDING));
        mvc.perform(post(base("/pull-requests/sync")).with(as(viewer)).with(csrf())).andExpect(status().isOk());
        mvc.perform(get(base("/pull-requests")).with(as(viewer))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get(base("/pull-requests") + "?author=me").with(as(viewer))).andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void detailReturnsTheGithubBodyWhileTheListStaysLight() throws Exception {
        stubFetch(fetched(1, 1, ownerGithubId, "open", false, null, PullRequest.ReviewDecision.PENDING));
        sync().andExpect(status().isOk());
        list("").andExpect(jsonPath("$.items[0].body").doesNotExist());
        String id = JsonPath.read(list("").andReturn().getResponse().getContentAsString(), "$.items[0].id");
        mvc.perform(get(base("/pull-requests/" + id)).with(as(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("본문")).andExpect(jsonPath("$.title").value("PR 1"))
                .andExpect(jsonPath("$.repositoryName").value("server"));
    }

    @Test
    void detailIsHiddenFromOutsidersOtherProjectsAndUsersWhoLostGithubAccess() throws Exception {
        stubFetch(fetched(1, 1, ownerGithubId, "open", false, null, PullRequest.ReviewDecision.PENDING));
        sync().andExpect(status().isOk());
        String id = JsonPath.read(list("").andReturn().getResponse().getContentAsString(), "$.items[0].id");
        var visitor = login.login(new GitHubProfile(IDS.incrementAndGet(), "detail-visitor", null, null, null));
        mvc.perform(get(base("/pull-requests/" + id)).with(as(visitor))).andExpect(status().isNotFound());
        var created = mvc.perform(post("/api/projects").with(as(owner)).with(csrf()).contentType("application/json")
                .content("{\"name\":\"다른 프로젝트\"}")).andExpect(status().isCreated()).andReturn();
        String otherProject = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        mvc.perform(get("/api/projects/" + otherProject + "/pull-requests/" + id).with(as(owner))).andExpect(status().isNotFound());
        mvc.perform(get(base("/pull-requests/999999999")).with(as(owner))).andExpect(status().isNotFound());
        when(github.repository(anyString(), eq(repoId))).thenThrow(new ApiException(404, "GITHUB_RESOURCE_NOT_FOUND", "접근할 수 없습니다."));
        mvc.perform(get(base("/pull-requests/" + id)).with(as(owner))).andExpect(status().isNotFound());
    }

    private void stubFetch(GitHubPullRequests.Fetched... items) {
        when(pulls.fetch(anyString(), eq("team"), eq("server"))).thenReturn(List.of(items));
    }

    private GitHubPullRequests.Fetched fetched(long id, int number, long authorId, String state, boolean draft, Instant mergedAt,
                                               PullRequest.ReviewDecision decision) {
        // PR id는 테스트 간 충돌하지 않도록 저장소 id를 섞는다.
        long prId = repoId * 100 + id;
        var remote = new GitHubPullRequests.Remote(prId, number, "https://github.com/team/server/pull/" + number, "PR " + number, "본문",
                state, draft, new GitHubPullRequests.User(authorId, "remote-" + authorId, "User", null),
                new GitHubPullRequests.Ref("feat/" + number, "sha" + number), new GitHubPullRequests.Ref("main", null), null,
                Instant.parse("2026-10-29T01:00:00Z"), Instant.parse("2026-10-30T01:00:00Z"),
                mergedAt == null ? null : mergedAt, mergedAt);
        return new GitHubPullRequests.Fetched(remote, decision);
    }

    private GitHubPullRequests.Fetched withUpdatedAt(GitHubPullRequests.Fetched item, String updatedAt) {
        var r = item.pull();
        return new GitHubPullRequests.Fetched(new GitHubPullRequests.Remote(r.id(), r.number(), r.htmlUrl(), r.title(), r.body(), r.state(),
                r.draft(), r.user(), r.head(), r.base(), r.mergeCommitSha(), r.createdAt(), Instant.parse(updatedAt), r.closedAt(), r.mergedAt()),
                item.reviewDecision());
    }

    private GitHubPullRequests.Remote remote(int number, String head, String base) {
        return new GitHubPullRequests.Remote(repoId * 100 + number, number, "https://github.com/team/server/pull/" + number, "새 PR", "팀 요약",
                "open", false, new GitHubPullRequests.User(ownerGithubId, "remote-" + ownerGithubId, "User", null),
                new GitHubPullRequests.Ref(head, "sha" + number), new GitHubPullRequests.Ref(base, null), null,
                Instant.parse("2026-10-31T01:00:00Z"), Instant.parse("2026-10-31T01:00:00Z"), null, null);
    }

    private ResultActions create(String key, String head, String base, boolean draft) throws Exception { return createAs(owner, key, head, base, draft); }
    private ResultActions createAs(ZelloPrincipal principal, String key, String head, String base) throws Exception { return createAs(principal, key, head, base, false); }
    private ResultActions createAs(ZelloPrincipal principal, String key, String head, String base, boolean draft) throws Exception {
        return mvc.perform(post(base("/pull-requests")).with(as(principal)).with(csrf()).contentType("application/json")
                .content("{\"repositoryId\":\"" + repositoryDbId + "\",\"title\":\"새 PR\",\"headBranch\":\"" + head + "\",\"baseBranch\":\"" + base
                        + "\",\"teamSummary\":\"팀 요약\",\"draft\":" + draft + ",\"requestKey\":\"" + key + "\"}"));
    }

    private ResultActions sync() throws Exception {
        return mvc.perform(post(base("/pull-requests/sync")).with(as(owner)).with(csrf()));
    }
    private ResultActions list(String query) throws Exception {
        return mvc.perform(get(base("/pull-requests") + query).with(as(owner)));
    }
    private String base(String path) { return "/api/projects/" + projectId + path; }
    private org.springframework.test.web.servlet.request.RequestPostProcessor as(ZelloPrincipal principal) {
        return authentication(new UsernamePasswordAuthenticationToken(principal, "unused", principal.getAuthorities()));
    }
}
