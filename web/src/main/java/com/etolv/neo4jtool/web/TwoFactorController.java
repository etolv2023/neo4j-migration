package com.etolv.neo4jtool.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** The second sign-in step, and turning two-factor on and off for one's own account. */
@Controller
public class TwoFactorController {

    /** Session attribute: an enrolment that was shown but not yet confirmed with a code. */
    static final String PENDING = "totp.pending";

    private final TwoFactorService twoFactor;
    private final LoginThrottle throttle;
    private final UserStore users;
    private final Sessions sessions;
    private final SettingsStore settings;
    private final PasswordEncoder encoder;
    private final AuditLog audit;

    public TwoFactorController(TwoFactorService twoFactor, LoginThrottle throttle, UserStore users, Sessions sessions,
                               SettingsStore settings, PasswordEncoder encoder, AuditLog audit) {
        this.twoFactor = twoFactor;
        this.throttle = throttle;
        this.users = users;
        this.sessions = sessions;
        this.settings = settings;
        this.encoder = encoder;
        this.audit = audit;
    }

    // ---- sign-in: the code ----

    @GetMapping("/login/code")
    String codePage() {
        return "login-code";
    }

    @PostMapping("/login/code")
    String checkCode(@RequestParam String code, Authentication half, HttpServletRequest request, HttpServletResponse response,
                     RedirectAttributes redirect) {
        String user = half.getName();
        Duration locked = throttle.lockedFor(user);
        if (!locked.isZero()) {
            audit.record(user, "two_factor_locked", "from " + request.getRemoteAddr());
            redirect.addFlashAttribute("error", "Too many wrong codes. Try again in " + minutes(locked) + ".");
            return "redirect:/login/code";
        }
        boolean authenticator = TwoFactorService.clean(code).matches("\\d{" + Totp.DIGITS + "}");
        boolean ok = authenticator ? twoFactor.verifyCode(user, code) : twoFactor.useRecoveryCode(user, code);
        if (!ok) {
            throttle.failed(user);
            audit.record(user, "two_factor_failed", "from " + request.getRemoteAddr()
                    + (authenticator ? ", authenticator code" : ", recovery code"));
            redirect.addFlashAttribute("error", authenticator ? "That code is wrong or already used" : "That is not an unused recovery code");
            return "redirect:/login/code";
        }
        throttle.succeeded(user);
        if (!authenticator) {
            audit.record(user, "recovery_code_used", twoFactor.recoveryCodesLeft(user) + " left");
        }
        completeSignIn(half, authenticator ? "authenticator code" : "recovery code", request, response);
        return "redirect:/";
    }

    // ---- sign-in: forced set-up ----

    @GetMapping("/login/setup")
    String setupPage(Authentication half, HttpSession session, Model model) {
        model.addAttribute("enrolment", pending(half.getName(), session));
        return "login-setup";
    }

    @PostMapping("/login/setup")
    String confirmSetup(@RequestParam String code, Authentication half, HttpServletRequest request, HttpServletResponse response,
                        HttpSession session, RedirectAttributes redirect, Model model) {
        TwoFactorService.Enrolment enrolment = pending(half.getName(), session);
        List<String> recovery = twoFactor.confirm(half.getName(), enrolment.secret(), code);
        if (recovery == null) {
            audit.record(half.getName(), "two_factor_failed", "from " + request.getRemoteAddr() + ", set-up");
            redirect.addFlashAttribute("error", "That code is wrong. Check that the phone's clock is right and try the next code.");
            return "redirect:/login/setup";
        }
        session.removeAttribute(PENDING);
        audit.record(half.getName(), "two_factor_enabled", "at sign-in");
        completeSignIn(half, "set-up", request, response);
        model.addAttribute("codes", recovery);
        model.addAttribute("active", "settings");
        return "recovery-codes";
    }

    // ---- own account, from Settings ----

    @PostMapping("/settings/2fa/start")
    String start(Authentication auth, HttpSession session, RedirectAttributes redirect) {
        if (users.hasTwoFactor(auth.getName())) {
            redirect.addFlashAttribute("error", "Two-factor sign-in is already on");
        } else {
            session.setAttribute(PENDING, twoFactor.newEnrolment(auth.getName()));
        }
        return "redirect:/settings";
    }

    @PostMapping("/settings/2fa/cancel")
    String cancel(HttpSession session) {
        session.removeAttribute(PENDING);
        return "redirect:/settings";
    }

    @PostMapping("/settings/2fa/enable")
    String enable(@RequestParam String code, Authentication auth, HttpSession session, RedirectAttributes redirect, Model model,
                  HttpServletRequest request) {
        TwoFactorService.Enrolment enrolment = (TwoFactorService.Enrolment) session.getAttribute(PENDING);
        if (enrolment == null) {
            redirect.addFlashAttribute("error", "Start again: press \"Set up two-factor sign-in\"");
            return "redirect:/settings";
        }
        List<String> recovery = twoFactor.confirm(auth.getName(), enrolment.secret(), code);
        if (recovery == null) {
            audit.record("two_factor_failed", "from " + request.getRemoteAddr() + ", set-up");
            redirect.addFlashAttribute("error", "That code is wrong. Check that the phone's clock is right and try the next code.");
            return "redirect:/settings";
        }
        session.removeAttribute(PENDING);
        audit.record("two_factor_enabled", null);
        model.addAttribute("codes", recovery);
        model.addAttribute("active", "settings");
        return "recovery-codes";
    }

    @PostMapping("/settings/2fa/disable")
    String disable(@RequestParam String password, Authentication auth, RedirectAttributes redirect) {
        UserDetails user = users.loadUserByUsername(auth.getName());
        if (settings.get().requireTwoFactor()) {
            redirect.addFlashAttribute("error", "Two-factor sign-in is required for every user (see the settings above)");
        } else if (!encoder.matches(password, user.getPassword())) {
            redirect.addFlashAttribute("error", "The password is wrong");
        } else {
            users.find(idOf(user.getUsername())).ifPresent(row -> users.disableTwoFactor(row.id()));
            audit.record("two_factor_disabled", null);
            redirect.addFlashAttribute("message", "Two-factor sign-in is off");
        }
        return "redirect:/settings";
    }

    // ---- helpers ----

    private long idOf(String username) {
        return users.list().stream().filter(r -> r.username().equalsIgnoreCase(username)).mapToLong(UserStore.Row::id).findFirst().orElse(-1);
    }

    private TwoFactorService.Enrolment pending(String username, HttpSession session) {
        TwoFactorService.Enrolment enrolment = (TwoFactorService.Enrolment) session.getAttribute(PENDING);
        if (enrolment == null) {
            enrolment = twoFactor.newEnrolment(username);
            session.setAttribute(PENDING, enrolment);
        }
        return enrolment;
    }

    private void completeSignIn(Authentication half, String how, HttpServletRequest request, HttpServletResponse response) {
        UserDetails user = users.loadUserByUsername(half.getName());
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        sessions.install(sessions.fullySignedIn(user, half.getDetails()), request, response);
        audit.record(user.getUsername(), "login", "from " + request.getRemoteAddr() + ", " + how);
    }

    private static String minutes(Duration d) {
        long m = Math.max(1, (d.toSeconds() + 59) / 60);
        return m + (m == 1 ? " minute" : " minutes");
    }
}
