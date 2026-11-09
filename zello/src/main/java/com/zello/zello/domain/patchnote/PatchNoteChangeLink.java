package com.zello.zello.domain.patchnote;

import com.zello.zello.domain.common.IdentityEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A manual update keeps its own wording without moving the weekly source record. */
@Getter
@Entity
@Table(name = "patch_note_change_links", uniqueConstraints = @UniqueConstraint(name = "uk_note_change_link", columnNames = {"patch_note_id", "work_change_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PatchNoteChangeLink extends IdentityEntity {
    @Column(name = "project_id", nullable = false) private Long projectId;
    @Column(name = "patch_note_id", nullable = false) private Long patchNoteId;
    @Column(name = "work_change_id", nullable = false) private Long workChangeId;
    @Column(nullable = false, length = 255) private String title;
    @Column(columnDefinition = "text") private String summary;
    @Column(nullable = false, length = 20) private String category;
    @Column(name = "title_written", nullable = false) private boolean titleWritten;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns(value = {@JoinColumn(name = "patch_note_id", referencedColumnName = "id", insertable = false, updatable = false), @JoinColumn(name = "project_id", referencedColumnName = "project_id", insertable = false, updatable = false)}, foreignKey = @ForeignKey(name = "fk_note_change_link_note"))
    private PatchNote patchNote;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns(value = {@JoinColumn(name = "work_change_id", referencedColumnName = "id", insertable = false, updatable = false), @JoinColumn(name = "project_id", referencedColumnName = "project_id", insertable = false, updatable = false)}, foreignKey = @ForeignKey(name = "fk_note_change_link_source"))
    private WorkChange workChange;

    public PatchNoteChangeLink(PatchNote note, WorkChange source) {
        projectId = note.getProjectId(); patchNoteId = note.getId(); workChangeId = source.getId();
        patchNote = note; workChange = source;
        title = source.getTitle(); summary = source.getSummary(); category = source.getCategory(); titleWritten = source.isTitleWritten();
    }
    public void writeTitle(String value) { title = value.strip(); titleWritten = true; }

    public String getStatus() {
        // Consolidation deduplicates automatic weekly notes. A manual link still
        // refers to the same merged PR, even after that PR is linked to an issue.
        if ("CONSOLIDATED".equals(workChange.getStatus()) && workChange.getPullRequest() != null
                && workChange.getPullRequest().getState() == com.zello.zello.domain.pullrequest.PullRequest.State.MERGED)
            return "MERGED";
        return workChange.getStatus();
    }
}
