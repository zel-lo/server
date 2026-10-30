package com.zello.zello.integration.github;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.zello.zello.api.ApiException;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class GitHubGateway {
    private static final int PAGE_SIZE = 100;
    private final RestClient client;

    public GitHubGateway(@Qualifier("githubApiRestClient") RestClient client) { this.client = client; }

    public Page<Installation> installations(String token, int page) {
        var body = get("/user/installations?per_page=" + PAGE_SIZE + "&page=" + validPage(page), token, Installations.class);
        if (body == null || body.installations() == null) throw invalidResponse();
        var items = body.installations().stream().filter(i -> i.suspendedAt() == null).toList();
        return new Page<>(items, (long) page * PAGE_SIZE < body.totalCount(), page);
    }

    public Page<RemoteRepository> repositories(String token, long installationId, int page) {
        var body = get("/user/installations/" + positive(installationId) + "/repositories?per_page=" + PAGE_SIZE
                + "&page=" + validPage(page), token, Repositories.class);
        if (body == null || body.repositories() == null) throw invalidResponse();
        return new Page<>(body.repositories(), (long) page * PAGE_SIZE < body.totalCount(), page);
    }

    public Installation requireInstallation(String token, long installationId) {
        for (int page = 1; page <= 100; page++) {
            var result = installations(token, page);
            for (var installation : result.items()) {
                if (installation.id() == installationId) {
                    validateOwner(installation.account());
                    return installation;
                }
            }
            if (!result.hasNext()) throw notAccessible();
        }
        throw new ApiException(422, "GITHUB_RESULT_LIMIT", "설치 목록이 너무 많아 검증을 완료하지 못했습니다.");
    }

    public VerifiedRepository verify(String token, long installationId, long repositoryId) {
        var installation = requireInstallation(token, installationId);
        boolean found = false;
        for (int page = 1; page <= 100; page++) {
            var result = repositories(token, installationId, page);
            if (result.items().stream().anyMatch(repo -> repo.id() == repositoryId)) { found = true; break; }
            if (!result.hasNext()) break;
        }
        if (!found) throw notAccessible();
        var repo = get("/repositories/" + positive(repositoryId), token, RemoteRepository.class);
        if (repo == null || repo.id() != repositoryId || repo.owner() == null
                || repo.owner().id() != installation.account().id()
                || repo.name() == null || repo.name().isBlank() || repo.name().length() > 100
                || repo.defaultBranch() == null || repo.defaultBranch().isBlank() || repo.defaultBranch().length() > 255
                || repo.htmlUrl() == null || !repo.htmlUrl().startsWith("https://github.com/")) {
            throw invalidResponse();
        }
        validateOwner(repo.owner());
        return new VerifiedRepository(installation, repo);
    }

    public Set<String> accessibleRepositoryIds(String token, long installationId) {
        requireInstallation(token, installationId);
        var ids = new HashSet<String>();
        for (int page = 1; page <= 100; page++) {
            var result = repositories(token, installationId, page);
            result.items().forEach(repo -> ids.add(Long.toString(repo.id())));
            if (!result.hasNext()) return ids;
        }
        throw new ApiException(422, "GITHUB_RESULT_LIMIT", "저장소 목록이 너무 많아 검증을 완료하지 못했습니다.");
    }

    private <T> T get(String path, String token, Class<T> type) {
        try {
            return client.get().uri(path).headers(headers -> headers.setBearerAuth(token)).retrieve().body(type);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 401) throw new ApiException(401, "GITHUB_REAUTH_REQUIRED", "GitHub에 다시 로그인해 주세요.");
            if (status == 404) throw notAccessible();
            if (status == 403 || status == 429) {
                throw new ApiException(403, "GITHUB_ACCESS_RESTRICTED", "GitHub App 설치 권한이나 API 호출 한도를 확인해 주세요.");
            }
            throw new ApiException(502, "GITHUB_UNAVAILABLE", "GitHub 응답을 받지 못했습니다. 잠시 후 다시 시도해 주세요.");
        } catch (RestClientException exception) {
            throw new ApiException(502, "GITHUB_UNAVAILABLE", "GitHub에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    private void validateOwner(Owner owner) {
        if (owner == null || owner.id() <= 0 || owner.login() == null || owner.login().isBlank()
                || owner.login().length() > 100 || !("User".equals(owner.type()) || "Organization".equals(owner.type()))) {
            throw invalidResponse();
        }
    }

    private int validPage(int page) {
        if (page < 1 || page > 100) throw new ApiException(400, "INVALID_PAGE", "페이지 범위를 확인해 주세요.");
        return page;
    }

    private long positive(long id) {
        if (id <= 0) throw new ApiException(400, "INVALID_ID", "GitHub ID를 확인해 주세요.");
        return id;
    }

    private static ApiException notAccessible() {
        return new ApiException(404, "GITHUB_RESOURCE_NOT_FOUND", "현재 GitHub 계정과 App으로 접근할 수 없는 저장소 또는 설치입니다.");
    }

    private static ApiException invalidResponse() {
        return new ApiException(502, "INVALID_GITHUB_RESPONSE", "GitHub 저장소 정보를 확인하지 못했습니다.");
    }

    public record Page<T>(List<T> items, boolean hasNext, int page) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Owner(long id, String login, String type, @JsonProperty("avatar_url") String avatarUrl) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Installation(long id, Owner account, Map<String, Object> permissions,
                               @JsonProperty("suspended_at") String suspendedAt) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RemoteRepository(long id, Owner owner, String name, @JsonProperty("full_name") String fullName,
                                   @JsonProperty("html_url") String htmlUrl, String description, String language,
                                   @JsonProperty("default_branch") String defaultBranch,
                                   @JsonProperty("private") boolean privateRepository, boolean archived) { }
    public record VerifiedRepository(Installation installation, RemoteRepository repository) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Installations(@JsonProperty("total_count") long totalCount, List<Installation> installations) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Repositories(@JsonProperty("total_count") long totalCount, List<RemoteRepository> repositories) { }
}
