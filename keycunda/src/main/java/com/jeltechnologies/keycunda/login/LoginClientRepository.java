package com.jeltechnologies.keycunda.login;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LoginClientRepository {

    private static final org.springframework.jdbc.core.RowMapper<LoginClient> ROW_MAPPER = (rs, rowNum) -> new LoginClient(
            UUID.fromString(rs.getString("id")), rs.getString("client_id"), rs.getString("name"),
            rs.getString("audience"), rs.getString("redirect_uris"), rs.getTimestamp("created_at").toInstant());

    private final JdbcClient jdbcClient;

    public LoginClientRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<LoginClient> findAll() {
        return jdbcClient.sql("SELECT * FROM oauth_login_clients ORDER BY client_id").query(ROW_MAPPER).list();
    }

    public Optional<LoginClient> findByClientId(String clientId) {
        return jdbcClient.sql("SELECT * FROM oauth_login_clients WHERE client_id = :clientId")
                .param("clientId", clientId).query(ROW_MAPPER).optional();
    }

    public Optional<LoginClient> findById(UUID id) {
        return jdbcClient.sql("SELECT * FROM oauth_login_clients WHERE id = :id")
                .param("id", id).query(ROW_MAPPER).optional();
    }

    public LoginClient insert(String clientId, String audience, String redirectUris) {
        LoginClient client = new LoginClient(UUID.randomUUID(), clientId, clientId, audience, redirectUris, Instant.now());
        jdbcClient.sql("""
                INSERT INTO oauth_login_clients (id, client_id, name, audience, redirect_uris, created_at)
                VALUES (:id, :clientId, :name, :audience, :redirectUris, :createdAt)
                """)
                .param("id", client.id()).param("clientId", client.clientId()).param("name", client.name())
                .param("audience", client.audience()).param("redirectUris", client.redirectUris())
                .param("createdAt", java.sql.Timestamp.from(client.createdAt()))
                .update();
        return client;
    }

    public void update(UUID id, String clientId, String audience, String redirectUris) {
        jdbcClient.sql("""
                UPDATE oauth_login_clients SET client_id = :clientId, name = :clientId, audience = :audience,
                    redirect_uris = :redirectUris WHERE id = :id
                """)
                .param("clientId", clientId).param("audience", audience).param("redirectUris", redirectUris)
                .param("id", id).update();
    }

    public void deleteById(UUID id) {
        jdbcClient.sql("DELETE FROM oauth_login_clients WHERE id = :id").param("id", id).update();
    }
}
