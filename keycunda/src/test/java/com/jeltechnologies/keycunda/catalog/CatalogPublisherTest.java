package com.jeltechnologies.keycunda.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.jeltechnologies.keycunda.catalog.HubCatalogClient.CatalogAsset;

class CatalogPublisherTest {

    private static ConnectorTemplate template(String id, int version) {
        return new ConnectorTemplate(id, "Name " + id, "Description of " + id, "https://docs/" + id, version,
                "{\"id\":\"" + id + "\",\"version\":" + version + "}", List.of("kw"));
    }

    private static Map<String, ConnectorTemplate> local(ConnectorTemplate... templates) {
        Map<String, ConnectorTemplate> map = new LinkedHashMap<>();
        for (ConnectorTemplate t : templates) {
            map.put(t.id(), t);
        }
        return map;
    }

    private static CatalogAsset asset(String id, int latestVersion, boolean unpublished) {
        return new CatalogAsset("key-" + id, id, "Name " + id, latestVersion, unpublished);
    }

    @Test
    void publishingEverythingToAnEmptyCatalogNeedsNoConfirmation() {
        var local = local(template("a", 1), template("b", 1));

        var plan = CatalogPublisher.computePlan(List.of(), local, Set.of("a", "b"), local.keySet());

        assertThat(plan.toSend()).extracting(CatalogPublisher.Sendable::id).containsExactly("a", "b");
        assertThat(plan.needsConfirmation()).isFalse();
    }

    @Test
    void aVersionBumpIsAPlainUpdate() {
        var local = local(template("a", 2));

        var plan = CatalogPublisher.computePlan(List.of(asset("a", 1, false)), local, Set.of("a"), local.keySet());

        assertThat(plan.conflicts()).isEmpty();
        assertThat(plan.needsConfirmation()).isFalse();
    }

    @Test
    void anUnpublishedAssetOrAHigherPublishedVersionIsDeletedAndReAdded() {
        var local = local(template("a", 1), template("b", 1));

        var plan = CatalogPublisher.computePlan(List.of(asset("a", 1, true), asset("b", 5, false)), local,
                Set.of("a", "b"), local.keySet());

        assertThat(plan.conflicts()).extracting(CatalogAsset::resourceId).containsExactlyInAnyOrder("a", "b");
        assertThat(plan.needsConfirmation()).isTrue();
    }

    @Test
    void deselectingAPublishedTemplateDeletesItAfterConfirmation() {
        var local = local(template("a", 1), template("b", 1));

        var plan = CatalogPublisher.computePlan(List.of(asset("a", 1, false), asset("b", 1, false)), local,
                Set.of("a"), local.keySet());

        assertThat(plan.toDelete()).extracting(CatalogAsset::resourceId).containsExactly("b");
        assertThat(plan.toSend()).extracting(CatalogPublisher.Sendable::id).containsExactly("a");
        assertThat(plan.needsConfirmation()).isTrue();
    }

    @Test
    void publishedTemplatesThatAreNotSelectedButNotDisplayedEitherAreSentAgain() {
        // an asset whose template is known but not part of the selection and not deselected can't happen via the UI
        // (displayed == all known), so it is only re-sent when we are given a smaller displayed set
        var local = local(template("a", 1), template("b", 1));

        var plan = CatalogPublisher.computePlan(List.of(asset("b", 1, false)), local, Set.of("a"), Set.of("a"));

        assertThat(plan.toSend()).extracting(CatalogPublisher.Sendable::id).containsExactly("a", "b");
        assertThat(plan.toDelete()).isEmpty();
    }

    @Test
    void publishedAssetsWithoutLocalContentAreReportedAsUnpublishedByTheSubmission() {
        var local = local(template("a", 1));

        var plan = CatalogPublisher.computePlan(List.of(asset("other", 1, false)), local, Set.of("a"), local.keySet());

        assertThat(plan.foreign()).containsExactly("Name other");
        assertThat(plan.needsConfirmation()).isTrue();
    }

    @Test
    void nothingToSendMeansNothingIsUnpublished() {
        var local = local(template("a", 1));

        var plan = CatalogPublisher.computePlan(List.of(asset("other", 1, false)), local, Set.of(), local.keySet());

        assertThat(plan.toSend()).isEmpty();
        assertThat(plan.foreign()).isEmpty();
    }

    @Test
    void readmeHasTheFrontmatterHubNeeds() {
        String readme = CatalogPublisher.buildReadme(template("io.camunda:rest", 1));

        assertThat(readme).startsWith("---\ntemplate: \"io.camunda_rest.json\"\n");
        assertThat(readme).contains("category: \"Out-of-the-box connector templates\"")
                .contains("tags:\n  - \"kw\"\n")
                .contains("Description of io.camunda:rest")
                .contains("Documentation: https://docs/io.camunda:rest")
                .contains("*Published by Keycunda.*");
    }
}
