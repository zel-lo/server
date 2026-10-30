package com.zello.zello.domain.deployment;

import com.zello.zello.domain.common.*;
import com.zello.zello.domain.repository.GitRepository;
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
@Table(name = "deployments",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_deployments_scope", columnNames = {"id", "repository_id"}),
                @UniqueConstraint(name = "uk_deployments_repository_github", columnNames = {"repository_id", "github_deployment_id"})
        },
        indexes = {
                @Index(name = "idx_deployments_repository_environment_date", columnList = "repository_id, environment, deployed_at")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Deployment extends IdentityEntity {
    @Column(name = "repository_id", nullable = false)
    private Long repositoryId;

    @Column(name = "github_deployment_id", nullable = false)
    private Long githubDeploymentId;

    @Column(name = "environment", nullable = false, length = 100)
    private String environment;

    @Column(name = "commit_sha", nullable = false, length = 64)
    private String commitSha;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "environment_url", length = 2048)
    private String environmentUrl;

    @Column(name = "log_url", length = 2048)
    private String logUrl;

    @Column(name = "deployed_at", columnDefinition = "datetime(6)")
    private LocalDateTime deployedAt;

    @Column(name = "github_status_id")
    private Long githubStatusId;

    @Column(name = "github_status_at", columnDefinition = "datetime(6)")
    private LocalDateTime githubStatusAt;

    @Column(name = "last_fetched_at", columnDefinition = "datetime(6)")
    private LocalDateTime lastFetchedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", referencedColumnName = "id", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_deployments_repository"))
    private GitRepository repository;

    @Builder
    private Deployment(
            GitRepository repository,
            Long githubDeploymentId,
            String environment,
            String commitSha,
            String status,
            String environmentUrl,
            String logUrl,
            LocalDateTime deployedAt,
            Long githubStatusId,
            LocalDateTime githubStatusAt,
            LocalDateTime lastFetchedAt) {
        this.repository = EntitySupport.required(repository, "repository");
        this.repositoryId = EntitySupport.required(repository.getId(), "persisted repository.id");
        this.githubDeploymentId = EntitySupport.required(githubDeploymentId, "githubDeploymentId");
        this.environment = EntitySupport.text(environment, "environment");
        this.commitSha = EntitySupport.text(commitSha, "commitSha");
        this.status = Objects.requireNonNullElseGet(status, () -> "pending");
        this.environmentUrl = environmentUrl;
        this.logUrl = logUrl;
        this.deployedAt = deployedAt;
        this.githubStatusId = githubStatusId;
        this.githubStatusAt = githubStatusAt;
        this.lastFetchedAt = lastFetchedAt;
    }

}
