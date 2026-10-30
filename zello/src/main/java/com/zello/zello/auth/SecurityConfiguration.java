package com.zello.zello.auth;

import com.zello.zello.domain.user.UserRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "zello.auth.github", name = "enabled", havingValue = "true")
    ClientRegistrationRepository clientRegistrationRepository(AuthProperties properties) {
        var github = properties.github();
        var builder = ClientRegistration.withRegistrationId("github")
                .clientId(github.clientId()).clientSecret(github.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(github.redirectUri())
                .authorizationUri("https://github.com/login/oauth/authorize")
                .tokenUri("https://github.com/login/oauth/access_token")
                .userInfoUri("https://api.github.com/user")
                .userNameAttributeName("id").clientName("GitHub")
                .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build());
        if (github.appType() == AuthProperties.AppType.OAUTH) {
            builder.scope("read:user", "repo", "read:org");
        }
        return new InMemoryClientRegistrationRepository(builder.build());
    }

    @Bean
    OAuth2UserService<OAuth2UserRequest, OAuth2User> githubRemoteUserService() {
        return new DefaultOAuth2UserService();
    }

    @Bean
    OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> githubTokenResponseClient() {
        return new RestClientAuthorizationCodeTokenResponseClient();
    }

    @Bean
    OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, AuthProperties properties, ApiSecurityErrors errors,
            Optional<ClientRegistrationRepository> registrations,
            GitHubOAuth2UserService users, UserRepository userRepository,
            OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> githubTokenResponseClient,
            OAuth2AuthorizedClientRepository authorizedClients) throws Exception {
        http.cors(cors -> cors.configurationSource(corsConfigurationSource(properties)))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/auth/status", "/api/auth/csrf", "/oauth2/**", "/login/oauth2/**",
                                "/api/github/installations/callback",
                                "/error", "/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                errors.write(response, 401, "AUTHENTICATION_REQUIRED", "로그인이 필요합니다."))
                        .accessDeniedHandler((request, response, exception) ->
                                errors.write(response, 403, "ACCESS_DENIED", "요청 권한 또는 인증 토큰을 확인해 주세요.")))
                .headers(headers -> headers.referrerPolicy(policy -> policy.policy(ReferrerPolicy.NO_REFERRER)))
                .logout(logout -> logout.logoutUrl("/api/auth/logout")
                        .invalidateHttpSession(true).clearAuthentication(true).deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
                .addFilterBefore(new ActiveUserFilter(userRepository, errors), AuthorizationFilter.class);

        if (registrations.isPresent()) {
            http.oauth2Login(oauth -> oauth.clientRegistrationRepository(registrations.get())
                    .authorizedClientRepository(authorizedClients)
                    .tokenEndpoint(token -> token.accessTokenResponseClient(githubTokenResponseClient))
                    .userInfoEndpoint(info -> info.userService(users))
                    .successHandler((request, response, authentication) -> response.sendRedirect(properties.successUrl()))
                    .failureHandler((request, response, exception) -> {
                        var session = request.getSession(false);
                        if (session != null) session.invalidate();
                        String code = exception instanceof OAuth2AuthenticationException oauthError
                                ? oauthError.getError().getErrorCode() : "failed";
                        String safeCode = switch (code) {
                            case "access_denied" -> "access_denied";
                            case "authorization_request_not_found", "invalid_state_parameter" -> "expired";
                            case "account_inactive" -> "inactive";
                            default -> "failed";
                        };
                        response.sendRedirect(properties.failureUrl(safeCode));
                    }));
        }
        return http.build();
    }

    private CorsConfigurationSource corsConfigurationSource(AuthProperties properties) {
        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(properties.frontendBaseUrl()));
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "X-CSRF-TOKEN"));
        configuration.setAllowCredentials(true);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
