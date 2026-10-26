package com.jeltechnologies.keycunda.catalog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.jeltechnologies.keycunda.catalog.HubCatalogClient.CatalogAsset;
import com.jeltechnologies.keycunda.catalog.HubCatalogClient.CatalogTemplate;

/**
 * Publishes the chosen standard connector templates to the Catalog of Camunda Hub.
 *
 * <p>Ingestion is full-state (anything missing from the request is unpublished) and search returns no
 * template content, so the set to send is rebuilt every time: the selected templates plus every
 * template that is already published and that we still have content for. Deselecting an already
 * published template deletes its asset (the user can publish it again later), and a published asset we
 * have no content for - one that was not published from this page, or whose template is deprecated
 * now - would be unpublished by the submission; both need the user's confirmation first
 * ({@link Plan#needsConfirmation()}).
 */
@Service
public class CatalogPublisher {

    private static final Logger log = LoggerFactory.getLogger(CatalogPublisher.class);

    static final String CATEGORY = "Out-of-the-box connector templates";

    /** One asset to ingest: its template JSON and README. */
    public record Sendable(String id, String name, int version, String template, String readme) {
    }

    /**
     * What a publish would do. {@code toSend} is the complete submission; {@code conflicts} are existing
     * assets deleted before re-adding (unpublished, or a higher version than the local template);
     * {@code toDelete} are the deselected assets; {@code foreign} are published assets we have no content
     * for and that the submission would therefore unpublish.
     */
    public record Plan(List<Sendable> toSend, List<CatalogAsset> conflicts, List<CatalogAsset> toDelete,
            List<String> foreign) {
        public boolean needsConfirmation() {
            return !conflicts.isEmpty() || !toDelete.isEmpty() || !foreign.isEmpty();
        }
    }

    public record Result(int published, int replaced, int deleted) {
    }

    private final ConnectorTemplateSource source;
    private final HubCatalogClient hub;

    public CatalogPublisher(ConnectorTemplateSource source, HubCatalogClient hub) {
        this.source = source;
        this.hub = hub;
    }

    /** The plan for publishing {@code selectedIds}; every other known template id counts as deselected. */
    public Plan plan(Set<String> selectedIds) throws IOException {
        Map<String, ConnectorTemplate> local = byId(source.snapshot().templates());
        return computePlan(hub.listAssets(), local, selectedIds, local.keySet());
    }

    /** Executes the plan for {@code selectedIds}; the caller has already had it confirmed. */
    public synchronized Result publish(Set<String> selectedIds, Consumer<String> progress) throws IOException {
        progress.accept("Downloading the connector templates...");
        Map<String, ConnectorTemplate> local = byId(source.snapshot().templates());
        progress.accept("Checking the Catalog in Camunda Hub...");
        Plan plan = computePlan(hub.listAssets(), local, selectedIds, local.keySet());
        int replaced = 0;
        int deleted = 0;
        try {
            for (CatalogAsset asset : plan.conflicts()) {
                hub.deleteAsset(asset);
                replaced++;
            }
            if (!plan.toSend().isEmpty()) {
                progress.accept("Publishing " + plan.toSend().size() + " connector templates...");
                hub.ingest(plan.toSend().stream()
                        .map(t -> new CatalogTemplate(safeId(t.id()) + ".json", t.template(), t.readme())).toList());
            }
            for (CatalogAsset asset : plan.toDelete()) {
                hub.deleteAsset(asset);
                deleted++;
            }
        } catch (IOException e) {
            String done = replaced + deleted > 0
                    ? " (" + (replaced + deleted) + " existing asset(s) had already been removed before the failure)" : "";
            throw new IOException(e.getMessage() + done, e);
        }
        log.info("Catalog publish: {} sent, {} replaced, {} deleted", plan.toSend().size(), replaced, deleted);
        return new Result(plan.toSend().size(), replaced, deleted);
    }

    static Plan computePlan(List<CatalogAsset> assets, Map<String, ConnectorTemplate> local, Set<String> selectedIds,
            Set<String> displayedIds) {
        Map<String, CatalogAsset> byId = new LinkedHashMap<>();
        for (CatalogAsset asset : assets) {
            byId.putIfAbsent(asset.resourceId(), asset);
        }
        Set<String> deselected = new HashSet<>(displayedIds);
        deselected.removeAll(selectedIds);

        Map<String, Sendable> send = new LinkedHashMap<>();
        for (String id : selectedIds) {
            ConnectorTemplate template = local.get(id);
            if (template != null) {
                send.put(id, toSendable(template));
            }
        }

        // Everything already published must be sent again, or the submission unpublishes it.
        List<String> foreign = new ArrayList<>();
        for (CatalogAsset asset : assets) {
            String id = asset.resourceId();
            if (asset.unpublished() || send.containsKey(id) || deselected.contains(id)) {
                continue;
            }
            ConnectorTemplate template = local.get(id);
            if (template != null) {
                send.put(id, toSendable(template));
            } else {
                foreign.add(asset.name().isBlank() ? id : asset.name());
            }
        }
        // Nothing to ingest means nothing gets unpublished either.
        if (send.isEmpty()) {
            foreign.clear();
        }

        List<CatalogAsset> conflicts = new ArrayList<>();
        for (Sendable template : send.values()) {
            CatalogAsset existing = byId.get(template.id());
            // Hub refuses a re-add of an unpublished asset and a lower version than the latest one.
            if (existing != null && (existing.unpublished() || existing.latestVersion() > template.version())) {
                conflicts.add(existing);
            }
        }
        List<CatalogAsset> toDelete = new ArrayList<>();
        for (String id : deselected) {
            CatalogAsset existing = byId.get(id);
            if (existing != null) {
                toDelete.add(existing);
            }
        }
        List<Sendable> ordered = new ArrayList<>(send.values());
        ordered.sort((x, y) -> x.id().compareToIgnoreCase(y.id()));
        return new Plan(ordered, conflicts, toDelete, foreign);
    }

    private static Map<String, ConnectorTemplate> byId(List<ConnectorTemplate> templates) {
        Map<String, ConnectorTemplate> result = new LinkedHashMap<>();
        templates.forEach(t -> result.put(t.id(), t));
        return result;
    }

    private static Sendable toSendable(ConnectorTemplate t) {
        return new Sendable(t.id(), t.name(), t.version(), t.content(), buildReadme(t));
    }

    /** The template's file name in the Catalog: anything outside [A-Za-z0-9._-] becomes "_". */
    static String safeId(String id) {
        return id.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** README with the YAML frontmatter Hub requires (template, category, tags) and a Markdown description. */
    static String buildReadme(ConnectorTemplate t) {
        StringBuilder sb = new StringBuilder("---\n");
        sb.append("template: ").append(yaml(safeId(t.id()) + ".json")).append('\n');
        sb.append("category: ").append(yaml(CATEGORY)).append('\n');
        List<String> keywords = t.keywords().stream().filter(k -> !k.isBlank()).toList();
        if (!keywords.isEmpty()) {
            sb.append("tags:\n");
            keywords.forEach(k -> sb.append("  - ").append(yaml(k)).append('\n'));
        }
        sb.append("---\n");
        sb.append(t.description().isBlank() ? t.name() : t.description()).append('\n');
        if (!t.documentationRef().isBlank()) {
            sb.append("\nDocumentation: ").append(t.documentationRef()).append('\n');
        }
        sb.append("\n*Published by Keycunda.*\n");
        return sb.toString();
    }

    private static String yaml(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }
}
