package com.jeltechnologies.keycunda.catalog;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

/**
 * In-memory status of the most recent Catalog publish run, polled by the Catalog page. A single
 * mutable slot is enough because Keycunda runs at {@code replicas: 1}; not persisted, so a pod restart
 * mid-publish reads as "nothing running". Same idea as {@code ApplyJobStatus} for the Secrets page.
 */
@Component
public class CatalogJobStatus {

    public enum State {
        RUNNING, SUCCESS, ERROR
    }

    public record Job(State state, String message) {
    }

    private final AtomicReference<Job> current = new AtomicReference<>();

    public void markRunning(String message) {
        current.set(new Job(State.RUNNING, message));
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
