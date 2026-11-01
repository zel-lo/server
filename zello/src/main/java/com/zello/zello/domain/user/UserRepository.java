package com.zello.zello.domain.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from User u where u.id=:id")
    Optional<User> lockForProjectCreation(Long id);

    @EntityGraph(attributePaths = "githubAccount")
    Optional<User> findByGithubAccountId(Long githubAccountId);

    @EntityGraph(attributePaths = "githubAccount")
    Optional<User> findWithGithubAccountById(Long id);

    boolean existsByIdAndStatus(Long id, User.Status status);
}
