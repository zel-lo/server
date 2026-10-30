package com.zello.zello.auth;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("zello.auth")
public record AuthProperties(
        @DefaultValue("http://127.0.0.1:5173") String frontendBaseUrl,
        @DefaultValue GitHub github) {

    public AuthProperties {
        URI origin = URI.create(frontendBaseUrl);
        if (!origin.isAbsolute() || origin.getHost() == null || origin.getUserInfo() != null
                || origin.getQuery() != null || origin.getFragment() != null
                || !(origin.getPath().isEmpty() || origin.getPath().equals("/"))
                || !(origin.getScheme().equals("https") || origin.getScheme().equals("http"))) {
            throw new IllegalArgumentException("FRONTEND_BASE_URL must be an HTTP(S) origin");
        }
        frontendBaseUrl = frontendBaseUrl.replaceAll("/+$", "");
    }

    public String successUrl() {
        return frontendBaseUrl + "/#/auth/callback";
    }

    public String failureUrl(String code) {
        return frontendBaseUrl + "/#/login?error=" + code;
    }

    public record GitHub(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String clientId,
            @DefaultValue("") String clientSecret,
            @DefaultValue("http://127.0.0.1:8080/login/oauth2/code/github") String redirectUri,
            @DefaultValue("APP") AppType appType) {
        public GitHub {
            if (enabled && (clientId.isBlank() || clientSecret.isBlank())) {
                throw new IllegalArgumentException("GitHub login requires GITHUB_CLIENT_ID and GITHUB_CLIENT_SECRET");
            }
            URI callback = URI.create(redirectUri);
            if (!callback.isAbsolute() || callback.getHost() == null || callback.getUserInfo() != null
                    || callback.getFragment() != null || callback.getQuery() != null
                    || !(callback.getScheme().equals("http") || callback.getScheme().equals("https"))) {
                throw new IllegalArgumentException("Invalid GITHUB_REDIRECT_URI");
            }
        }

        @Override
        public String toString() {
            return "GitHub[enabled=" + enabled + ", appType=" + appType + ", credentials=REDACTED]";
        }
    }

    public enum AppType { APP, OAUTH }
}
