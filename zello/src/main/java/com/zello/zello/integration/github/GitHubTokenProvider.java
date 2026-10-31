package com.zello.zello.integration.github;

import com.zello.zello.api.ApiException;
import com.zello.zello.auth.AuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.stereotype.Component;

@Component
public class GitHubTokenProvider {
    private final OAuth2AuthorizedClientRepository clients;
    private final AuthProperties properties;

    public GitHubTokenProvider(OAuth2AuthorizedClientRepository clients, AuthProperties properties) {
        this.clients = clients;
        this.properties = properties;
    }

    public String token(Authentication authentication, HttpServletRequest request) {
        OAuth2AuthorizedClient client = clients.loadAuthorizedClient("github", authentication, request);
        if (client == null || (client.getAccessToken().getExpiresAt() != null
                && !client.getAccessToken().getExpiresAt().isAfter(Instant.now().plusSeconds(15)))
                || !client.getClientRegistration().getClientId().equals(properties.github().clientId())) {
            throw new ApiException(401, "GITHUB_REAUTH_REQUIRED", "GitHub에 다시 로그인해 주세요.");
        }
        return client.getAccessToken().getTokenValue();
    }

    public String oauthToken(Authentication authentication, HttpServletRequest request) {
        if (properties.github().appType() != AuthProperties.AppType.OAUTH) {
            throw new ApiException(503, "GITHUB_OAUTH_NOT_CONFIGURED", "GitHub 저장소 연결을 준비하고 있어요. 서비스 관리자에게 문의해 주세요.");
        }
        String token = token(authentication, request);
        OAuth2AuthorizedClient client = clients.loadAuthorizedClient("github", authentication, request);
        // GitHub returns comma-delimited scopes; Spring's default converter splits only on spaces.
        // Normalize here so already persisted authorized clients also remain usable.
        var scopes = client.getAccessToken().getScopes().stream()
                .flatMap(scope -> Arrays.stream(scope.split("[,\\s]+")))
                .collect(Collectors.toSet());
        boolean organizationAccess = scopes.contains("read:org") || scopes.contains("write:org") || scopes.contains("admin:org");
        if (!scopes.contains("repo") || !organizationAccess) {
            throw new ApiException(401, "GITHUB_REAUTH_REQUIRED", "저장소와 조직 접근 권한을 확인하려면 GitHub에 다시 로그인해 주세요.");
        }
        return token;
    }
}
