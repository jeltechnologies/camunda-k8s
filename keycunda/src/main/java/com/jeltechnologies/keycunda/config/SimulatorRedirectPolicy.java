package com.jeltechnologies.keycunda.config;

import java.net.URI;
import java.util.Locale;

/**
 * Which redirect URIs the "ticketing-simulator" login client may use. Instead of one exact registered URL, any
 * {@code https} URI on the allowed domain or one of its subdomains is accepted (so the simulator can move
 * between hosts/paths without a Keycunda change), plus {@code http} on localhost for local development.
 * Everything else is rejected, so a login link cannot send the authorization code to an arbitrary site.
 *
 * <p>The domain defaults to the Camunda domain without its first label (camunda-26.example.com gives
 * example.com), or the whole Camunda domain when it has fewer than three labels.
 */
public final class SimulatorRedirectPolicy {

    private final String allowedDomain;

    public SimulatorRedirectPolicy(String allowedDomain) {
        this.allowedDomain = allowedDomain.trim().toLowerCase(Locale.ROOT);
    }

    /** The configured domain, or the one derived from the Camunda domain when none is configured. */
    public static SimulatorRedirectPolicy of(String configuredDomain, String camundaDomain) {
        if (configuredDomain != null && !configuredDomain.isBlank()) {
            return new SimulatorRedirectPolicy(configuredDomain);
        }
        String[] labels = camundaDomain.trim().split("\\.");
        return new SimulatorRedirectPolicy(labels.length >= 3
                ? String.join(".", java.util.Arrays.copyOfRange(labels, 1, labels.length))
                : camundaDomain);
    }

    public String allowedDomain() {
        return allowedDomain;
    }

    public boolean allows(String uri) {
        if (uri == null || uri.isBlank()) {
            return false;
        }
        URI parsed;
        try {
            parsed = new URI(uri);
        } catch (java.net.URISyntaxException e) {
            return false;
        }
        String host = parsed.getHost();
        if (host == null || parsed.getUserInfo() != null || parsed.getFragment() != null || !parsed.isAbsolute()) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        String scheme = parsed.getScheme().toLowerCase(Locale.ROOT);
        if ("http".equals(scheme)) {
            return host.equals("localhost") || host.equals("127.0.0.1");
        }
        return "https".equals(scheme) && (host.equals(allowedDomain) || host.endsWith("." + allowedDomain));
    }
}
