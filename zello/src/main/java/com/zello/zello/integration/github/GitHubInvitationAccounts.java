package com.zello.zello.integration.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.zello.zello.api.ApiException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Optional targeted invitations are bound to a durable GitHub ID, not a renameable login. */
@Component
public class GitHubInvitationAccounts {
    private final RestClient github;

    public GitHubInvitationAccounts(@Qualifier("githubApiRestClient") RestClient github) {
        this.github = github;
    }

    public Account resolve(String token, String login) {
        try {
            var account = github.get().uri("/users/{login}", login).headers(h -> h.setBearerAuth(token))
                    .retrieve().body(Account.class);
            if (account == null || account.id() <= 0 || account.login() == null
                    || !account.login().matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}") || !"User".equals(account.type())) {
                throw new ApiException(400, "INVALID_INVITATION_ACCOUNT", "개인 GitHub 계정을 입력해 주세요.");
            }
            return account;
        } catch (RestClientResponseException exception) {
            throw switch (exception.getStatusCode().value()) {
                case 401 -> new ApiException(401, "GITHUB_REAUTH_REQUIRED", "GitHub에 다시 로그인해 주세요.");
                case 404 -> new ApiException(404, "INVITATION_ACCOUNT_NOT_FOUND", "해당 GitHub 계정을 찾을 수 없어요.");
                case 403, 429 -> new ApiException(403, "GITHUB_ACCESS_RESTRICTED", "GitHub 계정 확인이 제한됐어요. 잠시 후 다시 시도해 주세요.");
                default -> new ApiException(502, "GITHUB_UNAVAILABLE", "GitHub 계정을 확인하지 못했어요.");
            };
        } catch (RestClientException exception) {
            throw new ApiException(502, "GITHUB_UNAVAILABLE", "GitHub 계정을 확인하지 못했어요.");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Account(long id, String login, String type, @JsonProperty("avatar_url") String avatarUrl) { }
}
