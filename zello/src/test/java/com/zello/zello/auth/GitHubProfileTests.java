package com.zello.zello.auth;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

import static org.assertj.core.api.Assertions.*;

class GitHubProfileTests {
    @Test
    void publicIdentityDoesNotRequireAnEmail() {
        var profile = GitHubProfile.from(Map.of("id", 42, "login", "octocat", "type", "User"));
        assertThat(profile.githubId()).isEqualTo(42L);
        assertThat(profile.email()).isNull();
    }

    @Test
    void accountTypeAndExactPositiveIdsAreValidated() {
        for (Object id : java.util.List.of(-1, 0, "1.5", "not-a-number")) {
            assertThatThrownBy(() -> GitHubProfile.from(Map.of("id", id, "login", "octocat", "type", "User")))
                    .isInstanceOf(OAuth2AuthenticationException.class);
        }
        assertThatThrownBy(() -> GitHubProfile.from(Map.of("id", 42, "login", "org", "type", "Organization")))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    void credentialPropertiesNeverPrintTheSecret() {
        var github = new AuthProperties.GitHub(true, "test-id", "must-not-appear",
                "http://127.0.0.1:8080/login/oauth2/code/github", AuthProperties.AppType.APP);
        assertThat(github.toString()).doesNotContain("must-not-appear");
    }
}
