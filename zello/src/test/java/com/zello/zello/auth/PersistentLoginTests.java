package com.zello.zello.auth;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.convert.ConversionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=",
        "spring.session.jdbc.initialize-schema=always",
        "spring.session.timeout=30m",
        "server.servlet.session.cookie.name=JSESSIONID",
        "zello.auth.github.enabled=true",
        "zello.auth.github.client-id=test-id",
        "zello.auth.github.client-secret=test-secret",
        "zello.auth.github.redirect-uri=http://127.0.0.1:8080/login/oauth2/code/github"
})
@AutoConfigureMockMvc
class PersistentLoginTests {
    private static final AtomicLong IDS = new AtomicLong(90_000_000);
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired @Qualifier("springSessionConversionService") ConversionService conversions;
    @MockitoBean(name = "githubRemoteUserService") OAuth2UserService<OAuth2UserRequest, OAuth2User> remote;
    @MockitoBean(name = "githubTokenResponseClient") OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenClient;
    private long githubId;

    @BeforeEach
    void github() {
        githubId = IDS.incrementAndGet();
        when(tokenClient.getTokenResponse(any())).thenReturn(OAuth2AccessTokenResponse.withToken("persistent-test-token-secret")
                .tokenType(OAuth2AccessToken.TokenType.BEARER).expiresIn(3600).refreshToken("persistent-refresh-secret").build());
        when(remote.loadUser(any())).thenReturn(new DefaultOAuth2User(Set.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("id", githubId, "login", "persistent-user", "type", "User"), "id"));
    }

    @Test
    void oauthLoginIsRestoredByANewJdbcRepositoryAndLogoutDeletesThePersistedSession() throws Exception {
        Cookie cookie = login();
        String id = sessionId(cookie);
        var me = mvc.perform(server(get("/api/me")).cookie(cookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.githubId").value(Long.toString(githubId))).andReturn();
        assertThat(me.getResponse().getContentAsString()).doesNotContain("persistent-test-token-secret");

        // A separate repository has no in-memory session objects from the login request.
        var restarted = freshRepository();
        org.springframework.session.Session session = restarted.findById(id);
        assertThat(session).isNotNull();
        SecurityContext context = session.getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(context.getAuthentication().getPrincipal()).isInstanceOf(ZelloPrincipal.class);
        assertThat(((ZelloPrincipal) context.getAuthentication().getPrincipal()).githubId()).isEqualTo(githubId);
        assertThat(session.getAttributeNames()).anyMatch(name -> name.contains("AUTHORIZED_CLIENT"));
        var bytes = jdbc.queryForList("SELECT ATTRIBUTE_BYTES FROM SPRING_SESSION_ATTRIBUTES WHERE SESSION_PRIMARY_ID = (SELECT PRIMARY_ID FROM SPRING_SESSION WHERE SESSION_ID = ?)", byte[].class, id);
        assertThat(bytes).isNotEmpty().allSatisfy(value -> assertThat(new String(value, StandardCharsets.ISO_8859_1))
                .doesNotContain("persistent-test-token-secret", "persistent-refresh-secret", "SPRING_SECURITY_CONTEXT"));

        var csrf = mvc.perform(server(get("/api/auth/csrf")).cookie(cookie)).andExpect(status().isOk()).andReturn();
        String token = JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");
        mvc.perform(server(post("/api/auth/logout")).cookie(cookie).header("X-CSRF-TOKEN", token)).andExpect(status().isNoContent());
        assertThat(restarted.findById(id)).isNull();
        mvc.perform(server(get("/api/me")).cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void expiredPersistedSessionCannotAuthenticate() throws Exception {
        Cookie cookie = login();
        long expiredAt = System.currentTimeMillis() - 10_000;
        jdbc.update("UPDATE SPRING_SESSION SET LAST_ACCESS_TIME = ?, EXPIRY_TIME = ?, MAX_INACTIVE_INTERVAL = 1 WHERE SESSION_ID = ?",
                expiredAt - 1000, expiredAt, sessionId(cookie));
        mvc.perform(server(get("/api/me")).cookie(cookie)).andExpect(status().isUnauthorized());
    }

    private Cookie login() throws Exception {
        var start = mvc.perform(server(get("/oauth2/authorization/github"))).andExpect(status().is3xxRedirection()).andReturn();
        Cookie cookie = start.getResponse().getCookie("JSESSIONID");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(start.getResponse().getHeader("Set-Cookie")).contains("SameSite=Lax");
        String state = null;
        for (String part : URI.create(start.getResponse().getRedirectedUrl()).getRawQuery().split("&")) {
            String[] pieces = part.split("=", 2);
            if (pieces[0].equals("state")) state = URLDecoder.decode(pieces[1], StandardCharsets.UTF_8);
        }
        assertThat(state).isNotBlank();
        var finish = mvc.perform(server(get("/login/oauth2/code/github")).cookie(cookie).param("state", state).param("code", "test-code"))
                .andExpect(redirectedUrl("http://127.0.0.1:5173/#/auth/callback")).andReturn();
        Cookie authenticated = finish.getResponse().getCookie("JSESSIONID");
        assertThat(authenticated).isNotNull();
        assertThat(authenticated.getValue()).isNotEqualTo(cookie.getValue());
        return authenticated;
    }

    private String sessionId(Cookie cookie) { return new String(Base64.getDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8); }
    private JdbcIndexedSessionRepository freshRepository() {
        var repository = new JdbcIndexedSessionRepository(jdbc, new TransactionTemplate(transactions));
        repository.setConversionService(conversions);
        return repository;
    }
    private MockHttpServletRequestBuilder server(MockHttpServletRequestBuilder request) {
        return request.with(r -> { r.setServerName("127.0.0.1"); r.setServerPort(8080); return r; });
    }
}
