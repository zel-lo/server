package com.zello.zello.domain.patchnote;

import com.zello.zello.domain.common.*;
import com.zello.zello.domain.project.Project;
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
@Table(name = "patch_notes",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_patch_notes_scope", columnNames = {"id", "project_id"})
        },
        indexes = {
                @Index(name = "idx_patch_notes_project_status_published", columnList = "project_id, status, published_at")
        },
        check = {
                @CheckConstraint(name = "ck_patch_notes_status", constraint = "status IN ('DRAFT', 'PUBLISHED')"),
                @CheckConstraint(name = "ck_patch_notes_publication", constraint = "(status = 'DRAFT' AND published_at IS NULL) OR (status = 'PUBLISHED' AND published_at IS NOT NULL)"),
                @CheckConstraint(name = "ck_patch_notes_version", constraint = "version >= 0")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PatchNote extends VersionedEntity {
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "author_user_id", nullable = false)
    private Long authorUserId;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "subtitle", length = 255)
    private String subtitle;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @Column(name = "version_label", length = 100)
    private String versionLabel;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "published_at", columnDefinition = "datetime(6)")
    private LocalDateTime publishedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", referencedColumnName = "id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_patch_notes_project"))
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_user_id", referencedColumnName = "id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_patch_notes_author"))
    private User authorUser;

    @Builder
    private PatchNote(
            Project project,
            User authorUser,
            String title,
            String subtitle,
            String summary,
            String versionLabel,
            Status status,
            LocalDateTime publishedAt) {
        this.project = EntitySupport.required(project, "project");
        this.authorUser = EntitySupport.required(authorUser, "authorUser");
        this.projectId = EntitySupport.required(project.getId(), "persisted project.id");
        this.authorUserId = EntitySupport.required(authorUser.getId(), "persisted authorUser.id");
        this.title = EntitySupport.text(title, "title");
        this.subtitle = subtitle;
        this.summary = summary;
        this.versionLabel = versionLabel;
        this.status = Objects.requireNonNullElseGet(status, () -> Status.DRAFT);
        this.publishedAt = publishedAt;
    }

    public void edit(String title, String summary) {
        this.title = EntitySupport.text(title, "title");
        this.summary = summary;
    }

    public void publish(LocalDateTime publishedAt) {
        this.publishedAt = EntitySupport.required(publishedAt, "publishedAt");
        this.status = Status.PUBLISHED;
    }

    public enum Status {
        DRAFT, PUBLISHED
    }

}
