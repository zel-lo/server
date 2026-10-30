package com.zello.zello.integration.github;

import com.zello.zello.api.ApiException;
import com.zello.zello.auth.*;
import com.zello.zello.project.ProjectService;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GitHubConnectionFlowTests {
    private final ProjectService projects = mock(ProjectService.class);
    private final GitHubGateway github = mock(GitHubGateway.class);
    private final GitHubTokenProvider tokens = mock(GitHubTokenProvider.class);
    private final Clock clock = mock(Clock.class);
    private final ZelloPrincipal principal = new ZelloPrincipal(1L, 2L);
    private final UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(principal, "unused", principal.getAuthorities());
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private GitHubConnectionFlow flow;

    @BeforeEach
    void setup() {
        var auth = new AuthProperties("http://127.0.0.1:5173", new AuthProperties.GitHub(false, "", "",
                "http://127.0.0.1:8080/login/oauth2/code/github", AuthProperties.AppType.APP));
        flow = new GitHubConnectionFlow(projects, github, tokens, new GitHubProperties("zello-dev"), auth, clock);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-31T00:00:00Z"));
        when(tokens.token(any(), any())).thenReturn("server-only-token");
        when(github.requireInstallation(anyString(), eq(30L))).thenReturn(new GitHubGateway.Installation(30,
                new GitHubGateway.Owner(50, "team", "Organization", null), Map.of("metadata", "read"), null));
    }

    @Test
    void persistsStateWithEncryptedSessionSerializationAndRestoresTheCorrectProject() {
        String url = flow.start(20, principal, authentication, request);
        assertThat(url).startsWith("https://github.com/apps/zello-dev/installations/new?state=").doesNotContain("server-only-token");
        String state = url.substring(url.indexOf("state=") + 6);
        var configuration = new SessionEncryptionConfiguration();
        configuration.setBeanClassLoader(getClass().getClassLoader());
        var conversions = configuration.sessionConversionService("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        byte[] bytes = conversions.convert(request.getSession().getAttribute(GitHubConnectionFlow.SESSION_ATTRIBUTE), byte[].class);
        request.getSession().setAttribute(GitHubConnectionFlow.SESSION_ATTRIBUTE, conversions.convert(bytes, Object.class));
        assertThat(flow.finish(state, "30", principal, authentication, request))
                .isEqualTo("http://127.0.0.1:5173/#/settings?tab=repositories&github_connection=ready&project=20&installation=30");
        verify(github).requireInstallation("server-only-token", 30);
        assertThat(request.getSession().getAttribute(GitHubConnectionFlow.SESSION_ATTRIBUTE)).isNull();
        assertThat(flow.finish(state, "30", principal, authentication, request)).contains("github_connection=expired");
        verifyNoMoreInteractions(github);
    }

    @Test
    void wrongStateDoesNotBurnTheValidAttemptOrCallGithub() {
        String url = flow.start(20, principal, authentication, request);
        assertThat(flow.finish("forged", "30", principal, authentication, request)).contains("github_connection=expired");
        verifyNoInteractions(github);
        String state = url.substring(url.indexOf("state=") + 6);
        assertThat(flow.finish(state, "30", principal, authentication, request)).contains("github_connection=ready");
    }

    @Test
    void expiredAttemptAndCallbackFromAnotherSessionAreRejected() {
        String url = flow.start(20, principal, authentication, request);
        String state = url.substring(url.indexOf("state=") + 6);
        assertThat(flow.finish(state, "30", principal, authentication, new MockHttpServletRequest())).endsWith("/#/login?error=expired");
        when(clock.instant()).thenReturn(Instant.parse("2026-10-31T00:11:00Z"));
        assertThat(flow.finish(state, "30", principal, authentication, request)).contains("github_connection=expired");
        verifyNoInteractions(github);
    }

    @Test
    void anotherAccountCannotUseThePendingProjectSelection() {
        String url = flow.start(20, principal, authentication, request);
        String state = url.substring(url.indexOf("state=") + 6);
        assertThat(flow.finish(state, "30", new ZelloPrincipal(3L, 4L), authentication, request)).contains("github_connection=expired");
        verifyNoInteractions(github);
    }

    @Test
    void forgedInstallationIsNotAcceptedAndRemoteErrorsAreNotReflectedInTheReturnUrl() {
        String url = flow.start(20, principal, authentication, request);
        String state = url.substring(url.indexOf("state=") + 6);
        when(github.requireInstallation(anyString(), eq(30L))).thenThrow(new ApiException(404, "GITHUB_RESOURCE_NOT_FOUND", "untrusted details"));
        assertThat(flow.finish(state, "30", principal, authentication, request)).contains("github_connection=access").doesNotContain("untrusted", state);
    }

    @Test
    void changedProjectPermissionIsCheckedAgainAfterGithubApproval() {
        String url = flow.start(20, principal, authentication, request);
        String state = url.substring(url.indexOf("state=") + 6);
        when(projects.requireMember(20, 1, true)).thenThrow(new ApiException(403, "PROJECT_ADMIN_REQUIRED", "restricted"));
        assertThat(flow.finish(state, "30", principal, authentication, request)).contains("github_connection=project_access");
        verifyNoInteractions(github);
    }
}
