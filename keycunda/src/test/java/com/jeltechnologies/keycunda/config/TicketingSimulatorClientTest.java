package com.jeltechnologies.keycunda.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.test.context.ActiveProfiles;

import com.jeltechnologies.keycunda.user.Groups;
import com.jeltechnologies.keycunda.user.User;
import com.jeltechnologies.keycunda.user.UserGroupRepository;
import com.jeltechnologies.keycunda.user.UserRepository;

/** The ticketing simulator's login client, and the `groups` claim in user tokens. */
@SpringBootTest(properties = "keycunda.simulator-base-url=https://ticket.example.com/")
@ActiveProfiles("test")
class TicketingSimulatorClientTest {

    @Autowired
    private RegisteredClientRepository registeredClients;
    @Autowired
    private OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserGroupRepository userGroupRepository;

    private JwtEncodingContext context(String clientId, String principal, AuthorizationGrantType grant) {
        Authentication authentication = new UsernamePasswordAuthenticationToken(principal, null);
        RegisteredClient client = registeredClients.findByClientId(clientId);
        return JwtEncodingContext
                .with(JwsHeader.with(SignatureAlgorithm.RS256), JwtClaimsSet.builder().subject(principal))
                .registeredClient(client)
                .principal(authentication)
                .authorizationGrantType(grant)
                .tokenType(OAuth2TokenType.ACCESS_TOKEN)
                .build();
    }

    @Test
    void simulatorIsAPublicPkceClientWithItsRedirectUris() {
        RegisteredClient client = registeredClients.findByClientId("ticketing-simulator");

        assertThat(client).isNotNull();
        assertThat(client.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(client.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(client.getAuthorizationGrantTypes()).contains(AuthorizationGrantType.AUTHORIZATION_CODE);
        assertThat(client.getRedirectUris()).containsExactlyInAnyOrder(
                "https://ticket.example.com/login/oauth2/code/keycunda", "https://ticket.example.com/");
        assertThat(client.getPostLogoutRedirectUris()).contains("https://ticket.example.com/");
    }

    @Test
    void accessTokenForSimulatorUsersHasOrchestrationAudienceAndGroups() {
        User user = userRepository.insert("Tanja", "tanja-" + UUID.randomUUID() + "@example.com", "{noop}x", false);
        userGroupRepository.setGroups(user.id(), List.of(Groups.TASK_USERS, Groups.DEVELOPERS));

        JwtEncodingContext context = context("ticketing-simulator", user.email(), AuthorizationGrantType.AUTHORIZATION_CODE);
        tokenCustomizer.customize(context);

        var claims = context.getClaims().build().getClaims();
        assertThat(claims.get("aud")).isEqualTo(List.of("orchestration-api"));
        assertThat(claims.get("preferred_username")).isEqualTo(user.email());
        assertThat(claims.get("groups")).isEqualTo(List.of(Groups.DEVELOPERS, Groups.TASK_USERS));
    }

    @Test
    void m2mTokensCarryNoGroupsClaim() {
        JwtEncodingContext context = context("connectors", "connectors", AuthorizationGrantType.CLIENT_CREDENTIALS);
        tokenCustomizer.customize(context);
        assertThat(context.getClaims().build().getClaims()).doesNotContainKey("groups");
    }
}
