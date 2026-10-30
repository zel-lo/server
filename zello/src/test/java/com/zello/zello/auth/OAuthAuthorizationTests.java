package com.zello.zello.auth;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"zello.auth.github.enabled=true", "zello.auth.github.app-type=OAUTH",
        "zello.auth.github.client-id=oauth-test-client", "zello.auth.github.client-secret=test-only-secret"})
@AutoConfigureMockMvc
class OAuthAuthorizationTests {
    @Autowired MockMvc mvc;

    @Test
    void oauthRequestsRepositoryAndOrganizationApprovalDuringLoginWithStateAndPkce() throws Exception {
        var result = mvc.perform(get("/oauth2/authorization/github")).andExpect(status().is3xxRedirection()).andReturn();
        var location = result.getResponse().getRedirectedUrl();
        assertThat(location).startsWith("https://github.com/login/oauth/authorize?")
                .contains("state=", "code_challenge=", "code_challenge_method=S256").doesNotContain("test-only-secret", "/installations");
        String query = URLDecoder.decode(URI.create(location).getRawQuery(), StandardCharsets.UTF_8);
        assertThat(query).contains("read:user", "repo", "read:org");
    }

    @Test
    void githubAppCredentialsCannotBeSilentlyUsedAsAnOauthApp() {
        assertThatThrownBy(() -> new AuthProperties.GitHub(true, "Iv-registered-github-app", "test-secret",
                "http://127.0.0.1:8080/login/oauth2/code/github", AuthProperties.AppType.OAUTH)).isInstanceOf(IllegalArgumentException.class);
    }
}
