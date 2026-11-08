package com.zello.zello.domain.patchnote;

import com.zello.zello.domain.common.VersionedEntity;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One canonical change per issue, or per merged PR when no issue is linked. */
@Getter
@Entity
@Table(name = "work_changes", uniqueConstraints = @UniqueConstraint(name = "uk_work_changes_source", columnNames = {"project_id", "source_key"}),
        indexes = @Index(name = "idx_work_changes_note", columnList = "patch_note_id, id"), check = {
        @CheckConstraint(name = "ck_work_changes_target", constraint = "(issue_id IS NOT NULL AND pull_request_id IS NULL) OR (issue_id IS NULL AND pull_request_id IS NOT NULL)"),
        @CheckConstraint(name = "ck_work_changes_category", constraint = "category IN ('FEATURE', 'FIX', 'IMPROVE')"),
        @CheckConstraint(name = "ck_work_changes_status", constraint = "status IN ('COMPLETED', 'MERGED', 'REOPENED', 'ARCHIVED', 'CONSOLIDATED')")})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkChange extends VersionedEntity {
    @Column(name = "project_id", nullable = false) private Long projectId;
    @Column(name = "repository_id", nullable = false) private Long repositoryId;
    @Column(name = "source_key", nullable = false, length = 100) private String sourceKey;
    @Column(name = "issue_id") private Long issueId;
    @Column(name = "pull_request_id") private Long pullRequestId;
    @Column(name = "patch_note_id", nullable = false) private Long patchNoteId;
    @Column(nullable = false, length = 255) private String title;
    @Column(name = "title_written", nullable = false) private boolean titleWritten;
    @Column(columnDefinition = "text") private String summary;
    @Column(nullable = false, length = 20) private String category;
    @Column(nullable = false, length = 30) private String status;
    @Column(name = "occurred_at", nullable = false, columnDefinition = "datetime(6)") private LocalDateTime occurredAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_work_changes_project"))
    private com.zello.zello.domain.project.Project project;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns(value = {@JoinColumn(name = "repository_id", referencedColumnName = "id", insertable = false, updatable = false), @JoinColumn(name = "project_id", referencedColumnName = "project_id", insertable = false, updatable = false)}, foreignKey = @ForeignKey(name = "fk_work_changes_repository"))
    private com.zello.zello.domain.repository.GitRepository repository;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns(value = {@JoinColumn(name = "issue_id", referencedColumnName = "id", insertable = false, updatable = false), @JoinColumn(name = "repository_id", referencedColumnName = "repository_id", insertable = false, updatable = false)}, foreignKey = @ForeignKey(name = "fk_work_changes_issue"))
    private com.zello.zello.domain.issue.Issue issue;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns(value = {@JoinColumn(name = "pull_request_id", referencedColumnName = "id", insertable = false, updatable = false), @JoinColumn(name = "repository_id", referencedColumnName = "repository_id", insertable = false, updatable = false)}, foreignKey = @ForeignKey(name = "fk_work_changes_pull"))
    private com.zello.zello.domain.pullrequest.PullRequest pullRequest;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns(value = {@JoinColumn(name = "patch_note_id", referencedColumnName = "id", insertable = false, updatable = false), @JoinColumn(name = "project_id", referencedColumnName = "project_id", insertable = false, updatable = false)}, foreignKey = @ForeignKey(name = "fk_work_changes_note"))
    private PatchNote patchNote;

    public WorkChange(long project, long repository, String key, Long issue, Long pull, long note, LocalDateTime occurred) {
        projectId = project; repositoryId = repository; sourceKey = key; issueId = issue; pullRequestId = pull;
        patchNoteId = note; occurredAt = occurred;
    }
    public void update(String title, String summary, String category, String status) {
        this.title = title; this.summary = summary; this.category = category; this.status = status;
    }
    public void writeTitle(String title) {
        this.title = title.strip();
        this.titleWritten = true;
    }
}
