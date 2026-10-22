package com.jeltechnologies.keycunda.catalog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The standard Camunda connector templates, taken from the latest release of
 * {@code camunda/connectors} on GitHub (its single {@code connectors-bundle-templates-*.zip} asset).
 *
 * <p>The downloaded ZIP is only kept in memory, for {@link #CACHE_TTL}: GitHub's unauthenticated API
 * allows 60 requests per hour, and the Catalog page, the plan check and the publish itself would
 * otherwise each download it again. Nothing is written to disk.
 *
 * <p>Only templates that suit a self-managed Hub are kept: valid element templates (an object with
 * {@code id}, integer {@code version}, {@code appliesTo} and {@code properties}), not deprecated, not
 * "hybrid" (those target SaaS-hosted runtimes), not requiring a newer Camunda than the target release,
 * and only the highest version of each template id.
 */
@Component
public class ConnectorTemplateSource {

    private static final Logger log = LoggerFactory.getLogger(ConnectorTemplateSource.class);

    static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final Pattern MAJOR_MINOR = Pattern.compile("(\\d+)\\.(\\d+)");

    /** The templates of one connectors release. */
    public record Snapshot(String release, List<ConnectorTemplate> templates) {
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String releaseUrl;
    private final String targetRelease;
    private final Clock clock;

    private Snapshot cached;
    private Instant cachedAt;

    @Autowired
    public ConnectorTemplateSource(
            @Value("${keycunda.catalog.release-url:https://api.github.com/repos/camunda/connectors/releases/latest}") String releaseUrl,
            @Value("${keycunda.camunda-line:8.10}") String targetRelease) {
        this(releaseUrl, targetRelease, Clock.systemUTC());
    }

    ConnectorTemplateSource(String releaseUrl, String targetRelease, Clock clock) {
        this.releaseUrl = releaseUrl;
        this.targetRelease = targetRelease;
        this.clock = clock;
    }

    /** The templates of the latest release, from the in-memory cache when it is younger than ten minutes. */
    public synchronized Snapshot snapshot() throws IOException {
        if (cached != null && Duration.between(cachedAt, clock.instant()).compareTo(CACHE_TTL) < 0) {
            return cached;
        }
        cached = download();
        cachedAt = clock.instant();
        return cached;
    }

    private Snapshot download() throws IOException {
        RestClient github = RestClient.builder()
                .defaultHeader("User-Agent", "Keycunda")
                .defaultHeader("Accept", "application/vnd.github+json")
                .build();
        String releaseJson;
        try {
            releaseJson = github.get().uri(releaseUrl).retrieve().body(String.class);
        } catch (Exception e) {
            throw new IOException("Could not fetch the latest connectors release from GitHub: " + e.getMessage(), e);
        }
        JsonNode release = objectMapper.readTree(releaseJson);
        String tag = release.path("tag_name").asText("unknown");
        String downloadUrl = null;
        for (JsonNode asset : release.path("assets")) {
            String name = asset.path("name").asText("").toLowerCase();
            String url = asset.path("browser_download_url").asText("");
            if (name.endsWith(".zip") && !url.isBlank() && (downloadUrl == null || name.contains("template"))) {
                downloadUrl = url;
            }
        }
        if (downloadUrl == null) {
            throw new IOException("Connectors release " + tag + " has no ZIP asset with connector templates");
        }
        log.info("Downloading connector templates of release {} from {}", tag, downloadUrl);
        byte[] zip;
        try {
            zip = github.get().uri(URI.create(downloadUrl)).retrieve().body(byte[].class);
        } catch (Exception e) {
            throw new IOException("Could not download the connector templates from GitHub: " + e.getMessage(), e);
        }
        if (zip == null || zip.length == 0) {
            throw new IOException("The connector templates ZIP of release " + tag + " is empty");
        }
        List<ConnectorTemplate> templates = parse(zip);
        log.info("Release {}: {} connector templates after filtering", tag, templates.size());
        return new Snapshot(tag, templates);
    }

    /** Filters and de-duplicates the templates in a release ZIP; sorted by name. */
    List<ConnectorTemplate> parse(byte[] zip) throws IOException {
        Map<String, ConnectorTemplate> byId = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String filename = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
                if (entry.isDirectory() || !filename.toLowerCase().endsWith(".json")) {
                    continue;
                }
                ConnectorTemplate template = toTemplate(filename, new String(zis.readAllBytes(), StandardCharsets.UTF_8));
                if (template == null) {
                    continue;
                }
                ConnectorTemplate existing = byId.get(template.id());
                if (existing == null || template.version() > existing.version()) {
                    byId.put(template.id(), template);
                }
            }
        }
        List<ConnectorTemplate> result = new ArrayList<>(byId.values());
        result.sort((a, b) -> {
            int byName = a.name().compareToIgnoreCase(b.name());
            return byName != 0 ? byName : a.id().compareToIgnoreCase(b.id());
        });
        return result;
    }

    private ConnectorTemplate toTemplate(String filename, String content) {
        JsonNode root;
        try {
            root = objectMapper.readTree(content);
        } catch (IOException e) {
            log.debug("Skipping {}: not JSON", filename);
            return null;
        }
        // Hub only accepts element templates, and publishing needs a single template with id and version.
        if (!root.isObject() || !root.path("appliesTo").isArray() || !root.path("properties").isArray()
                || root.path("id").asText("").isBlank() || !root.path("version").isInt()) {
            log.debug("Skipping {}: not a publishable element template", filename);
            return null;
        }
        String id = root.path("id").asText();
        String name = root.path("name").asText("").isBlank() ? id : root.path("name").asText();
        String description = root.path("description").asText("");
        if (description.toLowerCase().contains("[deprecated]")) {
            return null;
        }
        if (isHybrid(filename, id, name)) {
            return null;
        }
        if (!compatibleWithTarget(root.path("engines").path("camunda").asText(null))) {
            return null;
        }
        List<String> keywords = new ArrayList<>();
        root.path("keywords").forEach(k -> keywords.add(k.asText()));
        return new ConnectorTemplate(id, name, description, root.path("documentationRef").asText(""),
                root.path("version").asInt(), content, keywords);
    }

    private static boolean isHybrid(String filename, String id, String name) {
        return filename.toLowerCase().contains("hybrid") || id.toLowerCase().contains("hybrid")
                || name.toLowerCase().contains("hybrid");
    }

    /** "engines.camunda" (e.g. "^8.10") is the minimum Camunda release; no or unparsable value = compatible. */
    private boolean compatibleWithTarget(String minimum) {
        int[] min = majorMinor(minimum);
        int[] target = majorMinor(targetRelease);
        if (min == null || target == null) {
            return true;
        }
        return min[0] != target[0] ? min[0] < target[0] : min[1] <= target[1];
    }

    private static int[] majorMinor(String version) {
        if (version == null) {
            return null;
        }
        Matcher m = MAJOR_MINOR.matcher(version);
        return m.find() ? new int[] { Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) } : null;
    }
}
