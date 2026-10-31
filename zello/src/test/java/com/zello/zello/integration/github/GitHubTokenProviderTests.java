package com.zello.zello.integration.github;

import com.zello.zello.api.ApiException;
import com.zello.zello.auth.AuthProperties;
import com.zello.zello.auth.ZelloPrincipal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.DefaultMapOAuth2AccessTokenResponseConverter;

import static org.assertj.core.api.Assertions.*;

class GitHubTokenProviderTests {
    @ParameterizedTest
    @ValueSource(strings = {"read:org,read:user,repo", "repo read:org read:user", "repo, read:org, read:user",
            "repo,write:org", "repo,admin:org"})
    void oauthRequiresTheCurrentClientAndRepositoryScopesInsteadOfReusingTheOldGithubAppSession(String grantedScopes) {
        var clients = new HttpSessionOAuth2AuthorizedClientRepository();
        var properties = new AuthProperties("http://127.0.0.1:5173", new AuthProperties.GitHub(true, "oauth-client", "test-secret",
                "http://127.0.0.1:8080/login/oauth2/code/github", AuthProperties.AppType.OAUTH));
        var provider = new GitHubTokenProvider(clients, properties);
        var principal = new ZelloPrincipal(1L, 2L);
        var authentication = new UsernamePasswordAuthenticationToken(principal, "unused", principal.getAuthorities());
        var request = new MockHttpServletRequest();
        var registration = ClientRegistration.withRegistrationId("github").clientId("oauth-client").clientSecret("test-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://127.0.0.1:8080/login/oauth2/code/github")
                .authorizationUri("https://github.com/login/oauth/authorize").tokenUri("https://github.com/login/oauth/access_token").build();
        var missingScopes = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "scope-limited", Instant.now(), Instant.now().plusSeconds(3600));
        clients.saveAuthorizedClient(new OAuth2AuthorizedClient(registration, authentication.getName(), missingScopes), authentication, request, new org.springframework.mock.web.MockHttpServletResponse());
        assertThatThrownBy(() -> provider.oauthToken(authentication, request)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo("GITHUB_REAUTH_REQUIRED"));
        var converter = new DefaultMapOAuth2AccessTokenResponseConverter();
        var valid = converter.convert(java.util.Map.of("access_token", "valid-oauth", "token_type", "bearer",
                "expires_in", 3600, "scope", grantedScopes)).getAccessToken();
        clients.saveAuthorizedClient(new OAuth2AuthorizedClient(registration, authentication.getName(), valid), authentication, request, new org.springframework.mock.web.MockHttpServletResponse());
        assertThat(provider.oauthToken(authentication, request)).isEqualTo("valid-oauth");
        var changedClient = new GitHubTokenProvider(clients, new AuthProperties("http://127.0.0.1:5173", new AuthProperties.GitHub(true, "another-client", "test-secret",
                "http://127.0.0.1:8080/login/oauth2/code/github", AuthProperties.AppType.OAUTH)));
        assertThatThrownBy(() -> changedClient.oauthToken(authentication, request)).isInstanceOf(ApiException.class);
        for (String insufficientScopes : java.util.List.of("repo:status,read:org", "repo,read:org:other", "repo", "read:org")) {
            var limited = converter.convert(java.util.Map.of("access_token", "limited-oauth", "token_type", "bearer",
                    "expires_in", 3600, "scope", insufficientScopes)).getAccessToken();
            clients.saveAuthorizedClient(new OAuth2AuthorizedClient(registration, authentication.getName(), limited), authentication, request,
                    new org.springframework.mock.web.MockHttpServletResponse());
            assertThatThrownBy(() -> provider.oauthToken(authentication, request)).isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.code()).isEqualTo("GITHUB_REAUTH_REQUIRED"));
        }
    }

    @Test
    void usesOnlyTheCurrentServerSessionAndRejectsExpiredOrMissingGithubAuthorization() {
        var clients = new HttpSessionOAuth2AuthorizedClientRepository();
        var properties = new AuthProperties("http://127.0.0.1:5173", new AuthProperties.GitHub(true, "id", "secret",
                "http://127.0.0.1:8080/login/oauth2/code/github", AuthProperties.AppType.APP));
        var provider = new GitHubTokenProvider(clients, properties);
        var principal = new ZelloPrincipal(1L, 2L);
        var authentication = new UsernamePasswordAuthenticationToken(principal, "unused", principal.getAuthorities());
        var request = new MockHttpServletRequest();
        assertThatThrownBy(() -> provider.token(authentication, request)).isInstanceOf(ApiException.class);
        var registration = ClientRegistration.withRegistrationId("github").clientId("id").clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://127.0.0.1:8080/login/oauth2/code/github")
                .authorizationUri("https://github.com/login/oauth/authorize").tokenUri("https://github.com/login/oauth/access_token").build();
        var token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "server-session-token", Instant.now(), Instant.now().plusSeconds(3600));
        clients.saveAuthorizedClient(new OAuth2AuthorizedClient(registration, authentication.getName(), token), authentication, request, new org.springframework.mock.web.MockHttpServletResponse());
        assertThat(provider.token(authentication, request)).isEqualTo("server-session-token");
        assertThatThrownBy(() -> provider.token(authentication, new MockHttpServletRequest())).isInstanceOf(ApiException.class);
        var expired = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "expired", Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));
        clients.saveAuthorizedClient(new OAuth2AuthorizedClient(registration, authentication.getName(), expired), authentication, request, new org.springframework.mock.web.MockHttpServletResponse());
        assertThatThrownBy(() -> provider.token(authentication, request)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo("GITHUB_REAUTH_REQUIRED"));
    }
}
