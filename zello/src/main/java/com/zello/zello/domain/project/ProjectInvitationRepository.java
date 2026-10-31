package com.zello.zello.domain.project;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProjectInvitationRepository extends JpaRepository<ProjectInvitation, Long> {
    Optional<ProjectInvitation> findByTokenHash(String tokenHash);
    Optional<ProjectInvitation> findByIdAndProjectId(Long id, Long projectId);

    @EntityGraph(attributePaths = {"invitedByUser", "invitedByUser.githubAccount", "targetAccount"})
    Page<ProjectInvitation> findByProjectIdOrderByCreatedAtDescIdDesc(Long projectId, Pageable pageable);

    @Query("select i from ProjectInvitation i where i.projectId=:projectId and i.acceptedAt is null and i.revokedAt is null and i.expiresAt>:now")
    List<ProjectInvitation> pending(Long projectId, LocalDateTime now);
}
