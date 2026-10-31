package com.zello.zello.domain.pullrequest;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** 목록·상세·집계는 GitHub에 발행이 끝난(PUBLISHED) PR만 대상으로 한다. */
public interface PullRequestRepository extends JpaRepository<PullRequest, Long> {
    List<PullRequest> findByRepositoryId(Long repositoryId);

    Optional<PullRequest> findByPublishRequestKey(String publishRequestKey);

    @EntityGraph(attributePaths = {"repository", "authorAccount"})
    @Query("""
            select p from PullRequest p
            where p.repositoryId in :repositoryIds and p.repository.projectId = :projectId
              and p.publishStatus = com.zello.zello.domain.common.PublishStatus.PUBLISHED
              and p.state in :states and (:authorAccountId is null or p.authorAccountId = :authorAccountId)
              and (:reviewerAccountId is null or (p.draft = false and exists (
                    select 1 from PullRequestReviewer r where r.id.pullRequestId = p.id
                      and r.id.reviewerAccountId = :reviewerAccountId and r.requestStatus = :requested)))
            order by p.githubUpdatedAt desc, p.id desc
            """)
    Page<PullRequest> search(Long projectId, Collection<Long> repositoryIds, Collection<PullRequest.State> states,
                             Long authorAccountId, Long reviewerAccountId, PullRequestReviewer.RequestStatus requested, Pageable pageable);

    @EntityGraph(attributePaths = {"repository", "authorAccount"})
    @Query("""
            select p from PullRequest p
            where p.id = :id and p.repository.projectId = :projectId
              and p.publishStatus = com.zello.zello.domain.common.PublishStatus.PUBLISHED
            """)
    Optional<PullRequest> findDetail(Long id, Long projectId);

    @Query("""
            select p.state, count(p) from PullRequest p
            where p.repositoryId in :repositoryIds and p.repository.projectId = :projectId
              and p.publishStatus = com.zello.zello.domain.common.PublishStatus.PUBLISHED
              and (:authorAccountId is null or p.authorAccountId = :authorAccountId)
            group by p.state
            """)
    List<Object[]> countByState(Long projectId, Collection<Long> repositoryIds, Long authorAccountId);
}
