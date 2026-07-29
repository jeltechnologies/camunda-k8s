package com.jeltechnologies.camundaidentityprovider.user;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    private static final RowMapper ROW_MAPPER = new RowMapper();

    private final JdbcClient jdbcClient;

    public UserRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public long count() {
        return jdbcClient.sql("SELECT COUNT(*) FROM users").query(Long.class).single();
    }

    public List<User> findAll() {
        return jdbcClient.sql("SELECT * FROM users ORDER BY email").query(ROW_MAPPER).list();
    }

    public Optional<User> findByEmail(String email) {
        return jdbcClient.sql("SELECT * FROM users WHERE email = :email")
                .param("email", email)
                .query(ROW_MAPPER)
                .optional();
    }

    public Optional<User> findById(UUID id) {
        return jdbcClient.sql("SELECT * FROM users WHERE id = :id")
                .param("id", id)
                .query(ROW_MAPPER)
                .optional();
    }

    public User insert(String name, String email, String passwordHash, boolean admin) {
        return insert(name, email, passwordHash, admin, false);
    }

    /** Used only by DemoUserSeeder to create the one protected, install-seeded admin user. */
    public User insertDefaultAdmin(String name, String email, String passwordHash) {
        return insert(name, email, passwordHash, true, true);
    }

    private User insert(String name, String email, String passwordHash, boolean admin, boolean defaultAdmin) {
        User user = new User(UUID.randomUUID(), name, email, passwordHash, admin, true, defaultAdmin, Instant.now());
        jdbcClient.sql("""
                INSERT INTO users (id, name, email, password_hash, is_admin, enabled, is_default_admin, created_at)
                VALUES (:id, :name, :email, :passwordHash, :admin, :enabled, :defaultAdmin, :createdAt)
                """)
                .param("id", user.id())
                .param("name", user.name())
                .param("email", user.email())
                .param("passwordHash", user.passwordHash())
                .param("admin", user.admin())
                .param("enabled", user.enabled())
                .param("defaultAdmin", user.defaultAdmin())
                .param("createdAt", java.sql.Timestamp.from(user.createdAt()))
                .update();
        return user;
    }

    /**
     * Marks the row matching {@code email} as the protected default admin. Runs on every startup
     * (not just first-ever seed) so an install upgrading to this feature - or this live database,
     * which already had its demo user created before is_default_admin existed - still ends up with
     * the right row flagged.
     */
    public void markAsDefaultAdmin(String email) {
        jdbcClient.sql("UPDATE users SET is_default_admin = true WHERE email = :email")
                .param("email", email)
                .update();
    }

    public void updateNameAndEmail(UUID id, String name, String email) {
        jdbcClient.sql("UPDATE users SET name = :name, email = :email WHERE id = :id")
                .param("name", name)
                .param("email", email)
                .param("id", id)
                .update();
    }

    /**
     * Name-only update, for the default admin: unlike other users, their email can't change (see
     * AdminUserController) since it's the OIDC "sub"/preferred_username value Web Modeler and other
     * Camunda components key user identity off - changing it would orphan everything that user
     * already owns in those systems, invisibly, since the old rows aren't deleted, just unreachable.
     */
    public void updateName(UUID id, String name) {
        jdbcClient.sql("UPDATE users SET name = :name WHERE id = :id")
                .param("name", name)
                .param("id", id)
                .update();
    }

    public void updatePassword(UUID id, String passwordHash) {
        jdbcClient.sql("UPDATE users SET password_hash = :passwordHash WHERE id = :id")
                .param("passwordHash", passwordHash)
                .param("id", id)
                .update();
    }

    public void deleteById(UUID id) {
        jdbcClient.sql("DELETE FROM users WHERE id = :id").param("id", id).update();
    }

    private static final class RowMapper implements org.springframework.jdbc.core.RowMapper<User> {
        @Override
        public User mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
            return new User(
                    UUID.fromString(rs.getString("id")),
                    rs.getString("name"),
                    rs.getString("email"),
                    rs.getString("password_hash"),
                    rs.getBoolean("is_admin"),
                    rs.getBoolean("enabled"),
                    rs.getBoolean("is_default_admin"),
                    rs.getTimestamp("created_at").toInstant());
        }
    }
}
