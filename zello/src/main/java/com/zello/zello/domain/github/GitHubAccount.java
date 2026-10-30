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
@Table(name = "github_accounts",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_github_accounts_github_id", columnNames = {"github_id"})
        },
        check = {
                @CheckConstraint(name = "ck_github_accounts_type", constraint = "account_type IN ('USER', 'BOT', 'ORGANIZATION')")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GitHubAccount extends IdentityEntity {
    @Column(name = "github_id", nullable = false)
    private Long githubId;

    @Column(name = "login", nullable = false, length = 100)
    private String login;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "avatar_url", length = 2048)
    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "account_type", nullable = false, length = 20)
    private AccountType accountType;

    @Builder
    private GitHubAccount(
            Long githubId,
            String login,
            String displayName,
            String avatarUrl,
            AccountType accountType) {
        this.githubId = EntitySupport.required(githubId, "githubId");
        this.login = EntitySupport.text(login, "login");
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
        this.accountType = EntitySupport.required(accountType, "accountType");
    }

    public void updateProfile(String login, String displayName, String avatarUrl) {
        this.login = EntitySupport.text(login, "login");
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
    }

    public enum AccountType {
        USER, BOT, ORGANIZATION
    }

}
