package com.jeltechnologies.keycunda.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** The grant runs the same statements as grant-webmodeler-public-api-access.sh, here against a stand-in for Identity's tables. */
class HubApiAccessGrantorTest {

    private static final String AUDIENCE = "web-modeler-public-api";

    private JdbcTemplate jdbc;
    private HubApiAccessGrantor grantor;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:identity" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE roles (name VARCHAR(255) PRIMARY KEY, description VARCHAR(255))");
        jdbc.execute("CREATE TABLE permissions (id INT PRIMARY KEY, audience VARCHAR(255), definition VARCHAR(255))");
        jdbc.execute("CREATE TABLE roles_permissions (role_entity_name VARCHAR(255), permissions_id INT,"
                + " PRIMARY KEY (role_entity_name, permissions_id))");
        jdbc.execute("CREATE TABLE mapping_rules (name VARCHAR(255) PRIMARY KEY, type VARCHAR(20),"
                + " claim_name VARCHAR(255), claim_value VARCHAR(255), operator VARCHAR(20))");
        jdbc.execute("CREATE TABLE mapping_rules_applied_roles (mapping_rules_name VARCHAR(255),"
                + " applied_roles_name VARCHAR(255), PRIMARY KEY (mapping_rules_name, applied_roles_name))");
        int id = 0;
        for (String definition : List.of("create:*", "read:*", "update:*", "delete:*")) {
            jdbc.update("INSERT INTO permissions VALUES (?, ?, ?)", ++id, AUDIENCE, definition);
        }
        jdbc.update("INSERT INTO permissions VALUES (?, ?, ?)", ++id, "web-modeler-api", "write:*");
        grantor = new HubApiAccessGrantor(dataSource, AUDIENCE);
    }

    @Test
    void grantCreatesRolePermissionsAndMappingRuleOnPreferredUsername() {
        assertThat(grantor.grant("caddy")).isNull();

        assertThat(jdbc.queryForList("SELECT permissions_id FROM roles_permissions WHERE role_entity_name = ?",
                Integer.class, HubApiAccessGrantor.ROLE)).hasSize(4);
        assertThat(jdbc.queryForMap("SELECT claim_name, claim_value, operator, type FROM mapping_rules WHERE name = 'caddy-m2m'"))
                .containsEntry("CLAIM_NAME", "preferred_username").containsEntry("CLAIM_VALUE", "caddy")
                .containsEntry("OPERATOR", "EQUALS").containsEntry("TYPE", "ROLE");
        assertThat(jdbc.queryForObject("SELECT applied_roles_name FROM mapping_rules_applied_roles WHERE mapping_rules_name = 'caddy-m2m'",
                String.class)).isEqualTo(HubApiAccessGrantor.ROLE);
    }

    @Test
    void grantIsIdempotentAndSharesTheRole() {
        assertThat(grantor.grant("caddy")).isNull();
        assertThat(grantor.grant("caddy")).isNull();
        assertThat(grantor.grant("chat")).isNull();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM roles", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM roles_permissions", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mapping_rules", Integer.class)).isEqualTo(2);
    }

    @Test
    void syncGrantsWhileTheAudienceIsPresentAndRevokesWhenItIsRemoved() {
        assertThat(grantor.sync("caddy", "orchestration-api, web-modeler-public-api")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mapping_rules", Integer.class)).isEqualTo(1);

        assertThat(grantor.sync("caddy", "orchestration-api")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mapping_rules", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mapping_rules_applied_roles", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM roles", Integer.class)).isEqualTo(1);
    }

    @Test
    void revokeLeavesOtherClientsAndMappingRulesAlone() {
        grantor.grant("caddy");
        grantor.grant("chat");
        jdbc.update("INSERT INTO mapping_rules VALUES ('AllUsers', 'ROLE', 'demo_user', 'true', 'EQUALS')");

        assertThat(grantor.revoke("caddy")).isNull();

        assertThat(jdbc.queryForList("SELECT name FROM mapping_rules ORDER BY name", String.class))
                .containsExactly("AllUsers", "chat-m2m");
    }

    @Test
    void reconcileGrantsOnlyClientsWithTheHubApiAudience() {
        grantor.reconcile(List.of(new HubApiAccessGrantor.ClientAudience("caddy", AUDIENCE),
                new HubApiAccessGrantor.ClientAudience("other", "orchestration-api")));

        assertThat(jdbc.queryForList("SELECT name FROM mapping_rules", String.class)).containsExactly("caddy-m2m");
    }

    @Test
    void failureIsReturnedAsMessageNotThrown() {
        jdbc.execute("DROP TABLE mapping_rules_applied_roles");

        assertThat(grantor.grant("caddy")).contains("Could not grant").contains("grant-webmodeler-public-api-access.sh caddy");
        // the whole grant is one transaction: nothing is left half-done
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mapping_rules", Integer.class)).isZero();
    }

    @Test
    void withoutIdentityDatabaseNothingHappens() {
        HubApiAccessGrantor unconfigured = new HubApiAccessGrantor("", "identity", "", AUDIENCE);

        assertThat(unconfigured.isConfigured()).isFalse();
        assertThat(unconfigured.sync("caddy", AUDIENCE)).isNull();
    }
}
