package com.zello.zello.domain.project;

import com.zello.zello.domain.common.*;
import com.zello.zello.domain.github.GitHubAccount;
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
@Table(name = "project_invitations",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_project_invitations_token_hash", columnNames = {"token_hash"})
        },
        indexes = {
                @Index(name = "idx_project_invitations_created", columnList = "project_id, created_at, id"),
                @Index(name = "idx_project_invitations_pending", columnList = "project_id, invited_by_user_id, accepted_at, revoked_at, expires_at")
        },
        check = {
                @CheckConstraint(name = "ck_project_invitations_role", constraint = "granted_role IN ('ADMIN', 'MEMBER', 'VIEWER')"),
                @CheckConstraint(name = "ck_project_invitations_acceptance", constraint = "(accepted_at IS NULL AND accepted_by_user_id IS NULL) OR (accepted_at IS NOT NULL AND accepted_by_user_id IS NOT NULL)"),
                @CheckConstraint(name = "ck_project_invitations_terminal", constraint = "accepted_at IS NULL OR revoked_at IS NULL"),
                @CheckConstraint(name = "ck_project_invitations_expiry", constraint = "expires_at > created_at"),
                @CheckConstraint(name = "ck_project_invitations_accepted_at", constraint = "accepted_at IS NULL OR (accepted_at >= created_at AND accepted_at < expires_at)")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectInvitation extends IdentityEntity {
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "invited_by_user_id", nullable = false)
    private Long invitedByUserId;

    @Column(name = "target_account_id")
    private Long targetAccountId;

    @Column(name = "accepted_by_user_id")
    private Long acceptedByUserId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "granted_role", nullable = false, length = 20)
    private GrantedRole grantedRole;

    @Column(name = "expires_at", nullable = false, columnDefinition = "datetime(6)")
    private LocalDateTime expiresAt;

    @Column(name = "accepted_at", columnDefinition = "datetime(6)")
    private LocalDateTime acceptedAt;

    @Column(name = "revoked_at", columnDefinition = "datetime(6)")
    private LocalDateTime revokedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", referencedColumnName = "id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_project_invitations_project"))
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invited_by_user_id", referencedColumnName = "id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_project_invitations_inviter"))
    private User invitedByUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_account_id", referencedColumnName = "id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_project_invitations_target"))
    private GitHubAccount targetAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accepted_by_user_id", referencedColumnName = "id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_project_invitations_accepter"))
    private User acceptedByUser;

    @Builder
    private ProjectInvitation(
            Project project,
            User invitedByUser,
            GitHubAccount targetAccount,
            User acceptedByUser,
            String tokenHash,
            GrantedRole grantedRole,
            LocalDateTime expiresAt,
            LocalDateTime acceptedAt,
            LocalDateTime revokedAt) {
        this.project = EntitySupport.required(project, "project");
        this.invitedByUser = EntitySupport.required(invitedByUser, "invitedByUser");
        this.targetAccount = targetAccount;
        this.acceptedByUser = acceptedByUser;
        this.projectId = EntitySupport.required(project.getId(), "persisted project.id");
        this.invitedByUserId = EntitySupport.required(invitedByUser.getId(), "persisted invitedByUser.id");
        this.targetAccountId = targetAccount == null ? null : EntitySupport.required(targetAccount.getId(), "persisted targetAccount.id");
        this.acceptedByUserId = acceptedByUser == null ? null : EntitySupport.required(acceptedByUser.getId(), "persisted acceptedByUser.id");
        this.tokenHash = EntitySupport.text(tokenHash, "tokenHash");
        this.grantedRole = Objects.requireNonNullElseGet(grantedRole, () -> GrantedRole.MEMBER);
        this.expiresAt = EntitySupport.required(expiresAt, "expiresAt");
        this.acceptedAt = acceptedAt;
        this.revokedAt = revokedAt;
    }

    public void accept(User user, LocalDateTime acceptedAt) {
        EntitySupport.required(user, "user");
        EntitySupport.required(acceptedAt, "acceptedAt");
        if (this.acceptedAt != null || revokedAt != null || !acceptedAt.isBefore(expiresAt)) {
            throw new IllegalStateException("Invitation is no longer available");
        }
        if (targetAccountId != null) EntitySupport.same(targetAccountId, user.getGithubAccountId(), "invitation account");
        if (user.getStatus() != User.Status.ACTIVE) throw new IllegalStateException("Inactive user");
        this.acceptedByUser = user;
        this.acceptedByUserId = EntitySupport.required(user.getId(), "persisted user");
        this.acceptedAt = acceptedAt;
    }

    public void revoke(LocalDateTime now) {
        if (acceptedAt == null && revokedAt == null) this.revokedAt = EntitySupport.required(now, "now");
    }

    public enum GrantedRole {
        ADMIN, MEMBER, VIEWER
    }

}
