package com.jeltechnologies.keycunda.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Uses fabric8's in-JVM mock Kubernetes API server ({@code crud = true} simulates real
 * create/get/list/patch/delete semantics against an in-memory store) - no real cluster, no
 * network calls. {@code client} below is injected automatically by the extension this annotation
 * registers.
 */
@EnableKubernetesMockClient(crud = true)
class ClusterSecretsApplierTest {

    private static final String NAMESPACE = "camunda";
    private static final String DEPLOYMENT_NAME = "camunda-connectors";
    private static final String POD_NAME = "camunda-connectors-abc123";

    KubernetesClient client;

    private ClusterSecretsApplier applier;

    @BeforeEach
    void setUp() {
        client.namespaces().resource(new NamespaceBuilder().withNewMetadata().withName(NAMESPACE).endMetadata().build())
                .createOrReplace();
        applier = new ClusterSecretsApplier(client);
    }

    private static Secret secret(String key, String value) {
        return new Secret(key, value);
    }

    /** A Deployment whose status already reports "ready", so ClusterSecretsApplier's post-apply
     * waitUntilReady() returns immediately instead of polling for a real rollout that this fake
     * API server has no controller to ever produce. */
    private Deployment createReadyConnectorsDeployment() {
        Deployment deployment = new DeploymentBuilder()
                .withNewMetadata().withName(DEPLOYMENT_NAME).withNamespace(NAMESPACE).endMetadata()
                .withNewSpec()
                    .withReplicas(1)
                    .withNewSelector().addToMatchLabels("app", DEPLOYMENT_NAME).endSelector()
                    .withNewTemplate()
                        .withNewMetadata().addToLabels("app", DEPLOYMENT_NAME).endMetadata()
                        .withNewSpec()
                            .addNewContainer().withName("connectors").withImage("example/connectors:latest").endContainer()
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .withNewStatus()
                    .withReplicas(1)
                    .withAvailableReplicas(1)
                .endStatus()
                .build();
        return client.apps().deployments().inNamespace(NAMESPACE).resource(deployment).create();
    }

    private void createConnectorsPod() {
        client.pods().inNamespace(NAMESPACE).resource(new PodBuilder()
                        .withNewMetadata().withName(POD_NAME).withNamespace(NAMESPACE).endMetadata()
                        .withNewSpec()
                            .addNewContainer().withName("connectors").withImage("example/connectors:latest").endContainer()
                        .endSpec()
                        .build())
                .create();
    }

    @Test
    void createsTheSecretWithTheGivenStringData() {
        createReadyConnectorsDeployment();

        applier.apply(List.of(secret("FOO", "bar"), secret("BAZ", "qux")), "test-connector-secrets");

        var stored = client.secrets().inNamespace(NAMESPACE).withName("test-connector-secrets").get();
        assertThat(stored).isNotNull();
        assertThat(stored.getStringData()).containsEntry("FOO", "bar").containsEntry("BAZ", "qux");
    }

    @Test
    void wiresTheSecretIntoTheConnectorsDeploymentEnvFrom() {
        createReadyConnectorsDeployment();

        applier.apply(List.of(secret("FOO", "bar")), "test-connector-secrets");

        Deployment updated = client.apps().deployments().inNamespace(NAMESPACE).withName(DEPLOYMENT_NAME).get();
        assertThat(updated.getSpec().getTemplate().getSpec().getContainers().get(0).getEnvFrom())
                .anyMatch(envFrom -> envFrom.getSecretRef() != null
                        && "test-connector-secrets".equals(envFrom.getSecretRef().getName()));
    }

    @Test
    void doesNotDuplicateTheEnvFromEntryOnASecondApply() {
        createReadyConnectorsDeployment();

        applier.apply(List.of(secret("FOO", "bar")), "test-connector-secrets");
        applier.apply(List.of(secret("FOO", "bar-updated")), "test-connector-secrets");

        Deployment updated = client.apps().deployments().inNamespace(NAMESPACE).withName(DEPLOYMENT_NAME).get();
        long matchingEnvFromEntries = updated.getSpec().getTemplate().getSpec().getContainers().get(0).getEnvFrom().stream()
                .filter(envFrom -> envFrom.getSecretRef() != null
                        && "test-connector-secrets".equals(envFrom.getSecretRef().getName()))
                .count();
        assertThat(matchingEnvFromEntries).isEqualTo(1);
    }

    @Test
    void deletesTheExistingConnectorsPodToForceARestart() {
        createReadyConnectorsDeployment();
        createConnectorsPod();

        applier.apply(List.of(secret("FOO", "bar")), "test-connector-secrets");

        assertThat(client.pods().inNamespace(NAMESPACE).withName(POD_NAME).get()).isNull();
    }

    @Test
    void failsClearlyWhenNoConnectorsDeploymentExists() {
        assertThatThrownBy(() -> applier.apply(List.of(secret("FOO", "bar")), "test-connector-secrets"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No connectors Deployment found");
    }

    @Test
    void fetchReturnsAnEmptyMapWhenTheSecretDoesNotExistYet() {
        assertThat(applier.fetch("does-not-exist-yet")).isEmpty();
    }

    @Test
    void fetchReturnsWhatWasApplied() {
        createReadyConnectorsDeployment();

        applier.apply(List.of(secret("FOO", "bar"), secret("BAZ", "qux")), "test-connector-secrets");

        Map<String, String> fetched = applier.fetch("test-connector-secrets");
        assertThat(fetched).containsEntry("FOO", "bar").containsEntry("BAZ", "qux");
    }

    @Test
    void removesKeysNoLongerInTheDesiredSetOnReapply() {
        createReadyConnectorsDeployment();

        applier.apply(List.of(secret("OLD_KEY", "old-value"), secret("KEEP_KEY", "keep-value")), "test-connector-secrets");
        applier.apply(List.of(secret("KEEP_KEY", "keep-value"), secret("NEW_KEY", "new-value")), "test-connector-secrets");

        Map<String, String> fetched = applier.fetch("test-connector-secrets");
        assertThat(fetched).containsEntry("KEEP_KEY", "keep-value").containsEntry("NEW_KEY", "new-value");
        assertThat(fetched).doesNotContainKey("OLD_KEY");
    }

    @Test
    void applyingAnEmptySetLeavesTheSecretWithNoKeys() {
        createReadyConnectorsDeployment();

        applier.apply(List.of(secret("SOON_GONE", "value")), "test-connector-secrets");
        applier.apply(List.of(), "test-connector-secrets");

        assertThat(applier.fetch("test-connector-secrets")).isEmpty();
    }

    @Test
    void applyReturnsTheVerifiedContentsForTheCallerToResyncWith() {
        createReadyConnectorsDeployment();

        Map<String, String> verified = applier.apply(List.of(secret("FOO", "bar")), "test-connector-secrets");

        assertThat(verified).containsExactly(Map.entry("FOO", "bar"));
    }
}
