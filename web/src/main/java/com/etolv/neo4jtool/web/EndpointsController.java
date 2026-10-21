package com.etolv.neo4jtool.web;

import com.etolv.neo4jtool.engine.schema.SchemaReader;
import com.etolv.neo4jtool.web.Endpoint.Kind;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class EndpointsController {

    private final EndpointStore endpoints;
    private final EndpointService service;
    private final SettingsStore settings;
    private final AuditLog audit;

    public EndpointsController(EndpointStore endpoints, EndpointService service, SettingsStore settings, AuditLog audit) {
        this.endpoints = endpoints;
        this.service = service;
        this.settings = settings;
        this.audit = audit;
    }

    @GetMapping("/endpoints")
    String list(Model model) {
        model.addAttribute("endpoints", endpoints.all());
        model.addAttribute("kinds", Kind.values());
        return "endpoints";
    }

    @PostMapping("/endpoints")
    String add(@RequestParam String name, @RequestParam Kind kind, @RequestParam(required = false) String location,
               @RequestParam(required = false) String username, @RequestParam(required = false) String password,
               @RequestParam(defaultValue = "neo4j") String database, RedirectAttributes redirect) {
        try {
            endpoints.add(name, kind, location, username, password, database);
            audit.record("endpoint_add", name.trim() + " (" + kind + ") " + location.trim());
            redirect.addFlashAttribute("message", "Saved '" + name.trim() + "'");
        } catch (DuplicateKeyException e) {
            redirect.addFlashAttribute("error", "There is already an endpoint named '" + name.trim() + "'");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/endpoints";
    }

    @PostMapping("/endpoints/{id}/check")
    String check(@PathVariable long id, RedirectAttributes redirect) {
        Endpoint endpoint = find(id);
        try {
            redirect.addFlashAttribute("message", endpoint.name() + ": " + service.check(endpoint));
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", endpoint.name() + ": " + e.getMessage());
        }
        return "redirect:/endpoints";
    }

    @PostMapping("/endpoints/{id}/delete")
    String delete(@PathVariable long id, RedirectAttributes redirect) {
        Endpoint endpoint = find(id);
        endpoints.delete(id);
        audit.record("endpoint_delete", endpoint.name());
        redirect.addFlashAttribute("message", "Deleted '" + endpoint.name() + "'. Dumps and data directories on disk are not touched.");
        return "redirect:/endpoints";
    }

    @GetMapping("/endpoints/{id}/schema")
    String schema(@PathVariable long id, @RequestParam(required = false) String orgProperty,
                  @RequestParam(defaultValue = "false") boolean orgBreakdown, Model model) {
        Endpoint endpoint = find(id);
        String property = orgProperty == null || orgProperty.isBlank() ? settings.get().orgProperty() : orgProperty.strip();
        model.addAttribute("endpoint", endpoint);
        model.addAttribute("orgProperty", property);
        model.addAttribute("orgBreakdown", orgBreakdown);
        try (EndpointService.Opened opened = service.open(endpoint, line -> { })) {
            model.addAttribute("schema", new SchemaReader(opened.client(), property, orgBreakdown).read());
            audit.record("schema_view", endpoint.name());
        } catch (RuntimeException e) {
            model.addAttribute("error", e.getMessage());
        }
        return "schema";
    }

    private Endpoint find(long id) {
        return endpoints.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such endpoint"));
    }
}
