package com.jeltechnologies.keycunda.secret;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Exports/imports secrets as a Kubernetes {@code kind: Secret} manifest - the same shape
 * {@code update-connector-secrets.sh} already expects in {@code connector-secrets.yaml} (it reads
 * {@code metadata.name} and any {@code data}/{@code stringData} keys). Built with SnakeYAML rather
 * than string concatenation specifically because secret values can contain quotes, colons and raw
 * newlines (e.g. a multi-line GCP service-account JSON key) that hand-rolled YAML would get wrong;
 * SnakeYAML picks the correct scalar style (quoted/block) per value automatically.
 */
@Component
public class SecretYamlCodec {

    /**
     * {@code update-connector-secrets.sh} only ever fell back to "camunda-connector-secrets" when
     * a {@code connector-secrets.yaml} had no parseable {@code metadata.name} - in practice every
     * real file had one, matching the script's own {@code SECRETS_FILE} convention, i.e. literally
     * "connector-secrets". Defaulting to the fallback name instead of the name real installs
     * actually ended up with meant a fresh Keycunda install created/managed a *different* Secret
     * than the one already wired into the connectors Deployment's {@code envFrom} - the connectors
     * pod kept reading the old "connector-secrets" while this UI edited a new, disconnected
     * "camunda-connector-secrets" that nothing consumed. Found by an admin migrating from the old
     * script whose connector kept reporting missing env vars despite the values being visibly
     * present in what Keycunda showed as applied.
     */
    public static final String DEFAULT_SECRET_NAME = "connector-secrets";

    private final Yaml yaml;

    public SecretYamlCodec() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        this.yaml = new Yaml(options);
    }

    public String export(List<Secret> secrets, String secretName) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", secretName);

        Map<String, Object> stringData = new TreeMap<>();
        for (Secret secret : secrets) {
            stringData.put(secret.key(), secret.value());
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("apiVersion", "v1");
        manifest.put("kind", "Secret");
        manifest.put("metadata", metadata);
        manifest.put("type", "Opaque");
        manifest.put("stringData", stringData);
        return yaml.dump(manifest);
    }

    /** Reads back both {@code stringData} (as-is) and {@code data} (base64-decoded) entries. */
    @SuppressWarnings("unchecked")
    public Map<String, String> parse(String manifestYaml) {
        Object loaded = yaml.load(manifestYaml);
        if (!(loaded instanceof Map)) {
            throw new IllegalArgumentException("Not a valid Kubernetes Secret manifest.");
        }
        Map<String, Object> manifest = (Map<String, Object>) loaded;
        Map<String, String> result = new LinkedHashMap<>();

        Object stringData = manifest.get("stringData");
        if (stringData instanceof Map) {
            ((Map<String, Object>) stringData).forEach((key, value) -> result.put(key, String.valueOf(value)));
        }

        Object data = manifest.get("data");
        if (data instanceof Map) {
            ((Map<String, Object>) data).forEach((key, value) -> {
                byte[] decoded = Base64.getDecoder().decode(String.valueOf(value));
                result.put(key, new String(decoded, java.nio.charset.StandardCharsets.UTF_8));
            });
        }

        if (result.isEmpty()) {
            throw new IllegalArgumentException("Manifest has no \"data\" or \"stringData\" entries.");
        }
        return result;
    }
}
