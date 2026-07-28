package com.jeltechnologies.camundaidentityprovider.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Migrates an existing "users" table from the old username-based schema, if present. No-ops on a
 * fresh install, where {@code schema.sql} already created the table with the current schema.
 *
 * <p>This runs as plain Java/JDBC, not a {@code spring.sql.init.schema-locations} file: Spring's
 * script splitter cuts SQL scripts into statements on every {@code ;} it sees, including ones
 * inside a PL/pgSQL {@code DO $$ ... $$} block, truncating it mid-statement and sending Postgres
 * an unterminated dollar-quote (found by testing against a real Postgres instance - H2 doesn't
 * support DO blocks at all, so this never showed up against the H2 test profile). Sending the
 * whole block as one JDBC call sidesteps Spring's splitter entirely.
 *
 * <p>{@code @Order(HIGHEST_PRECEDENCE)} so this always runs before {@link DemoUserSeeder} or
 * anything else that touches the users table - on an unmigrated database, the table still has no
 * "name" column until this runs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Profile("!test")
public class UsersTableMigration implements ApplicationRunner {

    private final JdbcClient jdbcClient;

    public UsersTableMigration(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbcClient.sql("""
                DO $$
                BEGIN
                    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'users' AND column_name = 'username') THEN
                        ALTER TABLE users RENAME COLUMN username TO name;
                        ALTER TABLE users DROP CONSTRAINT IF EXISTS users_username_key;
                    END IF;
                END $$;
                """).update();
    }
}
