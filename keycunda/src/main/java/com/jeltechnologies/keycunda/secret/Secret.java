package com.jeltechnologies.keycunda.secret;

/**
 * A Secrets Management entry: a key/value pair for the connectors Deployment - an API key, a
 * connection string, a whole service-account JSON blob.
 *
 * <p>Kubernetes is the source of truth (see {@link ClusterSecretsApplier}), not a local database
 * or an HTTP session: a Kubernetes Secret's {@code data}/{@code stringData} is itself just a flat,
 * unique-by-key map, so {@code key} is this entry's only identity - there's no separate database
 * row/id to track. {@code AdminSecretController} fetches the live Secret fresh on every request
 * rather than holding these anywhere between requests.
 */
public record Secret(String key, String value) {
}
