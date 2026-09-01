package com.jeltechnologies.keycunda.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jeltechnologies.keycunda.client.Client;
import com.jeltechnologies.keycunda.client.ClientRepository;

/**
 * Covers the user-managed OAuth2 client secret handling: the secret is a plain editable field on
 * both the add and edit pages now, so both endpoints validate it against
 * {@code AdminClientController.SECRET_PATTERN}, and {@code edit} persists a changed value (there is
 * no separate {@code regenerate-secret} endpoint any more).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@WithMockUser(roles = "ADMIN")
class AdminClientControllerTest {

    private static final String VALID_SECRET = "abcdef0123456789abcdefABCDEF-_xyz";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private Client createClient(String clientId) {
        return clientRepository.insert(clientId, clientId, VALID_SECRET,
                passwordEncoder.encode(VALID_SECRET), "orchestration-api");
    }

    @Test
    void addStoresAnAdminChosenSecretVerbatim() throws Exception {
        String clientId = "custom-secret-" + UUID.randomUUID();
        String chosen = "my-own-hand-picked-secret-value";

        mockMvc.perform(post("/admin/clients").with(csrf())
                        .param("clientId", clientId)
                        .param("secret", chosen)
                        .param("customAudience", "orchestration-api"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/clients"));

        Client stored = clientRepository.findByClientId(clientId).orElseThrow();
        assertThat(stored.secret()).isEqualTo(chosen);
        assertThat(passwordEncoder.matches(chosen, stored.secretHash())).isTrue();
    }

    @Test
    void addRejectsASecretWithWhitespaceAndInsertsNothing() throws Exception {
        String clientId = "bad-secret-" + UUID.randomUUID();

        mockMvc.perform(post("/admin/clients").with(csrf())
                        .param("clientId", clientId)
                        .param("secret", "has a space in it somewhere"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/clients/new"))
                .andExpect(flash().attributeExists("error"));

        assertThat(clientRepository.findByClientId(clientId)).isEmpty();
    }

    @Test
    void addRejectsATooShortSecret() throws Exception {
        String clientId = "short-secret-" + UUID.randomUUID();

        mockMvc.perform(post("/admin/clients").with(csrf())
                        .param("clientId", clientId)
                        .param("secret", "short"))
                .andExpect(redirectedUrl("/admin/clients/new"))
                .andExpect(flash().attributeExists("error"));

        assertThat(clientRepository.findByClientId(clientId)).isEmpty();
    }

    @Test
    void editPersistsAChangedSecret() throws Exception {
        Client client = createClient("edit-secret-" + UUID.randomUUID());
        String replacement = "a-freshly-generated-looking-secret-value";

        mockMvc.perform(post("/admin/clients/{id}/edit", client.id()).with(csrf())
                        .param("customAudience", "orchestration-api")
                        .param("secret", replacement))
                .andExpect(redirectedUrl("/admin/clients"));

        Client updated = clientRepository.findById(client.id()).orElseThrow();
        assertThat(updated.secret()).isEqualTo(replacement);
        assertThat(passwordEncoder.matches(replacement, updated.secretHash())).isTrue();
    }

    @Test
    void editLeavesTheSecretUntouchedWhenTheFieldStillHoldsTheCurrentValue() throws Exception {
        Client client = createClient("unchanged-secret-" + UUID.randomUUID());
        String hashBefore = client.secretHash();

        mockMvc.perform(post("/admin/clients/{id}/edit", client.id()).with(csrf())
                        .param("customAudience", "optimize-api")
                        .param("secret", VALID_SECRET))
                .andExpect(redirectedUrl("/admin/clients"));

        Client updated = clientRepository.findById(client.id()).orElseThrow();
        assertThat(updated.secret()).isEqualTo(VALID_SECRET);
        assertThat(updated.secretHash()).isEqualTo(hashBefore);
        assertThat(updated.audience()).contains("optimize-api");
    }

    @Test
    void editRejectsAnInvalidNewSecretAndKeepsTheOldOne() throws Exception {
        Client client = createClient("reject-secret-" + UUID.randomUUID());
        String hashBefore = client.secretHash();

        mockMvc.perform(post("/admin/clients/{id}/edit", client.id()).with(csrf())
                        .param("customAudience", "orchestration-api")
                        .param("secret", "nope"))
                .andExpect(redirectedUrl("/admin/clients/" + client.id() + "/edit"))
                .andExpect(flash().attributeExists("error"));

        Client updated = clientRepository.findById(client.id()).orElseThrow();
        assertThat(updated.secret()).isEqualTo(VALID_SECRET);
        assertThat(updated.secretHash()).isEqualTo(hashBefore);
    }
}
