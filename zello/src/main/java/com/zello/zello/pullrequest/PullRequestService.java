package com.zello.zello.pullrequest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.zello.zello.api.ApiException;
import com.zello.zello.domain.common.EntitySupport;
import com.zello.zello.domain.common.PublishStatus;
import com.zello.zello.domain.github.GitHubAccount;
import com.zello.zello.domain.github.GitHubAccountRepository;
import com.zello.zello.domain.project.ProjectRepository;
import com.zello.zello.domain.pullrequest.PullRequest;
import com.zello.zello.domain.pullrequest.PullRequestRepository;
import com.zello.zello.domain.repository.GitRepository;
import com.zello.zello.domain.repository.GitRepositoryRepository;
import com.zello.zello.domain.user.UserRepository;
import com.zello.zello.integration.github.GitHubPullRequests;
import com.zello.zello.project.ProjectService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PullRequestService {
    private final ProjectService projects;
    private final ProjectRepository projectRepository;
    private final GitRepositoryRepository repositories;
    private final PullRequestRepository pullRequests;
    private final GitHubAccountRepository accounts;
    private final UserRepository users;

    public PullRequestService(ProjectService projects, ProjectRepository projectRepository, GitRepositoryRepository repositories,
                              PullRequestRepository pullRequests, GitHubAccountRepository accounts, UserRepository users) {
        this.projects = projects;
        this.projectRepository = projectRepository;
        this.repositories = repositories;
        this.pullRequests = pullRequests;
        this.accounts = accounts;
        this.users = users;
    }

    public List<GitRepository> connectedRepositories(long projectId, long userId) {
        projects.requireMember(projectId, userId, false);
        return repositories.findByProjectIdOrderByIdAsc(projectId).stream()
                .filter(repo -> repo.getConnectionStatus() != GitRepository.ConnectionStatus.DISCONNECTED).toList();
    }

    public PullRequestPage list(long projectId, long userId, Collection<Long> repositoryIds, Filter filter, int page, int size) {
        projects.requireMember(projectId, userId, false);
        if (repositoryIds.isEmpty()) return new PullRequestPage(List.of(), page, false, new Counts(0, 0, 0));
        Long authorAccountId = filter.mine() ? users.findById(userId).orElseThrow(ProjectService::notFound).getGithubAccountId() : null;
        var result = pullRequests.search(projectId, repositoryIds, filter.states(), authorAccountId, PageRequest.of(page - 1, size));
        return new PullRequestPage(result.getContent().stream().map(pull -> PullRequestView.from(pull, null)).toList(), page, result.hasNext(),
                counts(projectId, repositoryIds, authorAccountId));
    }

    public PullRequest find(long projectId, long userId, long pullRequestId) {
        projects.requireMember(projectId, userId, false);
        return pullRequests.findDetail(pullRequestId, projectId)
                .filter(pull -> pull.getRepository().getConnectionStatus() != GitRepository.ConnectionStatus.DISCONNECTED)
                .orElseThrow(() -> new ApiException(404, "PULL_REQUEST_NOT_FOUND", "PR을 찾을 수 없습니다."));
    }

    public PullRequestView detail(PullRequest pull) { return PullRequestView.from(pull, pull.getBody()); }

    /** 외부 호출은 트랜잭션 밖에서 끝내고, 여기서는 프로젝트 행 잠금 안에서 반영만 한다. */
    @Transactional
    public int apply(long projectId, long userId, long repositoryId, List<GitHubPullRequests.Fetched> fetched) {
        projectRepository.findLockedById(projectId).orElseThrow(ProjectService::notFound);
        projects.requireMember(projectId, userId, false);
        var repository = repositories.findByIdAndProjectId(repositoryId, projectId)
                .filter(repo -> repo.getConnectionStatus() != GitRepository.ConnectionStatus.DISCONNECTED)
                .orElseThrow(() -> new ApiException(404, "REPOSITORY_NOT_FOUND", "연결된 저장소를 찾을 수 없습니다."));
        var rows = pullRequests.findByRepositoryId(repositoryId);
        Map<Long, PullRequest> existing = rows.stream()
                .filter(pull -> pull.getGithubPrId() != null).collect(Collectors.toMap(PullRequest::getGithubPrId, Function.identity()));
        // zello에서 만들던 중이라 아직 GitHub 번호가 없는 행. 같은 브랜치의 PR이 수집되면 새 행을 만들지 않고 이어받는다.
        var inFlight = new ArrayList<>(rows.stream().filter(pull -> pull.getGithubPrId() == null
                && (pull.getPublishStatus() == PublishStatus.PENDING || pull.getPublishStatus() == PublishStatus.UNKNOWN)).toList());
        Map<Long, GitHubAccount> authors = new HashMap<>();
        var now = EntitySupport.now();
        for (var item : fetched) {
            var remote = item.pull();
            var current = existing.get(remote.id());
            if (current == null) {
                var adopted = inFlight.stream().filter(pull -> pull.getHeadBranch().equals(remote.head().ref())
                        && pull.getBaseBranch().equals(remote.base().ref())).findFirst();
                if (adopted.isPresent()) {
                    current = adopted.get();
                    inFlight.remove(current);
                    current.markPublished(remote.id(), remote.number(), remote.htmlUrl(), remote.head().sha(), remote.draft(),
                            utc(remote.createdAt()), utc(remote.updatedAt()), now);
                }
            }
            // 응답 순서가 뒤바뀌어도 더 최신 데이터를 덮어쓰지 않는다.
            if (current != null && current.getGithubUpdatedAt() != null && utc(remote.updatedAt()).isBefore(current.getGithubUpdatedAt())) continue;
            var author = remote.user() == null ? null : authors.computeIfAbsent(remote.user().id(), id -> author(remote.user()));
            var state = remote.mergedAt() != null ? PullRequest.State.MERGED
                    : "open".equals(remote.state()) ? PullRequest.State.OPEN : PullRequest.State.CLOSED;
            var title = remote.title().length() > 255 ? remote.title().substring(0, 255) : remote.title();
            if (current == null) {
                pullRequests.save(PullRequest.builder().repository(repository).authorAccount(author).githubPrId(remote.id())
                        .githubNumber(remote.number()).githubUrl(remote.htmlUrl()).title(title).body(remote.body())
                        .headBranch(remote.head().ref()).baseBranch(remote.base().ref()).headSha(remote.head().sha())
                        .mergeCommitSha(remote.mergeCommitSha()).state(state).draft(remote.draft() && state != PullRequest.State.MERGED)
                        .reviewDecision(item.reviewDecision()).openedAt(utc(remote.createdAt())).closedAt(utc(remote.closedAt()))
                        .mergedAt(utc(remote.mergedAt())).publishStatus(PublishStatus.PUBLISHED)
                        .githubUpdatedAt(utc(remote.updatedAt())).lastFetchedAt(now).build());
            } else {
                current.syncFromRemote(author, remote.htmlUrl(), title, remote.body(), remote.head().ref(), remote.base().ref(),
                        remote.head().sha(), remote.mergeCommitSha(), state, remote.draft(), item.reviewDecision(),
                        utc(remote.createdAt()), utc(remote.closedAt()), utc(remote.mergedAt()), utc(remote.updatedAt()), now);
            }
        }
        repository.recordSync(now);
        return fetched.size();
    }

    private GitHubAccount author(GitHubPullRequests.User user) {
        var type = "Bot".equals(user.type()) ? GitHubAccount.AccountType.BOT
                : "Organization".equals(user.type()) ? GitHubAccount.AccountType.ORGANIZATION : GitHubAccount.AccountType.USER;
        var account = accounts.findByGithubId(user.id()).orElseGet(() -> accounts.saveAndFlush(GitHubAccount.builder()
                .githubId(user.id()).login(user.login()).avatarUrl(user.avatarUrl()).accountType(type).build()));
        account.updateProfile(user.login(), account.getDisplayName(), user.avatarUrl());
        return account;
    }

    private Counts counts(long projectId, Collection<Long> repositoryIds, Long authorAccountId) {
        long open = 0, merged = 0, closed = 0;
        for (var row : pullRequests.countByState(projectId, repositoryIds, authorAccountId)) {
            long count = (Long) row[1];
            switch ((PullRequest.State) row[0]) {
                case OPEN -> open = count;
                case MERGED -> merged = count;
                case CLOSED -> closed = count;
            }
        }
        return new Counts(open, merged, closed);
    }

    private static LocalDateTime utc(Instant instant) { return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }

    public record Filter(EnumSet<PullRequest.State> states, boolean mine) { }

    public record Counts(long open, long merged, long closed) { }

    public record PullRequestPage(List<PullRequestView> items, int page, boolean hasNext, Counts counts) { }

    public record Author(String login, String avatarUrl) { }

    /** body는 상세 응답에만 담는다. 목록에서는 응답이 커지지 않도록 null로 두고 JSON에서 뺀다. */
    public record PullRequestView(String id, String repositoryId, String repositoryOwner, String repositoryName, Integer number,
                                  String url, String title, String headBranch, String baseBranch, String state, boolean draft,
                                  String reviewDecision, String teamSummary, Author author, Instant openedAt, Instant mergedAt,
                                  Instant githubUpdatedAt, @JsonInclude(JsonInclude.Include.NON_NULL) String body) {
        static PullRequestView from(PullRequest pull, String body) {
            var repo = pull.getRepository();
            var author = pull.getAuthorAccount();
            return new PullRequestView(pull.getId().toString(), repo.getId().toString(), repo.getOwnerLogin(), repo.getName(),
                    pull.getGithubNumber(), pull.getGithubUrl(), pull.getTitle(), pull.getHeadBranch(), pull.getBaseBranch(),
                    pull.getState().name(), pull.getDraft(), pull.getReviewDecision().name(), pull.getTeamSummary(),
                    author == null ? null : new Author(author.getLogin(), author.getAvatarUrl()), instant(pull.getOpenedAt()),
                    instant(pull.getMergedAt()), instant(pull.getGithubUpdatedAt()), body);
        }

        private static Instant instant(LocalDateTime value) { return value == null ? null : value.toInstant(ZoneOffset.UTC); }
    }
}
