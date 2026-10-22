package com.jeltechnologies.keycunda.web;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.jeltechnologies.keycunda.catalog.CatalogJobStatus;
import com.jeltechnologies.keycunda.catalog.CatalogPublisher;
import com.jeltechnologies.keycunda.catalog.ConnectorTemplate;
import com.jeltechnologies.keycunda.catalog.ConnectorTemplateSource;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Admin UI for the Catalog: publishes the standard connector templates (from the latest
 * {@code camunda/connectors} release) to the Catalog of Camunda Hub, which only exists on the 8.10 line.
 *
 * <p>Like the Secrets page, reads are always live (GitHub through a ten-minute in-memory ZIP cache, and
 * the Catalog straight from Hub) and nothing is stored here. The browser first asks for a
 * {@link #plan} - synchronous, so it can show a confirmation when the publish would remove or replace
 * Catalog assets - and then starts the {@link #publish} job, which runs on a single background thread
 * and is polled through {@link #publishStatusJson()}.
 */
@Controller
public class AdminCatalogController {

    /** Basic mode publishes everything ("all"); Advanced mode only the {@code selectedIds}. */
    public record PublishRequest(String mode, List<String> selectedIds) {
    }

    /** One row of the connector template table. */
    public record Row(String id, String name, String description, String documentationRef, int version,
            Integer publishedVersion) {
        public boolean published() {
            return publishedVersion != null;
        }
    }

    private final ConnectorTemplateSource source;
    private final CatalogPublisher publisher;
    private final CatalogJobStatus jobStatus;
    private final String camundaLine;
    // Single-threaded: publishes change shared Catalog state, so two clicks must not race each other.
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public AdminCatalogController(ConnectorTemplateSource source, CatalogPublisher publisher, CatalogJobStatus jobStatus,
            @Value("${keycunda.camunda-line:8.10}") String camundaLine) {
        this.source = source;
        this.publisher = publisher;
        this.jobStatus = jobStatus;
        this.camundaLine = camundaLine;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    @GetMapping("/admin/catalog")
    public String catalog(Model model) {
        if ("8.9".equals(camundaLine)) {
            return "redirect:/admin";
        }
        List<ConnectorTemplate> templates = List.of();
        String release = "";
        String sourceError = null;
        try {
            ConnectorTemplateSource.Snapshot snapshot = source.snapshot();
            templates = snapshot.templates();
            release = snapshot.release();
        } catch (IOException e) {
            sourceError = e.getMessage();
        }
        Map<String, Integer> publishedVersions = Map.of();
        String hubError = null;
        try {
            publishedVersions = publisher.publishedVersions();
        } catch (IOException e) {
            hubError = e.getMessage();
        }
        Map<String, Integer> published = publishedVersions;
        List<Row> rows = templates.stream()
                .map(t -> new Row(t.id(), t.name(), t.description(), t.documentationRef(), t.version(),
                        published.get(t.id())))
                .toList();
        model.addAttribute("rows", rows);
        model.addAttribute("release", release);
        model.addAttribute("publishedCount", rows.stream().filter(Row::published).count());
        model.addAttribute("sourceError", sourceError);
        model.addAttribute("hubError", hubError);
        return "admin/catalog";
    }

    /** What publishing would do; the browser asks for a confirmation when anything is removed or replaced. */
    @PostMapping(value = "/admin/catalog/plan", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> plan(@RequestBody PublishRequest request) {
        try {
            CatalogPublisher.Plan plan = publisher.plan(selectedIds(request));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("publish", plan.toSend().size());
            body.put("needsConfirmation", plan.needsConfirmation());
            body.put("willReplace", plan.conflicts().stream().map(a -> label(a.name(), a.resourceId())).toList());
            body.put("willDelete", plan.toDelete().stream().map(a -> label(a.name(), a.resourceId())).toList());
            body.put("willUnpublish", plan.foreign());
            return ResponseEntity.ok(body);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
        }
    }

    /** Starts the publish on the background thread; the browser has already confirmed the plan. */
    @PostMapping(value = "/admin/catalog/publish", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Void> publish(@RequestBody PublishRequest request) {
        Set<String> selected;
        try {
            selected = selectedIds(request);
        } catch (IOException e) {
            jobStatus.markError("Failed to publish: " + e.getMessage());
            return ResponseEntity.accepted().build();
        }
        jobStatus.markRunning("Publishing connector templates...");
        executor.submit(() -> {
            try {
                CatalogPublisher.Result result = publisher.publish(selected, jobStatus::markRunning);
                jobStatus.markSuccess("Published " + result.published() + " connector templates to the Catalog"
                        + (result.deleted() > 0 ? ", removed " + result.deleted() + " deselected." : "."));
            } catch (Exception e) {
                jobStatus.markError("Failed to publish to the Catalog: " + e.getMessage());
            }
        });
        return ResponseEntity.accepted().build();
    }

    @GetMapping(value = "/admin/catalog/publish-status.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, String> publishStatusJson() {
        CatalogJobStatus.Job job = jobStatus.current();
        if (job == null) {
            return Map.of("state", "NONE");
        }
        return Map.of("state", job.state().name(), "message", job.message());
    }

    private Set<String> selectedIds(PublishRequest request) throws IOException {
        if ("selection".equals(request.mode())) {
            return new HashSet<>(request.selectedIds() == null ? List.of() : request.selectedIds());
        }
        Set<String> all = new HashSet<>();
        source.snapshot().templates().forEach(t -> all.add(t.id()));
        return all;
    }

    private static String label(String name, String id) {
        return name == null || name.isBlank() ? id : name;
    }
}
