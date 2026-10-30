package com.zello.zello.auth;

import java.io.Serializable;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

public record ZelloPrincipal(Long userId, Long githubId) implements OAuth2User, Serializable {
    @Override
    public Map<String, Object> getAttributes() {
        return Map.of("userId", userId.toString(), "githubId", githubId.toString());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Set.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getName() {
        return userId.toString();
    }
}
