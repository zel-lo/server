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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(name = "project_members",
        indexes = {
                @Index(name = "idx_project_members_user_active", columnList = "user_id, left_at, project_id")
        },
        check = {
                @CheckConstraint(name = "ck_project_members_role", constraint = "role IN ('ADMIN', 'MEMBER', 'VIEWER')")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectMember {
    @EmbeddedId
    private ProjectMemberId id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    @Column(name = "job_title", length = 100)
    private String jobTitle;

    @Column(name = "joined_at", nullable = false, columnDefinition = "datetime(6)")
    private LocalDateTime joinedAt;

    @Column(name = "left_at", columnDefinition = "datetime(6)")
    private LocalDateTime leftAt;

    @Column(name = "notify_team_updates", nullable = false)
    private Boolean notifyTeamUpdates;

    @Column(name = "notify_deployments", nullable = false)
    private Boolean notifyDeployments;

    @Column(name = "weekly_summary_enabled", nullable = false)
    private Boolean weeklySummaryEnabled;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", referencedColumnName = "id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_project_members_project"))
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", referencedColumnName = "id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_project_members_user"))
    private User user;

    @Builder
    private ProjectMember(
            Project project,
            User user,
            Role role,
            String jobTitle,
            LocalDateTime joinedAt,
            LocalDateTime leftAt,
            Boolean notifyTeamUpdates,
            Boolean notifyDeployments,
            Boolean weeklySummaryEnabled) {
        this.project = EntitySupport.required(project, "project");
        this.user = EntitySupport.required(user, "user");
        this.id = new ProjectMemberId(EntitySupport.required(project.getId(), "persisted project.id"), EntitySupport.required(user.getId(), "persisted user.id"));
        this.role = Objects.requireNonNullElseGet(role, () -> Role.MEMBER);
        this.jobTitle = jobTitle;
        this.joinedAt = Objects.requireNonNullElseGet(joinedAt, () -> EntitySupport.now());
        this.leftAt = leftAt;
        this.notifyTeamUpdates = Objects.requireNonNullElseGet(notifyTeamUpdates, () -> true);
        this.notifyDeployments = Objects.requireNonNullElseGet(notifyDeployments, () -> true);
        this.weeklySummaryEnabled = Objects.requireNonNullElseGet(weeklySummaryEnabled, () -> true);
    }

    public void leave(LocalDateTime leftAt) {
        this.leftAt = EntitySupport.required(leftAt, "leftAt");
    }

    public void changeRole(Role role) {
        this.role = EntitySupport.required(role, "role");
    }

    public enum Role {
        ADMIN, MEMBER, VIEWER
    }

}
