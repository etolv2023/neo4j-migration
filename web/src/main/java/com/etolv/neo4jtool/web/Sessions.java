package com.etolv.neo4jtool.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;

/** Moves a browser session between "password only" and "fully signed in". */
@Component
public class Sessions {

    /** The only authority a user has between the password and the second factor. */
    public static final String PRE_2FA = "PRE_2FA";

    private final HttpSessionSecurityContextRepository repository = new HttpSessionSecurityContextRepository();

    public Authentication halfSignedIn(Authentication passwordLogin) {
        UsernamePasswordAuthenticationToken half = UsernamePasswordAuthenticationToken.authenticated(
                passwordLogin.getName(), null, List.of(new SimpleGrantedAuthority(PRE_2FA)));
        half.setDetails(passwordLogin.getDetails());
        return half;
    }

    public Authentication fullySignedIn(UserDetails user, Object details) {
        UsernamePasswordAuthenticationToken full = UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
        full.setDetails(details);
        return full;
    }

    public boolean isHalfSignedIn(Authentication auth) {
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(PRE_2FA::equals);
    }

    /** Makes this the signed-in state of the browser session. */
    public void install(Authentication auth, HttpServletRequest request, HttpServletResponse response) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        repository.saveContext(context, request, response);
    }
}
