package com.zello.zello.domain.issue;

import java.util.*;
import java.time.*;
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
        select i from Issue i left join i.assigneeAccount a where i.repository.projectId=:projectId and i.repositoryId in :repositories

        and ((:archived=true and i.archivedAt is not null) or (:archived=false and i.archivedAt is null))
        and (:activeOnly=false or i.workStatus<>com.zello.zello.domain.issue.Issue.WorkStatus.DONE)
        and (:status is null or i.workStatus=:status) and (:assignee is null or i.assigneeAccountId=:assignee)
        and (:query='' or lower(i.title) like concat('%',lower(:query),'%') or lower(i.issueKey) like concat('%',lower(:query),'%'))
        and (:repositoryFilter is null or i.repositoryId=:repositoryFilter)
        and (:unassigned=false or i.assigneeAccountId is null)
        and (:assigneeFilter='' or lower(a.login)=lower(:assigneeFilter))
        and (:week is null or i.workStatus=com.zello.zello.domain.issue.Issue.WorkStatus.BACKLOG
            or (i.workStatus<>com.zello.zello.domain.issue.Issue.WorkStatus.DONE and i.plannedWeekStart<=:week)
            or (i.workStatus=com.zello.zello.domain.issue.Issue.WorkStatus.DONE and i.completedAt>=:weekAt and (:weekEnd is null or i.completedAt<:weekEnd)))
        order by i.boardOrder asc, i.id desc
        """)
    Page<Issue> search(Long projectId, Collection<Long> repositories, boolean archived, boolean activeOnly, Issue.WorkStatus status,
                       Long assignee, String query, Long repositoryFilter, boolean unassigned, String assigneeFilter, LocalDate week, LocalDateTime weekAt, LocalDateTime weekEnd, Pageable pageable);

    @Query("""
        select i from Issue i where i.repository.projectId=:projectId and i.workStatus=:status
        and i.archivedAt is null
        and i.repository.connectionStatus<>com.zello.zello.domain.repository.GitRepository.ConnectionStatus.DISCONNECTED
        order by i.boardOrder asc, i.id desc
        """)
    List<Issue> boardColumn(Long projectId, Issue.WorkStatus status);
}
