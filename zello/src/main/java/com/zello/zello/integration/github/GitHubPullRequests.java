package com.zello.zello.integration.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.zello.zello.api.ApiException;
import com.zello.zello.domain.pullrequest.PullRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class GitHubPullRequests {
    /** 최근 수정순으로 이 페이지 수까지만 수집한다. */
    static final int MAX_PAGES = 5;
    private final RestClient client;

    public GitHubPullRequests(@Qualifier("githubApiRestClient") RestClient client) { this.client = client; }

    /** 브랜치는 이 페이지 수까지만 가져오고, 더 있으면 hasMore로 알린다. */
    static final int MAX_BRANCH_PAGES = 3;

    public Branches branches(String token, String owner, String repository) {
        requireName(owner, repository);
        var names = new ArrayList<String>();
        boolean hasMore = false;
        for (int page = 1; page <= MAX_BRANCH_PAGES; page++) {
            var response = get(token, Branch[].class, "/repos/{owner}/{repo}/branches?per_page=100&page={page}", owner, repository, page);
            if (response.getBody() == null) throw invalid();
            for (var branch : response.getBody()) {
                if (branch == null || branch.name() == null || branch.name().isBlank() || branch.name().length() > 255) throw invalid();
                names.add(branch.name());
            }
            hasMore = hasNext(response);
            if (!hasMore) break;
        }
        return new Branches(names, hasMore);
    }

    /** 같은 저장소 안의 브랜치끼리 PR을 만든다. 응답이 유실될 수 있는 오류는 GITHUB_RESULT_UNKNOWN으로 구분한다. */
    public Remote create(String token, String owner, String repository, NewPull input) {
        requireName(owner, repository);
        try {
            var created = client.post().uri("/repos/{owner}/{repo}/pulls", owner, repository).headers(headers -> headers.setBearerAuth(token))
                    .body(Map.of("title", input.title(), "head", input.head(), "base", input.base(), "body", input.body(), "draft", input.draft()))
                    .retrieve().body(Remote.class);
            validate(created);
            return created;
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            String detail = exception.getResponseBodyAsString();
            if (status == 401) throw new ApiException(401, "GITHUB_REAUTH_REQUIRED", "GitHub에 다시 로그인해 주세요.");
            if (status == 403 || status == 429) throw new ApiException(403, "GITHUB_ACCESS_RESTRICTED",
                    "저장소에 PR을 만들 권한이 없거나, 조직의 OAuth 승인·SSO 또는 API 호출 한도를 확인해야 해요.");
            if (status == 404) throw new ApiException(404, "GITHUB_RESOURCE_NOT_FOUND", "현재 계정으로 접근할 수 없는 GitHub 저장소입니다.");
            if (status == 422 && detail.contains("No commits between")) throw new ApiException(422, "PULL_REQUEST_NO_CHANGES", "대상 브랜치와 비교해 변경된 커밋이 없어요.");
            if (status == 422 && detail.contains("already exists")) throw new ApiException(409, "PULL_REQUEST_ALREADY_EXISTS", "같은 브랜치로 열린 PR이 이미 있어요.");
            if (status == 422) throw new ApiException(422, "PULL_REQUEST_REJECTED", "GitHub가 PR 생성을 거부했어요. 브랜치 이름과 변경 내용을 확인해 주세요.");
            throw unknownResult();
        } catch (RestClientException exception) {
            throw unknownResult();
        }
    }

    /** 생성 결과를 알 수 없을 때, 같은 브랜치로 열린 PR이 실제로 만들어졌는지 확인한다. */
    public Optional<Remote> findOpen(String token, String owner, String repository, String head, String base) {
        requireName(owner, repository);
        var response = get(token, Remote[].class, "/repos/{owner}/{repo}/pulls?state=open&head={head}&base={base}&per_page=1",
                owner, repository, owner + ":" + head, base);
        if (response.getBody() == null) throw invalid();
        for (var remote : response.getBody()) validate(remote);
        return Arrays.stream(response.getBody()).findFirst();
    }

    private void requireName(String owner, String repository) {
        if (owner == null || !owner.matches("[A-Za-z0-9][A-Za-z0-9-]{0,99}")
                || repository == null || !repository.matches("[A-Za-z0-9._-]{1,100}")) {
            throw new ApiException(400, "INVALID_REPOSITORY", "저장소 이름을 확인해 주세요.");
        }
    }

    private ApiException unknownResult() {
        return new ApiException(503, "GITHUB_RESULT_UNKNOWN", "PR이 만들어졌는지 확인하지 못했어요. 잠시 후 코드 검토 화면에서 새로고침해 주세요.");
    }

    public List<Fetched> fetch(String token, String owner, String repository) { return fetch(token, owner, repository, Set.of()); }

    /**
     * reviewAlso는 zello에 열린 상태로 저장된 PR의 GitHub id. 그 사이 닫히거나 병합됐어도 리뷰를 한 번 더 읽어
     * 병합 직전의 승인·수정 요청을 놓치지 않는다.
     */
    public List<Fetched> fetch(String token, String owner, String repository, Set<Long> reviewAlso) {
        requireName(owner, repository);
        var remotes = new ArrayList<Remote>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            var response = get(token, Remote[].class, "/repos/{owner}/{repo}/pulls?state=all&sort=updated&direction=desc&per_page=100&page={page}",
                    owner, repository, page);
            if (response.getBody() == null) throw invalid();
            for (var remote : response.getBody()) validate(remote);
            remotes.addAll(Arrays.asList(response.getBody()));
            if (!hasNext(response)) break;
        }
        var fetched = new ArrayList<Fetched>(remotes.size());
        for (var remote : remotes) {
            fetched.add("open".equals(remote.state()) || reviewAlso.contains(remote.id()) ? reviews(token, owner, repository, remote)
                    : new Fetched(remote, PullRequest.ReviewDecision.UNKNOWN));
        }
        return fetched;
    }

    /**
     * 리뷰어별 마지막 결정만 본다. 수정 요청이 있으면 우선, 그다음 승인. 댓글·대기 리뷰는 결정에서 무시한다.
     * 리뷰를 제출하면 GitHub가 requested_reviewers에서 빼므로, 리뷰한 사람도 따로 모아 "맡긴 리뷰어"로 남긴다.
     */
    private Fetched reviews(String token, String owner, String repository, Remote remote) {
        var response = get(token, Review[].class, "/repos/{owner}/{repo}/pulls/{number}/reviews?per_page=100", owner, repository, remote.number());
        if (response.getBody() == null) throw invalid();
        Map<Long, String> latest = new HashMap<>();
        Map<Long, User> reviewed = new LinkedHashMap<>();
        long authorId = remote.user() == null ? -1 : remote.user().id();
        for (var review : response.getBody()) {
            if (review.user() == null) continue;
            if (List.of("APPROVED", "CHANGES_REQUESTED", "DISMISSED").contains(review.state())) latest.put(review.user().id(), review.state());
            if (!"PENDING".equals(review.state()) && review.user().id() > 0 && review.user().id() != authorId && review.user().login() != null) {
                reviewed.putIfAbsent(review.user().id(), review.user());
            }
        }
        var decision = latest.containsValue("CHANGES_REQUESTED") ? PullRequest.ReviewDecision.CHANGES_REQUESTED
                : latest.containsValue("APPROVED") ? PullRequest.ReviewDecision.APPROVED : PullRequest.ReviewDecision.PENDING;
        return new Fetched(remote, decision, List.copyOf(reviewed.values()));
    }

    /** PR 하나의 변경 규모. 목록 API에는 없는 값이라 상세에서만 따로 조회한다. */
    public Stats stats(String token, String owner, String repository, int number) {
        requireName(owner, repository);
        var response = get(token, Stats.class, "/repos/{owner}/{repo}/pulls/{number}", owner, repository, number);
        var stats = response.getBody();
        if (stats == null || stats.changedFiles() < 0 || stats.additions() < 0 || stats.deletions() < 0 || stats.commits() < 0) throw invalid();
        return stats;
    }

    /** 리뷰어로 지정할 수 있는 사람. 저장소의 assignees 목록을 쓴다. */
    public Candidates reviewerCandidates(String token, String owner, String repository) {
        requireName(owner, repository);
        var users = new ArrayList<User>();
        boolean hasMore = false;
        for (int page = 1; page <= MAX_BRANCH_PAGES; page++) {
            var response = get(token, User[].class, "/repos/{owner}/{repo}/assignees?per_page=100&page={page}", owner, repository, page);
            if (response.getBody() == null) throw invalid();
            for (var user : response.getBody()) {
                if (user == null || user.id() <= 0 || user.login() == null || user.login().isBlank()) throw invalid();
                users.add(user);
            }
            hasMore = hasNext(response);
            if (!hasMore) break;
        }
        return new Candidates(users, hasMore);
    }

    /** 만들어진 PR에 리뷰어를 요청한다. 같은 사람을 다시 요청해도 GitHub에서 중복되지 않는다. */
    public Remote requestReviewers(String token, String owner, String repository, int number, List<String> logins) {
        requireName(owner, repository);
        try {
            var updated = client.post().uri("/repos/{owner}/{repo}/pulls/{number}/requested_reviewers", owner, repository, number)
                    .headers(headers -> headers.setBearerAuth(token)).body(Map.of("reviewers", logins)).retrieve().body(Remote.class);
            validate(updated);
            return updated;
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 401) throw new ApiException(401, "GITHUB_REAUTH_REQUIRED", "GitHub에 다시 로그인해 주세요.");
            if (status == 422) throw new ApiException(422, "REVIEWER_REJECTED", "저장소에 접근할 수 없거나 PR 작성자인 사람은 리뷰어로 지정할 수 없어요.");
            if (status == 403 || status == 404) throw new ApiException(403, "GITHUB_ACCESS_RESTRICTED", "리뷰어를 지정할 권한이 없어요.");
            throw new ApiException(502, "GITHUB_UNAVAILABLE", "GitHub 응답을 받지 못했습니다.");
        } catch (RestClientException exception) {
            throw new ApiException(502, "GITHUB_UNAVAILABLE", "GitHub에 연결하지 못했습니다.");
        }
    }

    private void validate(Remote remote) {
        if (remote == null || remote.id() <= 0 || remote.number() <= 0 || remote.title() == null || remote.title().isBlank()
                || remote.htmlUrl() == null || !remote.htmlUrl().startsWith("https://github.com/") || remote.htmlUrl().length() > 2048
                || !("open".equals(remote.state()) || "closed".equals(remote.state()))
                || remote.head() == null || remote.head().ref() == null || remote.head().ref().isBlank() || remote.head().ref().length() > 255
                || remote.base() == null || remote.base().ref() == null || remote.base().ref().isBlank() || remote.base().ref().length() > 255
                || remote.updatedAt() == null || (remote.user() != null && (remote.user().login() == null || remote.user().id() <= 0))
                || remote.requestedReviewers().stream().anyMatch(reviewer -> reviewer == null || reviewer.login() == null || reviewer.id() <= 0)) {
            throw invalid();
        }
    }

    private <T> ResponseEntity<T> get(String token, Class<T> type, String path, Object... variables) {
        try {
            return client.get().uri(path, variables).headers(headers -> headers.setBearerAuth(token)).retrieve().toEntity(type);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 401) throw new ApiException(401, "GITHUB_REAUTH_REQUIRED", "GitHub에 다시 로그인해 주세요.");
            if (status == 404) throw new ApiException(404, "GITHUB_RESOURCE_NOT_FOUND", "현재 계정으로 접근할 수 없는 GitHub 저장소입니다.");
            if (status == 403 || status == 429) throw new ApiException(403, "GITHUB_ACCESS_RESTRICTED",
                    "GitHub 조직의 OAuth 승인·SSO 또는 API 호출 한도를 확인해 주세요.");
            throw new ApiException(502, "GITHUB_UNAVAILABLE", "GitHub 응답을 받지 못했습니다. 잠시 후 다시 시도해 주세요.");
        } catch (RestClientException exception) {
            throw new ApiException(502, "GITHUB_UNAVAILABLE", "GitHub에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    private boolean hasNext(ResponseEntity<?> response) {
        return response.getHeaders().getOrEmpty("Link").stream().anyMatch(value -> value.contains("rel=\"next\""));
    }
    private ApiException invalid() { return new ApiException(502, "INVALID_GITHUB_RESPONSE", "GitHub PR 정보를 확인하지 못했습니다."); }

    /**
     * reviewed는 리뷰를 제출한 사람(PR 작성자 제외). reviewsFetched는 이번 수집에서 리뷰 목록을 실제로 읽었는지로,
     * 읽지 않은 닫힌 PR은 저장된 리뷰 결과를 그대로 둔다.
     */
    public record Fetched(Remote pull, PullRequest.ReviewDecision reviewDecision, List<User> reviewed, boolean reviewsFetched) {
        public Fetched {
            reviewed = reviewed == null ? List.of() : List.copyOf(reviewed);
        }

        public Fetched(Remote pull, PullRequest.ReviewDecision reviewDecision, List<User> reviewed) { this(pull, reviewDecision, reviewed, true); }

        public Fetched(Remote pull, PullRequest.ReviewDecision reviewDecision) { this(pull, reviewDecision, List.of(), false); }
    }

    public record Candidates(List<User> users, boolean hasMore) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Stats(@JsonProperty("changed_files") int changedFiles, int additions, int deletions, int commits) { }

    public record NewPull(String title, String body, String head, String base, boolean draft) { }

    public record Branches(List<String> names, boolean hasMore) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Branch(String name) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Remote(long id, int number, @JsonProperty("html_url") String htmlUrl, String title, String body, String state,
                         boolean draft, User user, Ref head, Ref base, @JsonProperty("merge_commit_sha") String mergeCommitSha,
                         @JsonProperty("created_at") Instant createdAt, @JsonProperty("updated_at") Instant updatedAt,
                         @JsonProperty("closed_at") Instant closedAt, @JsonProperty("merged_at") Instant mergedAt,
                         @JsonProperty("requested_reviewers") List<User> requestedReviewers) {
        public Remote {
            requestedReviewers = requestedReviewers == null ? List.of() : List.copyOf(requestedReviewers);
        }

        public Remote(long id, int number, String htmlUrl, String title, String body, String state, boolean draft, User user,
                      Ref head, Ref base, String mergeCommitSha, Instant createdAt, Instant updatedAt, Instant closedAt, Instant mergedAt) {
            this(id, number, htmlUrl, title, body, state, draft, user, head, base, mergeCommitSha, createdAt, updatedAt, closedAt, mergedAt, List.of());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(long id, String login, String type, @JsonProperty("avatar_url") String avatarUrl) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ref(String ref, String sha) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Review(User user, String state) { }
}
