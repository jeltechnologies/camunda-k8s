package com.jeltechnologies.camundaidentityprovider.client;

import java.time.Instant;
import java.util.UUID;

/**
 * A user-managed OAuth2 client-credentials ("M2M") client, distinct from the fixed, code-defined
 * set of first-party Camunda-component clients in {@link
 * com.jeltechnologies.camundaidentityprovider.config.OidcClientsConfig}. {@code clientId} is the
 * unique identifier external systems are configured with and, like a user's email, is immutable
 * once created - see {@link com.jeltechnologies.camundaidentityprovider.web.AdminClientController}.
 *
 * <p>{@code secret} is the plaintext secret, kept in the clear (not just its hash) so an admin can
 * look it up again from the UI at any time - a deliberate demo-grade choice (see the top-level
 * CLAUDE.md), unlike user passwords, which are never stored in recoverable form. It's null only
 * for a client created before this field existed, until its secret is regenerated. {@code
 * secretHash} is the encoded form actually fed to {@code RegisteredClient}/the OAuth2
 * client-authentication provider.
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
