package com.zello.zello.domain.issue;

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
@Table(name = "issues",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_issues_scope", columnNames = {"id", "repository_id"}),
                @UniqueConstraint(name = "uk_issues_key", columnNames = {"issue_key"}),
                @UniqueConstraint(name = "uk_issues_publish_request", columnNames = {"publish_request_key"}),
                @UniqueConstraint(name = "uk_issues_repository_github_id", columnNames = {"repository_id", "github_issue_id"}),
                @UniqueConstraint(name = "uk_issues_repository_number", columnNames = {"repository_id", "github_number"})
        },
        indexes = {
                @Index(name = "idx_issues_week", columnList = "planned_week_start, work_status, completed_at"),
                @Index(name = "idx_issues_board", columnList = "repository_id, archived_at, work_status, board_order, id"),
                @Index(name = "idx_issues_repository_status_priority", columnList = "repository_id, work_status, priority"),
                @Index(name = "idx_issues_assignee_status_repository", columnList = "assignee_account_id, work_status, repository_id"),
                @Index(name = "idx_issues_repository_source_meeting", columnList = "repository_id, source_type, meeting_date")
        },
        check = {
                @CheckConstraint(name = "ck_issues_work_status", constraint = "work_status IN ('BACKLOG', 'TODO', 'IN_PROGRESS', 'IN_REVIEW', 'DONE')"),
                @CheckConstraint(name = "ck_issues_priority", constraint = "priority IN ('HIGH', 'MEDIUM', 'LOW')"),
                @CheckConstraint(name = "ck_issues_source_type", constraint = "source_type IN ('DAILY', 'WEEKLY', 'REQUEST', 'GITHUB')"),
                @CheckConstraint(name = "ck_issues_publish_status", constraint = "publish_status IN ('LOCAL', 'PENDING', 'PUBLISHED', 'FAILED', 'UNKNOWN')"),
                @CheckConstraint(name = "ck_issues_meeting_date", constraint = "source_type NOT IN ('DAILY', 'WEEKLY') OR meeting_date IS NOT NULL"),
                @CheckConstraint(name = "ck_issues_external_identity", constraint = "(github_issue_id IS NULL AND github_number IS NULL) OR (github_issue_id IS NOT NULL AND github_number IS NOT NULL AND github_issue_id > 0 AND github_number > 0)"),
                @CheckConstraint(name = "ck_issues_published", constraint = "publish_status <> 'PUBLISHED' OR (github_issue_id IS NOT NULL AND github_number IS NOT NULL AND github_state IS NOT NULL)"),
                @CheckConstraint(name = "ck_issues_github_state", constraint = "github_state IS NULL OR github_state IN ('OPEN', 'CLOSED')"),
                @CheckConstraint(name = "ck_issues_version", constraint = "version >= 0")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Issue extends VersionedEntity {
    @Column(name = "issue_key", nullable = false, length = 32)
    private String issueKey;

    @Column(name = "repository_id", nullable = false)
    private Long repositoryId;

    @Column(name = "author_account_id")
    private Long authorAccountId;

    @Column(name = "assignee_account_id")
    private Long assigneeAccountId;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "github_issue_id")
    private Long githubIssueId;

    @Column(name = "github_number")
    private Integer githubNumber;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "github_state", length = 20)
    private GithubState githubState;

    @Column(name = "github_url", length = 2048)
    private String githubUrl;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "acceptance_criteria", columnDefinition = "text")
    private String acceptanceCriteria;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "work_status", nullable = false, length = 20)
    private WorkStatus workStatus;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "priority", nullable = false, length = 20)
    private Priority priority;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "source_type", nullable = false, length = 20)
    private SourceType sourceType;

    @Column(name = "meeting_date")
    private LocalDate meetingDate;

    @Column(name = "meeting_context", columnDefinition = "text")
    private String meetingContext;

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
    @JoinColumn(name = "repository_id", referencedColumnName = "id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_issues_repository"))
    private GitRepository repository;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_account_id", referencedColumnName = "id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_issues_author"))
    private GitHubAccount authorAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_account_id", referencedColumnName = "id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_issues_assignee"))
    private GitHubAccount assigneeAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id", referencedColumnName = "id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_issues_creator"))
    private User createdByUser;

    @Builder
    private Issue(
            GitRepository repository,
            GitHubAccount authorAccount,
            GitHubAccount assigneeAccount,
            User createdByUser,
            String issueKey,
            Long githubIssueId,
            Integer githubNumber,
            GithubState githubState,
            String githubUrl,
            String title,
            String description,
            String acceptanceCriteria,
            WorkStatus workStatus,
            Priority priority,
            SourceType sourceType,
            LocalDate meetingDate,
            String meetingContext,
            PublishStatus publishStatus,
            String publishRequestKey,
            String lastPublishError,
            LocalDateTime githubUpdatedAt,
            LocalDateTime lastFetchedAt) {
        this.repository = EntitySupport.required(repository, "repository");
        this.authorAccount = authorAccount;
        this.assigneeAccount = assigneeAccount;
        this.createdByUser = createdByUser;
        this.repositoryId = EntitySupport.required(repository.getId(), "persisted repository.id");
        this.authorAccountId = authorAccount == null ? null : EntitySupport.required(authorAccount.getId(), "persisted authorAccount.id");
        this.assigneeAccountId = assigneeAccount == null ? null : EntitySupport.required(assigneeAccount.getId(), "persisted assigneeAccount.id");
        this.createdByUserId = createdByUser == null ? null : EntitySupport.required(createdByUser.getId(), "persisted createdByUser.id");
        this.issueKey = EntitySupport.text(issueKey, "issueKey");
        this.githubIssueId = githubIssueId;
        this.githubNumber = githubNumber;
        this.githubState = githubState;
        this.githubUrl = githubUrl;
        this.title = EntitySupport.text(title, "title");
        this.description = description;
        this.acceptanceCriteria = acceptanceCriteria;
        this.workStatus = Objects.requireNonNullElseGet(workStatus, () -> WorkStatus.BACKLOG);
        if (this.workStatus == WorkStatus.DONE) this.completedAt = EntitySupport.now();
        this.priority = Objects.requireNonNullElseGet(priority, () -> Priority.MEDIUM);
        this.sourceType = Objects.requireNonNullElseGet(sourceType, () -> SourceType.REQUEST);
        this.meetingDate = meetingDate;
        this.meetingContext = meetingContext;
        this.publishStatus = Objects.requireNonNullElseGet(publishStatus, () -> PublishStatus.LOCAL);
        this.publishRequestKey = publishRequestKey;
        this.lastPublishError = lastPublishError;
        this.githubUpdatedAt = githubUpdatedAt;
        this.lastFetchedAt = lastFetchedAt;
    }

    @Column(name = "archived_at", columnDefinition = "datetime(6)")
    private LocalDateTime archivedAt;

    @Column(name = "creation_hash", length = 64)
    private String creationHash;

    @Column(name = "board_order", nullable = false)
    private long boardOrder;

    @Column(name = "planned_week_start", nullable = false)
    private LocalDate plannedWeekStart = LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).with(java.time.DayOfWeek.MONDAY);

    @Column(name = "completed_at", columnDefinition = "datetime(6)")
    private LocalDateTime completedAt;

    public void recordGithubCompletion(LocalDateTime closedAt) {
        if(this.githubState==GithubState.CLOSED && closedAt!=null) this.completedAt=closedAt;
    }

    public void schedule(LocalDate week) { if (week != null) this.plannedWeekStart = week; }

    public void placeOnBoard(long order) { this.boardOrder = order; }
    public void assignDisplayKey() { this.issueKey = "ZELLO-" + EntitySupport.required(getId(), "persisted issue.id"); }
    public void creationHash(String hash) { this.creationHash = hash; }
    public void preparePublish(String key) { this.publishRequestKey = key; claim(EntitySupport.now()); }

    public void updateDetails(String title, String description, String acceptanceCriteria) {
        this.title = EntitySupport.text(title, "title");
        this.description = description;
        this.acceptanceCriteria = acceptanceCriteria;
    }

    public void assignTo(GitHubAccount assigneeAccount) {
        this.assigneeAccount = assigneeAccount;
        this.assigneeAccountId = assigneeAccount == null ? null : EntitySupport.required(assigneeAccount.getId(), "persisted assigneeAccount");
    }

    public void plan(WorkStatus status, Priority priority, SourceType source, LocalDate date, String context, String criteria) {
        if (status == WorkStatus.DONE && this.completedAt == null) this.completedAt = EntitySupport.now();
        if (status != WorkStatus.DONE) this.completedAt = null;
        this.workStatus = status;
        this.priority = priority;
        this.sourceType = source;
        this.meetingDate = date;
        this.meetingContext = context;
        this.acceptanceCriteria = criteria;
    }

    public void claim(LocalDateTime now) { this.publishStatus = PublishStatus.PENDING; this.lastFetchedAt = now; }
    public void publicationFailed(boolean unknown, String message) {
        if (publishStatus != PublishStatus.PUBLISHED) {
            this.publishStatus = unknown ? PublishStatus.UNKNOWN : PublishStatus.FAILED;
            this.lastPublishError = message;
        }
    }
    public void archive(LocalDateTime now) { this.archivedAt = now; }
    public void restore() { this.archivedAt = null; }
    public void syncFromRemote(Long remoteId, Integer number, String url, String title, String body, GithubState state,
                               GitHubAccount author, GitHubAccount assignee, LocalDateTime updatedAt, LocalDateTime now) {
        this.githubIssueId = remoteId;
        this.githubNumber = number;
        this.githubUrl = url;
        this.title = EntitySupport.text(title, "title");
        this.description = body;
        if (state == GithubState.CLOSED) {
            if (this.completedAt == null) this.completedAt = updatedAt == null ? now : updatedAt;
            this.workStatus = WorkStatus.DONE;
        }
        else if (this.githubState == GithubState.CLOSED && this.workStatus == WorkStatus.DONE) {
            this.workStatus = WorkStatus.TODO;
            this.completedAt = null;
        }
        this.githubState = state;
        this.authorAccount = author;
        this.authorAccountId = author == null ? null : author.getId();
        assignTo(assignee);
        this.githubUpdatedAt = updatedAt;
        this.lastFetchedAt = now;
        this.publishStatus = PublishStatus.PUBLISHED;
        this.lastPublishError = null;
    }

    public enum WorkStatus {
        BACKLOG, TODO, IN_PROGRESS, IN_REVIEW, DONE
    }

    public enum Priority {
        HIGH, MEDIUM, LOW
    }

    public enum SourceType {
        DAILY, WEEKLY, REQUEST, GITHUB
    }

    public enum GithubState {
        OPEN, CLOSED
    }

}
