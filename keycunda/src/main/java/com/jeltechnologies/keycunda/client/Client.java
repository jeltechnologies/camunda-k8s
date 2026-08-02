package com.jeltechnologies.keycunda.client;

import java.time.Instant;
import java.util.UUID;

/**
 * A user-managed OAuth2 client-credentials ("M2M") client, distinct from the fixed, code-defined
 * set of first-party Camunda-component clients in {@link
 * com.jeltechnologies.keycunda.config.OidcClientsConfig}. {@code clientId} is the
 * unique identifier external systems are configured with and, like a user's email, is immutable
 * once created - see {@link com.jeltechnologies.keycunda.web.AdminClientController}.
 *
 * <p>{@code secret} is the plaintext secret, but only transiently: it's populated when the client
 * is created or its secret regenerated, shown exactly once on the edit page, and nulled out by
 * {@link ClientRepository#clearSecret} the moment the admin confirms they've copied it - see
 * AdminClientController. Unlike user passwords it briefly exists in recoverable form (an admin who
 * hasn't yet confirmed can still see it), but it is never kept around indefinitely. {@code
 * secretHash} is the encoded form actually fed to {@code RegisteredClient}/the OAuth2
 * client-authentication provider, and is unaffected by clearing {@code secret}.
 *
 * <p>{@code audience} is what gets stamped into this client's access tokens as {@code aud} - see
 * {@code AuthorizationServerConfig.audiencesFor} - so it can be authorized against a specific
 * resource server (e.g. {@code orchestration-api}, to call Orchestration's REST API), the same way
 * the fixed "orchestration"/"connectors" clients are. Space/comma-separated for more than one
 * audience; null/blank falls back to the client's own client ID.
 */
public record Client(UUID id, String clientId, String name, String secret, String secretHash,
        String audience, Instant createdAt) {
}
