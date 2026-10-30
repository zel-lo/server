package com.zello.zello.domain.project;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, ProjectMemberId> {
    @EntityGraph(attributePaths = "project")
    List<ProjectMember> findByIdUserIdAndLeftAtIsNullOrderByJoinedAtAsc(Long userId);

    Optional<ProjectMember> findByIdAndLeftAtIsNull(ProjectMemberId id);
}
