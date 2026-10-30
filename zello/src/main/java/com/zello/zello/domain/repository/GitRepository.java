package com.zello.zello.domain.repository;

import com.zello.zello.domain.common.*;
import com.zello.zello.domain.github.GitHubInstallation;
import com.zello.zello.domain.project.Project;
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
@Table(name = "repositories",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_repositories_scope", columnNames = {"id", "project_id"}),
                @UniqueConstraint(name = "uk_repositories_project_github", columnNames = {"project_id", "github_repo_id"})
        },
        check = {
                @CheckConstraint(name = "ck_repositories_connection_status", constraint = "connection_status IN ('PENDING', 'SYNCING', 'CONNECTED', 'PARTIAL_FAILURE', 'AUTH_REQUIRED', 'DISCONNECTED')")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GitRepository extends IdentityEntity {
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "installation_id", nullable = false)
    private Long installationId;

    @Column(name = "github_repo_id", nullable = false)
    private Long githubRepoId;

    @Column(name = "owner_login", nullable = false, length = 100)
    private String ownerLogin;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "html_url", nullable = false, length = 2048)
    private String htmlUrl;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "primary_language", length = 100)
    private String primaryLanguage;

    @Column(name = "default_branch", nullable = false, length = 255)
    private String defaultBranch;

    @Column(name = "is_private", nullable = false)
    private Boolean privateRepository;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "connection_status", nullable = false, length = 30)
    private ConnectionStatus connectionStatus;

    @Column(name = "initial_sync_completed_at", columnDefinition = "datetime(6)")
    private LocalDateTime initialSyncCompletedAt;

    @Column(name = "last_synced_at", columnDefinition = "datetime(6)")
    private LocalDateTime lastSyncedAt;

    @Column(name = "last_sync_error", columnDefinition = "text")
    private String lastSyncError;

    @Column(name = "disconnected_at", columnDefinition = "datetime(6)")
    private LocalDateTime disconnectedAt;

    @Column(name = "github_updated_at", columnDefinition = "datetime(6)")
    private LocalDateTime githubUpdatedAt;

    @Column(name = "last_fetched_at", columnDefinition = "datetime(6)")
    private LocalDateTime lastFetchedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", referencedColumnName = "id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_repositories_project"))
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "installation_id", referencedColumnName = "id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_repositories_installation"))
    private GitHubInstallation installation;

    @Builder
    private GitRepository(
            Project project,
            GitHubInstallation installation,
            Long githubRepoId,
            String ownerLogin,
            String name,
            String htmlUrl,
            String description,
            String primaryLanguage,
            String defaultBranch,
            Boolean privateRepository,
            ConnectionStatus connectionStatus,
            LocalDateTime initialSyncCompletedAt,
            LocalDateTime lastSyncedAt,
            String lastSyncError,
            LocalDateTime disconnectedAt,
            LocalDateTime githubUpdatedAt,
            LocalDateTime lastFetchedAt) {
        this.project = EntitySupport.required(project, "project");
        this.installation = EntitySupport.required(installation, "installation");
        this.projectId = EntitySupport.required(project.getId(), "persisted project.id");
        this.installationId = EntitySupport.required(installation.getId(), "persisted installation.id");
        this.githubRepoId = EntitySupport.required(githubRepoId, "githubRepoId");
        this.ownerLogin = EntitySupport.text(ownerLogin, "ownerLogin");
        this.name = EntitySupport.text(name, "name");
        this.htmlUrl = EntitySupport.text(htmlUrl, "htmlUrl");
        this.description = description;
        this.primaryLanguage = primaryLanguage;
        this.defaultBranch = EntitySupport.text(defaultBranch, "defaultBranch");
        this.privateRepository = EntitySupport.required(privateRepository, "privateRepository");
        this.connectionStatus = Objects.requireNonNullElseGet(connectionStatus, () -> ConnectionStatus.PENDING);
        this.initialSyncCompletedAt = initialSyncCompletedAt;
        this.lastSyncedAt = lastSyncedAt;
        this.lastSyncError = lastSyncError;
        this.disconnectedAt = disconnectedAt;
        this.githubUpdatedAt = githubUpdatedAt;
        this.lastFetchedAt = lastFetchedAt;
    }

    public void disconnect(LocalDateTime disconnectedAt) {
        this.disconnectedAt = EntitySupport.required(disconnectedAt, "disconnectedAt");
        this.connectionStatus = ConnectionStatus.DISCONNECTED;
    }

    public enum ConnectionStatus {
        PENDING, SYNCING, CONNECTED, PARTIAL_FAILURE, AUTH_REQUIRED, DISCONNECTED
    }

}
