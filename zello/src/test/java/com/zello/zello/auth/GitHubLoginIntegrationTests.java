package com.zello.zello.auth;

import com.jayway.jsonpath.JsonPath;
import com.zello.zello.domain.common.EntitySupport;
import com.zello.zello.domain.user.UserRepository;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "zello.auth.github.enabled=true",
        "zello.auth.github.app-type=APP",
        "zello.auth.github.client-id=test-client-id",
        "zello.auth.github.client-secret=test-only-secret",
        "zello.auth.github.redirect-uri=http://127.0.0.1:8080/login/oauth2/code/github",
        "zello.auth.frontend-base-url=http://127.0.0.1:5173"
})
@AutoConfigureMockMvc
class GitHubLoginIntegrationTests {
    private static final AtomicLong IDS = new AtomicLong(10_000_000);
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired GitHubLoginService loginService;
    @MockitoBean(name = "githubRemoteUserService")
    OAuth2UserService<OAuth2UserRequest, OAuth2User> remote;
    @MockitoBean(name = "githubTokenResponseClient")
    OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokens;
    private long githubId;

    @BeforeEach
    void prepareGithub() {
        githubId = IDS.incrementAndGet();
        when(tokens.getTokenResponse(any())).thenReturn(OAuth2AccessTokenResponse.withToken("server-only-access-token")
                .tokenType(OAuth2AccessToken.TokenType.BEARER).expiresIn(28_800)
                .refreshToken("server-only-refresh-token").build());
        when(remote.loadUser(any())).thenReturn(profile("octocat", null));
    }

    @Test
    void firstLoginCreatesAnAccountAndServerSessionWithoutExposingTokens() throws Exception {
        var started = begin();
        assertThat(started.location()).startsWith("https://github.com/login/oauth/authorize?")
                .contains("state=", "code_challenge=", "code_challenge_method=S256")
                .doesNotContain("scope=", "test-only-secret");
        started.session().setNew(false);
        String previousSessionId = started.session().getId();
        var authenticated = finish(started);
        assertThat(authenticated.getId()).isNotEqualTo(previousSessionId);
        var me = mvc.perform(server(get("/api/me")).session(authenticated))
                .andExpect(status().isOk()).andExpect(jsonPath("$.githubId").value(Long.toString(githubId)))
                .andExpect(jsonPath("$.login").value("octocat"))
                .andExpect(jsonPath("$.displayName").value("octocat"))
                .andExpect(jsonPath("$.email").isEmpty()).andReturn();
        assertThat(me.getResponse().getContentAsString()).doesNotContain("server-only", "accessToken", "refreshToken", "clientSecret");
        assertThat(countAccounts()).isEqualTo(1);
        assertThat(countUsers()).isEqualTo(1);
    }

    @Test
    void repeatLoginUsesGithubIdAndRefreshesTheProfile() throws Exception {
        var first = finish(begin());
        String firstId = JsonPath.read(mvc.perform(server(get("/api/me")).session(first)).andReturn()
                .getResponse().getContentAsString(), "$.id");
        when(remote.loadUser(any())).thenReturn(profile("renamed-login", "새 이름"));
        var second = finish(begin());
        mvc.perform(server(get("/api/me")).session(second))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(firstId))
                .andExpect(jsonPath("$.login").value("renamed-login"))
                .andExpect(jsonPath("$.displayName").value("새 이름"));
        assertThat(countAccounts()).isEqualTo(1);
        assertThat(countUsers()).isEqualTo(1);
    }

    @Test
    void stateMismatchDoesNotExchangeACodeOrCreateAUser() throws Exception {
        var started = begin();
        mvc.perform(server(get("/login/oauth2/code/github")).session(started.session())
                        .param("code", "fake-code").param("state", "forged-state"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://127.0.0.1:5173/#/login?error=expired"));
        verifyNoInteractions(tokens, remote);
        assertThat(countUsers()).isZero();
    }

    @Test
    void cancellationReturnsOnlyASafeErrorCode() throws Exception {
        var started = begin();
        mvc.perform(server(get("/login/oauth2/code/github")).session(started.session())
                        .param("error", "access_denied").param("error_description", "private upstream message")
                        .param("state", started.state()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://127.0.0.1:5173/#/login?error=access_denied"));
        verifyNoInteractions(tokens, remote);
        assertThat(countUsers()).isZero();
    }

    @Test
    void unauthenticatedApiRequestsReturnJsonRatherThanAGithubRedirect() throws Exception {
        mvc.perform(server(get("/api/me")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void logoutRequiresCsrfAndInvalidatesTheServerSession() throws Exception {
        var session = finish(begin());
        mvc.perform(server(post("/api/auth/logout")).session(session)).andExpect(status().isForbidden());
        mvc.perform(server(get("/api/me")).session(session)).andExpect(status().isOk());
        var csrf = mvc.perform(server(get("/api/auth/csrf")).session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(csrf, "$.token");
        String header = JsonPath.read(csrf, "$.headerName");
        mvc.perform(server(post("/api/auth/logout")).session(session).header(header, token))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(server(get("/api/me"))).andExpect(status().isUnauthorized());
    }

    @Test
    void withdrawnUsersLoseExistingSessionsAndCannotLogInAgain() throws Exception {
        var session = finish(begin());
        String id = JsonPath.read(mvc.perform(server(get("/api/me")).session(session)).andReturn()
                .getResponse().getContentAsString(), "$.id");
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                users.findById(Long.parseLong(id)).orElseThrow().withdraw(EntitySupport.now()));
        mvc.perform(server(get("/api/me")).session(session)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_INACTIVE"));
        assertThat(session.isInvalid()).isTrue();
        var again = begin();
        mvc.perform(server(get("/login/oauth2/code/github")).session(again.session())
                        .param("code", "fake-code").param("state", again.state()))
                .andExpect(redirectedUrl("http://127.0.0.1:5173/#/login?error=inactive"));
        assertThat(countUsers()).isEqualTo(1);
    }

    @Test
    void otherOriginsCannotReadCredentialedApiResponses() throws Exception {
        mvc.perform(server(get("/api/auth/status")).header("Origin", "https://untrusted.example"))
                .andExpect(status().isForbidden());
        mvc.perform(server(get("/api/auth/status")).header("Origin", "http://127.0.0.1:5173"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void concurrentFirstLoginsStillCreateOneMember() throws Exception {
        var start = new java.util.concurrent.CountDownLatch(1);
        var identity = new GitHubProfile(githubId, "parallel-user", null, null, null);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<ZelloPrincipal> action = () -> {
                start.await();
                return loginService.login(identity);
            };
            var first = workers.submit(action);
            var second = workers.submit(action);
            start.countDown();
            assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS).userId())
                    .isEqualTo(second.get(10, java.util.concurrent.TimeUnit.SECONDS).userId());
        }
        assertThat(countAccounts()).isEqualTo(1);
        assertThat(countUsers()).isEqualTo(1);
    }

    @Test
    void anInvalidUpstreamIdentityCannotCreateAMember() throws Exception {
        when(remote.loadUser(any())).thenReturn(new DefaultOAuth2User(
                Set.of(new SimpleGrantedAuthority("OAUTH2_USER")),
                Map.of("id", githubId, "login", "org", "type", "Organization"), "id"));
        var started = begin();
        mvc.perform(server(get("/login/oauth2/code/github")).session(started.session())
                        .param("code", "fake-code").param("state", started.state()))
                .andExpect(redirectedUrl("http://127.0.0.1:5173/#/login?error=failed"));
        assertThat(countAccounts()).isZero();
        assertThat(countUsers()).isZero();
    }

    private Started begin() throws Exception {
        MvcResult result = mvc.perform(server(get("/oauth2/authorization/github"))
                        .param("returnTo", "https://untrusted.example"))
                .andExpect(status().is3xxRedirection()).andReturn();
        String location = result.getResponse().getRedirectedUrl();
        String state = java.util.Arrays.stream(URI.create(location).getRawQuery().split("&"))
                .filter(parameter -> parameter.startsWith("state="))
                .map(parameter -> URLDecoder.decode(parameter.substring(6), StandardCharsets.UTF_8)).findFirst().orElseThrow();
        return new Started((MockHttpSession) result.getRequest().getSession(false), state, location);
    }

    private MockHttpSession finish(Started started) throws Exception {
        var result = mvc.perform(server(get("/login/oauth2/code/github")).session(started.session())
                        .param("code", "fake-code").param("state", started.state()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://127.0.0.1:5173/#/auth/callback")).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private static MockHttpServletRequestBuilder server(MockHttpServletRequestBuilder request) {
        return request.with(value -> {
            value.setScheme("http"); value.setServerName("127.0.0.1"); value.setServerPort(8080);
            if (value.getSession(false) != null) {
                value.setRequestedSessionId(value.getSession(false).getId());
                value.setRequestedSessionIdValid(true);
            }
            return value;
        });
    }

    private OAuth2User profile(String login, String name) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("id", githubId); attributes.put("login", login); attributes.put("type", "User");
        attributes.put("name", name); attributes.put("avatar_url", "https://avatars.githubusercontent.com/u/1");
        return new DefaultOAuth2User(Set.of(new SimpleGrantedAuthority("OAUTH2_USER")), attributes, "id");
    }

    private int countAccounts() {
        return jdbc.queryForObject("select count(*) from github_accounts where github_id=?", Integer.class, githubId);
    }

    private int countUsers() {
        return jdbc.queryForObject("select count(*) from users u join github_accounts g on u.github_account_id=g.id where g.github_id=?", Integer.class, githubId);
    }

    private record Started(MockHttpSession session, String state, String location) { }
}
