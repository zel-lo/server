package com.zello.zello.issue;

import com.zello.zello.api.ApiException;
import com.zello.zello.domain.common.PublishStatus;
import com.zello.zello.domain.issue.*;
import com.zello.zello.domain.project.ProjectRepository;
import com.zello.zello.domain.pullrequest.*;
import com.zello.zello.domain.user.UserRepository;
import com.zello.zello.project.ProjectService;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class IssuePullRequestService {
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;
    private final IssueRepository issues;
    private final PullRequestRepository pulls;
    private final IssuePullRequestRepository links;
    private final ProjectRepository projects;
    private final ProjectService access;
    private final UserRepository users;
    private final com.zello.zello.workspace.WorkChangeService changes;

    public IssuePullRequestService(IssueRepository issues, PullRequestRepository pulls, IssuePullRequestRepository links,
                                   ProjectRepository projects, ProjectService access, UserRepository users, com.zello.zello.workspace.WorkChangeService changes) {
        this.changes = changes;
        this.issues = issues; this.pulls = pulls; this.links = links;
        this.projects = projects; this.access = access; this.users = users;
    }

    /** Same-repository links inherit the repository access already checked by the controller. */
    public List<LinkedPullRequest> pullsFor(long issueId) {
        return links.findByIdIssueIdIn(List.of(issueId)).stream().map(IssuePullRequest::getPullRequest)
                .filter(p -> p.getPublishStatus() == PublishStatus.PUBLISHED)
                .sorted(Comparator.comparing(PullRequest::getId))
                .map(p -> new LinkedPullRequest(p.getId().toString(), p.getGithubNumber(), p.getTitle(), p.getGithubUrl(),
                        p.getState().name(), p.getDraft(), p.getReviewDecision().name())).toList();
    }

    public List<LinkedIssue> issuesFor(long pullId) {
        return links.findByIdPullRequestIdIn(List.of(pullId)).stream().map(IssuePullRequest::getIssue)
                .sorted(Comparator.comparing(Issue::getId))
                .map(i -> new LinkedIssue(i.getId().toString(), i.getIssueKey(), i.getGithubNumber(), i.getTitle(), i.getGithubUrl(),
                        i.getWorkStatus().name(), i.getArchivedAt() != null)).toList();
    }

    public List<Issue> validate(long projectId, long repositoryId, Collection<Long> ids) {
        if (ids == null) return List.of();
        return ids.stream().distinct().sorted().map(id -> issues.findDetail(id, projectId)
                .filter(i -> i.getRepositoryId() == repositoryId && i.getArchivedAt() == null)
                .orElseThrow(() -> new ApiException(400, "INVALID_LINKED_ISSUE", "같은 저장소의 보관되지 않은 이슈만 연결할 수 있어요."))).toList();
    }

    @Transactional
    public void change(long projectId, long userId, long issueId, long pullId, long version, boolean linked) {
        projects.findLockedById(projectId).orElseThrow(ProjectService::notFound);
        access.requireWriter(projectId, userId);
        var issue = issues.findDetail(issueId, projectId).orElseThrow(this::notFound);
        var pull = pulls.findDetail(pullId, projectId).filter(p -> p.getRepositoryId().equals(issue.getRepositoryId())
                && p.getPublishStatus() == PublishStatus.PUBLISHED).orElseThrow(this::notFound);
        var id = new IssuePullRequestId(issueId, pullId);
        if (links.existsById(id) == linked) return; // replay, including a lost response
        if (issue.getVersion() != version) throw new ApiException(409, "ISSUE_CHANGED", "다른 변경이 있어요. 최신 작업을 확인해 주세요.");
        if (linked) {
            validate(projectId, issue.getRepositoryId(), List.of(issueId));
            attach(pull, List.of(issue), userId);
        } else { links.deleteById(id); links.flush(); }
        recalculate(issueId);
        changes.pull(pull);
        entityManager.lock(issue, jakarta.persistence.LockModeType.OPTIMISTIC_FORCE_INCREMENT);
        // A link edit must invalidate an issue editor even when the derived status stays the same.
        issues.flush();
    }

    @Transactional
    public void attach(PullRequest pull, List<Issue> selected, long userId) {
        var user = users.findById(userId).orElseThrow(ProjectService::notFound);
        for (var issue : selected) {
            var id = new IssuePullRequestId(issue.getId(), pull.getId());
            if (!links.existsById(id)) links.save(IssuePullRequest.builder().issue(issue).pullRequest(pull).createdByUser(user).build());
        }
        links.flush();
    }

    @Transactional
    public void transfer(long from, PullRequest to) {
        var rows = links.findByIdPullRequestIdIn(List.of(from));
        for (var row : rows) {
            var id = new IssuePullRequestId(row.getIssue().getId(), to.getId());
            if (!links.existsById(id)) links.save(IssuePullRequest.builder().issue(row.getIssue()).pullRequest(to).createdByUser(row.getCreatedByUser()).build());
        }
        links.deleteAll(rows); links.flush();
        recalculateForPull(to.getId());
    }

    @Transactional
    public void recalculateForPull(long pullId) {
        for (var row : links.findByIdPullRequestIdIn(List.of(pullId))) recalculate(row.getIssue().getId());
        pulls.findById(pullId).ifPresent(changes::pull);
    }

    @Transactional
    public void recalculate(long issueId) {
        var issue = issues.findById(issueId).orElseThrow(this::notFound);
        var active = links.findByIdIssueIdIn(List.of(issueId)).stream().map(IssuePullRequest::getPullRequest)
                .filter(p -> p.getPublishStatus() == PublishStatus.PUBLISHED && p.getState() != PullRequest.State.CLOSED).toList();
        var open = active.stream().filter(p -> p.getState() == PullRequest.State.OPEN).toList();
        Issue.WorkStatus status = null;
        java.time.LocalDateTime completed = null;
        if (!open.isEmpty()) status = open.stream().anyMatch(p -> p.getDraft() || p.getReviewDecision() == PullRequest.ReviewDecision.CHANGES_REQUESTED)
                ? Issue.WorkStatus.IN_PROGRESS : Issue.WorkStatus.IN_REVIEW;
        else if (!active.isEmpty()) {
            status = Issue.WorkStatus.DONE;
            completed = active.stream().map(PullRequest::getMergedAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        }
        issue.applyPullRequestStatus(status, completed);
        changes.issue(issue);
    }

    private ApiException notFound() { return new ApiException(404, "ISSUE_PR_LINK_NOT_FOUND", "같은 저장소의 이슈와 PR을 찾을 수 없어요."); }

    public record LinkedIssue(String id, String key, Integer number, String title, String url, String status, boolean archived) { }
    public record LinkedPullRequest(String id, Integer number, String title, String url, String state, boolean draft, String reviewDecision) { }
}
