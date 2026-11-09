package com.jeltechnologies.keycunda.login;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

/**
 * Redirect URI patterns of a login client, one per line in the admin UI. A pattern is an absolute URI in which
 * {@code *} matches any text; everything else must match exactly. {@code https} is required, except {@code http}
 * on localhost. To keep an admin typo from opening the door to any site, a wildcard in the host part is only
 * allowed as the first label ({@code https://*.example.com/...}) and must leave at least a domain and a
 * top-level label after it; wildcards in the path and query are free.
 *
 * <p>Spring's built-in redirect check only does exact matching, so {@code SecurityConfig} installs validators that
 * consult these patterns, which {@code CompositeRegisteredClientRepository} stores in the client's settings
 * under {@link #SETTING} (the registered URIs themselves are only {@link #placeholder placeholders}).
 */
public final class RedirectPatterns {

    /** Client setting that carries the patterns of a login client; absent on all other clients. */
    public static final String SETTING = "keycunda.redirect-patterns";

    private static final Pattern HOST_LABEL = Pattern.compile("[A-Za-z0-9-]+");

    private RedirectPatterns() {
    }

    /** One pattern per non-blank line, trimmed, without duplicates. */
    public static List<String> parse(String text) {
        LinkedHashSet<String> patterns = new LinkedHashSet<>();
        if (text != null) {
            for (String line : text.split("\\R")) {
                if (!line.isBlank()) {
                    patterns.add(line.trim());
                }
            }
        }
        return List.copyOf(patterns);
    }

    /** A message describing why the pattern is not allowed, or null when it is fine. */
    public static String validate(String pattern) {
        if (pattern == null || pattern.isBlank() || !pattern.equals(pattern.trim()) || pattern.matches(".*\\s.*")) {
            return "must not be empty or contain spaces";
        }
        int schemeEnd = pattern.indexOf("://");
        if (schemeEnd < 0) {
            return "must be an absolute URI like https://app.example.com/login/callback";
        }
        String scheme = pattern.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
        if (pattern.contains("#")) {
            return "must not contain a # fragment";
        }
        String authority = authority(pattern, schemeEnd);
        if (authority.isEmpty() || authority.contains("@")) {
            return "must have a host and no user info";
        }
        String hostPort = authority;
        String host = hostPort.contains(":") ? hostPort.substring(0, hostPort.indexOf(':')) : hostPort;
        if (host.contains("*")) {
            if (!host.startsWith("*.") || host.indexOf('*', 1) >= 0) {
                return "may only use * as the first label of the host (*.example.com)";
            }
            String rest = host.substring(2);
            String[] labels = rest.split("\\.", -1);
            if (labels.length < 2 || java.util.Arrays.stream(labels).anyMatch(l -> !HOST_LABEL.matcher(l).matches())) {
                return "a *. host needs a domain after it, like *.example.com";
            }
        }
        if (hostPort.contains(":") && !hostPort.substring(hostPort.indexOf(':') + 1).matches("\\d{1,5}")) {
            return "has an invalid port";
        }
        if ("https".equals(scheme)) {
            return null;
        }
        if ("http".equals(scheme) && (host.equals("localhost") || host.equals("127.0.0.1"))) {
            return null;
        }
        return "must use https (http is only allowed on localhost)";
    }

    /** Whether the redirect URI the client sent matches one of the patterns. */
    public static boolean anyMatches(List<String> patterns, String uri) {
        if (patterns == null || uri == null || uri.isBlank()) {
            return false;
        }
        URI parsed;
        try {
            parsed = new URI(uri);
        } catch (java.net.URISyntaxException e) {
            return false;
        }
        if (!parsed.isAbsolute() || parsed.getHost() == null || parsed.getUserInfo() != null || parsed.getFragment() != null) {
            return false;
        }
        return patterns.stream().anyMatch(pattern -> validate(pattern) == null && compile(pattern).matcher(uri).matches());
    }

    /** The patterns of a login client, or null for any other client. */
    @SuppressWarnings("unchecked")
    public static List<String> of(RegisteredClient client) {
        Object value = client.getClientSettings().getSettings().get(SETTING);
        return value instanceof List<?> list ? (List<String>) list : null;
    }

    /** A plain URI standing in for a pattern in the registered client (Spring wants valid URIs there). */
    public static String placeholder(String pattern) {
        return pattern.replace('*', 'x');
    }

    private static Pattern compile(String pattern) {
        int schemeEnd = pattern.indexOf("://");
        String authority = authority(pattern, schemeEnd);
        String scheme = pattern.substring(0, schemeEnd);
        String rest = pattern.substring(schemeEnd + 3 + authority.length());
        StringBuilder regex = new StringBuilder("(?i:").append(Pattern.quote(scheme + "://"));
        if (authority.startsWith("*.")) {
            regex.append("(?:[A-Za-z0-9-]+\\.)+").append(Pattern.quote(authority.substring(2)));
        } else {
            regex.append(Pattern.quote(authority));
        }
        regex.append(")");
        String[] parts = rest.split("\\*", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                regex.append(".*");
            }
            if (!parts[i].isEmpty()) {
                regex.append(Pattern.quote(parts[i]));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    private static String authority(String pattern, int schemeEnd) {
        int start = schemeEnd + 3;
        int end = start;
        while (end < pattern.length() && "/?".indexOf(pattern.charAt(end)) < 0) {
            end++;
        }
        return pattern.substring(start, end);
    }
}
