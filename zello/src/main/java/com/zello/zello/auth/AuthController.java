package com.zello.zello.auth;

import com.zello.zello.domain.user.User;
import com.zello.zello.domain.user.UserRepository;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {
    private final AuthProperties properties;
    private final UserRepository users;

    public AuthController(AuthProperties properties, UserRepository users) {
        this.properties = properties;
        this.users = users;
    }

    @GetMapping("/api/auth/status")
    ResponseEntity<LoginStatus> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new LoginStatus(properties.github().enabled()));
    }

    @GetMapping("/api/auth/csrf")
    ResponseEntity<CsrfResponse> csrf(CsrfToken token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CsrfResponse(token.getHeaderName(), token.getParameterName(), token.getToken()));
    }

    @GetMapping("/oauth2/authorization/github")
    ResponseEntity<ApiSecurityErrors.ApiError> unavailable() {
        // When configured, Spring Security handles this route before MVC.
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                .body(new ApiSecurityErrors.ApiError("GITHUB_LOGIN_UNAVAILABLE", "GitHub 로그인을 준비하고 있습니다."));
    }

    @GetMapping("/api/me")
    @Transactional(readOnly = true)
    ResponseEntity<?> me(@AuthenticationPrincipal ZelloPrincipal principal) {
        if (principal == null) return unauthorized();
        User user = users.findWithGithubAccountById(principal.userId()).orElse(null);
        if (user == null || user.getStatus() != User.Status.ACTIVE) return unauthorized();
        var account = user.getGithubAccount();
        String name = account.getDisplayName() == null ? account.getLogin() : account.getDisplayName();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new CurrentUser(
                user.getId().toString(), account.getGithubId().toString(), account.getLogin(), name,
                account.getAvatarUrl(), user.getEmail(), user.getTheme()));
    }

    private ResponseEntity<ApiSecurityErrors.ApiError> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore())
                .body(new ApiSecurityErrors.ApiError("AUTHENTICATION_REQUIRED", "로그인이 필요합니다."));
    }

    public record LoginStatus(boolean githubLoginEnabled) { }
    public record CsrfResponse(String headerName, String parameterName, String token) { }
    public record CurrentUser(String id, String githubId, String login, String displayName,
                              String avatarUrl, String email, String theme) { }
}
