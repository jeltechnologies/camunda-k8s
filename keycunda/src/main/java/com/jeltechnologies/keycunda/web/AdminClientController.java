package com.jeltechnologies.keycunda.web;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.jeltechnologies.keycunda.client.Client;
import com.jeltechnologies.keycunda.client.ClientRepository;
import com.jeltechnologies.keycunda.config.AuthorizationServerConfig;
import com.jeltechnologies.keycunda.config.KeycundaProperties;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Admin UI for user-managed OAuth2 client-credentials ("M2M") clients - what Camunda's own
 * Console calls "Clients" from 8.9 onward (formerly "M2M"; default terminology in 8.10). Distinct
 * from the fixed, code-defined Camunda-component clients - see OidcClientsConfig - which have no
 * admin UI on purpose. Assigning roles/authorizations to a client created here is done in Camunda
 * Identity, the same way it's done for users; this app only issues the client's identity/token,
 * it doesn't know about Camunda's authorization model.
 */
@Controller
public class AdminClientController {

    // Client IDs the fixed repository already owns (see OidcClientsConfig) - the composite
    // repository always resolves these to the fixed client first, so a same-named admin-managed
    // client would silently never be reachable. Reject it up front with a clear error instead.
    private static final Set<String> RESERVED_CLIENT_IDS =
            Set.of("camunda-identity", "orchestration", "optimize", "web-modeler", "console", "connectors");

    private static final Pattern CLIENT_ID_PATTERN = Pattern.compile("[a-zA-Z0-9._-]{1,255}");

    private final ClientRepository clientRepository;
    private final PasswordEncoder passwordEncoder;
    private final KeycundaProperties keycundaProperties;

    public AdminClientController(ClientRepository clientRepository, PasswordEncoder passwordEncoder,
            KeycundaProperties keycundaProperties) {
        this.clientRepository = clientRepository;
        this.passwordEncoder = passwordEncoder;
        this.keycundaProperties = keycundaProperties;
    }

    @GetMapping("/admin/clients")
    public String list(Model model) {
        model.addAttribute("clients", clientRepository.findAll());
        return "admin/clients";
    }

    @GetMapping("/admin/clients/new")
    public String newClientForm(Model model) {
        Map<String, String> knownAudiences = knownAudiences();
        model.addAttribute("knownAudiences", knownAudiences);
        // Pre-check every known audience - unchecking the ones a client doesn't need is one click,
        // versus having to know up front which of several components it must reach.
        model.addAttribute("selectedAudiences", new LinkedHashSet<>(knownAudiences.values()));
        model.addAttribute("customAudience", "");
        // Pre-filled so creating a client is a single click in the common case; still editable for
        // an admin who wants a specific ID.
        model.addAttribute("clientId", generateClientId());
        return "admin/add-client";
    }

    @PostMapping("/admin/clients")
    public String add(@RequestParam String clientId,
            @RequestParam(name = "knownAudiences", required = false) List<String> knownAudiences,
            @RequestParam(required = false) String customAudience, RedirectAttributes redirectAttributes) {
        if (RESERVED_CLIENT_IDS.contains(clientId) || !CLIENT_ID_PATTERN.matcher(clientId).matches()) {
            redirectAttributes.addFlashAttribute("error",
                    "Client ID \"" + clientId + "\" is reserved or invalid. Use letters, digits, \".\", \"_\" or \"-\".");
            return "redirect:/admin/clients/new";
        }
        String audience = combineAudiences(knownAudiences, customAudience);
        String secret = generateSecret();
        Client client;
        try {
            // No separate display name in this UI any more - the client ID doubles as the name.
            client = clientRepository.insert(clientId, clientId, secret, passwordEncoder.encode(secret), audience);
        } catch (DataIntegrityViolationException e) {
            redirectAttributes.addFlashAttribute("error", "A client with client ID \"" + clientId + "\" already exists.");
            return "redirect:/admin/clients/new";
        }
        redirectAttributes.addFlashAttribute("message", "Client \"" + clientId + "\" created.");
        // To the edit page, not the list: the secret is only ever shown there (see edit-client.html
        // and the clients list template), and the admin needs to see this one right after creating it.
        return "redirect:/admin/clients/" + client.id() + "/edit";
    }

    @GetMapping("/admin/clients/{id}/edit")
    public String editClientForm(@PathVariable UUID id, Model model, RedirectAttributes redirectAttributes) {
        return clientRepository.findById(id)
                .map(client -> {
                    Map<String, String> knownAudiences = knownAudiences();
                    model.addAttribute("client", client);
                    model.addAttribute("knownAudiences", knownAudiences);
                    model.addAttribute("selectedAudiences", selectedKnownAudiences(client.audience(), knownAudiences));
                    model.addAttribute("customAudience", customAudiencePart(client.audience(), knownAudiences));
                    return "admin/edit-client";
                })
                .orElseGet(() -> {
                    redirectAttributes.addFlashAttribute("error", "Client not found.");
                    return "redirect:/admin/clients";
                });
    }

    @PostMapping("/admin/clients/{id}/edit")
    public String edit(@PathVariable UUID id, @RequestParam String name,
            @RequestParam(name = "knownAudiences", required = false) List<String> knownAudiences,
            @RequestParam(required = false) String customAudience, RedirectAttributes redirectAttributes) {
        if (clientRepository.findById(id).isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Client not found.");
            return "redirect:/admin/clients";
        }
        clientRepository.updateNameAndAudience(id, name, combineAudiences(knownAudiences, customAudience));
        redirectAttributes.addFlashAttribute("message", "Client \"" + name + "\" updated.");
        return "redirect:/admin/clients";
    }

    @PostMapping("/admin/clients/{id}/regenerate-secret")
    public String regenerateSecret(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        Client client = clientRepository.findById(id).orElse(null);
        if (client == null) {
            redirectAttributes.addFlashAttribute("error", "Client not found.");
            return "redirect:/admin/clients";
        }
        String secret = generateSecret();
        clientRepository.updateSecret(id, secret, passwordEncoder.encode(secret));
        redirectAttributes.addFlashAttribute("message", "Secret regenerated for \"" + client.name() + "\".");
        // To the edit page, not the list: the new secret is only ever shown there.
        return "redirect:/admin/clients/" + id + "/edit";
    }

    @PostMapping("/admin/clients/{id}/delete")
    public String delete(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        clientRepository.findById(id).ifPresentOrElse(client -> {
            clientRepository.deleteById(id);
            redirectAttributes.addFlashAttribute("message", "Client \"" + client.name() + "\" removed.");
        }, () -> redirectAttributes.addFlashAttribute("error", "Client not found."));
        return "redirect:/admin/clients";
    }

    private static String generateSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String generateClientId() {
        return "client-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * The audiences every fixed, code-defined Camunda component (see OidcClientsConfig) is actually
     * configured with in this Camunda 8.9 install, offered as checkboxes so an admin-managed client
     * can be pointed at the same resource server without having to know/type the exact string. Read
     * from {@link KeycundaProperties} (and {@link AuthorizationServerConfig#CONSOLE_AUDIENCE}
     * for Console, which has no per-install config of its own) rather than hardcoded here, so this
     * list can never drift from what {@code AuthorizationServerConfig.audiencesFor} actually stamps
     * into the fixed clients' tokens. A future Camunda version's new audience won't appear here
     * automatically - that's what the freeform "custom" field next to these checkboxes is for.
     */
    private Map<String, String> knownAudiences() {
        KeycundaProperties.Clients clients = keycundaProperties.clients();
        // TreeMap, not LinkedHashMap: keeps the checkboxes sorted alphabetically by label.
        Map<String, String> options = new java.util.TreeMap<>();
        options.put("Camunda Identity", clients.identity().audience());
        options.put("Orchestration", clients.orchestration().audience());
        options.put("Optimize", clients.optimize().audience());
        options.put("Web Modeler (client API)", clients.webModeler().clientApiAudience());
        options.put("Web Modeler (public API)", clients.webModeler().publicApiAudience());
        options.put("Console", AuthorizationServerConfig.CONSOLE_AUDIENCE);
        return options;
    }

    /** Combines the checked known-audience values with whatever's typed into the custom field. */
    private static String combineAudiences(List<String> knownAudiences, String customAudience) {
        Set<String> all = new LinkedHashSet<>();
        if (knownAudiences != null) {
            all.addAll(knownAudiences);
        }
        if (customAudience != null) {
            for (String token : customAudience.trim().split("[,\\s]+")) {
                if (!token.isBlank()) {
                    all.add(token);
                }
            }
        }
        return String.join(", ", all);
    }

    /** Which of the known-audience checkboxes should come back checked for an already-stored value. */
    private static Set<String> selectedKnownAudiences(String storedAudience, Map<String, String> knownAudiences) {
        if (storedAudience == null || storedAudience.isBlank()) {
            return Set.of();
        }
        Set<String> known = new LinkedHashSet<>(knownAudiences.values());
        Set<String> selected = new LinkedHashSet<>();
        for (String token : storedAudience.trim().split("[,\\s]+")) {
            if (known.contains(token)) {
                selected.add(token);
            }
        }
        return selected;
    }

    /** Whatever's left of a stored audience value once the known checkboxes' values are removed. */
    private static String customAudiencePart(String storedAudience, Map<String, String> knownAudiences) {
        if (storedAudience == null || storedAudience.isBlank()) {
            return "";
        }
        Set<String> known = new LinkedHashSet<>(knownAudiences.values());
        List<String> custom = new ArrayList<>();
        for (String token : storedAudience.trim().split("[,\\s]+")) {
            if (!token.isBlank() && !known.contains(token)) {
                custom.add(token);
            }
        }
        return String.join(", ", custom);
    }
}
