package com.etolv.neo4jtool.web;

import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Who may sign in. Every user is an administrator; there are no other roles. */
@Controller
public class UsersController {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._@-]{3,100}");

    private final UserStore users;
    private final PasswordEncoder encoder;
    private final AuditLog audit;

    public UsersController(UserStore users, PasswordEncoder encoder, AuditLog audit) {
        this.users = users;
        this.encoder = encoder;
        this.audit = audit;
    }

    @GetMapping("/users")
    String list(Model model) {
        model.addAttribute("users", users.list());
        return "users";
    }

    @PostMapping("/users")
    String create(@RequestParam String username, @RequestParam String password, @RequestParam String confirm,
                  RedirectAttributes redirect) {
        String name = username.strip();
        if (!NAME.matcher(name).matches()) {
            return fail(redirect, "The user name needs 3 to 100 letters, digits, dots, dashes, underscores or @");
        }
        String problem = passwordProblem(password, confirm);
        if (problem != null) {
            return fail(redirect, problem);
        }
        try {
            users.create(name, encoder.encode(password));
        } catch (DuplicateKeyException e) {
            return fail(redirect, "There is already a user called " + name);
        }
        audit.record("user_created", name);
        redirect.addFlashAttribute("message", "User " + name + " added");
        return "redirect:/users";
    }

    @PostMapping("/users/{id}/enabled")
    String setEnabled(@PathVariable long id, @RequestParam boolean enabled, Authentication auth, RedirectAttributes redirect) {
        Optional<UserStore.Row> user = users.find(id);
        if (user.isEmpty()) {
            return fail(redirect, "No such user");
        }
        String name = user.get().username();
        if (!enabled && isMe(name, auth)) {
            return fail(redirect, "You cannot switch off the account you are using");
        }
        if (!enabled && user.get().enabled() && users.countEnabled() <= 1) {
            return fail(redirect, "This is the last user who can sign in");
        }
        users.setEnabled(id, enabled);
        audit.record(enabled ? "user_enabled" : "user_disabled", name);
        redirect.addFlashAttribute("message", "User " + name + (enabled ? " can sign in again" : " can no longer sign in"));
        return "redirect:/users";
    }

    @PostMapping("/users/{id}/password")
    String resetPassword(@PathVariable long id, @RequestParam String password, @RequestParam String confirm,
                         Authentication auth, RedirectAttributes redirect) {
        Optional<UserStore.Row> user = users.find(id);
        if (user.isEmpty()) {
            return fail(redirect, "No such user");
        }
        if (isMe(user.get().username(), auth)) {
            return fail(redirect, "Change your own password in Settings");
        }
        String problem = passwordProblem(password, confirm);
        if (problem != null) {
            return fail(redirect, problem);
        }
        users.setPassword(id, encoder.encode(password));
        audit.record("user_password_reset", user.get().username());
        redirect.addFlashAttribute("message", "New password set for " + user.get().username());
        return "redirect:/users";
    }

    @PostMapping("/users/{id}/two-factor/disable")
    String resetTwoFactor(@PathVariable long id, RedirectAttributes redirect) {
        Optional<UserStore.Row> user = users.find(id);
        if (user.isEmpty()) {
            return fail(redirect, "No such user");
        }
        users.disableTwoFactor(id);
        audit.record("two_factor_reset", user.get().username());
        redirect.addFlashAttribute("message", "Two-factor sign-in is off for " + user.get().username()
                + (user.get().twoFactor() ? ". They can set it up again from Settings." : ""));
        return "redirect:/users";
    }

    @PostMapping("/users/{id}/delete")
    String delete(@PathVariable long id, Authentication auth, RedirectAttributes redirect) {
        Optional<UserStore.Row> user = users.find(id);
        if (user.isEmpty()) {
            return fail(redirect, "No such user");
        }
        String name = user.get().username();
        if (isMe(name, auth)) {
            return fail(redirect, "You cannot delete the account you are using");
        }
        if (user.get().enabled() && users.countEnabled() <= 1) {
            return fail(redirect, "This is the last user who can sign in");
        }
        users.delete(id);
        audit.record("user_deleted", name);
        redirect.addFlashAttribute("message", "User " + name + " deleted");
        return "redirect:/users";
    }

    private static boolean isMe(String name, Authentication auth) {
        return name.equalsIgnoreCase(auth.getName());
    }

    private static String passwordProblem(String password, String confirm) {
        if (password.length() < 8) {
            return "The password needs at least 8 characters";
        }
        if (!password.equals(confirm)) {
            return "The two passwords are different";
        }
        return null;
    }

    private static String fail(RedirectAttributes redirect, String message) {
        redirect.addFlashAttribute("error", message);
        return "redirect:/users";
    }
}
