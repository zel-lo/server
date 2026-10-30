package com.zello.zello.integration.github;

import com.zello.zello.api.ApiException;
import com.zello.zello.auth.AuthProperties;
import com.zello.zello.auth.ZelloPrincipal;
import com.zello.zello.project.ProjectService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class GitHubConnectionFlow {
    static final String SESSION_ATTRIBUTE = GitHubConnectionFlow.class.getName() + ".PENDING";
    private final ProjectService projects;
    private final GitHubGateway github;
    private final GitHubTokenProvider tokens;
    private final GitHubProperties properties;
    private final AuthProperties auth;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public GitHubConnectionFlow(ProjectService projects, GitHubGateway github, GitHubTokenProvider tokens,
                                GitHubProperties properties, AuthProperties auth) {
        this(projects, github, tokens, properties, auth, Clock.systemUTC());
    }

    GitHubConnectionFlow(ProjectService projects, GitHubGateway github, GitHubTokenProvider tokens,
                         GitHubProperties properties, AuthProperties auth, Clock clock) {
        this.projects = projects;
        this.github = github;
        this.tokens = tokens;
        this.properties = properties;
        this.auth = auth;
        this.clock = clock;
    }

    public String start(long projectId, ZelloPrincipal principal, Authentication authentication, HttpServletRequest request) {
        projects.requireMember(projectId, principal.userId(), true);
        if (properties.appSlug().isEmpty()) {
            throw new ApiException(503, "GITHUB_CONNECTION_UNAVAILABLE", "GitHub 연결 설정을 준비하고 있습니다.");
        }
        tokens.token(authentication, request);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        request.getSession().setAttribute(SESSION_ATTRIBUTE,
                new PendingConnection(digest(state), projectId, principal.userId(), clock.instant().plusSeconds(600)));
        return "https://github.com/apps/" + properties.appSlug() + "/installations/new?state=" + state;
    }

    public String finish(String state, String installationId, ZelloPrincipal principal,
                          Authentication authentication, HttpServletRequest request) {
        var session = request.getSession(false);
        if (principal == null || session == null) return auth.failureUrl("expired");
        Object attribute = session.getAttribute(SESSION_ATTRIBUTE);
        if (!(attribute instanceof PendingConnection pending) || state == null || state.length() > 128
                || !MessageDigest.isEqual(pending.stateHash(), digest(state))
                || pending.userId() != principal.userId() || !pending.expiresAt().isAfter(clock.instant())) {
            return result(null, "expired", null);
        }
        // A new start replaces the old attempt, just as the OAuth login request does.
        // A callback only prepares a verified selection; it never creates a repository link.
        session.removeAttribute(SESSION_ATTRIBUTE);
        try {
            long id = Long.parseLong(installationId == null ? "" : installationId);
            if (id <= 0) return result(pending.projectId(), "invalid", null);
            projects.requireMember(pending.projectId(), principal.userId(), true);
            github.requireInstallation(tokens.token(authentication, request), id);
            return result(pending.projectId(), "ready", id);
        } catch (NumberFormatException exception) {
            return result(pending.projectId(), "invalid", null);
        } catch (ApiException exception) {
            String code = switch (exception.code()) {
                case "GITHUB_REAUTH_REQUIRED" -> "reauth";
                case "PROJECT_NOT_FOUND", "PROJECT_ADMIN_REQUIRED" -> "project_access";
                case "GITHUB_RESOURCE_NOT_FOUND" -> "access";
                default -> "failed";
            };
            return result(pending.projectId(), code, null);
        }
    }

    private String result(Long projectId, String code, Long installationId) {
        return auth.frontendBaseUrl() + "/#/settings?tab=repositories&github_connection=" + code
                + (projectId == null ? "" : "&project=" + projectId)
                + (installationId == null ? "" : "&installation=" + installationId);
    }

    private byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    record PendingConnection(byte[] stateHash, long projectId, long userId, Instant expiresAt) implements Serializable {
        private static final long serialVersionUID = 1L;
    }
}
