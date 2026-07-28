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
        return jdbcClient.sql("SELECT * FROM users ORDER BY username").query(ROW_MAPPER).list();
    }

    public Optional<User> findByUsername(String username) {
        return jdbcClient.sql("SELECT * FROM users WHERE username = :username")
                .param("username", username)
                .query(ROW_MAPPER)
                .optional();
    }

    public Optional<User> findById(UUID id) {
        return jdbcClient.sql("SELECT * FROM users WHERE id = :id")
                .param("id", id)
                .query(ROW_MAPPER)
                .optional();
    }

    public User insert(String username, String email, String passwordHash, boolean admin) {
        User user = new User(UUID.randomUUID(), username, email, passwordHash, admin, true, Instant.now());
        jdbcClient.sql("""
                INSERT INTO users (id, username, email, password_hash, is_admin, enabled, created_at)
                VALUES (:id, :username, :email, :passwordHash, :admin, :enabled, :createdAt)
                """)
                .param("id", user.id())
                .param("username", user.username())
                .param("email", user.email())
                .param("passwordHash", user.passwordHash())
                .param("admin", user.admin())
                .param("enabled", user.enabled())
                .param("createdAt", java.sql.Timestamp.from(user.createdAt()))
                .update();
        return user;
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
                    rs.getString("username"),
                    rs.getString("email"),
                    rs.getString("password_hash"),
                    rs.getBoolean("is_admin"),
                    rs.getBoolean("enabled"),
                    rs.getTimestamp("created_at").toInstant());
        }
    }
}
