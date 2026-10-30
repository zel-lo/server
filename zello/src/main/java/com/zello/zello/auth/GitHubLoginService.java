package com.zello.zello.auth;

import com.zello.zello.domain.common.EntitySupport;
import com.zello.zello.domain.github.GitHubAccount;
import com.zello.zello.domain.github.GitHubAccountRepository;
import com.zello.zello.domain.user.User;
import com.zello.zello.domain.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class GitHubLoginService {
    private final GitHubAccountRepository accounts;
    private final UserRepository users;
    private final TransactionTemplate transactions;

    public GitHubLoginService(GitHubAccountRepository accounts, UserRepository users,
                              PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.users = users;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public ZelloPrincipal login(GitHubProfile profile) {
        // A concurrent first login may win the unique github_id insert. Retry in a new transaction.
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return transactions.execute(status -> register(profile));
            } catch (DataIntegrityViolationException exception) {
                if (attempt == 1) throw new OAuth2AuthenticationException(new OAuth2Error("account_storage_failed"));
            }
        }
        throw new OAuth2AuthenticationException(new OAuth2Error("account_storage_failed"));
    }

    private ZelloPrincipal register(GitHubProfile profile) {
        GitHubAccount account = accounts.findByGithubId(profile.githubId()).orElseGet(() ->
                accounts.saveAndFlush(GitHubAccount.builder().githubId(profile.githubId())
                        .login(profile.login()).accountType(GitHubAccount.AccountType.USER).build()));
        if (account.getAccountType() != GitHubAccount.AccountType.USER) {
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_github_profile"));
        }
        User user = users.findByGithubAccountId(account.getId()).orElseGet(() ->
                users.save(User.builder().githubAccount(account).build()));
        if (user.getStatus() != User.Status.ACTIVE) {
            throw new OAuth2AuthenticationException(new OAuth2Error("account_inactive"));
        }
        account.updateProfile(profile.login(), profile.displayName(), profile.avatarUrl());
        user.recordLogin(profile.email(), EntitySupport.now());
        return new ZelloPrincipal(user.getId(), profile.githubId());
    }
}
