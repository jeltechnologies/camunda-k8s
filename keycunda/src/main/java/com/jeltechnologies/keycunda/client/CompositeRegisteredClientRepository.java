package com.jeltechnologies.keycunda.client;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.stereotype.Component;

import com.jeltechnologies.keycunda.config.OidcClientsConfig;
import com.jeltechnologies.keycunda.login.LoginClient;
import com.jeltechnologies.keycunda.login.LoginClientRepository;
import com.jeltechnologies.keycunda.login.RedirectPatterns;

/**
 * The {@link RegisteredClientRepository} the rest of the app actually sees: the fixed, code-defined
 * Camunda-component clients from {@link OidcClientsConfig} take priority (they're never stored in
 * the database), falling through to the admin-managed "Clients" feature (see {@link
 * ClientRepository}) for anything else. {@code @Primary} because Spring requires exactly one
 * unqualified {@code RegisteredClientRepository} bean, and every other consumer (the authorization
 * server filter chain, {@code JdbcOAuth2AuthorizationService}, ...) injects by type, not by name.
 *
 * <p>Admin-managed clients are client-credentials-only ("M2M") - there's no redirect URI or
 * authorization_code flow to configure, so creating one is a single name + generated client ID +
 * generated secret, unlike the fixed clients' per-component wiring.
 */
@Component
@Primary
public class CompositeRegisteredClientRepository implements RegisteredClientRepository {

    private final RegisteredClientRepository fixedClients;
    private final ClientRepository clientRepository;
    private final LoginClientRepository loginClientRepository;

    public CompositeRegisteredClientRepository(
            @Qualifier("fixedRegisteredClientRepository") RegisteredClientRepository fixedClients,
            ClientRepository clientRepository, LoginClientRepository loginClientRepository) {
        this.fixedClients = fixedClients;
        this.clientRepository = clientRepository;
        this.loginClientRepository = loginClientRepository;
    }

    /**
     * Admin-managed clients are created/edited through {@code AdminClientController} and {@link
     * ClientRepository} directly, not through this Spring Authorization Server SPI method - it
     * exists only to satisfy the interface (used, in stock Spring Authorization Server, by the
     * dynamic client registration endpoint, which this app doesn't enable).
     */
    @Override
    public void save(RegisteredClient registeredClient) {
        throw new UnsupportedOperationException(
                "Clients are managed via /admin/clients, not the OAuth2 dynamic client registration SPI");
    }

    @Override
    public RegisteredClient findById(String id) {
        RegisteredClient fixed = fixedClients.findById(id);
        return fixed != null ? fixed : findDynamic(id);
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        RegisteredClient fixed = fixedClients.findByClientId(clientId);
        return fixed != null ? fixed : findDynamic(clientId);
    }

    // Admin-managed clients are registered with RegisteredClient.withId(clientId) below, the same
    // convention OidcClientsConfig uses for the fixed set, so findById and findByClientId agree.
    private RegisteredClient findDynamic(String clientId) {
        RegisteredClient api = clientRepository.findByClientId(clientId).map(this::toRegisteredClient).orElse(null);
        return api != null ? api : loginClientRepository.findByClientId(clientId).map(this::toLoginRegisteredClient).orElse(null);
    }

    /**
     * A login client: public (no secret), authorization code + PKCE, scopes like the fixed browser clients. The
     * redirect URIs are patterns (see {@link RedirectPatterns}), kept in the client settings for the validators in
     * SecurityConfig; the registered URIs are placeholders, since Spring requires at least one valid URI.
     */
    private RegisteredClient toLoginRegisteredClient(LoginClient client) {
        List<String> patterns = client.redirectPatterns();
        List<String> placeholders = patterns.stream().map(RedirectPatterns::placeholder).toList();
        return RegisteredClient.withId(client.clientId())
                .clientId(client.clientId())
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUris(uris -> uris.addAll(placeholders))
                .postLogoutRedirectUris(uris -> uris.addAll(placeholders))
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope(OidcScopes.EMAIL)
                .scope("offline_access")
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(false)
                        .requireProofKey(true)
                        .setting(RedirectPatterns.SETTING, patterns)
                        .build())
                .tokenSettings(OidcClientsConfig.m2mTokenSettings())
                .build();
    }

    private RegisteredClient toRegisteredClient(Client client) {
        return RegisteredClient.withId(client.clientId())
                .clientId(client.clientId())
                .clientSecret(client.secretHash())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .clientSettings(ClientSettings.builder().requireAuthorizationConsent(false).build())
                .tokenSettings(OidcClientsConfig.m2mTokenSettings())
                .build();
    }
}
