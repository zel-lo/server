package com.zello.zello.repository;

import com.jayway.jsonpath.JsonPath;
import com.zello.zello.api.ApiException;
import com.zello.zello.auth.*;
import com.zello.zello.domain.project.*;
import com.zello.zello.domain.repository.GitRepositoryRepository;
import com.zello.zello.domain.user.UserRepository;
import com.zello.zello.integration.github.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class RepositoryConnectionTests {
    private static final AtomicLong IDS = new AtomicLong(70_000_000);
    @Autowired MockMvc mvc;
    @Autowired GitHubLoginService login;
    @Autowired ProjectMemberRepository members;
    @Autowired UserRepository users;
    @Autowired ProjectRepository projects;
    @Autowired GitRepositoryRepository repositories;
    @MockitoBean GitHubGateway github;
    @MockitoBean GitHubTokenProvider tokens;
    private ZelloPrincipal owner;
    private long projectId;
    private long installationId;
    private long githubRepoId;

    @BeforeEach
    void prepare() throws Exception {
        long id = IDS.incrementAndGet();
        owner = login.login(new GitHubProfile(id, "owner-" + id, null, null, null));
        installationId = IDS.incrementAndGet();
        githubRepoId = IDS.incrementAndGet();
        when(tokens.token(any(), any())).thenReturn("session-token-never-exposed");
        var result = mvc.perform(post("/api/projects").with(as(owner)).with(csrf()).contentType("application/json")
                .content("{\"name\":\"저장소 연결 프로젝트\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.canManageRepositories").value(true)).andReturn();
        projectId = Long.parseLong(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
        when(github.verify(anyString(), eq(installationId), eq(githubRepoId))).thenReturn(verified("Organization"));
        when(github.accessibleRepositoryIds(anyString(), eq(installationId))).thenReturn(Set.of(Long.toString(githubRepoId)));
    }

    @Test
    void projectCreationCreatesOwnerMembershipAndValidatesInput() throws Exception {
        assertThat(members.findByIdAndLeftAtIsNull(new ProjectMemberId(projectId, owner.userId()))).isPresent();
        mvc.perform(get("/api/projects").with(as(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(Long.toString(projectId)));
        mvc.perform(post("/api/projects").with(as(owner)).with(csrf()).contentType("application/json").content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void connectsOrganizationRepositoryWithoutPretendingInitialSyncIsDone() throws Exception {
        connect().andExpect(status().isCreated()).andExpect(jsonPath("$.owner").value("test-team"))
                .andExpect(jsonPath("$.githubRepositoryId").value(Long.toString(githubRepoId)))
                .andExpect(jsonPath("$.initialSyncCompleted").value(false));
        mvc.perform(get(path()).with(as(owner))).andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("server"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("session-token"))));
        assertThat(repositories.findByProjectIdAndGithubRepoId(projectId, githubRepoId)).isPresent();
    }

    @Test
    void connectsPersonalRepositoryUsingTheSameFlow() throws Exception {
        when(github.verify(anyString(), eq(installationId), eq(githubRepoId))).thenReturn(verified("User"));
        connect().andExpect(status().isCreated()).andExpect(jsonPath("$.owner").value("personal-owner"));
    }

    @Test
    void duplicateReturnsConflictAndReconnectReusesTheSameRow() throws Exception {
        var first = connect().andExpect(status().isCreated()).andReturn();
        String id = JsonPath.read(first.getResponse().getContentAsString(), "$.id");
        connect().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REPOSITORY_ALREADY_CONNECTED"));
        mvc.perform(delete(path() + "/" + id).with(as(owner)).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get(path()).with(as(owner))).andExpect(jsonPath("$.length()").value(0));
        connect().andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void nonmemberCannotReadOrConnectAndDoesNotCallGithub() throws Exception {
        var outsider = login.login(new GitHubProfile(IDS.incrementAndGet(), "outsider", null, null, null));
        mvc.perform(get(path()).with(as(outsider))).andExpect(status().isNotFound());
        mvc.perform(post(path()).with(as(outsider)).with(csrf()).contentType("application/json").content(body()))
                .andExpect(status().isNotFound());
        verifyNoInteractions(github, tokens);
    }

    @Test
    void viewerCanReadOnlyGithubAccessibleRepositoriesButCannotConnectOrDisconnect() throws Exception {
        connect().andExpect(status().isCreated());
        var viewer = login.login(new GitHubProfile(IDS.incrementAndGet(), "viewer", null, null, null));
        members.saveAndFlush(ProjectMember.builder().project(projects.findById(projectId).orElseThrow())
                .user(users.findById(viewer.userId()).orElseThrow()).role(ProjectMember.Role.VIEWER).build());
        mvc.perform(get(path()).with(as(viewer))).andExpect(status().isOk());
        clearInvocations(github, tokens);
        mvc.perform(post(path()).with(as(viewer)).with(csrf()).contentType("application/json").content(body()))
                .andExpect(status().isForbidden());
        mvc.perform(delete(path() + "/1").with(as(viewer)).with(csrf())).andExpect(status().isForbidden());
        verifyNoInteractions(github, tokens);
    }

    @Test
    void forgedGithubIdsDoNotCreateLocalRecords() throws Exception {
        when(github.verify(anyString(), eq(installationId), eq(githubRepoId)))
                .thenThrow(new ApiException(404, "GITHUB_RESOURCE_NOT_FOUND", "접근할 수 없습니다."));
        connect().andExpect(status().isNotFound());
        assertThat(repositories.findByProjectIdAndGithubRepoId(projectId, githubRepoId)).isEmpty();
    }

    @Test
    void revokedAccessHidesPreviouslyLinkedPrivateRepositoryNames() throws Exception {
        connect().andExpect(status().isCreated());
        when(github.accessibleRepositoryIds(anyString(), eq(installationId))).thenReturn(Set.of());
        mvc.perform(get(path()).with(as(owner))).andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void expiredGithubTokenDoesNotReturnCachedPrivateMetadata() throws Exception {
        connect().andExpect(status().isCreated());
        when(tokens.token(any(), any())).thenThrow(new ApiException(401, "GITHUB_REAUTH_REQUIRED", "다시 로그인해 주세요."));
        mvc.perform(get(path()).with(as(owner))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("GITHUB_REAUTH_REQUIRED"));
    }

    @Test
    void mutationsRequireCsrfAndAuthentication() throws Exception {
        mvc.perform(post(path()).with(as(owner)).contentType("application/json").content(body())).andExpect(status().isForbidden());
        mvc.perform(get("/api/projects")).andExpect(status().isUnauthorized());
        mvc.perform(post(path()).with(as(owner)).with(csrf()).contentType("application/json").content("{\"githubInstallationId\":-1}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(github, tokens);
    }

    private org.springframework.test.web.servlet.ResultActions connect() throws Exception {
        return mvc.perform(post(path()).with(as(owner)).with(csrf()).contentType("application/json").content(body()));
    }

    private String path() { return "/api/projects/" + projectId + "/repositories"; }
    private String body() { return "{\"githubInstallationId\":\"" + installationId + "\",\"githubRepositoryId\":\"" + githubRepoId + "\"}"; }
    private org.springframework.test.web.servlet.request.RequestPostProcessor as(ZelloPrincipal principal) {
        return authentication(new UsernamePasswordAuthenticationToken(principal, "unused", principal.getAuthorities()));
    }

    private GitHubGateway.VerifiedRepository verified(String type) {
        var account = new GitHubGateway.Owner(IDS.incrementAndGet(), "Organization".equals(type) ? "test-team" : "personal-owner", type, null);
        var installation = new GitHubGateway.Installation(installationId, account, Map.of("metadata", "read"), null);
        var repo = new GitHubGateway.RemoteRepository(githubRepoId, account, "server", account.login() + "/server",
                "https://github.com/" + account.login() + "/server", "실제 GitHub 메타데이터", "Java", "main", true, false);
        return new GitHubGateway.VerifiedRepository(installation, repo);
    }
}
