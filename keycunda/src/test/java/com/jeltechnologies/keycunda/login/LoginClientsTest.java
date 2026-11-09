package com.jeltechnologies.keycunda.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jeltechnologies.keycunda.user.Groups;
import com.jeltechnologies.keycunda.user.User;
import com.jeltechnologies.keycunda.user.UserGroupRepository;
import com.jeltechnologies.keycunda.user.UserRepository;

/** Admin-managed login clients: the admin pages, the registered client Spring sees, tokens, and the authorize endpoint. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "keycunda.authorizations-enabled=true")
@ActiveProfiles("test")
@WithMockUser(roles = "ADMIN")
class LoginClientsTest {

    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    @Autowired
    private WebApplicationContext webApplicationContext;
    @Autowired
    private LoginClientRepository loginClients;
    @Autowired
    private RegisteredClientRepository registeredClients;
    @Autowired
    private OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserGroupRepository userGroupRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    private static String newId() {
        return "app-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private MockHttpServletRequestBuilder authorize(String clientId, String redirectUri) {
        java.net.URI uri = org.springframework.web.util.UriComponentsBuilder.fromPath("/oauth2/authorize")
                .queryParam("response_type", "code").queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri).queryParam("scope", "openid")
                .queryParam("state", "s").queryParam("code_challenge", CHALLENGE)
                .queryParam("code_challenge_method", "S256").build().encode().toUri();
        return get(uri).with(user("demo@example.com"));
    }

    @Test
    void adminCreatesEditsAndDeletesALoginClient() throws Exception {
        String id = newId();
        mockMvc.perform(post("/admin/login-clients").with(csrf()).param("clientId", id)
                .param("redirectUris", "https://ticket.example.com/login/oauth2/code/keycunda\nhttps://*.example.org/*")
                .param("knownAudiences", "orchestration-api").param("customAudience", "my-api"))
                .andExpect(redirectedUrl("/admin/login-clients"));
        LoginClient client = loginClients.findByClientId(id).orElseThrow();
        assertThat(client.redirectPatterns()).containsExactly("https://ticket.example.com/login/oauth2/code/keycunda", "https://*.example.org/*");
        assertThat(client.audience()).isEqualTo("orchestration-api, my-api");

        mockMvc.perform(post("/admin/login-clients/{id}/edit", client.id()).with(csrf())
                .param("clientId", id).param("redirectUris", "https://other.example.com/cb").param("knownAudiences", "orchestration-api"))
                .andExpect(redirectedUrl("/admin/login-clients"));
        assertThat(loginClients.findById(client.id()).orElseThrow().redirectPatterns()).containsExactly("https://other.example.com/cb");

        mockMvc.perform(get("/admin/login-clients")).andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString(id)));
        mockMvc.perform(post("/admin/login-clients/{id}/delete", client.id()).with(csrf()))
                .andExpect(redirectedUrl("/admin/login-clients"));
        assertThat(loginClients.findByClientId(id)).isEmpty();
    }

    @Test
    void clientIdCanBeRenamedButNotToATakenOrInvalidId() throws Exception {
        String id = newId();
        String taken = newId();
        LoginClient client = loginClients.insert(id, "orchestration-api", "https://a.example.com/cb");
        loginClients.insert(taken, "orchestration-api", "https://b.example.com/cb");

        String renamed = newId();
        mockMvc.perform(post("/admin/login-clients/{id}/edit", client.id()).with(csrf()).param("clientId", renamed)
                .param("redirectUris", "https://a.example.com/cb")).andExpect(redirectedUrl("/admin/login-clients"));
        assertThat(loginClients.findByClientId(id)).isEmpty();
        assertThat(loginClients.findByClientId(renamed)).isPresent();
        assertThat(registeredClients.findByClientId(renamed)).isNotNull();
        assertThat(registeredClients.findByClientId(id)).isNull();

        for (String bad : List.of(taken, "orchestration", "has space")) {
            mockMvc.perform(post("/admin/login-clients/{id}/edit", client.id()).with(csrf()).param("clientId", bad)
                    .param("redirectUris", "https://a.example.com/cb"))
                    .andExpect(redirectedUrl("/admin/login-clients/" + client.id() + "/edit"));
            assertThat(loginClients.findById(client.id()).orElseThrow().clientId()).isEqualTo(renamed);
        }
    }

    @Test
    void invalidInputIsRejected() throws Exception {
        mockMvc.perform(post("/admin/login-clients").with(csrf()).param("clientId", newId()).param("redirectUris", ""))
                .andExpect(redirectedUrl("/admin/login-clients/new"));
        mockMvc.perform(post("/admin/login-clients").with(csrf()).param("clientId", newId()).param("redirectUris", "http://ticket.example.com/cb"))
                .andExpect(redirectedUrl("/admin/login-clients/new"));
        mockMvc.perform(post("/admin/login-clients").with(csrf()).param("clientId", "orchestration").param("redirectUris", "https://a.example.com/cb"))
                .andExpect(redirectedUrl("/admin/login-clients/new"));
        assertThat(loginClients.findByClientId("orchestration")).isEmpty();

        String id = newId();
        loginClients.insert(id, "orchestration-api", "https://a.example.com/cb");
        mockMvc.perform(post("/admin/login-clients").with(csrf()).param("clientId", id).param("redirectUris", "https://a.example.com/cb"))
                .andExpect(redirectedUrl("/admin/login-clients/new"));
    }

    @Test
    void apiClientsAndLoginClientsCannotShareAnId() throws Exception {
        String id = newId();
        mockMvc.perform(post("/admin/clients").with(csrf()).param("clientId", id).param("secret", "abcdef0123456789abcdef"))
                .andExpect(redirectedUrl("/admin/clients"));
        mockMvc.perform(post("/admin/login-clients").with(csrf()).param("clientId", id).param("redirectUris", "https://a.example.com/cb"))
                .andExpect(redirectedUrl("/admin/login-clients/new"));
        assertThat(loginClients.findByClientId(id)).isEmpty();

        String loginId = newId();
        loginClients.insert(loginId, "orchestration-api", "https://a.example.com/cb");
        mockMvc.perform(post("/admin/clients").with(csrf()).param("clientId", loginId).param("secret", "abcdef0123456789abcdef"))
                .andExpect(redirectedUrl("/admin/clients/new"));
    }

    @Test
    void registeredClientIsPublicPkceWithPatternsInItsSettings() {
        String id = newId();
        loginClients.insert(id, "orchestration-api", "https://ticket.example.com/cb\nhttps://*.example.org/*");
        RegisteredClient client = registeredClients.findByClientId(id);

        assertThat(client).isNotNull();
        assertThat(registeredClients.findById(id)).isNotNull();
        assertThat(client.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(client.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(client.getAuthorizationGrantTypes()).contains(AuthorizationGrantType.AUTHORIZATION_CODE);
        assertThat(RedirectPatterns.of(client)).containsExactly("https://ticket.example.com/cb", "https://*.example.org/*");
        assertThat(registeredClients.findByClientId("orchestration")).satisfies(c -> assertThat(RedirectPatterns.of(c)).isNull());
    }

    @Test
    void accessTokenHasTheClientsAudienceAndTheUsersGroups() {
        String id = newId();
        loginClients.insert(id, "orchestration-api, my-api", "https://ticket.example.com/cb");
        User user = userRepository.insert("Tanja", "tanja-" + UUID.randomUUID() + "@example.com", "{noop}x", false);
        userGroupRepository.setGroups(user.id(), List.of(Groups.TASK_USERS, Groups.DEVELOPERS));

        Authentication principal = new UsernamePasswordAuthenticationToken(user.email(), null);
        JwtEncodingContext context = JwtEncodingContext
                .with(JwsHeader.with(SignatureAlgorithm.RS256), JwtClaimsSet.builder().subject(user.email()))
                .registeredClient(registeredClients.findByClientId(id)).principal(principal)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).tokenType(OAuth2TokenType.ACCESS_TOKEN).build();
        tokenCustomizer.customize(context);

        var claims = context.getClaims().build().getClaims();
        assertThat(claims.get("aud")).isEqualTo(List.of("orchestration-api", "my-api"));
        assertThat(claims.get("preferred_username")).isEqualTo(user.email());
        assertThat(claims.get("groups")).isEqualTo(List.of(Groups.DEVELOPERS, Groups.TASK_USERS));
    }

    @Test
    void authorizeEndpointAcceptsMatchingRedirectsOnly() throws Exception {
        String id = newId();
        loginClients.insert(id, "orchestration-api", "https://ticket.example.com/login/oauth2/code/keycunda\nhttps://*.example.org/*");

        mockMvc.perform(authorize(id, "https://ticket.example.com/login/oauth2/code/keycunda"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", Matchers.startsWith("https://ticket.example.com/login/oauth2/code/keycunda?code=")));
        mockMvc.perform(authorize(id, "https://deep.sub.example.org/any/path"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", Matchers.startsWith("https://deep.sub.example.org/any/path?code=")));
        mockMvc.perform(authorize(id, "https://ticket.example.com/other"))
                .andExpect(status().isBadRequest()).andExpect(status().reason(Matchers.containsString("redirect_uri")));
        mockMvc.perform(authorize(id, "https://evil.com/steal"))
                .andExpect(status().isBadRequest()).andExpect(status().reason(Matchers.containsString("redirect_uri")));
    }

    @Test
    void fixedClientsKeepExactMatching() throws Exception {
        mockMvc.perform(authorize("web-modeler", "https://ticket.example.com/some/where"))
                .andExpect(status().isBadRequest()).andExpect(status().reason(Matchers.containsString("redirect_uri")));
    }

    @Test
    void navShowsLoginClientsWhenAuthorizationsAreEnabled() throws Exception {
        mockMvc.perform(get("/admin/users")).andExpect(content().string(Matchers.containsString("/keycunda/login-clients")));
    }
}
