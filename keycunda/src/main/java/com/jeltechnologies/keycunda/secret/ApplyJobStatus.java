package com.jeltechnologies.keycunda.secret;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

/**
 * In-memory status of the most recent "apply to cluster" run - a single mutable slot is enough
 * since {@code keycunda} runs at {@code replicas: 1} (see
 * template-keycunda.yaml), so there's no cross-replica coordination to do.
 * Not persisted: a pod restart mid-apply is meant to be visible as "unknown/nothing running", not
 * resurrected as a stale job.
 *
 * <p>A successful job carries the verified, freshly-fetched secret map ({@link
 * ClusterSecretsApplier#apply}'s return value) rather than resyncing {@link SecretsWorkingCopy}
 * itself here - this class is a plain singleton, updated from the background thread the apply
 * runs on, and that thread has no HTTP session bound to it, so it can't touch the session-scoped
 * working copy at all ("Scope 'session' is not active for the current thread"). Resyncing happens
 * instead in {@code AdminSecretController.applyStatus}, which runs on a real request thread.
 */
@Component
public class ApplyJobStatus {

    public enum State {
        RUNNING, SUCCESS, ERROR
    }

    public record Job(State state, String message, Map<String, String> verifiedSecrets) {
    }

    private final AtomicReference<Job> current = new AtomicReference<>();

    public void markRunning() {
        current.set(new Job(State.RUNNING,
                "Applying secrets to the cluster and restarting the connectors pod - this can take a minute or two...",
                null));
    }

    public void markSuccess(String message, Map<String, String> verifiedSecrets) {
        current.set(new Job(State.SUCCESS, message, verifiedSecrets));
    }

    public void markError(String message) {
        current.set(new Job(State.ERROR, message, null));
    }

    public Job current() {
        return current.get();
    }
}
