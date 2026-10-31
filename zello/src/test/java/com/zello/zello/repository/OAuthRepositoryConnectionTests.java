package com.zello.zello.repository;

import com.jayway.jsonpath.JsonPath;
import com.zello.zello.api.ApiException;
import com.zello.zello.auth.*;
import com.zello.zello.domain.project.*;
import com.zello.zello.domain.repository.GitRepositoryRepository;
import com.zello.zello.domain.user.UserRepository;
import com.zello.zello.integration.github.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "zello.auth.github.app-type=OAUTH")
@AutoConfigureMockMvc
class OAuthRepositoryConnectionTests {
    private static final AtomicLong IDS = new AtomicLong(110_000_000);
    @Autowired MockMvc mvc;
    @Autowired GitHubLoginService login;
    @Autowired GitRepositoryRepository repositories;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectRepository projects;
    @Autowired ProjectMemberRepository members;
    @Autowired UserRepository users;
    @MockitoBean GitHubOAuthRepositories github;
    @MockitoBean GitHubTokenProvider tokens;
    private ZelloPrincipal owner;
    private long projectId;
    private long repoId;

    @BeforeEach
    void setup() throws Exception {
        long id = IDS.incrementAndGet();
        owner = login.login(new GitHubProfile(id, "oauth-user-" + id, null, null, null));
        repoId = IDS.incrementAndGet();
        when(tokens.oauthToken(any(), any())).thenReturn("encrypted-session-token");
        var created = mvc.perform(post("/api/projects").with(as(owner)).with(csrf()).contentType("application/json")
                .content("{\"name\":\"OAuth 프로젝트\"}"))
                .andExpect(status().isCreated()).andReturn();
        projectId = Long.parseLong(JsonPath.read(created.getResponse().getContentAsString(), "$.id"));
        when(github.repository(anyString(), eq(repoId))).thenAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new GitHubGateway.RemoteRepository(repoId, new GitHubGateway.Owner(45, "team", "Organization", null),
                    "server", "team/server", "https://github.com/team/server", "검증한 원본 설명", "Java", "main", true, false);
        });
    }

    @Test
    void connectsByRepositoryIdWithoutCreatingAnInstallationOrTrustingClientMetadata() throws Exception {
        long installations = jdbc.queryForObject("SELECT COUNT(*) FROM github_installations", Long.class);
        mvc.perform(post(path()).with(as(owner)).with(csrf()).contentType("application/json")
                        .content("{\"githubRepositoryId\":\"" + repoId + "\",\"name\":\"forged-name\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("server"))
                .andExpect(jsonPath("$.installationId").isEmpty());
        var row = repositories.findByProjectIdAndGithubRepoId(projectId, repoId).orElseThrow();
        assertThat(row.getInstallationId()).isNull();
        assertThat(row.getAuthorizationMethod().name()).isEqualTo("OAUTH");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM github_installations", Long.class)).isEqualTo(installations);
    }

    @Test
    void supportsMultipleRepositoriesInOneProjectAndChecksEachCurrentGithubPermission() throws Exception {
        long secondId = IDS.incrementAndGet();
        when(github.repository(anyString(), eq(secondId))).thenReturn(new GitHubGateway.RemoteRepository(secondId,
                new GitHubGateway.Owner(45, "team", "Organization", null), "client", "team/client",
                "https://github.com/team/client", null, "TypeScript", "main", true, false));
        connect().andExpect(status().isCreated());
        mvc.perform(post(path()).with(as(owner)).with(csrf()).contentType("application/json")
                .content("{\"githubRepositoryId\":\"" + secondId + "\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get(path()).with(as(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("server"))
                .andExpect(jsonPath("$[1].name").value("client"));
        connect().andExpect(status().isConflict());
        when(github.repository(anyString(), eq(repoId))).thenThrow(new ApiException(404, "GITHUB_RESOURCE_NOT_FOUND", "권한 취소"));
        mvc.perform(get(path()).with(as(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].name").value("client"));
        assertThat(repositories.findByProjectIdOrderByIdAsc(projectId)).hasSize(2);
    }

    @Test
    void duplicateAndReconnectKeepTheRepositoryIdentity() throws Exception {
        var first = connect().andExpect(status().isCreated()).andReturn();
        String id = JsonPath.read(first.getResponse().getContentAsString(), "$.id");
        connect().andExpect(status().isConflict());
        mvc.perform(delete(path() + "/" + id).with(as(owner)).with(csrf())).andExpect(status().isNoContent());
        connect().andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void revokedAccessHidesCachedPrivateNamesAndPreventsConnection() throws Exception {
        connect().andExpect(status().isCreated());
        when(github.repository(anyString(), eq(repoId))).thenThrow(new ApiException(404, "GITHUB_RESOURCE_NOT_FOUND", "접근할 수 없습니다."));
        mvc.perform(get(path()).with(as(owner))).andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void nonmemberAndViewerCannotConnectAndLocalAuthorizationPrecedesGithubRequests() throws Exception {
        var visitor = login.login(new GitHubProfile(IDS.incrementAndGet(), "visitor", null, null, null));
        mvc.perform(post(path()).with(as(visitor)).with(csrf()).contentType("application/json").content(body()))
                .andExpect(status().isNotFound());
        members.saveAndFlush(ProjectMember.builder().project(projects.findById(projectId).orElseThrow())
                .user(users.findById(visitor.userId()).orElseThrow()).role(ProjectMember.Role.VIEWER).build());
        mvc.perform(post(path()).with(as(visitor)).with(csrf()).contentType("application/json").content(body()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(github, tokens);
    }

    @Test
    void csrfAndGithubReauthenticationAreRequired() throws Exception {
        mvc.perform(post(path()).with(as(owner)).contentType("application/json").content(body())).andExpect(status().isForbidden());
        when(tokens.oauthToken(any(), any())).thenThrow(new ApiException(401, "GITHUB_REAUTH_REQUIRED", "다시 로그인해 주세요."));
        connect().andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("GITHUB_REAUTH_REQUIRED"));
        assertThat(repositories.findByProjectIdAndGithubRepoId(projectId, repoId)).isEmpty();
    }

    private org.springframework.test.web.servlet.ResultActions connect() throws Exception {
        return mvc.perform(post(path()).with(as(owner)).with(csrf()).contentType("application/json").content(body()));
    }
    private String path() { return "/api/projects/" + projectId + "/repositories"; }
    private String body() { return "{\"githubRepositoryId\":\"" + repoId + "\"}"; }
    private org.springframework.test.web.servlet.request.RequestPostProcessor as(ZelloPrincipal principal) {
        return authentication(new UsernamePasswordAuthenticationToken(principal, "unused", principal.getAuthorities()));
    }
}
