package com.zello.zello.repository;

import com.zello.zello.api.ApiException;
import com.zello.zello.domain.common.EntitySupport;
import com.zello.zello.domain.github.*;
import com.zello.zello.domain.project.ProjectRepository;
import com.zello.zello.domain.repository.*;
import com.zello.zello.integration.github.GitHubGateway.VerifiedRepository;
import com.zello.zello.project.ProjectService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class RepositoryService {
    private final ProjectService projects;
    private final ProjectRepository projectRepository;
    private final GitRepositoryRepository repositories;
    private final GitHubInstallationRepository installations;
    private final GitHubAccountRepository accounts;

    public RepositoryService(ProjectService projects, ProjectRepository projectRepository, GitRepositoryRepository repositories,
                             GitHubInstallationRepository installations, GitHubAccountRepository accounts) {
        this.projects = projects;
        this.projectRepository = projectRepository;
        this.repositories = repositories;
        this.installations = installations;
        this.accounts = accounts;
    }

    public List<RepositoryView> list(long projectId, long userId) {
        // The controller checks current GitHub access before returning these DTOs to a client.
        projects.requireMember(projectId, userId, false);
        return repositories.findByProjectIdOrderByIdAsc(projectId).stream()
                .filter(repo -> repo.getConnectionStatus() != GitRepository.ConnectionStatus.DISCONNECTED)
                .map(RepositoryView::from).toList();
    }

    @Transactional
    public RepositoryView connect(long projectId, long userId, VerifiedRepository verified) {
        var project = projectRepository.findLockedById(projectId).orElseThrow(ProjectService::notFound);
        projects.requireMember(projectId, userId, true);
        var source = verified.repository();
        var remoteInstallation = verified.installation();
        var existing = repositories.findByProjectIdAndGithubRepoId(projectId, source.id());
        if (existing.isPresent() && existing.get().getConnectionStatus() != GitRepository.ConnectionStatus.DISCONNECTED) {
            throw new ApiException(409, "REPOSITORY_ALREADY_CONNECTED", "이 프로젝트에 이미 연결된 저장소입니다.");
        }
        var owner = remoteInstallation.account();
        var ownerType = "Organization".equals(owner.type()) ? GitHubAccount.AccountType.ORGANIZATION : GitHubAccount.AccountType.USER;
        var account = accounts.findByGithubId(owner.id()).orElseGet(() -> accounts.saveAndFlush(GitHubAccount.builder()
                .githubId(owner.id()).login(owner.login()).avatarUrl(owner.avatarUrl()).accountType(ownerType).build()));
        if (account.getAccountType() != ownerType) throw new ApiException(409, "GITHUB_ACCOUNT_CONFLICT", "GitHub 계정 유형을 확인해 주세요.");
        account.updateProfile(owner.login(), account.getDisplayName(), owner.avatarUrl());
        var installation = installations.findByGithubInstallationId(remoteInstallation.id()).orElseGet(() -> installations.saveAndFlush(
                GitHubInstallation.builder().ownerAccount(account).githubInstallationId(remoteInstallation.id())
                        .permissions(remoteInstallation.permissions()).build()));
        if (!installation.getOwnerAccountId().equals(account.getId())) {
            throw new ApiException(409, "GITHUB_INSTALLATION_CONFLICT", "GitHub 설치 소유 계정을 확인해 주세요.");
        }
        installation.confirmAccess(remoteInstallation.permissions());
        var repository = existing.orElseGet(() -> GitRepository.builder().project(project).installation(installation)
                .githubRepoId(source.id()).ownerLogin(source.owner().login()).name(source.name()).htmlUrl(source.htmlUrl())
                .defaultBranch(source.defaultBranch()).privateRepository(source.privateRepository()).build());
        repository.confirmConnection(installation, source.owner().login(), source.name(), source.htmlUrl(), source.description(),
                source.language(), source.defaultBranch(), source.privateRepository());
        return RepositoryView.from(repositories.saveAndFlush(repository));
    }

    @Transactional
    public void disconnect(long projectId, long repositoryId, long userId) {
        projectRepository.findLockedById(projectId).orElseThrow(ProjectService::notFound);
        projects.requireMember(projectId, userId, true);
        repositories.findByIdAndProjectId(repositoryId, projectId)
                .orElseThrow(() -> new ApiException(404, "REPOSITORY_NOT_FOUND", "연결된 저장소를 찾을 수 없습니다."))
                .disconnect(EntitySupport.now());
    }

    public record RepositoryView(String id, String projectId, String githubRepositoryId, String installationId,
                                 String owner, String name, String htmlUrl, String description, String language,
                                 String defaultBranch, boolean privateRepository, String status, boolean initialSyncCompleted) {
        static RepositoryView from(GitRepository repo) {
            return new RepositoryView(repo.getId().toString(), repo.getProjectId().toString(), repo.getGithubRepoId().toString(),
                    repo.getInstallation().getGithubInstallationId().toString(), repo.getOwnerLogin(), repo.getName(), repo.getHtmlUrl(),
                    repo.getDescription(), repo.getPrimaryLanguage(), repo.getDefaultBranch(), repo.getPrivateRepository(),
                    repo.getConnectionStatus().name(), repo.getInitialSyncCompletedAt() != null);
        }
    }
}
