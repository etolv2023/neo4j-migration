package com.etolv.neo4jtool.web;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

/** Login with the users in MySQL. The first user is created from the environment when there are none. */
@Configuration
public class SecurityConfig {

    private final AuditLog audit;

    public SecurityConfig(AuditLog audit) {
        this.audit = audit;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(a -> a.requestMatchers("/login", "/app.css").permitAll().anyRequest().authenticated())
                .formLogin(f -> f.loginPage("/login").defaultSuccessUrl("/", true))
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
    void loggedIn(AuthenticationSuccessEvent event) {
        audit.record(event.getAuthentication().getName(), "login", where(event.getAuthentication()));
    }

    @EventListener
    void loginFailed(AbstractAuthenticationFailureEvent event) {
        audit.record(String.valueOf(event.getAuthentication().getName()), "login_failed", where(event.getAuthentication()));
    }

    private static String where(Authentication auth) {
        return auth.getDetails() instanceof WebAuthenticationDetails details ? "from " + details.getRemoteAddress() : null;
    }
}
