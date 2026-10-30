package com.zello.zello.domain.github;

import com.zello.zello.domain.common.*;
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
@Table(name = "github_installations",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_github_installations_github_id", columnNames = {"github_installation_id"})
        },
        check = {
                @CheckConstraint(name = "ck_github_installations_status", constraint = "status IN ('ACTIVE', 'SUSPENDED', 'REMOVED')")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GitHubInstallation extends IdentityEntity {
    @Column(name = "github_installation_id", nullable = false)
    private Long githubInstallationId;

    @Column(name = "owner_account_id", nullable = false)
    private Long ownerAccountId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "permissions", nullable = false, columnDefinition = "json")
    private Map<String, Object> permissions;

    @Column(name = "installed_at", nullable = false, columnDefinition = "datetime(6)")
    private LocalDateTime installedAt;

    @Column(name = "suspended_at", columnDefinition = "datetime(6)")
    private LocalDateTime suspendedAt;

    @Column(name = "removed_at", columnDefinition = "datetime(6)")
    private LocalDateTime removedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_account_id", referencedColumnName = "id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_github_installations_owner"))
    private GitHubAccount ownerAccount;

    @Builder
    private GitHubInstallation(
            GitHubAccount ownerAccount,
            Long githubInstallationId,
            Status status,
            Map<String, Object> permissions,
            LocalDateTime installedAt,
            LocalDateTime suspendedAt,
            LocalDateTime removedAt) {
        this.ownerAccount = EntitySupport.required(ownerAccount, "ownerAccount");
        this.ownerAccountId = EntitySupport.required(ownerAccount.getId(), "persisted ownerAccount.id");
        this.githubInstallationId = EntitySupport.required(githubInstallationId, "githubInstallationId");
        this.status = Objects.requireNonNullElseGet(status, () -> Status.ACTIVE);
        this.permissions = (Objects.requireNonNullElseGet(permissions, () -> Map.of()) == null ? null : new java.util.LinkedHashMap<>(Objects.requireNonNullElseGet(permissions, () -> Map.of())));
        this.installedAt = Objects.requireNonNullElseGet(installedAt, () -> EntitySupport.now());
        this.suspendedAt = suspendedAt;
        this.removedAt = removedAt;
    }

    public enum Status {
        ACTIVE, SUSPENDED, REMOVED
    }

}
