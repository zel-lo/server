package com.zello.zello.domain.project;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, ProjectMemberId> {
    @EntityGraph(attributePaths = "project")
    List<ProjectMember> findByIdUserIdAndLeftAtIsNullOrderByJoinedAtAsc(Long userId);

    @EntityGraph(attributePaths = {"user", "user.githubAccount"})
    @org.springframework.data.jpa.repository.Query("select m from ProjectMember m where m.id.projectId=:projectId and m.leftAt is null and m.user.status=com.zello.zello.domain.user.User.Status.ACTIVE")
    List<ProjectMember> active(Long projectId);

    @EntityGraph(attributePaths = {"user", "user.githubAccount"})
    @org.springframework.data.jpa.repository.Query("""
            select m from ProjectMember m where m.id.projectId=:projectId and m.leftAt is null
            and m.user.status=com.zello.zello.domain.user.User.Status.ACTIVE
            and (:query='' or lower(m.user.githubAccount.login) like concat('%',lower(:query),'%')
                or lower(m.user.githubAccount.displayName) like concat('%',lower(:query),'%'))
            order by m.joinedAt asc, m.id.userId asc
            """)
    org.springframework.data.domain.Page<ProjectMember> search(Long projectId, String query, org.springframework.data.domain.Pageable pageable);

    Optional<ProjectMember> findByIdAndLeftAtIsNull(ProjectMemberId id);
}
