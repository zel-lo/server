package com.zello.zello.domain.github;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GitHubInstallationRepository extends JpaRepository<GitHubInstallation, Long> {
    Optional<GitHubInstallation> findByGithubInstallationId(Long githubInstallationId);
}
