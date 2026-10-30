package com.zello.zello.domain;

import com.zello.zello.domain.github.GitHubAccount;
import com.zello.zello.domain.user.User;
import com.zello.zello.domain.project.Project;
import com.zello.zello.domain.project.ProjectMember;
import com.zello.zello.domain.github.GitHubInstallation;
import com.zello.zello.domain.repository.GitRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class EntityPersistenceTests {
    private static final java.util.concurrent.atomic.AtomicLong IDS = new java.util.concurrent.atomic.AtomicLong(100);

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void persistenceContextStarts() {
        assertThat(entityManager.isOpen()).isTrue();
    }

    private <T> T persist(T entity) {
        entityManager.persist(entity);
        entityManager.flush();
        return entity;
    }
    @Test
    void persistsGitHubAccount() {
        GitHubAccount value = account();
        entityManager.clear();
        GitHubAccount loaded = entityManager.find(GitHubAccount.class, value.getId());
        assertThat(loaded.getGithubId()).isEqualTo(value.getGithubId());
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
    }

    private GitHubAccount account() {
        long value = IDS.incrementAndGet();
        return persist(GitHubAccount.builder().githubId(value).login("user" + value)
                .accountType(GitHubAccount.AccountType.USER).build());
    }

    @Test
    void persistsUser() {
        User value = user();
        value.withdraw(com.zello.zello.domain.common.EntitySupport.now());
        entityManager.flush();
        entityManager.clear();
        User loaded = entityManager.find(User.class, value.getId());
        assertThat(loaded.getStatus()).isEqualTo(User.Status.WITHDRAWN);
        assertThat(loaded.getWithdrawnAt()).isNotNull();
        assertThat(loaded.getGithubAccount().getId()).isEqualTo(value.getGithubAccountId());
    }

    private User user() {
        return persist(User.builder().githubAccount(account()).build());
    }

    @Test
    void persistsProject() {
        Project value = project();
        Long version = value.getVersion();
        value.rename("Renamed");
        entityManager.flush();
        assertThat(value.getVersion()).isGreaterThan(version);
        entityManager.clear();
        assertThat(entityManager.find(Project.class, value.getId()).getName()).isEqualTo("Renamed");
    }

    private Project project() {
        return persist(Project.builder().ownerUser(user()).name("Project").build());
    }

    @Test
    void persistsProjectMember() {
        ProjectMember value = member(project(), user());
        entityManager.clear();
        ProjectMember loaded = entityManager.find(ProjectMember.class, value.getId());
        assertThat(loaded.getProject().getId()).isEqualTo(value.getId().getProjectId());
        assertThat(loaded.getUser().getId()).isEqualTo(value.getId().getUserId());
    }

    private ProjectMember member(Project project, User user) {
        return persist(ProjectMember.builder().project(project).user(user).build());
    }

    @Test
    void persistsGitHubInstallation() {
        GitHubInstallation value = installation();
        entityManager.clear();
        assertThat(entityManager.find(GitHubInstallation.class, value.getId()).getPermissions()).containsEntry("issues", "write");
    }

    private GitHubInstallation installation() {
        return persist(GitHubInstallation.builder().ownerAccount(account())
                .githubInstallationId(IDS.incrementAndGet()).permissions(java.util.Map.of("issues", "write")).build());
    }

    @Test
    void persistsGitRepository() {
        GitRepository value = repository();
        value.disconnect(com.zello.zello.domain.common.EntitySupport.now());
        entityManager.flush();
        entityManager.clear();
        GitRepository loaded = entityManager.find(GitRepository.class, value.getId());
        assertThat(loaded.getProject().getId()).isEqualTo(value.getProjectId());
        assertThat(loaded.getConnectionStatus()).isEqualTo(GitRepository.ConnectionStatus.DISCONNECTED);
    }

    private GitRepository repository() {
        return repository(project());
    }

    private GitRepository repository(Project project) {
        return persist(GitRepository.builder().project(project).installation(installation())
                .githubRepoId(IDS.incrementAndGet()).ownerLogin("team").name("client")
                .htmlUrl("https://example.test/team/client").defaultBranch("main").privateRepository(true).build());
    }

}
