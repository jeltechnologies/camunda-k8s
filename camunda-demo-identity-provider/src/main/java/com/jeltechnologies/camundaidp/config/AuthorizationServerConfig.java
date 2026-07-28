package com.jeltechnologies.camundaidp.config;

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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

@Configuration
public class AuthorizationServerConfig {

    private final IdpProperties idpProperties;

    public AuthorizationServerConfig(IdpProperties idpProperties) {
        this.idpProperties = idpProperties;
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder()
                .issuer(idpProperties.publicIssuer())
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
     * Loads the RSA signing key from {@code idp.jwt-signing-key-pem} (a PKCS8 PEM, provided via
     * the camunda-idp-signing-key Kubernetes Secret in the real cluster - see
     * 2-install-camunda-microk8s.sh) so every replica signs/validates with the same key and a pod
     * restart doesn't invalidate outstanding tokens. Falls back to a freshly generated ephemeral
     * key when unset, which is fine for a single local-dev instance but wrong for anything with
     * more than one replica.
     */
    @Bean
    public JWKSource<SecurityContext> jwkSource() {
        KeyPair keyPair = idpProperties.jwtSigningKeyPem() == null || idpProperties.jwtSigningKeyPem().isBlank()
                ? generateEphemeralRsaKey()
                : loadRsaKeyPair(idpProperties.jwtSigningKeyPem());
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
     * Camunda validates tokens by expected `aud` and reads the username from
     * `preferred_username` - neither is inferred automatically, so stamp both here.
     */
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer() {
        return context -> {
            String clientId = context.getRegisteredClient().getClientId();
            context.getClaims().audience(audiencesFor(clientId));

            if (context.getPrincipal() != null && context.getPrincipal().getName() != null) {
                context.getClaims().claim("preferred_username", context.getPrincipal().getName());
            }
        };
    }

    private static final String CONSOLE_AUDIENCE = "console-api";

    /**
     * Returns plain {@link java.util.ArrayList}s, not {@code List.of(...)}. The audience ends up
     * in a JWT claims map that {@link JdbcOAuth2AuthorizationService} JSON-serializes into
     * Postgres; {@code List.of(...)}'s concrete type is a JDK-internal class
     * ({@code ImmutableCollections$List12} etc.) that Jackson's polymorphic-type allowlist
     * refuses to deserialize on read-back - found by actually testing a refresh-token request
     * against a second instance, which is exactly the scenario this DB-backed service exists for.
     */
    private java.util.List<String> audiencesFor(String clientId) {
        IdpProperties.Clients clients = idpProperties.clients();
        return switch (clientId) {
            case "camunda-identity" -> new java.util.ArrayList<>(java.util.List.of(clients.identity().audience()));
            case "orchestration" -> new java.util.ArrayList<>(java.util.List.of(clients.orchestration().audience()));
            case "optimize" -> new java.util.ArrayList<>(java.util.List.of(clients.optimize().audience()));
            case "web-modeler" -> new java.util.ArrayList<>(java.util.List.of(
                    clients.webModeler().clientApiAudience(), clients.webModeler().publicApiAudience()));
            case "console" -> new java.util.ArrayList<>(java.util.List.of(CONSOLE_AUDIENCE));
            default -> new java.util.ArrayList<>(java.util.List.of(clientId));
        };
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
                    "idp.jwt-signing-key-pem is not a valid PKCS8 RSA private key PEM", e);
        }
    }
}
