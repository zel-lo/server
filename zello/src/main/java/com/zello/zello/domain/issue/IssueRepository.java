package com.zello.zello.domain.issue;

import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface IssueRepository extends JpaRepository<Issue, Long> {
    List<Issue> findByRepositoryId(Long repositoryId);
    Optional<Issue> findByPublishRequestKey(String key);

    @EntityGraph(attributePaths = {"repository", "authorAccount", "assigneeAccount"})
    @Query("select i from Issue i where i.id=:id and i.repository.projectId=:projectId")
    Optional<Issue> findDetail(Long id, Long projectId);

    @EntityGraph(attributePaths = {"repository", "authorAccount", "assigneeAccount"})
    @Query("""
        select i from Issue i where i.repository.projectId=:projectId and i.repositoryId in :repositories

        and ((:archived=true and i.archivedAt is not null) or (:archived=false and i.archivedAt is null))
        and (:activeOnly=false or i.workStatus<>com.zello.zello.domain.issue.Issue.WorkStatus.DONE)
        and (:status is null or i.workStatus=:status) and (:assignee is null or i.assigneeAccountId=:assignee)
        and (:query='' or lower(i.title) like concat('%',lower(:query),'%') or lower(i.issueKey) like concat('%',lower(:query),'%'))
        order by i.id desc
        """)
    Page<Issue> search(Long projectId, Collection<Long> repositories, boolean archived, boolean activeOnly, Issue.WorkStatus status,
                       Long assignee, String query, Pageable pageable);
}
