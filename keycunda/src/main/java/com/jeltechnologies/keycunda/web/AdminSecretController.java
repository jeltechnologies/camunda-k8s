package com.jeltechnologies.keycunda.web;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import com.jeltechnologies.keycunda.secret.ApplyJobStatus;
import com.jeltechnologies.keycunda.secret.ClusterSecretsApplier;
import com.jeltechnologies.keycunda.secret.DuplicateSecretKeyException;
import com.jeltechnologies.keycunda.secret.SecretEnvCodec;
import com.jeltechnologies.keycunda.secret.SecretYamlCodec;
import com.jeltechnologies.keycunda.secret.SecretsWorkingCopy;

import jakarta.annotation.PreDestroy;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Admin UI for Secrets Management. Kubernetes is the source of truth here,
 * not a local database: {@link SecretsWorkingCopy} (session-scoped) holds an in-memory working
 * copy, fetched from the live connectors Secret the first time an admin visits in a browser
 * session (see {@link #ensureLoaded()}), edited freely from there (add/edit/delete/import all
 * mutate only that in-memory copy), and pushed back only when "Apply to cluster" is clicked - the
 * connectors pod only ever picks up a change after a restart anyway, so writing every micro-edit
 * straight through would accomplish nothing except extra Kubernetes API calls.
 */
@Controller
public class AdminSecretController {

    // Deliberately stricter than a Kubernetes Secret key (which also allows "-"/".") - has to
    // double as a valid .env / shell variable name too, and SecretEnvCodec's import parser
    // specifically looks for this exact shape to detect where a new key starts.
    private static final Pattern SECRET_KEY_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    // The one Kubernetes Secret this working copy manages - fixed, not admin-configurable, to
    // keep the "what's the source of truth" story simple for a demo/learning tool: one managed
    // connector secret, named to match what a connectors Deployment migrating from the old
    // update-connector-secrets.sh script already has wired into its envFrom - see
    // SecretYamlCodec.DEFAULT_SECRET_NAME for why that's "connector-secrets", not
    // "camunda-connector-secrets".
    private static final String MANAGED_SECRET_NAME = SecretYamlCodec.DEFAULT_SECRET_NAME;

    private final SecretsWorkingCopy secretsWorkingCopy;
    private final SecretYamlCodec secretYamlCodec;
    private final SecretEnvCodec secretEnvCodec;
    private final ClusterSecretsApplier clusterSecretsApplier;
    private final ApplyJobStatus applyJobStatus;
    // Single-threaded: apply-to-cluster runs are rare, admin-triggered, and mutate shared cluster
    // state (the connectors Deployment/pod) - serializing them avoids two runs racing each other.
    private final ExecutorService applyExecutor = Executors.newSingleThreadExecutor();

    public AdminSecretController(SecretsWorkingCopy secretsWorkingCopy, SecretYamlCodec secretYamlCodec,
            SecretEnvCodec secretEnvCodec, ClusterSecretsApplier clusterSecretsApplier, ApplyJobStatus applyJobStatus) {
        this.secretsWorkingCopy = secretsWorkingCopy;
        this.secretYamlCodec = secretYamlCodec;
        this.secretEnvCodec = secretEnvCodec;
        this.clusterSecretsApplier = clusterSecretsApplier;
        this.applyJobStatus = applyJobStatus;
    }

    @PreDestroy
    void shutdown() {
        applyExecutor.shutdownNow();
    }

    /** Seeds the working copy from the live Kubernetes Secret the first time this session visits
     * any Secrets page - after that, the session's in-progress edits are left alone until an
     * apply resyncs them to the verified live state. */
    private void ensureLoaded() {
        if (!secretsWorkingCopy.isLoaded()) {
            secretsWorkingCopy.load(clusterSecretsApplier.fetch(MANAGED_SECRET_NAME));
        }
    }

    @GetMapping("/admin/secrets")
    public String list(Model model) {
        ensureLoaded();
        model.addAttribute("secrets", secretsWorkingCopy.list());
        model.addAttribute("defaultSecretName", MANAGED_SECRET_NAME);
        return "admin/secrets";
    }

    @GetMapping("/admin/secrets/new")
    public String newSecretForm(Model model) {
        model.addAttribute("key", "");
        model.addAttribute("value", "");
        return "admin/add-secret";
    }

    @PostMapping("/admin/secrets")
    public String add(@RequestParam String key, @RequestParam String value, RedirectAttributes redirectAttributes) {
        ensureLoaded();
        if (!SECRET_KEY_PATTERN.matcher(key).matches()) {
            redirectAttributes.addFlashAttribute("error",
                    "Key \"" + key + "\" is invalid. Use letters, digits or \"_\", and don't start with a digit.");
            return "redirect:/admin/secrets/new";
        }
        try {
            secretsWorkingCopy.add(key, value);
        } catch (DuplicateSecretKeyException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/secrets/new";
        }
        redirectAttributes.addFlashAttribute("message", "Secret \"" + key + "\" added (not yet applied to the cluster).");
        return "redirect:/admin/secrets";
    }

    @GetMapping("/admin/secrets/{key}/edit")
    public String editSecretForm(@PathVariable String key, Model model, RedirectAttributes redirectAttributes) {
        ensureLoaded();
        return secretsWorkingCopy.find(key)
                .map(secret -> {
                    model.addAttribute("secret", secret);
                    return "admin/edit-secret";
                })
                .orElseGet(() -> {
                    redirectAttributes.addFlashAttribute("error", "Secret not found.");
                    return "redirect:/admin/secrets";
                });
    }

    @PostMapping("/admin/secrets/{key}/edit")
    public String edit(@PathVariable String key, @RequestParam String newKey, @RequestParam String value,
            RedirectAttributes redirectAttributes) {
        ensureLoaded();
        if (secretsWorkingCopy.find(key).isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Secret not found.");
            return "redirect:/admin/secrets";
        }
        if (!SECRET_KEY_PATTERN.matcher(newKey).matches()) {
            redirectAttributes.addFlashAttribute("error",
                    "Key \"" + newKey + "\" is invalid. Use letters, digits or \"_\", and don't start with a digit.");
            return "redirect:/admin/secrets/" + key + "/edit";
        }
        try {
            secretsWorkingCopy.rename(key, newKey, value);
        } catch (DuplicateSecretKeyException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/secrets/" + key + "/edit";
        }
        redirectAttributes.addFlashAttribute("message", "Secret \"" + newKey + "\" updated (not yet applied to the cluster).");
        return "redirect:/admin/secrets";
    }

    @PostMapping("/admin/secrets/{key}/delete")
    public String delete(@PathVariable String key, RedirectAttributes redirectAttributes) {
        ensureLoaded();
        if (secretsWorkingCopy.find(key).isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Secret not found.");
        } else {
            secretsWorkingCopy.delete(key);
            redirectAttributes.addFlashAttribute("message", "Secret \"" + key + "\" removed (not yet applied to the cluster).");
        }
        return "redirect:/admin/secrets";
    }

    @GetMapping("/admin/secrets/export.yaml")
    public ResponseEntity<String> exportYaml(
            @RequestParam(defaultValue = SecretYamlCodec.DEFAULT_SECRET_NAME) String secretName) {
        ensureLoaded();
        String yaml = secretYamlCodec.export(secretsWorkingCopy.list(), secretName);
        return download(yaml, "connector-secrets.yaml", "application/x-yaml");
    }

    @GetMapping("/admin/secrets/export.env")
    public ResponseEntity<String> exportEnv() {
        ensureLoaded();
        String env = secretEnvCodec.export(secretsWorkingCopy.list());
        return download(env, "secrets.env", "text/plain");
    }

    @GetMapping("/admin/secrets/import")
    public String importForm() {
        return "admin/import-secrets";
    }

    @PostMapping("/admin/secrets/import")
    public String importSecrets(@RequestParam MultipartFile file, RedirectAttributes redirectAttributes) {
        ensureLoaded();
        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Choose a .yaml or .env file to import.");
            return "redirect:/admin/secrets/import";
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        try {
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            Map<String, String> entries;
            if (filename.endsWith(".yaml") || filename.endsWith(".yml")) {
                entries = secretYamlCodec.parse(content);
            } else if (filename.endsWith(".env")) {
                entries = secretEnvCodec.parse(content);
            } else {
                redirectAttributes.addFlashAttribute("error",
                        "Unrecognized file type for \"" + filename + "\" - expected .yaml, .yml or .env.");
                return "redirect:/admin/secrets/import";
            }
            long alreadyPresent = entries.keySet().stream().filter(key -> secretsWorkingCopy.find(key).isPresent()).count();
            entries.forEach(secretsWorkingCopy::upsert);
            long added = entries.size() - alreadyPresent;
            redirectAttributes.addFlashAttribute("message",
                    added + " secret(s) added, " + alreadyPresent + " updated (not yet applied to the cluster).");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Could not import \"" + filename + "\": " + e.getMessage());
            return "redirect:/admin/secrets/import";
        }
        return "redirect:/admin/secrets";
    }

    @PostMapping("/admin/secrets/import-text")
    public String importPastedText(@RequestParam String pastedText, RedirectAttributes redirectAttributes) {
        ensureLoaded();
        if (!StringUtils.hasText(pastedText)) {
            redirectAttributes.addFlashAttribute("error", "Paste some YAML or .env content to import.");
            return "redirect:/admin/secrets/import";
        }
        try {
            Map<String, String> entries = parsePastedSecrets(pastedText);
            long alreadyPresent = entries.keySet().stream().filter(key -> secretsWorkingCopy.find(key).isPresent()).count();
            entries.forEach(secretsWorkingCopy::upsert);
            long added = entries.size() - alreadyPresent;
            redirectAttributes.addFlashAttribute("message",
                    added + " secret(s) added, " + alreadyPresent + " updated (not yet applied to the cluster).");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error",
                    "Could not parse the pasted text as a Kubernetes Secret manifest or a .env file.");
            return "redirect:/admin/secrets/import";
        }
        return "redirect:/admin/secrets";
    }

    // Tries the Kubernetes Secret YAML shape first (secretYamlCodec.parse throws if the content
    // doesn't have that shape or has no data/stringData entries), then falls back to .env - the
    // same auto-detection a human would do by eye, since pasted text carries no filename/extension
    // to key off of the way the file-upload import above does.
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

    @PostMapping("/admin/secrets/apply-to-cluster")
    public String applyToCluster() {
        ensureLoaded();
        applyJobStatus.markRunning();
        List<com.jeltechnologies.keycunda.secret.Secret> secrets = secretsWorkingCopy.list();
        // Runs on a background thread with no HTTP session bound to it, so it must not touch
        // secretsWorkingCopy (session-scoped) directly - only ApplyJobStatus (a plain singleton).
        // Resyncing the working copy happens in applyStatus() below, on a real request thread.
        applyExecutor.submit(() -> {
            try {
                Map<String, String> verified = clusterSecretsApplier.apply(secrets, MANAGED_SECRET_NAME);
                applyJobStatus.markSuccess("Secret \"" + MANAGED_SECRET_NAME
                        + "\" applied, verified, and the connectors pod restarted successfully.", verified);
            } catch (Exception e) {
                applyJobStatus.markError("Failed to apply secrets to the cluster: " + e.getMessage());
            }
        });
        return "redirect:/admin/secrets/apply-status";
    }

    @GetMapping("/admin/secrets/apply-status")
    public String applyStatus(Model model) {
        ApplyJobStatus.Job job = applyJobStatus.current();
        if (job != null && job.state() == ApplyJobStatus.State.SUCCESS && job.verifiedSecrets() != null) {
            secretsWorkingCopy.load(job.verifiedSecrets());
        }
        model.addAttribute("job", job);
        return "admin/apply-status";
    }

    private static ResponseEntity<String> download(String content, String filename, String contentType) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType + "; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(content);
    }
}
