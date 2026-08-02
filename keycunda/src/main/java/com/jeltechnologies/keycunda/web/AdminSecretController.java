package com.jeltechnologies.keycunda.web;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import com.jeltechnologies.keycunda.secret.ApplyJobStatus;
import com.jeltechnologies.keycunda.secret.ClusterSecretsApplier;
import com.jeltechnologies.keycunda.secret.Secret;
import com.jeltechnologies.keycunda.secret.SecretEnvCodec;
import com.jeltechnologies.keycunda.secret.SecretYamlCodec;

import jakarta.annotation.PreDestroy;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

/**
 * Admin UI for Secrets Management. The list page always reflects the live connectors Secret -
 * fetched fresh from Kubernetes on every load via {@link ClusterSecretsApplier}, never cached in
 * an HTTP session, a database, or anywhere else server-side (an earlier version staged edits in a
 * {@code @SessionScope} bean that could go stale indefinitely; see git history).
 *
 * <p>Add/edit/delete/import happen entirely client-side, in the browser: the page bootstraps a
 * plain JS object from the live snapshot (Thymeleaf JavaScript inlining, see {@code secrets.html}),
 * and every mutation re-renders that in-memory state without contacting the server or restarting
 * connectors - exactly like editing a spreadsheet before saving it. Nothing reaches Kubernetes
 * until "Apply to cluster" is clicked, which POSTs the *entire* current working set as JSON to
 * {@link #applyToCluster}; that's the only point where a write, a verification re-fetch, an
 * envFrom check and a connectors pod restart happen. Reloading the page afterward re-fetches live
 * from Kubernetes again, so what's on screen can never be stale for longer than the current,
 * unsaved browser tab.
 */
@Controller
public class AdminSecretController {

    // Deliberately stricter than a Kubernetes Secret key (which also allows "-"/".") - has to
    // double as a valid .env / shell variable name too, and SecretEnvCodec's import parser
    // specifically looks for this exact shape to detect where a new key starts. Mirrored in
    // secrets.html's client-side validation, but re-checked here too since applyToCluster takes
    // arbitrary JSON from the browser - never trust client-side validation alone.
    private static final Pattern SECRET_KEY_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    // The one Kubernetes Secret this page manages - fixed, not admin-configurable, to keep the
    // "what's the source of truth" story simple for a demo/learning tool: one managed connector
    // secret, named to match what a connectors Deployment migrating from the old
    // update-connector-secrets.sh script already has wired into its envFrom - see
    // SecretYamlCodec.DEFAULT_SECRET_NAME for why.
    private static final String MANAGED_SECRET_NAME = SecretYamlCodec.DEFAULT_SECRET_NAME;

    private final SecretYamlCodec secretYamlCodec;
    private final SecretEnvCodec secretEnvCodec;
    private final ClusterSecretsApplier clusterSecretsApplier;
    private final ApplyJobStatus applyJobStatus;
    // Single-threaded: applies mutate shared cluster state (the connectors Deployment/pod) -
    // serializing them avoids two "Apply to cluster" clicks racing each other.
    private final ExecutorService applyExecutor = Executors.newSingleThreadExecutor();

    public AdminSecretController(SecretYamlCodec secretYamlCodec, SecretEnvCodec secretEnvCodec,
            ClusterSecretsApplier clusterSecretsApplier, ApplyJobStatus applyJobStatus) {
        this.secretYamlCodec = secretYamlCodec;
        this.secretEnvCodec = secretEnvCodec;
        this.clusterSecretsApplier = clusterSecretsApplier;
        this.applyJobStatus = applyJobStatus;
    }

    @PreDestroy
    void shutdown() {
        applyExecutor.shutdownNow();
    }

    @GetMapping("/admin/secrets")
    public String list(Model model) {
        Map<String, String> current = new TreeMap<>(clusterSecretsApplier.fetch(MANAGED_SECRET_NAME));
        model.addAttribute("secrets", toSortedList(current));
        model.addAttribute("secretsMap", current);
        model.addAttribute("defaultSecretName", MANAGED_SECRET_NAME);
        return "admin/secrets";
    }

    @GetMapping("/admin/secrets/export.yaml")
    public ResponseEntity<String> exportYaml(
            @RequestParam(defaultValue = SecretYamlCodec.DEFAULT_SECRET_NAME) String secretName) {
        String yaml = secretYamlCodec.export(toSortedList(clusterSecretsApplier.fetch(MANAGED_SECRET_NAME)), secretName);
        return download(yaml, "connector-secrets.yaml", "application/x-yaml");
    }

    @GetMapping("/admin/secrets/export.env")
    public ResponseEntity<String> exportEnv() {
        String env = secretEnvCodec.export(toSortedList(clusterSecretsApplier.fetch(MANAGED_SECRET_NAME)));
        return download(env, "secrets.env", "text/plain");
    }

    /** Parses an uploaded .yaml/.yml/.env file and hands the entries back as JSON - it never
     * touches Kubernetes. The browser merges the result into its in-memory working set. */
    @PostMapping(value = "/admin/secrets/import", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, String>> importFile(@RequestParam MultipartFile file) {
        if (file.isEmpty()) {
            return errorBody("Choose a .yaml or .env file to import.");
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        try {
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            if (filename.endsWith(".yaml") || filename.endsWith(".yml")) {
                return ResponseEntity.ok(secretYamlCodec.parse(content));
            } else if (filename.endsWith(".env")) {
                return ResponseEntity.ok(secretEnvCodec.parse(content));
            }
            return errorBody("Unrecognized file type for \"" + filename + "\" - expected .yaml, .yml or .env.");
        } catch (Exception e) {
            return errorBody("Could not import \"" + filename + "\": " + e.getMessage());
        }
    }

    /** Same as {@link #importFile}, but for text pasted directly into the page rather than
     * uploaded as a file - auto-detects YAML vs .env shape since pasted text carries no filename
     * to key off of. */
    @PostMapping(value = "/admin/secrets/import-text", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, String>> importPastedText(@RequestParam String pastedText) {
        if (!StringUtils.hasText(pastedText)) {
            return errorBody("Paste some YAML or .env content to import.");
        }
        try {
            return ResponseEntity.ok(parsePastedSecrets(pastedText));
        } catch (Exception e) {
            return errorBody("Could not parse the pasted text as a Kubernetes Secret manifest or a .env file.");
        }
    }

    // Tries the Kubernetes Secret YAML shape first (secretYamlCodec.parse throws if the content
    // doesn't have that shape or has no data/stringData entries), then falls back to .env - the
    // same auto-detection a human would do by eye.
    private Map<String, String> parsePastedSecrets(String text) {
        try {
            return secretYamlCodec.parse(text);
        } catch (RuntimeException notYaml) {
            Map<String, String> entries = secretEnvCodec.parse(text);
            if (entries.isEmpty()) {
                throw new IllegalArgumentException("Recognized neither a Kubernetes Secret manifest nor .env content.");
            }
            return entries;
        }
    }

    private static ResponseEntity<Map<String, String>> errorBody(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", message));
    }

    /** The only endpoint that writes to Kubernetes: takes the browser's entire current working
     * set and submits it wholesale on a background thread - writing, verifying, wiring up envFrom
     * and restarting the connectors pod can take up to two minutes. The browser navigates to the
     * polling status page itself once this returns. */
    @PostMapping(value = "/admin/secrets/apply-to-cluster", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> applyToCluster(@RequestBody Map<String, String> desired) {
        for (String key : desired.keySet()) {
            if (!SECRET_KEY_PATTERN.matcher(key).matches()) {
                return ResponseEntity.badRequest().body(
                        "Key \"" + key + "\" is invalid. Use letters, digits or \"_\", and don't start with a digit.");
            }
        }
        applyJobStatus.markRunning();
        List<Secret> secrets = toSortedList(desired);
        applyExecutor.submit(() -> {
            try {
                clusterSecretsApplier.apply(secrets, MANAGED_SECRET_NAME);
                applyJobStatus.markSuccess("Secrets applied, verified, and the connectors pod restarted successfully.");
            } catch (Exception e) {
                applyJobStatus.markError("Failed to apply secrets to the cluster: " + e.getMessage());
            }
        });
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/admin/secrets/apply-status")
    public String applyStatus(Model model) {
        model.addAttribute("job", applyJobStatus.current());
        return "admin/apply-status";
    }

    private static List<Secret> toSortedList(Map<String, String> map) {
        return new TreeMap<>(map).entrySet().stream().map(e -> new Secret(e.getKey(), e.getValue())).toList();
    }

    private static ResponseEntity<String> download(String content, String filename, String contentType) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType + "; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(content);
    }
}
