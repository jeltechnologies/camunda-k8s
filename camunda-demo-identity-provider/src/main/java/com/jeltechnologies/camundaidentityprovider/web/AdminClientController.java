package com.jeltechnologies.camundaidentityprovider.web;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.jeltechnologies.camundaidentityprovider.client.Client;
import com.jeltechnologies.camundaidentityprovider.client.ClientRepository;
import com.jeltechnologies.camundaidentityprovider.config.IdentityProviderProperties;

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
    private final IdentityProviderProperties identityProviderProperties;

    public AdminClientController(ClientRepository clientRepository, PasswordEncoder passwordEncoder,
            IdentityProviderProperties identityProviderProperties) {
        this.clientRepository = clientRepository;
        this.passwordEncoder = passwordEncoder;
        this.identityProviderProperties = identityProviderProperties;
    }

    @GetMapping("/admin/clients")
    public String list(Model model) {
        model.addAttribute("clients", clientRepository.findAll());
        model.addAttribute("consoleUrl", "https://" + identityProviderProperties.camundaDomain() + "/console");
        return "admin/clients";
    }

    @GetMapping("/admin/clients/new")
    public String newClientForm() {
        return "admin/add-client";
    }

    @PostMapping("/admin/clients")
    public String add(@RequestParam String clientId, @RequestParam String name,
            @RequestParam String audience, RedirectAttributes redirectAttributes) {
        if (RESERVED_CLIENT_IDS.contains(clientId) || !CLIENT_ID_PATTERN.matcher(clientId).matches()) {
            redirectAttributes.addFlashAttribute("error",
                    "Client ID \"" + clientId + "\" is reserved or invalid. Use letters, digits, \".\", \"_\" or \"-\".");
            return "redirect:/admin/clients/new";
        }
        String secret = generateSecret();
        Client client;
        try {
            client = clientRepository.insert(clientId, name, secret, passwordEncoder.encode(secret), audience);
        } catch (DataIntegrityViolationException e) {
            redirectAttributes.addFlashAttribute("error", "A client with client ID \"" + clientId + "\" already exists.");
            return "redirect:/admin/clients/new";
        }
        redirectAttributes.addFlashAttribute("message", "Client \"" + name + "\" created.");
        return "redirect:/admin/clients/" + client.id() + "/edit";
    }

    @GetMapping("/admin/clients/{id}/edit")
    public String editClientForm(@PathVariable UUID id, Model model, RedirectAttributes redirectAttributes) {
        return clientRepository.findById(id)
                .map(client -> {
                    model.addAttribute("client", client);
                    return "admin/edit-client";
                })
                .orElseGet(() -> {
                    redirectAttributes.addFlashAttribute("error", "Client not found.");
                    return "redirect:/admin/clients";
                });
    }

    @PostMapping("/admin/clients/{id}/edit")
    public String edit(@PathVariable UUID id, @RequestParam String name, @RequestParam String audience,
            RedirectAttributes redirectAttributes) {
        if (clientRepository.findById(id).isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Client not found.");
            return "redirect:/admin/clients";
        }
        clientRepository.updateNameAndAudience(id, name, audience);
        redirectAttributes.addFlashAttribute("message", "Client \"" + name + "\" updated.");
        return "redirect:/admin/clients/" + id + "/edit";
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
}
