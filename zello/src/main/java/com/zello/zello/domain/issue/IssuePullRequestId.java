package com.zello.zello.domain.issue;

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
public class IssuePullRequestId implements Serializable {
    private static final long serialVersionUID = 1L;

    @Column(name = "issue_id", nullable = false)
    private Long issueId;

    @Column(name = "pull_request_id", nullable = false)
    private Long pullRequestId;

}
