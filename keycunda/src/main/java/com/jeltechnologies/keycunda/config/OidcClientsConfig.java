package com.jeltechnologies.keycunda.config;

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
 * admin UI or database table for *these* on purpose - the set never changes without a code
 * change to this file (and the matching Camunda Helm values), so a config-driven in-memory
 * repository built at startup from {@link KeycundaProperties} is all that's needed. It's
 * exposed as the {@code fixedRegisteredClientRepository} bean and wrapped by {@link
 * com.jeltechnologies.keycunda.client.CompositeRegisteredClientRepository}, which
 * is the actual {@code RegisteredClientRepository} the rest of the app sees - it falls through to
 * the admin-managed, database-backed "Clients" feature (see that package) for any client ID not
 * defined here.
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
    public RegisteredClientRepository fixedRegisteredClientRepository(KeycundaProperties props, PasswordEncoder passwordEncoder) {
        String domain = props.camundaDomain();
        KeycundaProperties.Clients clients = props.clients();

        RegisteredClient camundaIdentity = confidentialClient("camunda-identity", clients.identity().secret(),
                passwordEncoder, "https://" + domain + "/identity/auth/login-callback", false);

        // Operate/Tasklist's logout lands on a different path ("/orchestration/post-logout") than
        // the login callback ("/orchestration/sso-callback") - found by testing logout directly,
        // since neither URI is documented anywhere as the "the" post-logout redirect.
        RegisteredClient orchestration = confidentialClient("orchestration", clients.orchestration().secret(),
                passwordEncoder, "https://" + domain + "/orchestration/sso-callback", true,
                "https://" + domain + "/orchestration/post-logout");

        RegisteredClient optimize = confidentialClient("optimize", clients.optimize().secret(),
                passwordEncoder, "https://" + domain + "/optimize/api/authentication/callback", false);

        // Public/PKCE, like console: the real camunda-platform chart values have no client-secret
        // field for web-modeler, confirmed against `helm show values` for the pinned chart version.
        RegisteredClient webModeler = publicClient("web-modeler", "https://" + domain + "/modeler/login-callback");

        RegisteredClient console = publicClient("console", "https://" + domain + "/console/");

        // Pure M2M: Camunda's own Identity component provisions Connectors with its own client
        // ID "connectors" (confirmed via `helm template`'s rendered camunda-connectors-configuration
        // ConfigMap), NOT the "orchestration" client ID as originally assumed - Connectors never
        // logs a human in, so no authorization_code/redirect URI is needed at all.
        RegisteredClient connectors = m2mClient("connectors", clients.connectors().secret(), passwordEncoder);

        return new InMemoryRegisteredClientRepository(
                camundaIdentity, orchestration, optimize, webModeler, console, connectors);
    }

    private RegisteredClient confidentialClient(String clientId, String secret, PasswordEncoder passwordEncoder,
            String redirectUri, boolean clientCredentials) {
        return confidentialClient(clientId, secret, passwordEncoder, redirectUri, clientCredentials, null);
    }

    private RegisteredClient confidentialClient(String clientId, String secret, PasswordEncoder passwordEncoder,
            String redirectUri, boolean clientCredentials, String extraPostLogoutRedirectUri) {
        RegisteredClient.Builder builder = RegisteredClient.withId(clientId)
                .clientId(clientId)
                .clientSecret(passwordEncoder.encode(secret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(redirectUri)
                .postLogoutRedirectUri(redirectUri)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope(OidcScopes.EMAIL)
                .scope("offline_access")
                // ClientSettings.builder() defaults requireProofKey to true in this Spring Security
                // version (confirmed by decompiling ClientSettings.class - it's not documented as a
                // behavior change anywhere obvious). camunda-identity/orchestration/optimize are
                // traditional confidential-client flows that never send a code_challenge, so leaving
                // the default made every authorization request 400 with "OAuth 2.0 Parameter:
                // code_challenge" (OAuth2AuthorizationCodeRequestAuthenticationValidator requires PKCE
                // whenever requireProofKey is true and none was sent).
                .clientSettings(ClientSettings.builder().requireAuthorizationConsent(false).requireProofKey(false).build())
                .tokenSettings(tokenSettings());

        if (extraPostLogoutRedirectUri != null) {
            builder.postLogoutRedirectUri(extraPostLogoutRedirectUri);
        }
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
                .postLogoutRedirectUri(redirectUri)
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

    private RegisteredClient m2mClient(String clientId, String secret, PasswordEncoder passwordEncoder) {
        return RegisteredClient.withId(clientId)
                .clientId(clientId)
                .clientSecret(passwordEncoder.encode(secret))
                // Camunda's own Java client SDK (used by orchestration for M2M and by
                // Connectors) sends credentials as client_secret_post (in the token request
                // body), not HTTP Basic - confirmed by testing both directly against this
                // endpoint. Accept both rather than guess which any given caller uses.
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .clientSettings(ClientSettings.builder().requireAuthorizationConsent(false).build())
                .tokenSettings(tokenSettings())
                .build();
    }

    private TokenSettings tokenSettings() {
        return m2mTokenSettings();
    }

    /**
     * Same access/refresh TTLs as the fixed clients above, reused by {@link
     * com.jeltechnologies.keycunda.client.CompositeRegisteredClientRepository} for
     * admin-managed clients so both sets of client-credentials clients behave identically.
     */
    public static TokenSettings m2mTokenSettings() {
        return TokenSettings.builder()
                .accessTokenTimeToLive(ACCESS_TOKEN_TTL)
                .refreshTokenTimeToLive(REFRESH_TOKEN_TTL)
                .reuseRefreshTokens(false)
                .build();
    }
}
