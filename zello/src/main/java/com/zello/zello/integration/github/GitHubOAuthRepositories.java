package com.zello.zello.integration.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.zello.zello.api.ApiException;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class GitHubOAuthRepositories {
    private final RestClient client;

    public GitHubOAuthRepositories(@Qualifier("githubApiRestClient") RestClient client) { this.client = client; }

    public GitHubGateway.Page<Organization> organizations(String token, int page) {
        var response = get("/user/orgs?per_page=100&page=" + page(page), token, Organization[].class);
        if (response.getBody() == null) throw invalid();
        return new GitHubGateway.Page<>(Arrays.asList(response.getBody()), hasNext(response), page);
    }

    public GitHubGateway.Page<GitHubGateway.RemoteRepository> repositories(String token, String scope, String organization, int page) {
        String path;
        if ("organization".equals(scope)) {
            if (organization == null || !organization.matches("[A-Za-z0-9][A-Za-z0-9-]{0,99}")) {
                throw new ApiException(400, "INVALID_ORGANIZATION", "조직 이름을 확인해 주세요.");
            }
            var membership = get("/user/memberships/orgs/" + organization, token, Membership.class).getBody();
            if (membership == null || !"active".equals(membership.state())) throw inaccessible();
            path = "/orgs/" + organization + "/repos?type=all&sort=updated&direction=desc&per_page=100&page=" + page(page);
        } else if ("all".equals(scope) || "personal".equals(scope)) {
            String affiliation = "personal".equals(scope) ? "owner" : "owner,collaborator,organization_member";
            path = "/user/repos?visibility=all&affiliation=" + affiliation + "&sort=updated&direction=desc&per_page=100&page=" + page(page);
        } else {
            throw new ApiException(400, "INVALID_REPOSITORY_SCOPE", "저장소 조회 범위를 확인해 주세요.");
        }
        var response = get(path, token, GitHubGateway.RemoteRepository[].class);
        if (response.getBody() == null) throw invalid();
        for (var repository : response.getBody()) validate(repository, repository.id());
        return new GitHubGateway.Page<>(Arrays.asList(response.getBody()), hasNext(response), page);
    }

    public GitHubGateway.RemoteRepository repository(String token, long repositoryId) {
        if (repositoryId <= 0) throw new ApiException(400, "INVALID_REPOSITORY_ID", "저장소 ID를 확인해 주세요.");
        var repo = get("/repositories/" + repositoryId, token, GitHubGateway.RemoteRepository.class).getBody();
        validate(repo, repositoryId);
        return repo;
    }

    private void validate(GitHubGateway.RemoteRepository repo, long id) {
        if (repo == null || repo.id() <= 0 || repo.id() != id || repo.owner() == null || repo.owner().id() <= 0
                || repo.owner().login() == null || !repo.owner().login().matches("[A-Za-z0-9][A-Za-z0-9-]{0,99}")
                || !("User".equals(repo.owner().type()) || "Organization".equals(repo.owner().type()))
                || repo.name() == null || repo.name().isBlank() || repo.name().length() > 100
                || repo.htmlUrl() == null || !repo.htmlUrl().startsWith("https://github.com/") || repo.htmlUrl().length() > 2048
                || repo.defaultBranch() == null || repo.defaultBranch().isBlank() || repo.defaultBranch().length() > 255
                || (repo.language() != null && repo.language().length() > 100)) throw invalid();
    }

    private <T> ResponseEntity<T> get(String path, String token, Class<T> type) {
        try {
            return client.get().uri(path).headers(headers -> headers.setBearerAuth(token)).retrieve().toEntity(type);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 401) throw new ApiException(401, "GITHUB_REAUTH_REQUIRED", "GitHub에 다시 로그인해 주세요.");
            if (status == 404) throw inaccessible();
            if (status == 403 || status == 429) throw new ApiException(403, "GITHUB_ACCESS_RESTRICTED",
                    "GitHub 조직의 OAuth 승인·SSO 또는 API 호출 한도를 확인해 주세요.");
            throw new ApiException(502, "GITHUB_UNAVAILABLE", "GitHub 응답을 받지 못했습니다. 잠시 후 다시 시도해 주세요.");
        } catch (RestClientException exception) {
            throw new ApiException(502, "GITHUB_UNAVAILABLE", "GitHub에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    private int page(int value) {
        if (value < 1 || value > 100) throw new ApiException(400, "INVALID_PAGE", "페이지 범위를 확인해 주세요.");
        return value;
    }
    private boolean hasNext(ResponseEntity<?> response) {
        return response.getHeaders().getOrEmpty("Link").stream().anyMatch(value -> value.contains("rel=\"next\""));
    }
    private ApiException inaccessible() { return new ApiException(404, "GITHUB_RESOURCE_NOT_FOUND", "현재 계정으로 접근할 수 없는 GitHub 저장소 또는 조직입니다."); }
    private ApiException invalid() { return new ApiException(502, "INVALID_GITHUB_RESPONSE", "GitHub 저장소 정보를 확인하지 못했습니다."); }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Organization(long id, String login) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Membership(String state) { }
}
