package com.etolv.neo4jtool.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Login with the users in MySQL. The first user is created from the environment when there are none.
 *
 * <p>A user with a second factor (or any user, when the setting asks for it) is not let in by the password alone: the password
 * gives a half-signed-in state, {@link Sessions#PRE_2FA}, that only opens the pages for the second step.
 */
@Configuration
public class SecurityConfig {

    private final AuditLog audit;

    public SecurityConfig(AuditLog audit) {
        this.audit = audit;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, UserStore users, SettingsStore settings, Sessions sessions) throws Exception {
        AuthenticationSuccessHandler afterPassword = (request, response, auth) -> {
            if (users.hasTwoFactor(auth.getName())) {
                audit.record(auth.getName(), "password_ok", where(auth));
                sessions.install(sessions.halfSignedIn(auth), request, response);
                response.sendRedirect(request.getContextPath() + "/login/code");
            } else if (settings.get().requireTwoFactor()) {
                audit.record(auth.getName(), "password_ok", where(auth) + ", must set up two-factor");
                sessions.install(sessions.halfSignedIn(auth), request, response);
                response.sendRedirect(request.getContextPath() + "/login/setup");
            } else {
                audit.record(auth.getName(), "login", where(auth));
                response.sendRedirect(request.getContextPath() + "/");
            }
        };
        OncePerRequestFilter stillEnabled = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            jakarta.servlet.FilterChain chain) throws IOException, jakarta.servlet.ServletException {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)
                        && !users.isEnabled(auth.getName())) {
                    SecurityContextHolder.clearContext();
                    if (request.getSession(false) != null) {
                        request.getSession(false).invalidate();
                    }
                    response.sendRedirect(request.getContextPath() + "/login");
                    return;
                }
                chain.doFilter(request, response);
            }
        };
        http.authorizeHttpRequests(a -> a
                        .requestMatchers("/login", "/app.css").permitAll()
                        .requestMatchers("/login/code", "/login/setup").hasAuthority(Sessions.PRE_2FA)
                        .anyRequest().hasRole("ADMIN"))
                .formLogin(f -> f.loginPage("/login").successHandler(afterPassword))
                .exceptionHandling(e -> e.accessDeniedHandler((request, response, denied) -> {
                    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                    if (auth != null && sessions.isHalfSignedIn(auth)) {
                        response.sendRedirect(request.getContextPath()
                                + (users.hasTwoFactor(auth.getName()) ? "/login/code" : "/login/setup"));
                    } else {
                        new AccessDeniedHandlerImpl().handle(request, response, denied);
                    }
                }))
                .addFilterBefore(stillEnabled, AuthorizationFilter.class)
                .logout(l -> l.logoutSuccessUrl("/login?logout")
                        .addLogoutHandler((request, response, auth) -> {
                            if (auth != null) {
                                audit.record(auth.getName(), "logout", where(auth));
                            }
                        }));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    ApplicationRunner createFirstUser(UserStore users, ToolProperties properties, PasswordEncoder encoder) {
        return args -> {
            if (users.isEmpty()) {
                users.create(properties.admin().user(), encoder.encode(properties.admin().password()));
                audit.record("system", "user_created", properties.admin().user());
            }
        };
    }

    @EventListener
    void loginFailed(AbstractAuthenticationFailureEvent event) {
        audit.record(String.valueOf(event.getAuthentication().getName()), "login_failed", where(event.getAuthentication()));
    }

    static String where(Authentication auth) {
        return auth.getDetails() instanceof WebAuthenticationDetails details ? "from " + details.getRemoteAddress() : null;
    }
}
