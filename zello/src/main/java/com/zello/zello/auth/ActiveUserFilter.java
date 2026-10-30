package com.zello.zello.auth;

import com.zello.zello.domain.user.User;
import com.zello.zello.domain.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

final class ActiveUserFilter extends OncePerRequestFilter {
    private final UserRepository users;
    private final ApiSecurityErrors errors;

    ActiveUserFilter(UserRepository users, ApiSecurityErrors errors) {
        this.users = users;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof ZelloPrincipal principal
                && !users.existsByIdAndStatus(principal.userId(), User.Status.ACTIVE)) {
            new SecurityContextLogoutHandler().logout(request, response, authentication);
            errors.write(response, 401, "ACCOUNT_INACTIVE", "사용할 수 없는 계정입니다. 다시 로그인해 주세요.");
            return;
        }
        chain.doFilter(request, response);
    }
}
