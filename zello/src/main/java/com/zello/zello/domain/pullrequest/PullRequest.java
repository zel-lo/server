package com.zello.zello.domain.pullrequest;

import com.zello.zello.domain.common.*;
import com.zello.zello.domain.github.GitHubAccount;
import com.zello.zello.domain.repository.GitRepository;
import com.zello.zello.domain.user.User;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(name = "pull_requests",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_pull_requests_scope", columnNames = {"id", "repository_id"}),
                @UniqueConstraint(name = "uk_pull_requests_publish_request", columnNames = {"publish_request_key"}),
                @UniqueConstraint(name = "uk_pull_requests_repository_github_id", columnNames = {"repository_id", "github_pr_id"}),
                @UniqueConstraint(name = "uk_pull_requests_repository_number", columnNames = {"repository_id", "github_number"})
        },
        indexes = {
                @Index(name = "idx_pull_requests_repository_state_updated", columnList = "repository_id, state, is_draft, updated_at")
        },
        check = {
                @CheckConstraint(name = "ck_pull_requests_state", constraint = "state IN ('OPEN', 'CLOSED', 'MERGED')"),
                @CheckConstraint(name = "ck_pull_requests_review_decision", constraint = "review_decision IN ('UNKNOWN', 'PENDING', 'APPROVED', 'CHANGES_REQUESTED')"),
                @CheckConstraint(name = "ck_pull_requests_publish_status", constraint = "publish_status IN ('LOCAL', 'PENDING', 'PUBLISHED', 'FAILED', 'UNKNOWN')"),
                @CheckConstraint(name = "ck_pull_requests_external_identity", constraint = "(github_pr_id IS NULL AND github_number IS NULL) OR (github_pr_id IS NOT NULL AND github_number IS NOT NULL AND github_pr_id > 0 AND github_number > 0)"),
                @CheckConstraint(name = "ck_pull_requests_published", constraint = "publish_status <> 'PUBLISHED' OR (github_pr_id IS NOT NULL AND github_number IS NOT NULL)"),
                @CheckConstraint(name = "ck_pull_requests_merge", constraint = "(state = 'MERGED' AND merged_at IS NOT NULL AND is_draft = FALSE AND publish_status = 'PUBLISHED') OR (state <> 'MERGED' AND merged_at IS NULL)"),
                @CheckConstraint(name = "ck_pull_requests_version", constraint = "version >= 0")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PullRequest extends VersionedEntity {
    @Column(name = "repository_id", nullable = false)
    private Long repositoryId;

    @Column(name = "author_account_id")
    private Long authorAccountId;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "github_pr_id")
    private Long githubPrId;

    @Column(name = "github_number")
    private Integer githubNumber;

    @Column(name = "github_url", length = 2048)
    private String githubUrl;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "body", columnDefinition = "text")
    private String body;

    @Column(name = "team_summary", columnDefinition = "text")
    private String teamSummary;

    @Column(name = "head_branch", nullable = false, length = 255)
    private String headBranch;

    @Column(name = "base_branch", nullable = false, length = 255)
    private String baseBranch;

    @Column(name = "head_sha", length = 64)
    private String headSha;

    @Column(name = "merge_commit_sha", length = 64)
    private String mergeCommitSha;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "state", nullable = false, length = 20)
    private State state;

    @Column(name = "is_draft", nullable = false)
    private Boolean draft;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "review_decision", nullable = false, length = 30)
    private ReviewDecision reviewDecision;

    @Column(name = "changed_files")
    private Integer changedFiles;

    @Column(name = "additions")
    private Integer additions;

    @Column(name = "deletions")
    private Integer deletions;

    @Column(name = "commit_count")
    private Integer commitCount;

    @Column(name = "opened_at", columnDefinition = "datetime(6)")
    private LocalDateTime openedAt;

    @Column(name = "closed_at", columnDefinition = "datetime(6)")
    private LocalDateTime closedAt;

    @Column(name = "merged_at", columnDefinition = "datetime(6)")
    private LocalDateTime mergedAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "publish_status", nullable = false, length = 20)
    private PublishStatus publishStatus;

    @Column(name = "publish_request_key", length = 64)
    private String publishRequestKey;

    @Column(name = "last_publish_error", columnDefinition = "text")
    private String lastPublishError;

    @Column(name = "github_updated_at", columnDefinition = "datetime(6)")
    private LocalDateTime githubUpdatedAt;

    @Column(name = "last_fetched_at", columnDefinition = "datetime(6)")
    private LocalDateTime lastFetchedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", referencedColumnName = "id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_pull_requests_repository"))
    private GitRepository repository;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_account_id", referencedColumnName = "id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_pull_requests_author"))
    private GitHubAccount authorAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id", referencedColumnName = "id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_pull_requests_creator"))
    private User createdByUser;

    @Builder
    private PullRequest(
            GitRepository repository,
            GitHubAccount authorAccount,
            User createdByUser,
            Long githubPrId,
            Integer githubNumber,
            String githubUrl,
            String title,
            String body,
            String teamSummary,
            String headBranch,
            String baseBranch,
            String headSha,
            String mergeCommitSha,
            State state,
            Boolean draft,
            ReviewDecision reviewDecision,
            Integer changedFiles,
            Integer additions,
            Integer deletions,
            Integer commitCount,
            LocalDateTime openedAt,
            LocalDateTime closedAt,
            LocalDateTime mergedAt,
            PublishStatus publishStatus,
            String publishRequestKey,
            String lastPublishError,
            LocalDateTime githubUpdatedAt,
            LocalDateTime lastFetchedAt) {
        this.repository = EntitySupport.required(repository, "repository");
        this.authorAccount = authorAccount;
        this.createdByUser = createdByUser;
        this.repositoryId = EntitySupport.required(repository.getId(), "persisted repository.id");
        this.authorAccountId = authorAccount == null ? null : EntitySupport.required(authorAccount.getId(), "persisted authorAccount.id");
        this.createdByUserId = createdByUser == null ? null : EntitySupport.required(createdByUser.getId(), "persisted createdByUser.id");
        this.githubPrId = githubPrId;
        this.githubNumber = githubNumber;
        this.githubUrl = githubUrl;
        this.title = EntitySupport.text(title, "title");
        this.body = body;
        this.teamSummary = teamSummary;
        this.headBranch = EntitySupport.text(headBranch, "headBranch");
        this.baseBranch = EntitySupport.text(baseBranch, "baseBranch");
        this.headSha = headSha;
        this.mergeCommitSha = mergeCommitSha;
        this.state = Objects.requireNonNullElseGet(state, () -> State.OPEN);
        this.draft = Objects.requireNonNullElseGet(draft, () -> false);
        this.reviewDecision = Objects.requireNonNullElseGet(reviewDecision, () -> ReviewDecision.UNKNOWN);
        this.changedFiles = changedFiles;
        this.additions = additions;
        this.deletions = deletions;
        this.commitCount = commitCount;
        this.openedAt = openedAt;
        this.closedAt = closedAt;
        this.mergedAt = mergedAt;
        this.publishStatus = Objects.requireNonNullElseGet(publishStatus, () -> PublishStatus.LOCAL);
        this.publishRequestKey = publishRequestKey;
        this.lastPublishError = lastPublishError;
        this.githubUpdatedAt = githubUpdatedAt;
        this.lastFetchedAt = lastFetchedAt;
    }

    public void updateTeamSummary(String teamSummary) {
        this.teamSummary = teamSummary;
    }

    public void markMerged(LocalDateTime mergedAt, String mergeCommitSha) {
        if (publishStatus != PublishStatus.PUBLISHED) throw new IllegalStateException("PR is not published");
        this.mergedAt = EntitySupport.required(mergedAt, "mergedAt");
        this.closedAt = mergedAt;
        this.mergeCommitSha = mergeCommitSha;
        this.draft = false;
        this.state = State.MERGED;
    }

    public enum State {
        OPEN, CLOSED, MERGED
    }

    public enum ReviewDecision {
        UNKNOWN, PENDING, APPROVED, CHANGES_REQUESTED
    }

}
