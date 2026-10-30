package com.zello.zello.domain.deployment;

import com.zello.zello.domain.common.*;
import com.zello.zello.domain.repository.GitRepository;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "build_runs",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_build_runs_scope", columnNames = {"id", "repository_id"}),
                @UniqueConstraint(name = "uk_build_runs_repository_run_attempt", columnNames = {"repository_id", "github_run_id", "run_attempt"})
        },
        indexes = {
                @Index(name = "idx_build_runs_repository_sha_started", columnList = "repository_id, head_sha, started_at")
        },
        check = {
                @CheckConstraint(name = "ck_build_runs_attempt", constraint = "run_attempt > 0")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BuildRun extends IdentityEntity {
    @Column(name = "repository_id", nullable = false)
    private Long repositoryId;

    @Column(name = "github_run_id", nullable = false)
    private Long githubRunId;

    @Column(name = "run_attempt", nullable = false)
    private Integer runAttempt;

    @Column(name = "workflow_name", nullable = false, length = 255)
    private String workflowName;

    @Column(name = "branch_name", length = 255)
    private String branchName;

    @Column(name = "head_sha", nullable = false, length = 64)
    private String headSha;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "conclusion", length = 30)
    private String conclusion;

    @Column(name = "html_url", nullable = false, length = 2048)
    private String htmlUrl;

    @Column(name = "started_at", columnDefinition = "datetime(6)")
    private LocalDateTime startedAt;

    @Column(name = "completed_at", columnDefinition = "datetime(6)")
    private LocalDateTime completedAt;

    @Column(name = "github_updated_at", columnDefinition = "datetime(6)")
    private LocalDateTime githubUpdatedAt;

    @Column(name = "last_fetched_at", columnDefinition = "datetime(6)")
    private LocalDateTime lastFetchedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", referencedColumnName = "id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_build_runs_repository"))
    private GitRepository repository;

    @Builder
    private BuildRun(
            GitRepository repository,
            Long githubRunId,
            Integer runAttempt,
            String workflowName,
            String branchName,
            String headSha,
            String status,
            String conclusion,
            String htmlUrl,
            LocalDateTime startedAt,
            LocalDateTime completedAt,
            LocalDateTime githubUpdatedAt,
            LocalDateTime lastFetchedAt) {
        this.repository = EntitySupport.required(repository, "repository");
        this.repositoryId = EntitySupport.required(repository.getId(), "persisted repository.id");
        this.githubRunId = EntitySupport.required(githubRunId, "githubRunId");
        this.runAttempt = Objects.requireNonNullElseGet(runAttempt, () -> 1);
        this.workflowName = EntitySupport.text(workflowName, "workflowName");
        this.branchName = branchName;
        this.headSha = EntitySupport.text(headSha, "headSha");
        this.status = Objects.requireNonNullElseGet(status, () -> "queued");
        this.conclusion = conclusion;
        this.htmlUrl = EntitySupport.text(htmlUrl, "htmlUrl");
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.githubUpdatedAt = githubUpdatedAt;
        this.lastFetchedAt = lastFetchedAt;
    }

}
