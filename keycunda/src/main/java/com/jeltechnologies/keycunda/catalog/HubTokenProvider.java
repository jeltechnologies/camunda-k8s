package com.jeltechnologies.keycunda.catalog;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jeltechnologies.keycunda.client.Client;
import com.jeltechnologies.keycunda.client.ClientRepository;
import com.jeltechnologies.keycunda.identity.HubApiAccessGrantor;

/**
 * The bearer token Keycunda uses to call Camunda Hub's API. Keycunda calls Hub as itself, with an
 * internal client-credentials client ({@value #CLIENT_ID}, audience {@code web-modeler-public-api}),
 * not on behalf of the logged-in admin - a browser session cannot produce a Hub API token.
 *
 * <p>The client is an ordinary row of {@code oauth_clients} (hidden from the "API clients" page) that
 * is created on first use with a random secret. Its Identity grant is (re)applied every time a fresh
 * token is fetched rather than at startup: Keycunda starts before Identity has created its tables on a
 * fresh install, so a startup grant would fail then. The token comes from Keycunda's own token endpoint
 * (loopback, so no TLS or DNS involved); its {@code iss} is still the public issuer, which is what Hub
 * validates against.
 */
@Component
public class HubTokenProvider implements HubCatalogClient.BearerTokenSource {

    /** The internal client ID; reserved in {@code AdminClientController} and hidden from its list. */
    public static final String CLIENT_ID = "keycunda-catalog";

    private static final long EXPIRY_MARGIN_SECONDS = 30;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ClientRepository clientRepository;
    private final PasswordEncoder passwordEncoder;
    private final HubApiAccessGrantor grantor;
    private final String tokenUrl;
    private final String publicApiAudience;
    private final Clock clock;

    private String cachedToken;
    private Instant cachedUntil = Instant.MIN;

    @Autowired
    public HubTokenProvider(ClientRepository clientRepository, PasswordEncoder passwordEncoder,
            HubApiAccessGrantor grantor,
            @Value("${keycunda.catalog.token-url:http://localhost:${server.port:8080}/auth/oauth2/token}") String tokenUrl,
            @Value("${keycunda.clients.web-modeler.public-api-audience:web-modeler-public-api}") String publicApiAudience) {
        this(clientRepository, passwordEncoder, grantor, tokenUrl, publicApiAudience, Clock.systemUTC());
    }

    HubTokenProvider(ClientRepository clientRepository, PasswordEncoder passwordEncoder, HubApiAccessGrantor grantor,
            String tokenUrl, String publicApiAudience, Clock clock) {
        this.clientRepository = clientRepository;
        this.passwordEncoder = passwordEncoder;
        this.grantor = grantor;
        this.tokenUrl = tokenUrl;
        this.publicApiAudience = publicApiAudience;
        this.clock = clock;
    }

    @Override
    public synchronized String token() throws IOException {
        if (cachedToken != null && clock.instant().isBefore(cachedUntil)) {
            return cachedToken;
        }
        Client client = ensureClient();
        String problem = grantor.grant(CLIENT_ID);
        if (problem != null) {
            throw new IOException(problem);
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        JsonNode response;
        try {
            // read as text and parse here: RestClient's own JSON converter is Jackson 3, not this JsonNode
            String json = RestClient.create().post().uri(tokenUrl)
                    .headers(h -> h.setBasicAuth(client.clientId(), client.secret()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            response = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IOException("Could not get a token for Camunda Hub from Keycunda's token endpoint: " + e.getMessage(), e);
        }
        String token = response == null ? "" : response.path("access_token").asText("");
        if (token.isBlank()) {
            throw new IOException("Keycunda's token endpoint returned no access token");
        }
        long expiresIn = response.path("expires_in").asLong(300);
        cachedToken = token;
        cachedUntil = clock.instant().plusSeconds(Math.max(0, expiresIn - EXPIRY_MARGIN_SECONDS));
        return token;
    }

    /** Creates the internal client if missing, and makes sure its audience includes the Hub API. */
    Client ensureClient() {
        Client existing = clientRepository.findByClientId(CLIENT_ID).orElse(null);
        if (existing == null) {
            String secret = randomSecret();
            return clientRepository.insert(CLIENT_ID, CLIENT_ID, secret, passwordEncoder.encode(secret), publicApiAudience);
        }
        if (!grantor.grantsHubApi(existing.audience())) {
            clientRepository.updateAudience(existing.id(), publicApiAudience);
        }
        if (existing.secret() == null || existing.secret().isBlank()) {
            String secret = randomSecret();
            clientRepository.updateSecret(existing.id(), secret, passwordEncoder.encode(secret));
            return clientRepository.findByClientId(CLIENT_ID).orElseThrow();
        }
        return existing;
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
