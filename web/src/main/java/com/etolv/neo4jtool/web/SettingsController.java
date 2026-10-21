package com.etolv.neo4jtool.web;

import com.etolv.neo4jtool.engine.instance.InstanceSettings.Runner;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class SettingsController {

    private final SettingsStore settings;
    private final UserStore users;
    private final PasswordEncoder encoder;
    private final AuditLog audit;

    public SettingsController(SettingsStore settings, UserStore users, PasswordEncoder encoder, AuditLog audit) {
        this.settings = settings;
        this.users = users;
        this.encoder = encoder;
        this.audit = audit;
    }

    @GetMapping("/settings")
    String show(Model model) {
        model.addAttribute("settings", settings.get());
        model.addAttribute("runners", Runner.values());
        return "settings";
    }

    @PostMapping("/settings")
    String save(@RequestParam Runner runner, @RequestParam(defaultValue = "") String dockerImage,
                @RequestParam(defaultValue = "") String neo4jHome, @RequestParam String workDir, @RequestParam String outputDir, @RequestParam String backupDir,
                @RequestParam(defaultValue = "false") boolean mergeSwap,
                @RequestParam(defaultValue = "false") boolean copySchema, @RequestParam(defaultValue = "false") boolean checkAfterBuild,
                @RequestParam String tempHeap, @RequestParam String tempPageCache, @RequestParam int startTimeoutMinutes,
                @RequestParam String orgProperty, @RequestParam(defaultValue = "false") boolean includeNeighbours,
                @RequestParam int batchSize, @RequestParam int sampleSize,
                @RequestParam(defaultValue = "false") boolean analyseRelationships, RedirectAttributes redirect) {
        try {
            settings.save(new AppSettings(runner, dockerImage.strip(), neo4jHome.strip(), workDir.strip(), outputDir.strip(), backupDir.strip(), mergeSwap, copySchema,
                    checkAfterBuild, tempHeap.strip(),
                    tempPageCache.strip(), startTimeoutMinutes, orgProperty.strip(), includeNeighbours, batchSize, sampleSize, analyseRelationships));
            audit.record("settings_save", null);
            redirect.addFlashAttribute("message", "Settings saved");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/settings";
    }

    @PostMapping("/settings/password")
    String changePassword(@RequestParam String current, @RequestParam String password, @RequestParam String confirm,
                          Authentication auth, RedirectAttributes redirect) {
        UserDetails user = users.loadUserByUsername(auth.getName());
        if (!encoder.matches(current, user.getPassword())) {
            redirect.addFlashAttribute("error", "The current password is wrong");
        } else if (password.length() < 8) {
            redirect.addFlashAttribute("error", "The new password needs at least 8 characters");
        } else if (!password.equals(confirm)) {
            redirect.addFlashAttribute("error", "The two new passwords are different");
        } else {
            users.changePassword(user.getUsername(), encoder.encode(password));
            audit.record("password_change", null);
            redirect.addFlashAttribute("message", "Password changed");
        }
        return "redirect:/settings";
    }
}
