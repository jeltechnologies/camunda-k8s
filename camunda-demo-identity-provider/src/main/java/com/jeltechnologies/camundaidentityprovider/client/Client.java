package com.jeltechnologies.camundaidentityprovider.client;

import java.time.Instant;
import java.util.UUID;

/**
 * A user-managed OAuth2 client-credentials ("M2M") client, distinct from the fixed, code-defined
 * set of first-party Camunda-component clients in {@link
 * com.jeltechnologies.camundaidentityprovider.config.OidcClientsConfig}. {@code clientId} is the
 * unique identifier external systems are configured with and, like a user's email, is immutable
 * once created - see {@link com.jeltechnologies.camundaidentityprovider.web.AdminClientController}.
 * {@code secretHash} is a one-way hash; the plaintext secret is shown to the admin exactly once,
 * at creation or regeneration time, and never persisted or displayed again.
 */
public record Client(UUID id, String clientId, String name, String secretHash, Instant createdAt) {
}
