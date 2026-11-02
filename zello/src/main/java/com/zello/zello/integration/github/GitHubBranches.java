package com.zello.zello.integration.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.zello.zello.api.ApiException;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Reads exact refs and creates only missing branches. Existing refs are never moved. */
@Component
public class GitHubBranches {
    private final RestClient client;
    public GitHubBranches(@Qualifier("githubApiRestClient") RestClient client) { this.client = client; }

    public Result ensure(String token, String owner, String repository, String base, String branch) {
        if (owner == null || !owner.matches("[A-Za-z0-9][A-Za-z0-9-]{0,99}")
                || repository == null || !repository.matches("[A-Za-z0-9._-]{1,100}")
                || branch == null || !branch.matches("feat/[1-9][0-9]*") || branch.equals(base)) {
            throw new ApiException(400, "INVALID_WORK_BRANCH", "작업 브랜치 정보를 확인해 주세요.");
        }
        if (read(token, owner, repository, branch).isPresent()) return new Result(branch, false);
        var source = read(token, owner, repository, base).orElseThrow(() ->
                new ApiException(409, "BASE_BRANCH_NOT_FOUND", "기본 브랜치가 없어요. GitHub에 첫 커밋을 올린 뒤 다시 시도해 주세요."));
        try {
            var created = client.post().uri("/repos/{owner}/{repo}/git/refs", owner, repository)
                    .headers(headers -> headers.setBearerAuth(token))
                    .body(Map.of("ref", "refs/heads/" + branch, "sha", source.object().sha()))
                    .retrieve().body(Reference.class);
            validate(created, branch);
            return new Result(branch, true);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 401 || status == 403 || status == 404 || status == 429) throw error(status);
            // Concurrent creation and a lost response both resolve by reading the deterministic name.
            var existing = read(token, owner, repository, branch);
            if (existing.isPresent()) return new Result(branch, false);
            if (status == 409 || status == 422) throw new ApiException(409, "BRANCH_CREATION_REJECTED", "브랜치를 만들지 못했어요. 저장소 규칙과 기본 브랜치를 확인해 주세요.");
            throw unknown();
        } catch (RestClientException | ApiException exception) {
            if (exception instanceof ApiException api && !"GITHUB_BRANCH_RESULT_UNKNOWN".equals(api.code())) throw api;
            if (read(token, owner, repository, branch).isPresent()) return new Result(branch, false);
            throw unknown();
        }
    }

    private Optional<Reference> read(String token, String owner, String repository, String branch) {
        if (branch == null || branch.isBlank()) throw new ApiException(409, "BASE_BRANCH_NOT_FOUND", "기본 브랜치가 없어요.");
        try {
            var value = client.get().uri("/repos/{owner}/{repo}/git/ref/heads/{branch}", owner, repository, branch)
                    .headers(headers -> headers.setBearerAuth(token)).retrieve().body(Reference.class);
            validate(value, branch);
            return Optional.of(value);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) return Optional.empty();
            throw error(exception.getStatusCode().value());
        } catch (RestClientException exception) { throw unknown(); }
    }

    private void validate(Reference ref, String branch) {
        if (ref == null || !("refs/heads/" + branch).equals(ref.ref()) || ref.object() == null
                || !"commit".equals(ref.object().type()) || ref.object().sha() == null || !ref.object().sha().matches("[0-9a-f]{40}")) throw unknown();
    }
    private ApiException error(int status) {
        if (status == 401) return new ApiException(401, "GITHUB_REAUTH_REQUIRED", "GitHub에 다시 로그인해 주세요.");
        if (status == 403 || status == 429) return new ApiException(403, "GITHUB_ACCESS_RESTRICTED", "브랜치 생성 권한이나 저장소 규칙, 조직 승인 또는 GitHub 호출 한도를 확인해 주세요.");
        if (status == 404) return new ApiException(404, "GITHUB_RESOURCE_NOT_FOUND", "현재 계정으로 저장소에 접근할 수 없어요.");
        return unknown();
    }
    private ApiException unknown() { return new ApiException(503, "GITHUB_BRANCH_RESULT_UNKNOWN", "브랜치 생성 결과를 확인하지 못했어요. 다시 작업 시작을 누르면 기존 브랜치를 먼저 확인해요."); }
    public record Result(String name, boolean created) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Reference(String ref, @JsonProperty("object") GitObject object) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitObject(String type, String sha) { }
}
