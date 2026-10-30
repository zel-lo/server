package com.zello.zello.auth;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

public record GitHubProfile(Long githubId, String login, String displayName, String avatarUrl, String email) {
    public static GitHubProfile from(Map<String, Object> attributes) {
        try {
            long id = new BigDecimal(attributes.get("id").toString()).longValueExact();
            String login = text(attributes.get("login"), 100);
            if (id <= 0 || login == null || !"User".equals(attributes.get("type"))) {
                throw new IllegalArgumentException("Invalid GitHub user");
            }
            return new GitHubProfile(id, login, text(attributes.get("name"), 100),
                    text(attributes.get("avatar_url"), 2048), text(attributes.get("email"), 320));
        } catch (RuntimeException exception) {
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_github_profile"));
        }
    }

    private static String text(Object value, int maxLength) {
        if (value == null) return null;
        if (!(value instanceof String text)) throw new IllegalArgumentException("Invalid profile value");
        if (text.isBlank()) return null;
        if (text.length() > maxLength) throw new IllegalArgumentException("Profile value is too long");
        return text;
    }
}
