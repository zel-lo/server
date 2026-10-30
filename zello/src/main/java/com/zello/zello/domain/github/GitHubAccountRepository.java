package com.zello.zello.domain.github;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface GitHubAccountRepository extends JpaRepository<GitHubAccount, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<GitHubAccount> findByGithubId(Long githubId);
}
