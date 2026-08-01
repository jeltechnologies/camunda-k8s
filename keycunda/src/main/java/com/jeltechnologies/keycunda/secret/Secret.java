package com.jeltechnologies.keycunda.secret;

/**
 * A Secrets Management entry: a key/value pair an admin is staging for the
 * connectors Deployment - an API key, a connection string, a whole service-account JSON blob.
 *
 * <p>Kubernetes is the source of truth (see {@link ClusterSecretsApplier}), not a local database:
 * a Kubernetes Secret's {@code data}/{@code stringData} is itself just a flat, unique-by-key map,
 * so {@code key} is this entry's only identity - there's no separate database row/id to track.
 * {@link SecretsWorkingCopy} holds the in-memory, per-admin-session set of these while they're
 * being edited, before "apply to cluster" pushes them back to the real Secret object.
 */
public record Secret(String key, String value) {
}
