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
    void createsAPullRequestKeepingTheTeamSummaryApartFromTheGithubBody() throws Exception {
        when(pulls.create(anyString(), eq("team"), eq("server"), any())).thenAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return remote(9, "feat/new", "main");
        });
        createWith("key-0001-aaaa", "\"body\":\"GitHub 본문\"").andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value(9)).andExpect(jsonPath("$.teamSummary").value("팀 요약"))
                .andExpect(jsonPath("$.body").value("GitHub 본문")).andExpect(jsonPath("$.author.login").value("pr-user-" + ownerGithubId))
                .andExpect(jsonPath("$.warning").doesNotExist());
        var sent = org.mockito.ArgumentCaptor.forClass(GitHubPullRequests.NewPull.class);
        verify(pulls).create(eq("session-token"), eq("team"), eq("server"), sent.capture());
        assertThat(sent.getValue().body()).isEqualTo("GitHub 본문");
        assertThat(sent.getValue().head()).isEqualTo("feat/new");
        verify(pulls, never()).requestReviewers(anyString(), anyString(), anyString(), anyInt(), any());
        list("").andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].title").value("새 PR"));
    }

    @Test
    void anEmptyGithubBodyIsSentAsBlankAndNotShownAsTheSummary() throws Exception {
        when(pulls.create(anyString(), anyString(), anyString(), any())).thenReturn(remote(9, "feat/new", "main"));
        create("key-0101-aaaa", "feat/new", "main", false).andExpect(status().isCreated())
                .andExpect(jsonPath("$.teamSummary").value("팀 요약")).andExpect(jsonPath("$.body").doesNotExist());
        var sent = org.mockito.ArgumentCaptor.forClass(GitHubPullRequests.NewPull.class);
        verify(pulls).create(anyString(), anyString(), anyString(), sent.capture());
        assertThat(sent.getValue().body()).isEmpty();
    }

    @Test
    void requestsReviewersRightAfterCreationAndListsThem() throws Exception {
        when(pulls.create(anyString(), anyString(), anyString(), any())).thenReturn(remote(9, "feat/new", "main"));
        long reviewerId = IDS.incrementAndGet();
        when(pulls.requestReviewers(anyString(), eq("team"), eq("server"), eq(9), eq(List.of("dev-a"))))
                .thenReturn(withRequested(remote(9, "feat/new", "main"), new GitHubPullRequests.User(reviewerId, "dev-a", "User", null)));
        createWith("key-0102-aaaa", "\"reviewers\":[\"dev-a\",\"dev-a\"]").andExpect(status().isCreated())
                .andExpect(jsonPath("$.reviewers[0].login").value("dev-a")).andExpect(jsonPath("$.warning").doesNotExist());
        list("").andExpect(jsonPath("$.items[0].reviewers.length()").value(1)).andExpect(jsonPath("$.items[0].reviewers[0].login").value("dev-a"));
    }

    @Test
    void aFailedReviewerRequestKeepsThePullRequestAndReturnsAWarning() throws Exception {
        when(pulls.create(anyString(), anyString(), anyString(), any())).thenReturn(remote(9, "feat/new", "main"));
        when(pulls.requestReviewers(anyString(), anyString(), anyString(), anyInt(), any()))
                .thenThrow(new ApiException(422, "REVIEWER_REJECTED", "지정할 수 없는 리뷰어예요."));
        createWith("key-0103-aaaa", "\"reviewers\":[\"outsider\"]").andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value(9)).andExpect(jsonPath("$.reviewers.length()").value(0))
                .andExpect(jsonPath("$.warning").value(org.hamcrest.Matchers.containsString("리뷰어를 지정하지 못했어요")));
        list("").andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void reviewerCandidatesComeFromGithubWithoutMeOrBots() throws Exception {
        when(pulls.reviewerCandidates(anyString(), eq("team"), eq("server"))).thenReturn(new GitHubPullRequests.Candidates(List.of(
                new GitHubPullRequests.User(ownerGithubId, "me", "User", null), new GitHubPullRequests.User(5, "dev-a", "User", "https://avatars/a"),
                new GitHubPullRequests.User(6, "ci-bot", "Bot", null)), false));
        mvc.perform(get(base("/repositories/" + repositoryDbId + "/reviewer-candidates")).with(as(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.users.length()").value(1)).andExpect(jsonPath("$.users[0].login").value("dev-a"))
                .andExpect(jsonPath("$.users[0].avatarUrl").value("https://avatars/a")).andExpect(jsonPath("$.hasMore").value(false));
        var outsider = login.login(new GitHubProfile(IDS.incrementAndGet(), "candidate-outsider", null, null, null));
        mvc.perform(get(base("/repositories/" + repositoryDbId + "/reviewer-candidates")).with(as(outsider))).andExpect(status().isNotFound());
    }

    @Test
    void teamSummaryCanBeEditedWithTheCurrentVersionOnly() throws Exception {
        stubFetch(fetched(1, 1, ownerGithubId, "open", false, null, PullRequest.ReviewDecision.PENDING));
        sync().andExpect(status().isOk());
        var item = list("").andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(item, "$.items[0].id");
        int version = JsonPath.read(item, "$.items[0].version");
        editSummary(owner, id, "{\"teamSummary\":\"  고친 요약  \",\"version\":" + version + "}").andExpect(status().isOk())
                .andExpect(jsonPath("$.teamSummary").value("고친 요약")).andExpect(jsonPath("$.body").value("본문"))
                .andExpect(jsonPath("$.version").value(version + 1));
        editSummary(owner, id, "{\"teamSummary\":\"늦은 수정\",\"version\":" + version + "}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PULL_REQUEST_CONFLICT"));
        editSummary(owner, id, "{\"teamSummary\":\"\",\"version\":" + (version + 1) + "}").andExpect(status().isOk())
                .andExpect(jsonPath("$.teamSummary").doesNotExist());
        editSummary(owner, id, "{\"teamSummary\":\"x\"}").andExpect(status().isBadRequest());

        // 다시 수집해도 고친 요약은 GitHub 값으로 덮이지 않는다.
        editSummary(owner, id, "{\"teamSummary\":\"남는 요약\",\"version\":" + (version + 2) + "}").andExpect(status().isOk());
        stubFetch(withUpdatedAt(fetched(1, 1, ownerGithubId, "open", false, null, PullRequest.ReviewDecision.PENDING), "2026-10-31T09:00:00Z"));
        sync().andExpect(status().isOk());
        list("").andExpect(jsonPath("$.items[0].teamSummary").value("남는 요약"));
    }

    @Test
    void viewersAndOutsidersCannotEditTheTeamSummary() throws Exception {
        stubFetch(fetched(1, 1, ownerGithubId, "open", false, null, PullRequest.ReviewDecision.PENDING));
        sync().andExpect(status().isOk());
        String id = JsonPath.read(list("").andReturn().getResponse().getContentAsString(), "$.items[0].id");
        var viewer = login.login(new GitHubProfile(IDS.incrementAndGet(), "summary-viewer", null, null, null));
        members.saveAndFlush(ProjectMember.builder().project(projects.findById(projectId).orElseThrow())
                .user(users.findById(viewer.userId()).orElseThrow()).role(ProjectMember.Role.VIEWER).build());
        editSummary(viewer, id, "{\"teamSummary\":\"x\",\"version\":0}").andExpect(status().isForbidden());
        var outsider = login.login(new GitHubProfile(IDS.incrementAndGet(), "summary-outsider", null, null, null));
        editSummary(outsider, id, "{\"teamSummary\":\"x\",\"version\":0}").andExpect(status().isNotFound());
        mvc.perform(patch(base("/pull-requests/" + id)).with(as(owner)).contentType("application/json")
                .content("{\"teamSummary\":\"x\",\"version\":0}")).andExpect(status().isForbidden());
    }

    @Test
    void reviewersWhoAlreadyReviewedStayListedButLeaveMyReviewQueue() throws Exception {
        long otherId = IDS.incrementAndGet();
        var me = new GitHubPullRequests.User(ownerGithubId, "remote-" + ownerGithubId, "User", null);
        var requested = withReviewers(fetched(1, 1, otherId, "open", false, null, PullRequest.ReviewDecision.PENDING), ownerGithubId, otherId + 1);
        stubFetch(requested);
        sync().andExpect(status().isOk());
        list("").andExpect(jsonPath("$.items[0].reviewers.length()").value(2));

        // 내가 리뷰를 제출하면 GitHub는 요청 목록에서 나를 빼지만, 맡긴 리뷰어로는 남는다.
        var reviewed = withReviewers(fetched(1, 1, otherId, "open", false, null, PullRequest.ReviewDecision.APPROVED), otherId + 1);
        stubFetch(new GitHubPullRequests.Fetched(reviewed.pull(), reviewed.reviewDecision(), List.of(me)));
        sync().andExpect(status().isOk());
        list("").andExpect(jsonPath("$.items[0].reviewers.length()").value(2));
        list("?state=open&reviewer=me").andExpect(jsonPath("$.items.length()").value(0));

        // 요청이 취소되고 리뷰도 안 한 사람은 빠진다.
        stubFetch(new GitHubPullRequests.Fetched(fetched(1, 1, otherId, "open", false, null, PullRequest.ReviewDecision.APPROVED).pull(),
                PullRequest.ReviewDecision.APPROVED, List.of(me)));
        sync().andExpect(status().isOk());
        list("").andExpect(jsonPath("$.items[0].reviewers.length()").value(1))
                .andExpect(jsonPath("$.items[0].reviewers[0].login").value("remote-" + ownerGithubId));
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
    void reviewerMeListsOnlyOpenReadyPullRequestsWhereIAmRequested() throws Exception {
        long otherId = IDS.incrementAndGet();
        stubFetch(withReviewers(fetched(1, 1, otherId, "open", false, null, PullRequest.ReviewDecision.PENDING), ownerGithubId),
                withReviewers(fetched(2, 2, otherId, "open", false, null, PullRequest.ReviewDecision.PENDING), otherId + 1),
                withReviewers(fetched(3, 3, otherId, "open", true, null, PullRequest.ReviewDecision.PENDING), ownerGithubId),
                withReviewers(fetched(4, 4, otherId, "closed", false, Instant.parse("2026-10-30T02:00:00Z"), PullRequest.ReviewDecision.UNKNOWN), ownerGithubId));
        sync().andExpect(status().isOk());

        list("?state=open&reviewer=me").andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].number").value(1))
                .andExpect(jsonPath("$.counts.open").value(1));
        list("?reviewer=me").andExpect(jsonPath("$.items.length()").value(1));
        list("?state=open").andExpect(jsonPath("$.items.length()").value(3));
    }

    @Test
    void reviewRequestsFollowGithubWhenTheRequestIsRemovedOrMadeAgain() throws Exception {
        long otherId = IDS.incrementAndGet();
        var requested = withReviewers(fetched(1, 1, otherId, "open", false, null, PullRequest.ReviewDecision.PENDING), ownerGithubId);
        stubFetch(requested);
        sync().andExpect(status().isOk());
        list("?state=open&reviewer=me").andExpect(jsonPath("$.items.length()").value(1));

        stubFetch(withReviewers(fetched(1, 1, otherId, "open", false, null, PullRequest.ReviewDecision.APPROVED), otherId + 1));
        sync().andExpect(status().isOk());
        list("?state=open&reviewer=me").andExpect(jsonPath("$.items.length()").value(0)).andExpect(jsonPath("$.counts.open").value(0));

        stubFetch(requested);
        sync().andExpect(status().isOk());
        list("?state=open&reviewer=me").andExpect(jsonPath("$.items.length()").value(1));
        // 같은 요청을 다시 수집해도 중복되지 않는다.
        sync().andExpect(status().isOk());
        list("?state=open&reviewer=me").andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void reviewerFilterIsPerUserAndRejectsBadOrConflictingValues() throws Exception {
        long otherId = IDS.incrementAndGet();
        stubFetch(withReviewers(fetched(1, 1, otherId, "open", false, null, PullRequest.ReviewDecision.PENDING), ownerGithubId));
        sync().andExpect(status().isOk());
        var viewer = login.login(new GitHubProfile(IDS.incrementAndGet(), "pr-reviewer-viewer", null, null, null));
        members.saveAndFlush(ProjectMember.builder().project(projects.findById(projectId).orElseThrow())
                .user(users.findById(viewer.userId()).orElseThrow()).role(ProjectMember.Role.VIEWER).build());
        mvc.perform(get(base("/pull-requests") + "?reviewer=me").with(as(viewer))).andExpect(jsonPath("$.items.length()").value(0));
        list("?reviewer=someone").andExpect(status().isBadRequest());
        list("?reviewer=me&author=me").andExpect(status().isBadRequest());
    }

    @Test
    void listPagesLikeTheIssueListWithTotalAndFiltersByReviewState() throws Exception {
        stubFetch(fetched(1, 1, ownerGithubId, "open", false, null, PullRequest.ReviewDecision.PENDING),
                fetched(2, 2, ownerGithubId, "open", false, null, PullRequest.ReviewDecision.CHANGES_REQUESTED),
                fetched(3, 3, ownerGithubId, "open", true, null, PullRequest.ReviewDecision.PENDING),
                fetched(4, 4, ownerGithubId, "open", false, null, PullRequest.ReviewDecision.APPROVED),
                fetched(5, 5, ownerGithubId, "closed", false, Instant.parse("2026-10-30T02:00:00Z"), PullRequest.ReviewDecision.UNKNOWN));
        sync().andExpect(status().isOk());

        list("?state=all&size=2&page=1").andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.hasNext").value(true)).andExpect(jsonPath("$.total").value(5));
        list("?state=all&size=2&page=3").andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.hasNext").value(false));

        // 초안은 검토 대기에서 빠진다. 탭 개수(counts)는 필터와 상관없이 전체 기준이다.
        list("?state=open&review=pending").andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].number").value(1))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.counts.open").value(4));
        list("?state=open&review=changes_requested").andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].number").value(2));
        list("?review=approved").andExpect(status().isBadRequest());
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
    void mergingKeepsTheLastReviewDecisionAndPullRequestsFirstSeenClosedStayUnknown() throws Exception {
        stubFetch(fetched(1, 1, ownerGithubId, "open", false, null, PullRequest.ReviewDecision.APPROVED),
                fetched(3, 3, ownerGithubId, "open", false, null, PullRequest.ReviewDecision.APPROVED));
        sync().andExpect(status().isOk());
        stubFetch(withUpdatedAt(fetched(1, 1, ownerGithubId, "closed", false, null, PullRequest.ReviewDecision.UNKNOWN), "2026-10-31T01:00:00Z"),
                withUpdatedAt(fetched(3, 3, ownerGithubId, "closed", false, Instant.parse("2026-10-31T01:00:00Z"), PullRequest.ReviewDecision.UNKNOWN), "2026-10-31T01:00:00Z"),
                fetched(2, 2, ownerGithubId, "closed", false, Instant.parse("2026-10-30T02:00:00Z"), PullRequest.ReviewDecision.UNKNOWN));
        sync().andExpect(status().isOk());
        list("?state=merged").andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[?(@.number == 3)].reviewDecision").value("APPROVED"))
                .andExpect(jsonPath("$.items[?(@.number == 2)].reviewDecision").value("UNKNOWN"));
        list("?state=closed").andExpect(jsonPath("$.items[0].reviewDecision").value("APPROVED"));

        // 병합 없이 닫힌 PR이 다시 열리면 GitHub 리뷰로 새로 계산한다.
        stubFetch(withUpdatedAt(fetched(1, 1, ownerGithubId, "open", false, null, PullRequest.ReviewDecision.CHANGES_REQUESTED), "2026-10-31T02:00:00Z"));
        sync().andExpect(status().isOk());
        list("?state=open").andExpect(jsonPath("$.items[0].reviewDecision").value("CHANGES_REQUESTED"));
    }

    @Test
    void approvalGivenRightBeforeMergeIsKeptEvenIfZelloNeverSawItOpen() throws Exception {
        long otherId = IDS.incrementAndGet();
        var me = new GitHubPullRequests.User(ownerGithubId, "remote-" + ownerGithubId, "User", null);
        stubFetch(withReviewers(fetched(1, 1, otherId, "open", false, null, PullRequest.ReviewDecision.PENDING), ownerGithubId));
        sync().andExpect(status().isOk());

        // GitHub에서 승인 → 병합까지 한 뒤 처음 새로고침한 경우. 열려 있던 PR이라 리뷰를 한 번 더 읽는다.
        var merged = withUpdatedAt(fetched(1, 1, otherId, "closed", false, Instant.parse("2026-10-31T01:00:00Z"), PullRequest.ReviewDecision.UNKNOWN),
                "2026-10-31T01:00:00Z");
        stubFetch(new GitHubPullRequests.Fetched(merged.pull(), PullRequest.ReviewDecision.APPROVED, List.of(me)));
        sync().andExpect(status().isOk());
        var asked = org.mockito.ArgumentCaptor.forClass(java.util.Set.class);
        verify(pulls, atLeastOnce()).fetch(anyString(), eq("team"), eq("server"), asked.capture());
        assertThat(asked.getValue()).containsExactly(repoId * 100 + 1);

        list("?state=merged").andExpect(jsonPath("$.items[0].reviewDecision").value("APPROVED"))
                .andExpect(jsonPath("$.items[0].reviewers[0].login").value("remote-" + ownerGithubId));
        list("?state=open&reviewer=me").andExpect(jsonPath("$.items.length()").value(0));

        // 이미 닫힌 PR로 저장된 뒤에는 다시 읽지 않는다.
        sync().andExpect(status().isOk());
        var askedAgain = org.mockito.ArgumentCaptor.forClass(java.util.Set.class);
        verify(pulls, atLeastOnce()).fetch(anyString(), eq("team"), eq("server"), askedAgain.capture());
        assertThat(askedAgain.getAllValues().getLast()).isEmpty();
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
    void detailFetchesChangeStatsForOpenPullRequestsAndKeepsThemWhenGithubFails() throws Exception {
        stubFetch(fetched(1, 1, ownerGithubId, "open", false, null, PullRequest.ReviewDecision.PENDING));
        sync().andExpect(status().isOk());
        String id = JsonPath.read(list("").andReturn().getResponse().getContentAsString(), "$.items[0].id");
        list("").andExpect(jsonPath("$.items[0].changedFiles").doesNotExist());

        when(pulls.stats(anyString(), eq("team"), eq("server"), eq(1))).thenReturn(new GitHubPullRequests.Stats(3, 40, 7, 2));
        var first = mvc.perform(get(base("/pull-requests/" + id)).with(as(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.changedFiles").value(3)).andExpect(jsonPath("$.additions").value(40))
                .andExpect(jsonPath("$.deletions").value(7)).andReturn();
        int version = JsonPath.read(first.getResponse().getContentAsString(), "$.version");

        // 같은 값이면 version이 오르지 않아 팀 요약 수정을 막지 않는다.
        mvc.perform(get(base("/pull-requests/" + id)).with(as(owner))).andExpect(jsonPath("$.version").value(version));

        when(pulls.stats(anyString(), anyString(), anyString(), anyInt())).thenThrow(new ApiException(502, "GITHUB_UNAVAILABLE", "응답 없음"));
        mvc.perform(get(base("/pull-requests/" + id)).with(as(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.changedFiles").value(3)).andExpect(jsonPath("$.body").value("본문"));
    }

    @Test
    void closedPullRequestsFetchChangeStatsOnlyOnce() throws Exception {
        stubFetch(fetched(1, 1, ownerGithubId, "closed", false, Instant.parse("2026-10-30T02:00:00Z"), PullRequest.ReviewDecision.UNKNOWN));
        sync().andExpect(status().isOk());
        String id = JsonPath.read(list("").andReturn().getResponse().getContentAsString(), "$.items[0].id");
        when(pulls.stats(anyString(), anyString(), anyString(), anyInt())).thenReturn(new GitHubPullRequests.Stats(1, 2, 3, 1));
        mvc.perform(get(base("/pull-requests/" + id)).with(as(owner))).andExpect(jsonPath("$.changedFiles").value(1));
        mvc.perform(get(base("/pull-requests/" + id)).with(as(owner))).andExpect(jsonPath("$.deletions").value(3));
        verify(pulls, times(1)).stats(anyString(), anyString(), anyString(), anyInt());
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
        when(pulls.fetch(anyString(), eq("team"), eq("server"), any())).thenReturn(List.of(items));
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

    private GitHubPullRequests.Fetched withReviewers(GitHubPullRequests.Fetched item, long... githubIds) {
        var r = item.pull();
        var reviewers = java.util.Arrays.stream(githubIds).mapToObj(id -> new GitHubPullRequests.User(id, "remote-" + id, "User", null)).toList();
        return new GitHubPullRequests.Fetched(new GitHubPullRequests.Remote(r.id(), r.number(), r.htmlUrl(), r.title(), r.body(), r.state(),
                r.draft(), r.user(), r.head(), r.base(), r.mergeCommitSha(), r.createdAt(), r.updatedAt(), r.closedAt(), r.mergedAt(), reviewers),
                item.reviewDecision());
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

    private ResultActions createWith(String key, String extraFields) throws Exception {
        return mvc.perform(post(base("/pull-requests")).with(as(owner)).with(csrf()).contentType("application/json")
                .content("{\"repositoryId\":\"" + repositoryDbId + "\",\"title\":\"새 PR\",\"headBranch\":\"feat/new\",\"baseBranch\":\"main\""
                        + ",\"teamSummary\":\"팀 요약\",\"draft\":false,\"requestKey\":\"" + key + "\"," + extraFields + "}"));
    }

    private ResultActions editSummary(ZelloPrincipal principal, String id, String json) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(base("/pull-requests/" + id))
                .with(as(principal)).with(csrf()).contentType("application/json").content(json));
    }

    private GitHubPullRequests.Remote withRequested(GitHubPullRequests.Remote r, GitHubPullRequests.User... reviewers) {
        return new GitHubPullRequests.Remote(r.id(), r.number(), r.htmlUrl(), r.title(), r.body(), r.state(), r.draft(), r.user(), r.head(),
                r.base(), r.mergeCommitSha(), r.createdAt(), r.updatedAt(), r.closedAt(), r.mergedAt(), List.of(reviewers));
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
