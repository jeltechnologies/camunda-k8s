package com.jeltechnologies.keycunda.login;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A user-managed login client: a web app that logs users in through Keycunda (authorization code + PKCE, public
 * client) and then calls Camunda with the user's own token, so the user's groups decide what the app may do for
 * them. Distinct from the API clients (client credentials, see {@code client.Client}). {@code redirectUris} holds
 * one allowed redirect URI pattern per line, see {@link RedirectPatterns}. {@code audience} is space/comma
 * separated, like for API clients; blank falls back to the client ID.
 */
public record LoginClient(UUID id, String clientId, String name, String audience, String redirectUris,
        Instant createdAt) {

    public List<String> redirectPatterns() {
        return RedirectPatterns.parse(redirectUris);
    }
}
