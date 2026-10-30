package com.zello.zello.domain.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GitRepositoryRepository extends JpaRepository<GitRepository, Long> {
    List<GitRepository> findByProjectIdOrderByIdAsc(Long projectId);
    Optional<GitRepository> findByProjectIdAndGithubRepoId(Long projectId, Long githubRepoId);
    Optional<GitRepository> findByIdAndProjectId(Long id, Long projectId);
}
