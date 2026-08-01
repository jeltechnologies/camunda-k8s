package com.jeltechnologies.keycunda.config;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

import com.jeltechnologies.keycunda.client.ClientRepository;
import com.jeltechnologies.keycunda.user.UserRepository;

@Configuration
public class AuthorizationServerConfig {

    private final KeycundaProperties keycundaProperties;
    private final UserRepository userRepository;
    private final ClientRepository clientRepository;

    public AuthorizationServerConfig(KeycundaProperties keycundaProperties,
            UserRepository userRepository, ClientRepository clientRepository) {
        this.keycundaProperties = keycundaProperties;
        this.userRepository = userRepository;
        this.clientRepository = clientRepository;
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder()
                .issuer(keycundaProperties.publicIssuer())
                .build();
    }

    /**
     * Issued authorizations (auth codes, access/refresh tokens) persisted in Postgres instead of
     * the in-memory default - so a refresh-token request that lands on a different replica (or
     * pod restart) than the one that issued it still works.
     */
    @Bean
    public OAuth2AuthorizationService authorizationService(
            JdbcOperations jdbcOperations, RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationService(jdbcOperations, registeredClientRepository);
    }

    /**
     * Loads the RSA signing key from {@code keycunda.jwt-signing-key-pem} (a PKCS8 PEM, provided via
     * the keycunda-signing-key Kubernetes Secret in the real cluster - see
     * 2-install-camunda-microk8s.sh) so every replica signs/validates with the same key and a pod
     * restart doesn't invalidate outstanding tokens. Falls back to a freshly generated ephemeral
     * key when unset, which is fine for a single local-dev instance but wrong for anything with
     * more than one replica.
     */
    @Bean
    public JWKSource<SecurityContext> jwkSource() {
        KeyPair keyPair = keycundaProperties.jwtSigningKeyPem() == null || keycundaProperties.jwtSigningKeyPem().isBlank()
                ? generateEphemeralRsaKey()
                : loadRsaKeyPair(keycundaProperties.jwtSigningKeyPem());
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        RSAKey.Builder builder = new RSAKey.Builder(publicKey).privateKey(privateKey);
        try {
            builder.keyID(builder.build().computeThumbprint().toString());
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("Failed to compute the JWK thumbprint", e);
        }
        return new ImmutableJWKSet<>(new JWKSet(builder.build()));
    }

    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    /**
     * Camunda validates tokens by expected `aud` and reads the login identifier from
     * `preferred_username` - neither is inferred automatically, so stamp both here.
     * `preferred_username` is the user's email (the actual unique identifier - see
     * AppUserDetailsService) for human logins, or the client ID for M2M tokens. The free-text
     * display `name` is a separate claim, looked up fresh so admin-screen edits show up
     * immediately without needing a new login. `username` duplicates the same value:
     * Management Identity's own "Add mapping" UI only offers `username`/`sub`/`groups`/`roles`
     * in its claim-name dropdown - `preferred_username` isn't selectable there, so without this,
     * an M2M client can never be granted a role through that UI (confirmed `sub` alone, despite
     * carrying the same value, does not get matched by Identity's mapping-rule evaluation either -
     * only a direct SQL insert against Identity's mapping_rules table with
     * claim_name='preferred_username' was ever observed to work).
     */
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer() {
        return context -> {
            String clientId = context.getRegisteredClient().getClientId();

            // Only the access token's aud gets replaced with the resource-server audiences below.
            // JwtClaimsSet.Builder.audience(...) overwrites the whole claim rather than appending,
            // so applying this to the ID token too would drop the client_id that OIDC Core requires
            // there - and that Spring Authorization Server's own RP-initiated logout endpoint relies
            // on to resolve the RegisteredClient from id_token_hint, causing every logout to 400.
            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                context.getClaims().audience(audiencesFor(clientId));
            }

            if (context.getPrincipal() != null && context.getPrincipal().getName() != null) {
                String principalName = context.getPrincipal().getName();
                context.getClaims().claim("preferred_username", principalName);
                context.getClaims().claim("username", principalName);

                boolean isUserToken = !AuthorizationGrantType.CLIENT_CREDENTIALS.equals(context.getAuthorizationGrantType());
                if (isUserToken) {
                    // Standard OIDC "email" claim - every client is granted the "email" scope
                    // (see confidentialClient/publicClient in OidcClientsConfig) but nothing was
                    // ever stamping the claim it corresponds to. Web Modeler in particular reads
                    // this to populate its own user record; without it, the email column stayed
                    // blank and the user never got auto-provisioned into the default organization
                    // ("Access denied to organization ..." / "Could not fetch your shared resources").
                    context.getClaims().claim("email", principalName);
                    context.getClaims().claim("email_verified", true);
                    // Dedicated claim (not reusing email_verified, which happens to always be true
                    // here but means something else) so Camunda Identity's mapping rules have an
                    // explicit, unambiguous way to say "every human user of this demo" instead of
                    // one hand-maintained rule per person. See the "AllUsers" mapping rule granting
                    // baseline Web Modeler/Console/Optimize/Orchestration access.
                    context.getClaims().claim("demo_user", "true");
                    userRepository.findByEmail(principalName)
                            .ifPresent(user -> context.getClaims().claim("name", user.name()));
                } else {
                    // M2M equivalent of "demo_user" above: an explicit, unambiguous claim so a
                    // single Identity mapping rule can grant every client_credentials client (not
                    // just one hand-picked client ID) a baseline role, instead of needing a new
                    // mapping rule per client.
                    context.getClaims().claim("m2m_client", "true");
                }
            }
        };
    }

    /** Also referenced by AdminClientController to offer it as a known-audience checkbox. */
    public static final String CONSOLE_AUDIENCE = "console-api";

    /**
     * Returns plain {@link java.util.ArrayList}s, not {@code List.of(...)}. The audience ends up
     * in a JWT claims map that {@link JdbcOAuth2AuthorizationService} JSON-serializes into
     * Postgres; {@code List.of(...)}'s concrete type is a JDK-internal class
     * ({@code ImmutableCollections$List12} etc.) that Jackson's polymorphic-type allowlist
     * refuses to deserialize on read-back - found by actually testing a refresh-token request
     * against a second instance, which is exactly the scenario this DB-backed service exists for.
     */
    private java.util.List<String> audiencesFor(String clientId) {
        KeycundaProperties.Clients clients = keycundaProperties.clients();
        return switch (clientId) {
            case "camunda-identity" -> new java.util.ArrayList<>(java.util.List.of(clients.identity().audience()));
            case "orchestration" -> new java.util.ArrayList<>(java.util.List.of(clients.orchestration().audience()));
            case "connectors" -> new java.util.ArrayList<>(java.util.List.of(clients.connectors().audience()));
            case "optimize" -> new java.util.ArrayList<>(java.util.List.of(clients.optimize().audience()));
            case "web-modeler" -> new java.util.ArrayList<>(java.util.List.of(
                    clients.webModeler().clientApiAudience(), clients.webModeler().publicApiAudience()));
            case "console" -> new java.util.ArrayList<>(java.util.List.of(CONSOLE_AUDIENCE));
            default -> dynamicClientAudiences(clientId);
        };
    }

    /**
     * Admin-managed clients (see the {@code client} package) aren't in the switch above - each one
     * carries its own {@code audience} column, set via /admin/clients, precisely so a client can be
     * authorized against a real resource server (e.g. "orchestration-api", to call Orchestration's
     * REST API) instead of always getting its own client ID as audience. Falls back to the client
     * ID when unset, which was this method's unconditional behavior before the audience column
     * existed - a client created earlier and never edited keeps working exactly as before.
     */
    private java.util.List<String> dynamicClientAudiences(String clientId) {
        String audience = clientRepository.findByClientId(clientId)
                .map(com.jeltechnologies.keycunda.client.Client::audience)
                .orElse(null);
        if (audience == null || audience.isBlank()) {
            return new java.util.ArrayList<>(java.util.List.of(clientId));
        }
        return new java.util.ArrayList<>(java.util.List.of(audience.trim().split("[,\\s]+")));
    }

    private static KeyPair generateEphemeralRsaKey() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            return keyPairGenerator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Failed to generate the JWT signing key", e);
        }
    }

    /** Parses a PKCS8 PEM RSA private key and derives its public key from the CRT parameters. */
    private static KeyPair loadRsaKeyPair(String pem) {
        try {
            String base64 = pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] decoded = Base64.getDecoder().decode(base64);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            RSAPrivateCrtKey privateKey =
                    (RSAPrivateCrtKey) keyFactory.generatePrivate(new PKCS8EncodedKeySpec(decoded));
            RSAPublicKeySpec publicKeySpec =
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent());
            RSAPublicKey publicKey = (RSAPublicKey) keyFactory.generatePublic(publicKeySpec);
            return new KeyPair(publicKey, privateKey);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | ClassCastException e) {
            throw new IllegalStateException(
                    "keycunda.jwt-signing-key-pem is not a valid PKCS8 RSA private key PEM", e);
        }
    }
}
