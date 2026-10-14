package com.jeltechnologies.keycunda.identity;

import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * Grants an admin-managed API client ("M2M" client created in {@code /admin/clients}) access to the
 * Camunda Hub API, in Identity's own database - the automated form of
 * {@code grant-webmodeler-public-api-access.sh}.
 *
 * <p>Identity has no "Applications" entry for such clients (see the top-level CLAUDE.md), so the only
 * mechanism is a claim-matching mapping rule: a "Web Modeler Public API" role holding every permission
 * on the {@code web-modeler-public-api} audience, and one mapping rule per client
 * ({@code <clientId>-m2m}) matching the {@code preferred_username} claim - which Keycunda stamps with
 * the client ID on every client-credentials token - to that role. The client's tokens must also carry
 * the audience, which is what the client's own audience setting does.
 *
 * <p>Needs a connection to Identity's database ({@code keycunda.identity-datasource.*}); without a URL
 * (local development, tests) every method is a no-op. All methods are idempotent and never throw:
 * a failure is returned as a message so the admin page can show it next to the saved client.
 */
@Component
public class HubApiAccessGrantor {

    private static final Logger log = LoggerFactory.getLogger(HubApiAccessGrantor.class);

    static final String ROLE = "Web Modeler Public API";

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final String publicApiAudience;

    @Autowired
    public HubApiAccessGrantor(@Value("${keycunda.identity-datasource.url:}") String url,
            @Value("${keycunda.identity-datasource.username:identity}") String username,
            @Value("${keycunda.identity-datasource.password:}") String password,
            @Value("${keycunda.clients.web-modeler.public-api-audience:web-modeler-public-api}") String publicApiAudience) {
        this(url == null || url.isBlank() ? null : new DriverManagerDataSource(url, username, password), publicApiAudience);
    }

    HubApiAccessGrantor(DataSource dataSource, String publicApiAudience) {
        this.publicApiAudience = publicApiAudience;
        this.jdbc = dataSource == null ? null : JdbcClient.create(dataSource);
        this.transaction = dataSource == null ? null : new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    public boolean isConfigured() {
        return jdbc != null;
    }

    /** True when the (space/comma-separated) audience value contains the Hub API audience. */
    public boolean grantsHubApi(String audience) {
        return audience != null && Set.of(audience.trim().split("[,\\s]+")).contains(publicApiAudience);
    }

    /**
     * Makes Identity's grant for clientId match its audience: granted when the audience contains the
     * Hub API audience, revoked otherwise. Returns null on success (or when nothing is configured),
     * else a message describing why Identity could not be updated.
     */
    public String sync(String clientId, String audience) {
        return grantsHubApi(audience) ? grant(clientId) : revoke(clientId);
    }

    public String grant(String clientId) {
        if (jdbc == null) {
            return null;
        }
        String rule = ruleName(clientId);
        try {
            transaction.executeWithoutResult(status -> {
                jdbc.sql("INSERT INTO roles (name, description) VALUES (:role, :description) ON CONFLICT DO NOTHING")
                        .param("role", ROLE)
                        .param("description", "Grants CRUD access to the Camunda Hub API, for M2M applications")
                        .update();
                jdbc.sql("""
                        INSERT INTO roles_permissions (role_entity_name, permissions_id)
                        SELECT :role, id FROM permissions WHERE audience = :audience
                        ON CONFLICT DO NOTHING
                        """).param("role", ROLE).param("audience", publicApiAudience).update();
                jdbc.sql("""
                        INSERT INTO mapping_rules (type, name, claim_name, claim_value, operator)
                        VALUES ('ROLE', :rule, 'preferred_username', :clientId, 'EQUALS')
                        ON CONFLICT DO NOTHING
                        """).param("rule", rule).param("clientId", clientId).update();
                jdbc.sql("""
                        INSERT INTO mapping_rules_applied_roles (mapping_rules_name, applied_roles_name)
                        VALUES (:rule, :role) ON CONFLICT DO NOTHING
                        """).param("rule", rule).param("role", ROLE).update();
            });
            log.info("Granted Camunda Hub API access to client {} in Identity", clientId);
            return null;
        } catch (RuntimeException e) {
            log.warn("Could not grant Camunda Hub API access to client {} in Identity: {}", clientId, e.getMessage());
            return "Could not grant the client access to the Camunda Hub API in Identity (" + rootMessage(e)
                    + "). Run grant-webmodeler-public-api-access.sh " + clientId + " instead.";
        }
    }

    /** Removes the client's mapping rule (the shared role stays); a no-op if there is none. */
    public String revoke(String clientId) {
        if (jdbc == null) {
            return null;
        }
        String rule = ruleName(clientId);
        try {
            transaction.executeWithoutResult(status -> {
                jdbc.sql("DELETE FROM mapping_rules_applied_roles WHERE mapping_rules_name = :rule AND applied_roles_name = :role")
                        .param("rule", rule).param("role", ROLE).update();
                // the rule itself goes only when no other role hangs off it
                jdbc.sql("""
                        DELETE FROM mapping_rules WHERE name = :rule
                        AND NOT EXISTS (SELECT 1 FROM mapping_rules_applied_roles WHERE mapping_rules_name = :rule)
                        """).param("rule", rule).update();
            });
            return null;
        } catch (RuntimeException e) {
            log.warn("Could not revoke Camunda Hub API access of client {} in Identity: {}", clientId, e.getMessage());
            return "Could not remove the client's Camunda Hub API access in Identity (" + rootMessage(e) + ").";
        }
    }

    /** Grants every given client that has the Hub API audience; used at startup for existing clients. */
    public void reconcile(List<ClientAudience> clients) {
        if (jdbc == null) {
            return;
        }
        for (ClientAudience client : clients) {
            if (grantsHubApi(client.audience())) {
                grant(client.clientId());
            }
        }
    }

    public record ClientAudience(String clientId, String audience) {
    }

    static String ruleName(String clientId) {
        return clientId + "-m2m";
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        return message.lines().findFirst().orElse(message);
    }
}
