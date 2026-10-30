package com.zello.zello.domain.deployment;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Embeddable
@EqualsAndHashCode
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeploymentPullRequestId implements Serializable {
    private static final long serialVersionUID = 1L;

    @Column(name = "deployment_id", nullable = false)
    private Long deploymentId;

    @Column(name = "pull_request_id", nullable = false)
    private Long pullRequestId;

}
