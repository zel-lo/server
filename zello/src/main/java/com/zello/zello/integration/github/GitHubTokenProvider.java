package com.zello.zello.integration.github;

import com.zello.zello.api.ApiException;
import com.zello.zello.auth.AuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
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
        if (properties.github().appType() != AuthProperties.AppType.APP) {
            throw new ApiException(409, "GITHUB_APP_REQUIRED", "저장소 연결에는 GitHub App 로그인이 필요합니다.");
        }
        OAuth2AuthorizedClient client = clients.loadAuthorizedClient("github", authentication, request);
        if (client == null || (client.getAccessToken().getExpiresAt() != null
                && !client.getAccessToken().getExpiresAt().isAfter(Instant.now().plusSeconds(15)))) {
            throw new ApiException(401, "GITHUB_REAUTH_REQUIRED", "GitHub에 다시 로그인해 주세요.");
        }
        return client.getAccessToken().getTokenValue();
    }
}
