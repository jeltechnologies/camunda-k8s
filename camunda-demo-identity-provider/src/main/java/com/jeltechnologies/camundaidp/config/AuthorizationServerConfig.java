package com.jeltechnologies.camundaidp.config;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
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
     * A fresh RSA key pair generated on every startup. Fine for a single-instance demo box:
     * restarting invalidates previously issued tokens, which just means users log in again.
     */
    @Bean
    public JWKSource<SecurityContext> jwkSource() {
        KeyPair keyPair = generateRsaKey();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        RSAKey rsaKey = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(UUID.randomUUID().toString())
                .build();
        JWKSet jwkSet = new JWKSet(rsaKey);
        return new ImmutableJWKSet<>(jwkSet);
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

    private java.util.List<String> audiencesFor(String clientId) {
        IdpProperties.Clients clients = idpProperties.clients();
        return switch (clientId) {
            case "camunda-identity" -> java.util.List.of(clients.identity().audience());
            case "orchestration" -> java.util.List.of(clients.orchestration().audience());
            case "optimize" -> java.util.List.of(clients.optimize().audience());
            case "web-modeler" -> java.util.List.of(
                    clients.webModeler().clientApiAudience(), clients.webModeler().publicApiAudience());
            case "console" -> java.util.List.of(CONSOLE_AUDIENCE);
            default -> java.util.List.of(clientId);
        };
    }

    private static KeyPair generateRsaKey() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            return keyPairGenerator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate the JWT signing key", e);
        }
    }
}
