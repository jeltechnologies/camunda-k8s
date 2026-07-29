package com.jeltechnologies.camundaidentityprovider.client;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ClientRepository {

    private static final RowMapper ROW_MAPPER = new RowMapper();

    private final JdbcClient jdbcClient;

    public ClientRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Client> findAll() {
        return jdbcClient.sql("SELECT * FROM oauth_clients ORDER BY client_id").query(ROW_MAPPER).list();
    }

    public Optional<Client> findByClientId(String clientId) {
        return jdbcClient.sql("SELECT * FROM oauth_clients WHERE client_id = :clientId")
                .param("clientId", clientId)
                .query(ROW_MAPPER)
                .optional();
    }

    public Optional<Client> findById(UUID id) {
        return jdbcClient.sql("SELECT * FROM oauth_clients WHERE id = :id")
                .param("id", id)
                .query(ROW_MAPPER)
                .optional();
    }

    public Client insert(String clientId, String name, String secretHash) {
        Client client = new Client(UUID.randomUUID(), clientId, name, secretHash, Instant.now());
        jdbcClient.sql("""
                INSERT INTO oauth_clients (id, client_id, name, secret_hash, created_at)
                VALUES (:id, :clientId, :name, :secretHash, :createdAt)
                """)
                .param("id", client.id())
                .param("clientId", client.clientId())
                .param("name", client.name())
                .param("secretHash", client.secretHash())
                .param("createdAt", java.sql.Timestamp.from(client.createdAt()))
                .update();
        return client;
    }

    public void updateName(UUID id, String name) {
        jdbcClient.sql("UPDATE oauth_clients SET name = :name WHERE id = :id")
                .param("name", name)
                .param("id", id)
                .update();
    }

    public void updateSecret(UUID id, String secretHash) {
        jdbcClient.sql("UPDATE oauth_clients SET secret_hash = :secretHash WHERE id = :id")
                .param("secretHash", secretHash)
                .param("id", id)
                .update();
    }

    public void deleteById(UUID id) {
        jdbcClient.sql("DELETE FROM oauth_clients WHERE id = :id").param("id", id).update();
    }

    private static final class RowMapper implements org.springframework.jdbc.core.RowMapper<Client> {
        @Override
        public Client mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
            return new Client(
                    UUID.fromString(rs.getString("id")),
                    rs.getString("client_id"),
                    rs.getString("name"),
                    rs.getString("secret_hash"),
                    rs.getTimestamp("created_at").toInstant());
        }
    }
}
