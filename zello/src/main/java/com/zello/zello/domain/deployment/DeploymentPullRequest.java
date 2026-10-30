package com.zello.zello.domain.deployment;

import com.zello.zello.domain.common.*;
import com.zello.zello.domain.pullrequest.PullRequest;
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
@Table(name = "deployment_pull_requests")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeploymentPullRequest {
    @EmbeddedId
    private DeploymentPullRequestId id;

    @Column(name = "repository_id", nullable = false)
    private Long repositoryId;

    @Column(name = "linked_at", nullable = false, columnDefinition = "datetime(6)")
    private LocalDateTime linkedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns(value = {
            @JoinColumn(name = "deployment_id", referencedColumnName = "id", insertable = false, updatable = false),
            @JoinColumn(name = "repository_id", referencedColumnName = "repository_id", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_deployment_pull_requests_deployment"))
    private Deployment deployment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns(value = {
            @JoinColumn(name = "pull_request_id", referencedColumnName = "id", insertable = false, updatable = false),
            @JoinColumn(name = "repository_id", referencedColumnName = "repository_id", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_deployment_pull_requests_pr"))
    private PullRequest pullRequest;

    @Builder
    private DeploymentPullRequest(
            Deployment deployment,
            PullRequest pullRequest,
            LocalDateTime linkedAt) {
        this.deployment = EntitySupport.required(deployment, "deployment");
        this.pullRequest = EntitySupport.required(pullRequest, "pullRequest");
        this.repositoryId = EntitySupport.required(deployment.getRepositoryId(), "persisted deployment.repository_id");
        this.id = new DeploymentPullRequestId(EntitySupport.required(deployment.getId(), "persisted deployment.id"), EntitySupport.required(pullRequest.getId(), "persisted pullRequest.id"));
        EntitySupport.same(this.repositoryId, pullRequest.getRepositoryId(), "repository_id");
        this.linkedAt = Objects.requireNonNullElseGet(linkedAt, () -> EntitySupport.now());
    }

}
