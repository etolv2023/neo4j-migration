package com.etolv.neo4jtool.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class LogsController {

    private static final int LIMIT = 500;

    private final AuditLog audit;
    private final JobLog jobLog;

    public LogsController(AuditLog audit, JobLog jobLog) {
        this.audit = audit;
        this.jobLog = jobLog;
    }

    /** tab: jobs (what background jobs printed), audit (what people did) or logins. */
    @GetMapping("/logs")
    String logs(@RequestParam(defaultValue = "jobs") String tab, Model model) {
        model.addAttribute("tab", tab);
        switch (tab) {
            case "audit" -> model.addAttribute("entries", audit.latest(LIMIT));
            case "logins" -> model.addAttribute("entries", audit.latestLogins(LIMIT));
            default -> {
                model.addAttribute("tab", "jobs");
                model.addAttribute("lines", jobLog.latest(LIMIT));
            }
        }
        return "logs";
    }

    @GetMapping("/login")
    String login() {
        return "login";
    }
}
