package com.zello.zello.domain.issue;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IssuePullRequestRepository extends JpaRepository<IssuePullRequest, IssuePullRequestId> {
    @EntityGraph(attributePaths = {"issue", "pullRequest"})
    List<IssuePullRequest> findByIdIssueIdIn(Collection<Long> ids);
    @EntityGraph(attributePaths = {"issue", "pullRequest"})
    List<IssuePullRequest> findByIdPullRequestIdIn(Collection<Long> ids);
}
