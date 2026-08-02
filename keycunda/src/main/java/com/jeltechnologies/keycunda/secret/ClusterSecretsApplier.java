package com.jeltechnologies.keycunda.secret;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

import org.springframework.stereotype.Component;

/**
 * Secrets Management: reads and writes the connectors Kubernetes Secret directly -
 * Kubernetes is the source of truth for Secrets Management, not a local database or an HTTP
 * session (see {@code AdminSecretController}, which fetches fresh here on every request rather
 * than caching between them). Mirrors {@code update-connector-secrets.sh}'s own delete/create/patch/restart/
 * wait steps, just via the fabric8 Kubernetes client instead of shelling out to {@code kubectl} -
 * see the top-level CLAUDE.md for why (this app's container has no {@code kubectl} binary, and
 * re-implementing the script for in-cluster use would have meant a second, hard-to-test copy of
 * the same bash).
 *
 * <p>Needs the {@code keycunda} ServiceAccount's RBAC grant (see
 * template-keycunda-rbac.yaml) - get/list/create/delete on secrets, get/list/patch on
 * deployments, get/list/delete on pods, all scoped to the {@code camunda} namespace.
 */
@Component
public class ClusterSecretsApplier {

    private static final String NAMESPACE = "camunda";
    private static final long ROLLOUT_TIMEOUT_SECONDS = 120;
    private static final long ROLLOUT_POLL_INTERVAL_MILLIS = 1000;

    private final KubernetesClient client;

    public ClusterSecretsApplier() {
        // Auto-detects in-cluster config (mounted ServiceAccount token) when running as a pod;
        // falls back to ~/.kube/config for local testing against a real cluster.
        this(new KubernetesClientBuilder().build());
    }

    /** Package-private: lets ClusterSecretsApplierTest inject a mock client. Spring still uses
     * the no-arg constructor above for the real bean. */
    ClusterSecretsApplier(KubernetesClient client) {
        this.client = client;
    }

    /** Reads the live Secret's key/value pairs - empty map if it doesn't exist yet (first-time
     * use). Called fresh on every Secrets page load and before every mutation - see
     * {@code AdminSecretController}. */
    public Map<String, String> fetch(String secretName) {
        io.fabric8.kubernetes.api.model.Secret existing =
                client.secrets().inNamespace(NAMESPACE).withName(secretName).get();
        return existing == null ? Map.of() : decode(existing);
    }

    /** Deletes, writes, verifies, wires up and restarts - returns the verified, freshly-fetched
     * contents so the caller (see AdminSecretController) has the confirmed live state without a
     * second round trip, if it needs it. */
    public Map<String, String> apply(List<Secret> secrets, String secretName) {
        Map<String, String> intended = toMap(secrets);
        applySecret(intended, secretName);
        Map<String, String> verified = verifyApplied(intended, secretName);
        Deployment deployment = findConnectorsDeployment();
        ensureEnvFromReference(deployment, secretName);
        restartConnectorsDeployment(deployment.getMetadata().getName());
        return verified;
    }

    private static Map<String, String> toMap(List<Secret> secrets) {
        Map<String, String> map = new TreeMap<>();
        for (Secret secret : secrets) {
            map.put(secret.key(), secret.value());
        }
        return map;
    }

    /** Deletes the existing Secret object outright before creating it fresh, rather than an
     * upsert-style {@code createOrReplace} - so a key that's no longer in the submitted set (the
     * admin deleted it in the browser, or "Delete all" cleared everything before Apply) is
     * guaranteed gone afterward, not just implicitly relied upon to be replaced away. Mirrors
     * {@code update-connector-secrets.sh}'s own delete-then-apply sequence exactly, rather than
     * an upsert whose "old keys definitely disappear" guarantee was never actually verified.
     * Deleting a Secret that doesn't exist yet (first-ever apply) is a harmless no-op. */
    private void applySecret(Map<String, String> stringData, String secretName) {
        client.secrets().inNamespace(NAMESPACE).withName(secretName).delete();
        io.fabric8.kubernetes.api.model.Secret k8sSecret = new SecretBuilder()
                .withNewMetadata()
                    .withName(secretName)
                    .withNamespace(NAMESPACE)
                .endMetadata()
                .withType("Opaque")
                .withStringData(stringData)
                .build();
        client.secrets().inNamespace(NAMESPACE).resource(k8sSecret).create();
    }

    /** Reads the Secret straight back and compares it against what was just submitted, per the
     * explicit request that this feature verify the values actually landed rather than trusting
     * the write call's success alone. */
    private Map<String, String> verifyApplied(Map<String, String> intended, String secretName) {
        Map<String, String> actual = fetch(secretName);
        if (!intended.equals(actual)) {
            throw new IllegalStateException("Secret \"" + secretName + "\" did not read back as written - "
                    + "expected " + intended.size() + " key(s), found " + actual.size() + " after re-fetching.");
        }
        return actual;
    }

    /** A Kubernetes Secret's {@code stringData} is a write-only convenience field - a real API
     * server transcodes it into base64 {@code data} and clears {@code stringData} on read, so
     * this has to be able to decode either shape (the in-JVM test mock server happens to echo
     * {@code stringData} back as-is, which a real cluster does not). */
    private static Map<String, String> decode(io.fabric8.kubernetes.api.model.Secret k8sSecret) {
        Map<String, String> result = new TreeMap<>();
        if (k8sSecret.getData() != null) {
            k8sSecret.getData().forEach((key, base64Value) ->
                    result.put(key, new String(Base64.getDecoder().decode(base64Value), StandardCharsets.UTF_8)));
        }
        if (k8sSecret.getStringData() != null) {
            result.putAll(k8sSecret.getStringData());
        }
        return result;
    }

    private Deployment findConnectorsDeployment() {
        return client.apps().deployments().inNamespace(NAMESPACE).list().getItems().stream()
                .filter(d -> d.getMetadata().getName().toLowerCase().contains("connector"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No connectors Deployment found in namespace \"" + NAMESPACE + "\"."));
    }

    /** Only patches the Deployment if it isn't already wired to this secret - avoids piling up
     * duplicate envFrom entries every time this is run (unlike update-connector-secrets.sh's
     * unconditional JSON-patch append, which does exactly that on repeated runs). */
    private void ensureEnvFromReference(Deployment deployment, String secretName) {
        boolean alreadyReferenced = deployment.getSpec().getTemplate().getSpec().getContainers().get(0)
                .getEnvFrom().stream()
                .anyMatch(envFrom -> envFrom.getSecretRef() != null && secretName.equals(envFrom.getSecretRef().getName()));
        if (alreadyReferenced) {
            return;
        }
        client.apps().deployments().inNamespace(NAMESPACE).withName(deployment.getMetadata().getName())
                .edit(d -> new DeploymentBuilder(d)
                        .editSpec()
                            .editTemplate()
                                .editSpec()
                                    .editFirstContainer()
                                        .addNewEnvFrom()
                                            .withNewSecretRef()
                                                .withName(secretName)
                                            .endSecretRef()
                                        .endEnvFrom()
                                    .endContainer()
                                .endSpec()
                            .endTemplate()
                        .endSpec()
                        .build());
    }

    /**
     * Triggers a proper {@code kubectl rollout restart}-equivalent (patches
     * {@code spec.template.metadata.annotations["kubectl.kubernetes.io/restartedAt"]}, which bumps
     * {@code metadata.generation} and makes the Deployment controller roll out a genuinely new
     * ReplicaSet) instead of deleting the connectors pod(s) directly. Deleting pods directly - the
     * original approach here - never touched the Deployment's generation at all, so there was
     * nothing for a caller to reliably wait on: the ReplicaSet just reactively recreated the
     * missing pod, and {@link #isFullyRolledOut} had no signal to distinguish "the new pod is
     * actually up" from "the old status briefly still says ready". Found the hard way: an admin
     * added a secret value, the apply "succeeded" almost instantly, and the connector still
     * couldn't see it - the pod was never actually replaced in time.
     */
    private void restartConnectorsDeployment(String deploymentName) {
        Deployment restarted = client.apps().deployments().inNamespace(NAMESPACE).withName(deploymentName)
                .rolling().restart();
        waitForRollout(deploymentName, restarted.getMetadata().getGeneration());
    }

    /**
     * Polls until the Deployment has genuinely finished rolling out the restart just triggered.
     * Deliberately doesn't reuse fabric8's {@code waitUntilReady()}/{@code isDeploymentReady()} -
     * that check only compares desired vs. available replica counts, and never looks at {@code
     * status.observedGeneration} at all. Right after a rollout-restart patch, the Deployment's
     * status can still transiently report the *old* (already-ready) replica counts for a moment,
     * before the controller has even noticed the new generation - a plain replica-count check can
     * pass on the very first poll, which reproduces exactly the bug this method exists to close.
     * Requiring {@code observedGeneration} to have caught up to the generation produced by this
     * specific restart first is what actually closes that race.
     */
    private void waitForRollout(String deploymentName, Long targetGeneration) {
        long deadline = System.currentTimeMillis() + ROLLOUT_TIMEOUT_SECONDS * 1000;
        while (System.currentTimeMillis() < deadline) {
            Deployment current = client.apps().deployments().inNamespace(NAMESPACE).withName(deploymentName).get();
            if (isFullyRolledOut(current, targetGeneration)) {
                return;
            }
            sleep(ROLLOUT_POLL_INTERVAL_MILLIS);
        }
        throw new IllegalStateException("Timed out waiting for the connectors Deployment \"" + deploymentName
                + "\" to finish rolling out within " + ROLLOUT_TIMEOUT_SECONDS + "s.");
    }

    /** {@code targetGeneration} is nullable defensively - a real API server always populates
     * {@code metadata.generation}, but a fake/test double might not, and skipping the generation
     * check entirely in that case is safer than an unboxing NPE. */
    static boolean isFullyRolledOut(Deployment deployment, Long targetGeneration) {
        if (deployment == null || deployment.getSpec() == null || deployment.getStatus() == null) {
            return false;
        }
        DeploymentStatus status = deployment.getStatus();
        int desired = deployment.getSpec().getReplicas() == null ? 1 : deployment.getSpec().getReplicas();
        boolean generationCaughtUp = targetGeneration == null
                || (status.getObservedGeneration() != null && status.getObservedGeneration() >= targetGeneration);
        return generationCaughtUp
                && desired == valueOrZero(status.getUpdatedReplicas())
                && desired == valueOrZero(status.getReplicas())
                && desired == valueOrZero(status.getAvailableReplicas());
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the connectors rollout", e);
        }
    }
}
