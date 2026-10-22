package com.jeltechnologies.keycunda.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.jeltechnologies.keycunda.client.Client;
import com.jeltechnologies.keycunda.client.ClientRepository;
import com.jeltechnologies.keycunda.identity.HubApiAccessGrantor;
import com.sun.net.httpserver.HttpServer;

class HubTokenProviderTest {

    private final ClientRepository clients = mock(ClientRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final HubApiAccessGrantor grantor = mock(HubApiAccessGrantor.class);
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private HubTokenProvider provider;
    private Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth/oauth2/token", exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization") + "|"
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"access_token\":\"abc\",\"expires_in\":300}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        Clock clock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
        when(encoder.encode(anyString())).thenReturn("{bcrypt}hash");
        when(grantor.grantsHubApi("web-modeler-public-api")).thenReturn(true);
        provider = new HubTokenProvider(clients, encoder, grantor,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/auth/oauth2/token", "web-modeler-public-api", clock);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static Client client(String secret) {
        return new Client(UUID.randomUUID(), HubTokenProvider.CLIENT_ID, HubTokenProvider.CLIENT_ID, secret, "{bcrypt}hash",
                "web-modeler-public-api", Instant.now());
    }

    @Test
    void createsTheInternalClientGrantsItAndFetchesAToken() throws IOException {
        when(clients.findByClientId(HubTokenProvider.CLIENT_ID)).thenReturn(Optional.empty());
        when(clients.insert(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(client("s3cret"));

        assertThat(provider.token()).isEqualTo("abc");

        verify(clients).insert(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(grantor).grant(HubTokenProvider.CLIENT_ID);
        String basic = "Basic " + java.util.Base64.getEncoder()
                .encodeToString((HubTokenProvider.CLIENT_ID + ":s3cret").getBytes(StandardCharsets.UTF_8));
        assertThat(authorizations).containsExactly(basic + "|grant_type=client_credentials");
    }

    @Test
    void cachesTheTokenUntilJustBeforeItExpires() throws IOException {
        when(clients.findByClientId(HubTokenProvider.CLIENT_ID)).thenReturn(Optional.of(client("s3cret")));

        provider.token();
        now = now.plusSeconds(200);
        provider.token();
        assertThat(authorizations).hasSize(1);

        now = now.plusSeconds(75); // 275 s: inside the 30 s margin before the 300 s expiry
        provider.token();
        assertThat(authorizations).hasSize(2);
    }

    @Test
    void failsWithTheGrantProblemInsteadOfCallingTheTokenEndpoint() {
        when(clients.findByClientId(HubTokenProvider.CLIENT_ID)).thenReturn(Optional.of(client("s3cret")));
        when(grantor.grant(HubTokenProvider.CLIENT_ID)).thenReturn("Identity is not ready");

        assertThatThrownBy(provider::token).isInstanceOf(IOException.class).hasMessage("Identity is not ready");
        assertThat(authorizations).isEmpty();
    }

    @Test
    void repairsAnInternalClientWithoutAudienceOrSecret() {
        Client broken = new Client(UUID.randomUUID(), HubTokenProvider.CLIENT_ID, HubTokenProvider.CLIENT_ID, null, "x",
                "something-else", Instant.now());
        when(grantor.grantsHubApi("something-else")).thenReturn(false);
        when(clients.findByClientId(HubTokenProvider.CLIENT_ID)).thenReturn(Optional.of(broken), Optional.of(client("new")));

        provider.ensureClient();

        verify(clients).updateAudience(broken.id(), "web-modeler-public-api");
        verify(clients).updateSecret(any(), anyString(), anyString());
        verify(clients, never()).insert(anyString(), anyString(), anyString(), anyString(), anyString());
    }
}
