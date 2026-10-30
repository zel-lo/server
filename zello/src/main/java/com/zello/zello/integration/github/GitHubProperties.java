package com.zello.zello.integration.github;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("zello.github")
public record GitHubProperties(@DefaultValue("") String appSlug) {
    public GitHubProperties {
        if (!appSlug.isEmpty() && !appSlug.matches("[a-z0-9][a-z0-9-]{0,99}")) {
            throw new IllegalArgumentException("Invalid GITHUB_APP_SLUG");
        }
    }
}
