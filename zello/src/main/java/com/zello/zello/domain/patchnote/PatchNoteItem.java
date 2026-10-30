package com.zello.zello.domain.patchnote;

import com.zello.zello.domain.common.*;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(name = "patch_note_items",
        check = {
                @CheckConstraint(name = "ck_patch_note_items_category", constraint = "category IN ('FEATURE', 'FIX', 'IMPROVE')")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PatchNoteItem extends CreatedAtEntity {
    @EmbeddedId
    private PatchNoteItemId id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "repository_id", nullable = false)
    private Long repositoryId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "category", nullable = false, length = 20)
    private Category category;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns(value = {
            @JoinColumn(name = "patch_note_id", referencedColumnName = "id", insertable = false, updatable = false),
            @JoinColumn(name = "project_id", referencedColumnName = "project_id", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_patch_note_items_note"))
    private PatchNote patchNote;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns(value = {
            @JoinColumn(name = "pull_request_id", referencedColumnName = "id", insertable = false, updatable = false),
            @JoinColumn(name = "repository_id", referencedColumnName = "repository_id", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_patch_note_items_pr"))
    private PullRequest pullRequest;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns(value = {
            @JoinColumn(name = "repository_id", referencedColumnName = "id", insertable = false, updatable = false),
            @JoinColumn(name = "project_id", referencedColumnName = "project_id", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_patch_note_items_repository_scope"))
    private GitRepository repository;

    @Builder
    private PatchNoteItem(
            PatchNote patchNote,
            PullRequest pullRequest,
            GitRepository repository,
            Category category,
            String title,
            String summary,
            Integer sortOrder) {
        this.patchNote = EntitySupport.required(patchNote, "patchNote");
        this.pullRequest = EntitySupport.required(pullRequest, "pullRequest");
        this.repository = EntitySupport.required(repository, "repository");
        this.projectId = EntitySupport.required(patchNote.getProjectId(), "persisted patchNote.project_id");
        this.repositoryId = EntitySupport.required(pullRequest.getRepositoryId(), "persisted pullRequest.repository_id");
        this.id = new PatchNoteItemId(EntitySupport.required(patchNote.getId(), "persisted patchNote.id"), EntitySupport.required(pullRequest.getId(), "persisted pullRequest.id"));
        EntitySupport.same(this.repositoryId, repository.getId(), "repository_id");
        EntitySupport.same(this.projectId, repository.getProjectId(), "project_id");
        this.category = EntitySupport.required(category, "category");
        this.title = EntitySupport.text(title, "title");
        this.summary = summary;
        this.sortOrder = Objects.requireNonNullElseGet(sortOrder, () -> 0);
    }

    public enum Category {
        FEATURE, FIX, IMPROVE
    }

}
