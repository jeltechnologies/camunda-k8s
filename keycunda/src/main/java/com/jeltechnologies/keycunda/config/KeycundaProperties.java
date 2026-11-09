package com.jeltechnologies.keycunda.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything the install script feeds in via environment variables: the public domain used to
 * build redirect URIs, the seeded first user, and the per-Camunda-component OAuth2 client
 * secrets/audiences (the client IDs themselves are fixed, see {@link OidcClientsConfig}).
 */
@ConfigurationProperties(prefix = "keycunda")
public record KeycundaProperties(String camundaDomain, DemoUser demoUser, Clients clients, String jwtSigningKeyPem,
        String simulatorBaseUrl, String simulatorAllowedDomain) {

    public record DemoUser(String name, String email, String password) {}

    public record Clients(ClientConfig identity, ClientConfig orchestration, ClientConfig optimize,
            ClientConfig connectors, WebModelerAudiences webModeler) {}

    public record ClientConfig(String secret, String audience) {}

    /** web-modeler is a public (PKCE) client on Keycunda - it has no secret, only audiences. */
    public record WebModelerAudiences(String clientApiAudience, String publicApiAudience) {}

    /**
     * Public base URL of the ticketing simulator (for example https://ticket.example.com), or blank for
     * none. When set, the fixed public OIDC client "ticketing-simulator" is registered with this URL as
     * its redirect/post-logout base.
     */
    public boolean hasSimulator() {
        return simulatorBaseUrl != null && !simulatorBaseUrl.isBlank();
    }

    /** Redirect policy of the simulator client: https on this domain (see {@link SimulatorRedirectPolicy}). */
    public SimulatorRedirectPolicy simulatorRedirectPolicy() {
        return SimulatorRedirectPolicy.of(simulatorAllowedDomain, camundaDomain);
    }

    public String publicIssuer() {
        return "https://" + camundaDomain + "/auth";
    }
}
