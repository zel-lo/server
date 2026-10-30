package com.zello.zello.domain.project;

import com.zello.zello.domain.common.*;
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

@Getter
@Entity
@Table(name = "projects",
        check = {
                @CheckConstraint(name = "ck_projects_sync_interval", constraint = "sync_interval_minutes > 0"),
                @CheckConstraint(name = "ck_projects_version", constraint = "version >= 0")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Project extends VersionedEntity {
    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "goal", columnDefinition = "text")
    private String goal;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone;

    @Column(name = "sync_interval_minutes", nullable = false)
    private Integer syncIntervalMinutes;

    @Column(name = "archived_at", columnDefinition = "datetime(6)")
    private LocalDateTime archivedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_user_id", referencedColumnName = "id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_projects_owner"))
    private User ownerUser;

    @Builder
    private Project(
            User ownerUser,
            String name,
            String goal,
            String description,
            String timezone,
            Integer syncIntervalMinutes,
            LocalDateTime archivedAt) {
        this.ownerUser = EntitySupport.required(ownerUser, "ownerUser");
        this.ownerUserId = EntitySupport.required(ownerUser.getId(), "persisted ownerUser.id");
        this.name = EntitySupport.text(name, "name");
        this.goal = goal;
        this.description = description;
        this.timezone = Objects.requireNonNullElseGet(timezone, () -> "Asia/Seoul");
        this.syncIntervalMinutes = Objects.requireNonNullElseGet(syncIntervalMinutes, () -> 15);
        this.archivedAt = archivedAt;
    }

    public void rename(String name) {
        this.name = EntitySupport.text(name, "name");
    }

    public void transferOwnership(User ownerUser) {
        this.ownerUser = EntitySupport.required(ownerUser, "ownerUser");
        this.ownerUserId = EntitySupport.required(ownerUser.getId(), "persisted ownerUser");
    }

}
