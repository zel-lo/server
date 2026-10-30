package com.zello.zello.domain.user;

import com.zello.zello.domain.common.*;
import com.zello.zello.domain.github.GitHubAccount;
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
@Table(name = "users",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_users_github_account", columnNames = {"github_account_id"})
        },
        check = {
                @CheckConstraint(name = "ck_users_status", constraint = "status IN ('ACTIVE', 'WITHDRAWN')"),
                @CheckConstraint(name = "ck_users_withdrawal", constraint = "(status = 'ACTIVE' AND withdrawn_at IS NULL) OR (status = 'WITHDRAWN' AND withdrawn_at IS NOT NULL)")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends IdentityEntity {
    @Column(name = "github_account_id", nullable = false)
    private Long githubAccountId;

    @Column(name = "email", length = 320)
    private String email;

    @Column(name = "theme", nullable = false, length = 20)
    private String theme;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "withdrawn_at", columnDefinition = "datetime(6)")
    private LocalDateTime withdrawnAt;

    @Column(name = "last_login_at", columnDefinition = "datetime(6)")
    private LocalDateTime lastLoginAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "github_account_id", referencedColumnName = "id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_users_github_account"))
    private GitHubAccount githubAccount;

    @Builder
    private User(
            GitHubAccount githubAccount,
            String email,
            String theme,
            Status status,
            LocalDateTime withdrawnAt,
            LocalDateTime lastLoginAt) {
        this.githubAccount = EntitySupport.required(githubAccount, "githubAccount");
        this.githubAccountId = EntitySupport.required(githubAccount.getId(), "persisted githubAccount.id");
        this.email = email;
        if (githubAccount.getAccountType() != GitHubAccount.AccountType.USER) throw new IllegalArgumentException("Login requires a GitHub user account");
        this.theme = Objects.requireNonNullElseGet(theme, () -> "light");
        this.status = Objects.requireNonNullElseGet(status, () -> Status.ACTIVE);
        this.withdrawnAt = withdrawnAt;
        this.lastLoginAt = lastLoginAt;
    }

    public void withdraw(LocalDateTime withdrawnAt) {
        this.withdrawnAt = EntitySupport.required(withdrawnAt, "withdrawnAt");
        this.status = Status.WITHDRAWN;
    }

    public void recordLogin(String email, LocalDateTime loggedInAt) {
        if (status != Status.ACTIVE) {
            throw new IllegalStateException("Inactive user cannot log in");
        }
        this.email = email;
        this.lastLoginAt = EntitySupport.required(loggedInAt, "loggedInAt");
    }

    public void changeTheme(String theme) {
        if (!java.util.Set.of("light", "dark").contains(theme)) {
            throw new IllegalArgumentException("Unknown theme");
        }
        this.theme = theme;
    }

    public enum Status {
        ACTIVE, WITHDRAWN
    }

}
