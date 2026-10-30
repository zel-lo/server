package com.zello.zello.domain.notification;

import com.zello.zello.domain.activity.Activity;
import com.zello.zello.domain.common.*;
import com.zello.zello.domain.project.ProjectMember;
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
@Table(name = "notifications",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_notifications_recipient_activity", columnNames = {"recipient_user_id", "activity_id"})
        },
        indexes = {
                @Index(name = "idx_notifications_recipient_read_created", columnList = "recipient_user_id, read_at, created_at, id"),
                @Index(name = "idx_notifications_project_recipient_read", columnList = "project_id, recipient_user_id, read_at, created_at, id"),
                @Index(name = "idx_notifications_recipient_created", columnList = "recipient_user_id, created_at, id")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends CreatedAtEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "recipient_user_id", nullable = false)
    private Long recipientUserId;

    @Column(name = "activity_id", nullable = false)
    private Long activityId;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "body", columnDefinition = "text")
    private String body;

    @Column(name = "read_at", columnDefinition = "datetime(6)")
    private LocalDateTime readAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns(value = {
            @JoinColumn(name = "project_id", referencedColumnName = "project_id", insertable = false, updatable = false),
            @JoinColumn(name = "recipient_user_id", referencedColumnName = "user_id", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_notifications_recipient"))
    private ProjectMember recipientMember;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns(value = {
            @JoinColumn(name = "activity_id", referencedColumnName = "id", insertable = false, updatable = false),
            @JoinColumn(name = "project_id", referencedColumnName = "project_id", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_notifications_activity"))
    private Activity activity;

    @Builder
    private Notification(
            ProjectMember recipientMember,
            Activity activity,
            String title,
            String body,
            LocalDateTime readAt) {
        this.recipientMember = EntitySupport.required(recipientMember, "recipientMember");
        this.activity = EntitySupport.required(activity, "activity");
        this.projectId = EntitySupport.required(recipientMember.getId().getProjectId(), "persisted recipientMember.project_id");
        this.recipientUserId = EntitySupport.required(recipientMember.getId().getUserId(), "persisted recipientMember.user_id");
        this.activityId = EntitySupport.required(activity.getId(), "persisted activity.id");
        EntitySupport.same(this.projectId, activity.getProjectId(), "project_id");
        this.title = EntitySupport.text(title, "title");
        this.body = body;
        this.readAt = readAt;
    }

    public void markRead(LocalDateTime readAt) {
        if (this.readAt == null) this.readAt = EntitySupport.required(readAt, "readAt");
    }

    public void markUnread() {
        this.readAt = null;
    }

}
