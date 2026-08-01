package com.jeltechnologies.keycunda.secret;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

/**
 * The admin's in-progress, per-browser-session edit of the connectors Kubernetes Secret - not a
 * database. Kubernetes is the source of truth (see {@link ClusterSecretsApplier}); this bean just
 * holds a working copy between the moment it's fetched (first visit to /admin/secrets in a
 * session) and the moment "Apply to cluster" pushes it back. Add/edit/delete/import all mutate
 * this in-memory copy only - nothing reaches Kubernetes until an explicit apply, since the
 * connectors pod only ever picks up a change after a restart anyway, so writing every micro-edit
 * through immediately would be pointless.
 *
 * <p>{@code @SessionScope} with a class-based proxy so this can be injected into the
 * singleton-scoped {@code AdminSecretController} - each HTTP session gets its own instance,
 * backed by the same {@code spring-session-jdbc} store already used for login sessions, so it
 * survives a pod restart the same way an in-progress login flow does.
 *
 * <p><b>Must implement {@link Serializable}.</b> Because sessions are JDBC-backed (not the
 * in-memory default), every session attribute - including the target object behind a
 * {@code @SessionScope} proxy - gets Java-serialized to a byte column on every request. Skipping
 * this isn't a theoretical nicety: without it, the very first visit to any {@code /admin/secrets}
 * page throws {@code IllegalArgumentException: DefaultSerializer requires a Serializable payload}
 * while committing the session, and - since the broken bean is already in the session by then -
 * every subsequent request in that same browser session fails the same way, including completely
 * unrelated pages.
 */
@Component
@SessionScope(proxyMode = ScopedProxyMode.TARGET_CLASS)
public class SecretsWorkingCopy implements Serializable {

    private static final long serialVersionUID = 1L;

    private final Map<String, String> secrets = new TreeMap<>();
    private boolean loaded = false;

    public boolean isLoaded() {
        return loaded;
    }

    /** Replaces the working copy wholesale - used to seed it from Kubernetes on first load, and
     * to resync it to the verified live state after a successful apply. */
    public void load(Map<String, String> fetched) {
        secrets.clear();
        secrets.putAll(fetched);
        loaded = true;
    }

    public List<Secret> list() {
        return secrets.entrySet().stream().map(e -> new Secret(e.getKey(), e.getValue())).toList();
    }

    public Optional<Secret> find(String key) {
        String value = secrets.get(key);
        return value == null ? Optional.empty() : Optional.of(new Secret(key, value));
    }

    public void add(String key, String value) {
        if (secrets.containsKey(key)) {
            throw new DuplicateSecretKeyException(key);
        }
        secrets.put(key, value);
    }

    /** Renames (or, if {@code newKey} equals {@code oldKey}, just updates the value of) an
     * existing entry. */
    public void rename(String oldKey, String newKey, String value) {
        if (!oldKey.equals(newKey) && secrets.containsKey(newKey)) {
            throw new DuplicateSecretKeyException(newKey);
        }
        secrets.remove(oldKey);
        secrets.put(newKey, value);
    }

    public void delete(String key) {
        secrets.remove(key);
    }

    /** Adds a new key or overwrites an existing one's value - the merge behavior used by
     * {@code .yaml}/{@code .env} import, which never removes a key just because it's absent from
     * the imported file. */
    public void upsert(String key, String value) {
        secrets.put(key, value);
    }

    public Map<String, String> asMap() {
        return Map.copyOf(secrets);
    }
}
