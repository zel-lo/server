package com.zello.zello.auth;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;

@Service
public class GitHubOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {
    private final OAuth2UserService<OAuth2UserRequest, OAuth2User> remote;
    private final GitHubLoginService loginService;

    public GitHubOAuth2UserService(
            @Qualifier("githubRemoteUserService") OAuth2UserService<OAuth2UserRequest, OAuth2User> remote,
            GitHubLoginService loginService) {
        this.remote = remote;
        this.loginService = loginService;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) {
        if (!"github".equals(request.getClientRegistration().getRegistrationId())) {
            throw new OAuth2AuthenticationException(new OAuth2Error("unsupported_provider"));
        }
        // Fetch identity with the freshly exchanged token before starting a DB transaction.
        try {
            return loginService.login(GitHubProfile.from(remote.loadUser(request).getAttributes()));
        } catch (DataAccessException exception) {
            throw new OAuth2AuthenticationException(new OAuth2Error("account_storage_failed"));
        }
    }
}
