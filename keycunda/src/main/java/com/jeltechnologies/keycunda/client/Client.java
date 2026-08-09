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
 * <p>{@code secret} is the plaintext secret, populated when the client is created or its secret
 * regenerated, and shown indefinitely on the edit page from then on - unlike a user's password,
 * it's kept around in recoverable form for as long as the client exists, since it's what gets
 * pasted into external systems and an admin may need to look it up again later. (A client created
 * before this became the behavior may still show no secret, from the older one-time-reveal design
 * that cleared it after the first view; "Generate new" replaces it with a permanently visible one.)
 * {@code secretHash} is the encoded form actually fed to {@code RegisteredClient}/the OAuth2
 * client-authentication provider, and is independent of {@code secret}.
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
