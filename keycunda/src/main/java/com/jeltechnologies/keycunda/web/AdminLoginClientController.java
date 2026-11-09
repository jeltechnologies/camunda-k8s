package com.jeltechnologies.keycunda.web;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.jeltechnologies.keycunda.client.ClientRepository;
import com.jeltechnologies.keycunda.login.LoginClient;
import com.jeltechnologies.keycunda.login.LoginClientRepository;
import com.jeltechnologies.keycunda.login.RedirectPatterns;

/**
 * Admin pages for login clients (web apps that log users in with authorization code + PKCE, see {@link
 * LoginClient}). Only linked from the nav when Camunda authorizations are enabled, since that is when a user's own
 * identity matters to the app calling Camunda; clients that exist keep working either way.
 */
@Controller
public class AdminLoginClientController {

    private final LoginClientRepository loginClientRepository;
    private final ClientRepository clientRepository;
    private final AdminClientController adminClientController;

    public AdminLoginClientController(LoginClientRepository loginClientRepository, ClientRepository clientRepository,
            AdminClientController adminClientController) {
        this.loginClientRepository = loginClientRepository;
        this.clientRepository = clientRepository;
        this.adminClientController = adminClientController;
    }

    @GetMapping("/admin/login-clients")
    public String list(Model model) {
        model.addAttribute("clients", loginClientRepository.findAll());
        return "admin/login-clients";
    }

    @GetMapping("/admin/login-clients/new")
    public String newForm(Model model) {
        Map<String, String> known = adminClientController.knownAudiences();
        model.addAttribute("knownAudiences", known);
        // Orchestration pre-checked: the typical login client calls Camunda's REST API as the user.
        model.addAttribute("selectedAudiences", new LinkedHashSet<>(known.entrySet().stream()
                .filter(e -> e.getKey().equals("Orchestration")).map(Map.Entry::getValue).toList()));
        model.addAttribute("customAudience", "");
        model.addAttribute("clientId", "");
        model.addAttribute("redirectUris", "");
        return "admin/add-login-client";
    }

    @PostMapping("/admin/login-clients")
    public String add(@RequestParam String clientId, @RequestParam String redirectUris,
            @RequestParam(name = "knownAudiences", required = false) List<String> knownAudiences,
            @RequestParam(required = false) String customAudience, RedirectAttributes redirectAttributes) {
        if (AdminClientController.RESERVED_CLIENT_IDS.contains(clientId)
                || !AdminClientController.CLIENT_ID_PATTERN.matcher(clientId).matches()) {
            return fail(redirectAttributes, "/admin/login-clients/new",
                    "Client ID \"" + clientId + "\" is reserved or invalid. Use letters, digits, \".\", \"_\" or \"-\".");
        }
        if (clientRepository.findByClientId(clientId).isPresent()) {
            return fail(redirectAttributes, "/admin/login-clients/new",
                    "Client ID \"" + clientId + "\" is already used by an API client.");
        }
        String problem = redirectProblem(redirectUris);
        if (problem != null) {
            return fail(redirectAttributes, "/admin/login-clients/new", problem);
        }
        try {
            loginClientRepository.insert(clientId, AdminClientController.combineAudiences(knownAudiences, customAudience),
                    String.join("\n", RedirectPatterns.parse(redirectUris)));
        } catch (DataIntegrityViolationException e) {
            return fail(redirectAttributes, "/admin/login-clients/new", "A client with client ID \"" + clientId + "\" already exists.");
        }
        redirectAttributes.addFlashAttribute("message", "Login client \"" + clientId + "\" created.");
        return "redirect:/admin/login-clients";
    }

    @GetMapping("/admin/login-clients/{id}/edit")
    public String editForm(@PathVariable UUID id, Model model, RedirectAttributes redirectAttributes) {
        return loginClientRepository.findById(id).map(client -> {
            Map<String, String> known = adminClientController.knownAudiences();
            model.addAttribute("client", client);
            model.addAttribute("knownAudiences", known);
            model.addAttribute("selectedAudiences", AdminClientController.selectedKnownAudiences(client.audience(), known));
            model.addAttribute("customAudience", AdminClientController.customAudiencePart(client.audience(), known));
            return "admin/edit-login-client";
        }).orElseGet(() -> {
            redirectAttributes.addFlashAttribute("error", "Login client not found.");
            return "redirect:/admin/login-clients";
        });
    }

    @PostMapping("/admin/login-clients/{id}/edit")
    public String edit(@PathVariable UUID id, @RequestParam String clientId, @RequestParam String redirectUris,
            @RequestParam(name = "knownAudiences", required = false) List<String> knownAudiences,
            @RequestParam(required = false) String customAudience, RedirectAttributes redirectAttributes) {
        LoginClient client = loginClientRepository.findById(id).orElse(null);
        if (client == null) {
            redirectAttributes.addFlashAttribute("error", "Login client not found.");
            return "redirect:/admin/login-clients";
        }
        String editPage = "/admin/login-clients/" + id + "/edit";
        boolean renamed = !clientId.equals(client.clientId());
        if (renamed) {
            if (AdminClientController.RESERVED_CLIENT_IDS.contains(clientId)
                    || !AdminClientController.CLIENT_ID_PATTERN.matcher(clientId).matches()) {
                return fail(redirectAttributes, editPage,
                        "Client ID \"" + clientId + "\" is reserved or invalid. Use letters, digits, \".\", \"_\" or \"-\".");
            }
            if (clientRepository.findByClientId(clientId).isPresent() || loginClientRepository.findByClientId(clientId).isPresent()) {
                return fail(redirectAttributes, editPage, "Client ID \"" + clientId + "\" is already used by another client.");
            }
        }
        String problem = redirectProblem(redirectUris);
        if (problem != null) {
            return fail(redirectAttributes, editPage, problem);
        }
        loginClientRepository.update(id, clientId, AdminClientController.combineAudiences(knownAudiences, customAudience),
                String.join("\n", RedirectPatterns.parse(redirectUris)));
        redirectAttributes.addFlashAttribute("message", "Login client \"" + clientId + "\" updated"
                + (renamed ? " (renamed from \"" + client.clientId() + "\" - update the app's configuration, and users logged in with"
                        + " the old ID have to log in again)." : "."));
        return "redirect:/admin/login-clients";
    }

    @PostMapping("/admin/login-clients/{id}/delete")
    public String delete(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        loginClientRepository.findById(id).ifPresentOrElse(client -> {
            loginClientRepository.deleteById(id);
            redirectAttributes.addFlashAttribute("message", "Login client \"" + client.clientId() + "\" removed.");
        }, () -> redirectAttributes.addFlashAttribute("error", "Login client not found."));
        return "redirect:/admin/login-clients";
    }

    /** At least one pattern, every pattern valid; returns the message for the first problem, or null. */
    private static String redirectProblem(String redirectUris) {
        List<String> patterns = RedirectPatterns.parse(redirectUris);
        if (patterns.isEmpty()) {
            return "Enter at least one redirect URI.";
        }
        for (String pattern : patterns) {
            String problem = RedirectPatterns.validate(pattern);
            if (problem != null) {
                return "Redirect URI \"" + pattern + "\" " + problem + ".";
            }
        }
        return null;
    }

    private static String fail(RedirectAttributes redirectAttributes, String target, String message) {
        redirectAttributes.addFlashAttribute("error", message);
        return "redirect:" + target;
    }
}
