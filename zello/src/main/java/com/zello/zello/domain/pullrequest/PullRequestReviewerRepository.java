package com.zello.zello.domain.pullrequest;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PullRequestReviewerRepository extends JpaRepository<PullRequestReviewer, PullRequestReviewerId> {
    List<PullRequestReviewer> findByIdPullRequestIdIn(Collection<Long> pullRequestIds);

    @EntityGraph(attributePaths = "reviewerAccount")
    List<PullRequestReviewer> findByIdPullRequestIdInAndRequestStatusIn(Collection<Long> pullRequestIds,
                                                                        Collection<PullRequestReviewer.RequestStatus> statuses);
}
