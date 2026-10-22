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

import com.jeltechnologies.keycunda.catalog.HubTokenProvider;
import com.jeltechnologies.keycunda.client.Client;
import com.jeltechnologies.keycunda.client.ClientRepository;
import com.jeltechnologies.keycunda.config.AuthorizationServerConfig;
import com.jeltechnologies.keycunda.config.KeycundaProperties;
import com.jeltechnologies.keycunda.identity.HubApiAccessGrantor;

import org.springframework.beans.factory.annotation.Value;
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
            Set.of("camunda-identity", "orchestration", "optimize", "web-modeler", "console", "connectors",
                    HubTokenProvider.CLIENT_ID);

    private static final Pattern CLIENT_ID_PATTERN = Pattern.compile("[a-zA-Z0-9._-]{1,255}");

    // A client secret can now be set to an admin-chosen value (not just the generated one), so it
    // needs its own validation: no whitespace (it goes into an Authorization header / token request
    // body and the "c8 add profile" shell snippet) and long enough not to be trivially guessable.
    // generateSecret() produces a 43-char base64url string, comfortably inside this.
    private static final Pattern SECRET_PATTERN = Pattern.compile("\\S{12,512}");
    private static final String SECRET_RULE_MESSAGE =
            "Secret must be 12-512 characters with no spaces.";

    private final ClientRepository clientRepository;
    private final PasswordEncoder passwordEncoder;
    private final KeycundaProperties keycundaProperties;
    private final HubApiAccessGrantor hubApiAccessGrantor;
    private final String camundaLine;

    public AdminClientController(ClientRepository clientRepository, PasswordEncoder passwordEncoder,
            KeycundaProperties keycundaProperties, HubApiAccessGrantor hubApiAccessGrantor,
            @Value("${keycunda.camunda-line:8.10}") String camundaLine) {
        this.clientRepository = clientRepository;
        this.passwordEncoder = passwordEncoder;
        this.keycundaProperties = keycundaProperties;
        this.hubApiAccessGrantor = hubApiAccessGrantor;
        this.camundaLine = camundaLine;
    }

    @GetMapping("/admin/clients")
    public String list(Model model) {
        // The internal Catalog client (see HubTokenProvider) is not for admins to edit or delete.
        model.addAttribute("clients", clientRepository.findAll().stream()
                .filter(c -> !HubTokenProvider.CLIENT_ID.equals(c.clientId())).toList());
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
        model.addAttribute("clientId", "");
        // Pre-fills the (editable) secret field with a generated value, so it's copyable before the
        // client is even created - the admin can also type their own or hit "Generate new". Whatever
        // is in the field at submit time is what gets persisted.
        model.addAttribute("secret", generateSecret());
        addC8ctlConfigAttributes(model);
        return "admin/add-client";
    }

    @PostMapping("/admin/clients")
    public String add(@RequestParam String clientId, @RequestParam String secret,
            @RequestParam(name = "knownAudiences", required = false) List<String> knownAudiences,
            @RequestParam(required = false) String customAudience, RedirectAttributes redirectAttributes) {
        if (RESERVED_CLIENT_IDS.contains(clientId) || !CLIENT_ID_PATTERN.matcher(clientId).matches()) {
            redirectAttributes.addFlashAttribute("error",
                    "Client ID \"" + clientId + "\" is reserved or invalid. Use letters, digits, \".\", \"_\" or \"-\".");
            return "redirect:/admin/clients/new";
        }
        if (!SECRET_PATTERN.matcher(secret == null ? "" : secret).matches()) {
            redirectAttributes.addFlashAttribute("error", SECRET_RULE_MESSAGE);
            return "redirect:/admin/clients/new";
        }
        String audience = combineAudiences(knownAudiences, customAudience);
        try {
            // No separate display name in this UI any more - the client ID doubles as the name.
            clientRepository.insert(clientId, clientId, secret, passwordEncoder.encode(secret), audience);
        } catch (DataIntegrityViolationException e) {
            redirectAttributes.addFlashAttribute("error", "A client with client ID \"" + clientId + "\" already exists.");
            return "redirect:/admin/clients/new";
        }
        redirectAttributes.addFlashAttribute("message", "Client \"" + clientId + "\" created.");
        syncHubApiAccess(clientId, audience, redirectAttributes);
        // Back to the list, not the edit page: the secret was already shown - and copyable - on the
        // add page itself before this submit, and it stays visible on the edit page for as long as
        // the client exists (see edit-client.html), so there's no need to detour through it here.
        return "redirect:/admin/clients";
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
                    addC8ctlConfigAttributes(model);
                    return "admin/edit-client";
                })
                .orElseGet(() -> {
                    redirectAttributes.addFlashAttribute("error", "Client not found.");
                    return "redirect:/admin/clients";
                });
    }

    @PostMapping("/admin/clients/{id}/edit")
    public String edit(@PathVariable UUID id,
            @RequestParam(name = "knownAudiences", required = false) List<String> knownAudiences,
            @RequestParam(required = false) String customAudience,
            @RequestParam(required = false) String secret, RedirectAttributes redirectAttributes) {
        Client client = clientRepository.findById(id).orElse(null);
        if (client == null || HubTokenProvider.CLIENT_ID.equals(client.clientId())) {
            redirectAttributes.addFlashAttribute("error", "Client not found.");
            return "redirect:/admin/clients";
        }
        // The secret field on the edit page is pre-filled with the current value, so a submit that
        // leaves it unchanged (or blank) must not touch it - only persist a genuine, valid change.
        boolean changeSecret = secret != null && !secret.isBlank() && !secret.equals(client.secret());
        if (changeSecret && !SECRET_PATTERN.matcher(secret).matches()) {
            redirectAttributes.addFlashAttribute("error", SECRET_RULE_MESSAGE);
            return "redirect:/admin/clients/" + id + "/edit";
        }
        String audience = combineAudiences(knownAudiences, customAudience);
        clientRepository.updateAudience(id, audience);
        if (changeSecret) {
            clientRepository.updateSecret(id, secret, passwordEncoder.encode(secret));
        }
        redirectAttributes.addFlashAttribute("message", "Client \"" + client.name() + "\" updated"
                + (changeSecret ? " (secret replaced - the old one no longer works)." : "."));
        syncHubApiAccess(client.clientId(), audience, redirectAttributes);
        return "redirect:/admin/clients";
    }

    @PostMapping("/admin/clients/{id}/delete")
    public String delete(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        clientRepository.findById(id).filter(c -> !HubTokenProvider.CLIENT_ID.equals(c.clientId())).ifPresentOrElse(client -> {
            clientRepository.deleteById(id);
            redirectAttributes.addFlashAttribute("message", "Client \"" + client.name() + "\" removed.");
            syncHubApiAccess(client.clientId(), null, redirectAttributes);
        }, () -> redirectAttributes.addFlashAttribute("error", "Client not found."));
        return "redirect:/admin/clients";
    }

    /**
     * Keeps Identity's Hub API grant for the client in step with its audience (see
     * {@link HubApiAccessGrantor}). The client is saved either way; a failure only adds a warning.
     */
    private void syncHubApiAccess(String clientId, String audience, RedirectAttributes redirectAttributes) {
        String problem = hubApiAccessGrantor.sync(clientId, audience);
        if (problem != null) {
            redirectAttributes.addFlashAttribute("warning", problem);
        } else if (hubApiAccessGrantor.isConfigured() && hubApiAccessGrantor.grantsHubApi(audience)) {
            redirectAttributes.addFlashAttribute("message", "Client \"" + clientId
                    + "\" saved and granted access to the Camunda Hub API in Identity.");
        }
    }

    /**
     * The base/OAuth URLs c8ctl (https://github.com/camunda/c8ctl) needs to reach this
     * installation's Orchestration REST API and Keycunda's token endpoint - shown, pre-filled, in
     * the "c8 add profile" snippet on the add/edit client pages. Same shape as this app's own
     * {@link KeycundaProperties#publicIssuer()} - "/orchestration" is Orchestration's
     * {@code contextPath} in the Helm values, "/auth" is Keycunda's own fixed OIDC path.
     */
    private void addC8ctlConfigAttributes(Model model) {
        String domain = keycundaProperties.camundaDomain();
        model.addAttribute("c8ctlBaseUrl", "https://" + domain + "/orchestration");
        model.addAttribute("c8ctlOAuthUrl", "https://" + domain + "/auth/oauth2/token");
    }

    private static String generateSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }


    /**
     * The audiences every fixed, code-defined Camunda component (see OidcClientsConfig) is actually
     * configured with in this install (8.10 by default, 8.9 when {@code keycunda.camunda-line=8.9}),
     * offered as checkboxes so an admin-managed client
     * can be pointed at the same resource server without having to know/type the exact string. Read
     * from {@link KeycundaProperties} (and {@link AuthorizationServerConfig#CONSOLE_AUDIENCE}
     * for 8.9's Console, which has no per-install config of its own) rather than hardcoded here, so this
     * list can never drift from what {@code AuthorizationServerConfig.audiencesFor} actually stamps
     * into the fixed clients' tokens. A future Camunda version's new audience won't appear here
     * automatically - that's what the freeform "custom" field next to these checkboxes is for.
     *
     * <p>8.10 differs from 8.9 in two ways: Camunda Hub replaces both Web Modeler and Console (the
     * chart has no Console client or {@code console-api} audience any more), and the two Web Modeler
     * audiences keep their names ({@code web-modeler-api}, {@code web-modeler-public-api}) but are
     * now Hub's "internal" and public API. The 8.10 list therefore drops Console and relabels those
     * two; 8.9 installs keep the old list unchanged.
     */
    private Map<String, String> knownAudiences() {
        KeycundaProperties.Clients clients = keycundaProperties.clients();
        // TreeMap, not LinkedHashMap: keeps the checkboxes sorted alphabetically by label.
        Map<String, String> options = new java.util.TreeMap<>();
        options.put("Camunda Identity", clients.identity().audience());
        options.put("Orchestration", clients.orchestration().audience());
        options.put("Optimize", clients.optimize().audience());
        if ("8.9".equals(camundaLine)) {
            options.put("Web Modeler (client API)", clients.webModeler().clientApiAudience());
            options.put("Web Modeler (public API)", clients.webModeler().publicApiAudience());
            options.put("Console", AuthorizationServerConfig.CONSOLE_AUDIENCE);
        } else {
            options.put("Camunda Hub (internal API)", clients.webModeler().clientApiAudience());
            options.put("Camunda Hub API", clients.webModeler().publicApiAudience());
        }
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
