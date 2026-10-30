package com.zello.zello.domain.activity;

import com.zello.zello.domain.common.*;
import com.zello.zello.domain.deployment.BuildRun;
import com.zello.zello.domain.deployment.Deployment;
import com.zello.zello.domain.github.GitHubAccount;
import com.zello.zello.domain.issue.Issue;
import com.zello.zello.domain.patchnote.PatchNote;
import com.zello.zello.domain.project.Project;
import com.zello.zello.domain.pullrequest.PullRequest;
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
@Table(name = "activities",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_activities_scope", columnNames = {"id", "project_id"}),
                @UniqueConstraint(name = "uk_activities_project_event", columnNames = {"project_id", "event_key"})
        },
        indexes = {
                @Index(name = "idx_activities_project_occurred", columnList = "project_id, occurred_at, id"),
                @Index(name = "idx_activities_repository_occurred", columnList = "repository_id, occurred_at, id")
        },
        check = {
                @CheckConstraint(name = "ck_activities_single_target", constraint = "(CASE WHEN issue_id IS NOT NULL THEN 1 ELSE 0 END) + (CASE WHEN pull_request_id IS NOT NULL THEN 1 ELSE 0 END) + (CASE WHEN build_run_id IS NOT NULL THEN 1 ELSE 0 END) + (CASE WHEN deployment_id IS NOT NULL THEN 1 ELSE 0 END) + (CASE WHEN patch_note_id IS NOT NULL THEN 1 ELSE 0 END) <= 1"),
                @CheckConstraint(name = "ck_activities_repository_target", constraint = "(issue_id IS NULL AND pull_request_id IS NULL AND build_run_id IS NULL AND deployment_id IS NULL) OR repository_id IS NOT NULL"),
                @CheckConstraint(name = "ck_activities_patch_note_scope", constraint = "patch_note_id IS NULL OR repository_id IS NULL")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Activity extends IdentityEntity {
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "repository_id")
    private Long repositoryId;

    @Column(name = "actor_account_id")
    private Long actorAccountId;

    @Column(name = "event_key", nullable = false, length = 255)
    private String eventKey;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @Column(name = "occurred_at", nullable = false, columnDefinition = "datetime(6)")
    private LocalDateTime occurredAt;

    @Column(name = "issue_id")
    private Long issueId;

    @Column(name = "pull_request_id")
    private Long pullRequestId;

    @Column(name = "build_run_id")
    private Long buildRunId;

    @Column(name = "deployment_id")
    private Long deploymentId;

    @Column(name = "patch_note_id")
    private Long patchNoteId;

    @Column(name = "commit_sha", length = 64)
    private String commitSha;

    @Column(name = "branch_name", length = 255)
    private String branchName;

    @Column(name = "source_url", length = 2048)
    private String sourceUrl;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", referencedColumnName = "id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_activities_project"))
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns(value = {
            @JoinColumn(name = "repository_id", referencedColumnName = "id", insertable = false, updatable = false),
            @JoinColumn(name = "project_id", referencedColumnName = "project_id", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_activities_repository"))
    private GitRepository repository;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_account_id", referencedColumnName = "id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_activities_actor"))
    private GitHubAccount actorAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns(value = {
            @JoinColumn(name = "issue_id", referencedColumnName = "id", insertable = false, updatable = false),
            @JoinColumn(name = "repository_id", referencedColumnName = "repository_id", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_activities_issue"))
    private Issue issue;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns(value = {
            @JoinColumn(name = "pull_request_id", referencedColumnName = "id", insertable = false, updatable = false),
            @JoinColumn(name = "repository_id", referencedColumnName = "repository_id", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_activities_pr"))
    private PullRequest pullRequest;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns(value = {
            @JoinColumn(name = "build_run_id", referencedColumnName = "id", insertable = false, updatable = false),
            @JoinColumn(name = "repository_id", referencedColumnName = "repository_id", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_activities_build"))
    private BuildRun buildRun;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns(value = {
            @JoinColumn(name = "deployment_id", referencedColumnName = "id", insertable = false, updatable = false),
            @JoinColumn(name = "repository_id", referencedColumnName = "repository_id", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_activities_deployment"))
    private Deployment deployment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns(value = {
            @JoinColumn(name = "patch_note_id", referencedColumnName = "id", insertable = false, updatable = false),
            @JoinColumn(name = "project_id", referencedColumnName = "project_id", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_activities_patch_note"))
    private PatchNote patchNote;

    @Builder
    private Activity(
            Project project,
            GitRepository repository,
            GitHubAccount actorAccount,
            Issue issue,
            PullRequest pullRequest,
            BuildRun buildRun,
            Deployment deployment,
            PatchNote patchNote,
            String eventKey,
            String eventType,
            String title,
            String summary,
            LocalDateTime occurredAt,
            String commitSha,
            String branchName,
            String sourceUrl) {
        this.project = EntitySupport.required(project, "project");
        this.repository = repository;
        this.actorAccount = actorAccount;
        this.issue = issue;
        this.pullRequest = pullRequest;
        this.buildRun = buildRun;
        this.deployment = deployment;
        this.patchNote = patchNote;
        this.projectId = EntitySupport.required(project.getId(), "persisted project.id");
        this.repositoryId = repository == null ? null : EntitySupport.required(repository.getId(), "persisted repository.id");
        this.actorAccountId = actorAccount == null ? null : EntitySupport.required(actorAccount.getId(), "persisted actorAccount.id");
        this.issueId = issue == null ? null : EntitySupport.required(issue.getId(), "persisted issue.id");
        this.pullRequestId = pullRequest == null ? null : EntitySupport.required(pullRequest.getId(), "persisted pullRequest.id");
        this.buildRunId = buildRun == null ? null : EntitySupport.required(buildRun.getId(), "persisted buildRun.id");
        this.deploymentId = deployment == null ? null : EntitySupport.required(deployment.getId(), "persisted deployment.id");
        this.patchNoteId = patchNote == null ? null : EntitySupport.required(patchNote.getId(), "persisted patchNote.id");
        if (repository != null) EntitySupport.same(this.projectId, repository.getProjectId(), "project_id");
        if (patchNote != null) EntitySupport.same(this.projectId, patchNote.getProjectId(), "project_id");
        if (issue != null) EntitySupport.same(this.repositoryId, issue.getRepositoryId(), "repository_id");
        if (pullRequest != null) EntitySupport.same(this.repositoryId, pullRequest.getRepositoryId(), "repository_id");
        if (buildRun != null) EntitySupport.same(this.repositoryId, buildRun.getRepositoryId(), "repository_id");
        if (deployment != null) EntitySupport.same(this.repositoryId, deployment.getRepositoryId(), "repository_id");
        this.eventKey = EntitySupport.text(eventKey, "eventKey");
        this.eventType = EntitySupport.text(eventType, "eventType");
        this.title = EntitySupport.text(title, "title");
        this.summary = summary;
        this.occurredAt = EntitySupport.required(occurredAt, "occurredAt");
        this.commitSha = commitSha;
        this.branchName = branchName;
        this.sourceUrl = sourceUrl;
    }

}
