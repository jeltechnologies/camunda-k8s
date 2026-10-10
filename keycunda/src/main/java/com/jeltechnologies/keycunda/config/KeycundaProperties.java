package com.jeltechnologies.keycunda.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything the install script feeds in via environment variables: the public domain used to
 * build redirect URIs, the seeded first user, and the per-Camunda-component OAuth2 client
 * secrets/audiences (the client IDs themselves are fixed, see {@link OidcClientsConfig}).
 */
@ConfigurationProperties(prefix = "keycunda")
public record KeycundaProperties(String camundaDomain, DemoUser demoUser, Clients clients, String jwtSigningKeyPem) {

    public record DemoUser(String name, String email, String password) {}

    public record Clients(ClientConfig identity, ClientConfig orchestration, ClientConfig optimize,
            ClientConfig connectors, WebModelerAudiences webModeler) {}

    public record ClientConfig(String secret, String audience) {}

    /** web-modeler is a public (PKCE) client on Keycunda - it has no secret, only audiences. */
    public record WebModelerAudiences(String clientApiAudience, String publicApiAudience) {}

    public String publicIssuer() {
        return "https://" + camundaDomain + "/auth";
    }
}
