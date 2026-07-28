package com.jeltechnologies.camundaidentityprovider.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

/**
 * Fixed, small set of first-party OAuth2 clients: one per Camunda component. There is no
 * client-management UI or database table for these on purpose - the set never changes without a
 * code change to this file (and the matching Camunda Helm values), so a config-driven in-memory
 * repository built at startup from {@link IdentityProviderProperties} is all that's needed.
 *
 * <p>Client IDs, redirect URI shapes and required scopes follow Camunda's documented "generic
 * OIDC provider" contract. Verify these against the pinned camunda-platform Helm chart version
 * before going live - alpha chart versions can drift from the published docs.
 */
@Configuration
public class OidcClientsConfig {

    private static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(30);
    private static final Duration REFRESH_TOKEN_TTL = Duration.ofHours(12);

    @Bean
    public RegisteredClientRepository registeredClientRepository(IdentityProviderProperties props, PasswordEncoder passwordEncoder) {
        String domain = props.camundaDomain();
        IdentityProviderProperties.Clients clients = props.clients();

        RegisteredClient camundaIdentity = confidentialClient("camunda-identity", clients.identity().secret(),
                passwordEncoder, "https://" + domain + "/identity/auth/login-callback", false);

        RegisteredClient orchestration = confidentialClient("orchestration", clients.orchestration().secret(),
                passwordEncoder, "https://" + domain + "/orchestration/sso-callback", true);

        RegisteredClient optimize = confidentialClient("optimize", clients.optimize().secret(),
                passwordEncoder, "https://" + domain + "/optimize/api/authentication/callback", false);

        // Public/PKCE, like console: the real camunda-platform chart values have no client-secret
        // field for web-modeler, confirmed against `helm show values` for the pinned chart version.
        RegisteredClient webModeler = publicClient("web-modeler", "https://" + domain + "/modeler/login-callback");

        RegisteredClient console = publicClient("console", "https://" + domain + "/console/");

        return new InMemoryRegisteredClientRepository(
                camundaIdentity, orchestration, optimize, webModeler, console);
    }

    private RegisteredClient confidentialClient(String clientId, String secret, PasswordEncoder passwordEncoder,
            String redirectUri, boolean clientCredentials) {
        RegisteredClient.Builder builder = RegisteredClient.withId(clientId)
                .clientId(clientId)
                .clientSecret(passwordEncoder.encode(secret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(redirectUri)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope(OidcScopes.EMAIL)
                .scope("offline_access")
                .clientSettings(ClientSettings.builder().requireAuthorizationConsent(false).build())
                .tokenSettings(tokenSettings());

        if (clientCredentials) {
            builder.authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS);
        }
        return builder.build();
    }

    private RegisteredClient publicClient(String clientId, String redirectUri) {
        return RegisteredClient.withId(clientId)
                .clientId(clientId)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(redirectUri)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope(OidcScopes.EMAIL)
                .scope("offline_access")
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(false)
                        .requireProofKey(true)
                        .build())
                .tokenSettings(tokenSettings())
                .build();
    }

    private TokenSettings tokenSettings() {
        return TokenSettings.builder()
                .accessTokenTimeToLive(ACCESS_TOKEN_TTL)
                .refreshTokenTimeToLive(REFRESH_TOKEN_TTL)
                .reuseRefreshTokens(false)
                .build();
    }
}
