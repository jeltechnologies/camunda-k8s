package com.jeltechnologies.keycunda.secret;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

/**
 * In-memory status of the most recent Secrets Management apply run - a single mutable slot is
 * enough since {@code keycunda} runs at {@code replicas: 1} (see
 * template-keycunda.yaml), so there's no cross-replica coordination to do. Not persisted: a pod
 * restart mid-apply is meant to be visible as "unknown/nothing running", not resurrected as a
 * stale job.
 *
 * <p>Every Secrets Management mutation (add/edit/delete/import) runs through this now, not just a
 * standalone "Apply to cluster" action - see {@code AdminSecretController}, which reads the live
 * Kubernetes Secret fresh on every request rather than keeping any in-memory or session-backed
 * working copy between them.
 */
@Component
public class ApplyJobStatus {

    public enum State {
        RUNNING, SUCCESS, ERROR
    }

    public record Job(State state, String message) {
    }

    private final AtomicReference<Job> current = new AtomicReference<>();

    public void markRunning() {
        current.set(new Job(State.RUNNING, "Applying secrets and restarting connectors..."));
    }

    public void markSuccess(String message) {
        current.set(new Job(State.SUCCESS, message));
    }

    public void markError(String message) {
        current.set(new Job(State.ERROR, message));
    }

    public Job current() {
        return current.get();
    }
}
