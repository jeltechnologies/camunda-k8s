package com.jeltechnologies.camundaidp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything the install script feeds in via environment variables: the public domain used to
 * build redirect URIs, the seeded first user, and the per-Camunda-component OAuth2 client
 * secrets/audiences (the client IDs themselves are fixed, see {@link OidcClientsConfig}).
 */
@ConfigurationProperties(prefix = "idp")
public record IdpProperties(String camundaDomain, DemoUser demoUser, Clients clients) {

    public record DemoUser(String username, String email, String password) {}

    public record Clients(ClientConfig identity, ClientConfig orchestration, ClientConfig optimize,
            WebModelerAudiences webModeler) {}

    public record ClientConfig(String secret, String audience) {}

    /** web-modeler is a public (PKCE) client on this IdP - it has no secret, only audiences. */
    public record WebModelerAudiences(String clientApiAudience, String publicApiAudience) {}

    public String publicIssuer() {
        return "https://" + camundaDomain + "/auth";
    }
}
