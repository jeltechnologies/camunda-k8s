package com.jeltechnologies.keycunda.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jeltechnologies.keycunda.secret.ApplyJobStatus;
import com.jeltechnologies.keycunda.secret.ClusterSecretsApplier;
import com.jeltechnologies.keycunda.secret.Secret;

/**
 * Kubernetes is Secrets Management's source of truth, not a database (see SecretsWorkingCopy) - so
 * {@link ClusterSecretsApplier} is replaced with a Mockito mock here ({@code @MockitoBean}) rather
 * than exercised for real, both because it would otherwise try a real network call on the first
 * page load (see the note in ClusterSecretsApplierTest about why a *real* cluster connection
 * attempt, not a mock, is the wrong tool inside this sandbox) and because these tests are about
 * the working-copy/session behavior, not Kubernetes I/O (already covered separately).
 *
 * <p>Every request in a given test reuses the same {@link MockHttpSession} - {@code
 * SecretsWorkingCopy} is session-scoped, so a fresh session each call would mean a fresh, empty
 * working copy each call.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@WithMockUser(roles = "ADMIN")
class AdminSecretControllerTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ApplyJobStatus applyJobStatus;

    @MockitoBean
    private ClusterSecretsApplier clusterSecretsApplier;

    private MockMvc mockMvc;
    private MockHttpSession session;

    @BeforeEach
    void setUp() throws Exception {
        when(clusterSecretsApplier.fetch(anyString())).thenReturn(Map.of());
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        // First request in the session seeds the (empty, per the stub above) working copy.
        MvcResult result = mockMvc.perform(get("/admin/secrets").session(new MockHttpSession()))
                .andExpect(status().isOk())
                .andReturn();
        session = (MockHttpSession) result.getRequest().getSession();
    }

    @Test
    void addingASecretShowsUpInTheWorkingCopy() throws Exception {
        mockMvc.perform(post("/admin/secrets").session(session).with(csrf())
                        .param("key", "MY_KEY")
                        .param("value", "my-value"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/secrets"));

        mockMvc.perform(get("/admin/secrets").session(session))
                .andExpect(status().isOk())
                .andExpect(model().attribute("secrets", hasItem(new Secret("MY_KEY", "my-value"))));
    }

    @Test
    void rejectsADuplicateKey() throws Exception {
        mockMvc.perform(post("/admin/secrets").session(session).with(csrf())
                .param("key", "DUPLICATE_KEY").param("value", "first"));

        mockMvc.perform(post("/admin/secrets").session(session).with(csrf())
                        .param("key", "DUPLICATE_KEY").param("value", "second"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/secrets/new"))
                .andExpect(flash().attributeExists("error"));

        mockMvc.perform(get("/admin/secrets").session(session))
                .andExpect(model().attribute("secrets", hasItem(new Secret("DUPLICATE_KEY", "first"))));
    }

    @Test
    void rejectsAnInvalidKey() throws Exception {
        mockMvc.perform(post("/admin/secrets").session(session).with(csrf())
                        .param("key", "not a valid key")
                        .param("value", "whatever"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/secrets/new"))
                .andExpect(flash().attributeExists("error"));
    }

    @Test
    void editingRenamesTheKeyAndUpdatesTheValue() throws Exception {
        mockMvc.perform(post("/admin/secrets").session(session).with(csrf())
                .param("key", "OLD_KEY").param("value", "old-value"));

        mockMvc.perform(post("/admin/secrets/{key}/edit", "OLD_KEY").session(session).with(csrf())
                        .param("newKey", "NEW_KEY")
                        .param("value", "new-value"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/secrets"));

        mockMvc.perform(get("/admin/secrets").session(session))
                .andExpect(model().attribute("secrets", hasItem(new Secret("NEW_KEY", "new-value"))));
    }

    @Test
    void renamingToAnAlreadyExistingKeyIsRejected() throws Exception {
        mockMvc.perform(post("/admin/secrets").session(session).with(csrf())
                .param("key", "TAKEN_KEY").param("value", "taken"));
        mockMvc.perform(post("/admin/secrets").session(session).with(csrf())
                .param("key", "RENAME_ME").param("value", "value"));

        mockMvc.perform(post("/admin/secrets/{key}/edit", "RENAME_ME").session(session).with(csrf())
                        .param("newKey", "TAKEN_KEY")
                        .param("value", "value"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("error"));

        mockMvc.perform(get("/admin/secrets").session(session))
                .andExpect(model().attribute("secrets", hasItem(new Secret("RENAME_ME", "value"))));
    }

    @Test
    void deletingRemovesTheSecret() throws Exception {
        mockMvc.perform(post("/admin/secrets").session(session).with(csrf())
                .param("key", "TO_DELETE").param("value", "value"));

        mockMvc.perform(post("/admin/secrets/{key}/delete", "TO_DELETE").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/secrets"));

        mockMvc.perform(get("/admin/secrets").session(session))
                .andExpect(model().attribute("secrets", org.hamcrest.Matchers.not(hasItem(new Secret("TO_DELETE", "value")))));
    }

    @Test
    void importingAnEnvFileUpsertsNewAndExistingKeys() throws Exception {
        mockMvc.perform(post("/admin/secrets").session(session).with(csrf())
                .param("key", "EXISTING_KEY").param("value", "old-value"));

        String envContent = "EXISTING_KEY=updated-value\nBRAND_NEW_KEY=fresh-value\n";
        MockMultipartFile file = new MockMultipartFile("file", "secrets.env", "text/plain", envContent.getBytes());

        mockMvc.perform(multipart("/admin/secrets/import").file(file).session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/secrets"));

        mockMvc.perform(get("/admin/secrets").session(session))
                .andExpect(model().attribute("secrets", hasItem(new Secret("EXISTING_KEY", "updated-value"))))
                .andExpect(model().attribute("secrets", hasItem(new Secret("BRAND_NEW_KEY", "fresh-value"))));
    }

    @Test
    void applyToClusterMarksTheJobSuccessfulAndResyncsTheWorkingCopy() throws Exception {
        when(clusterSecretsApplier.apply(any(), anyString())).thenReturn(Map.of("FOO", "bar"));

        mockMvc.perform(post("/admin/secrets/apply-to-cluster").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/secrets/apply-status"));

        awaitTerminalJobState();
        assertThat(applyJobStatus.current().state()).isEqualTo(ApplyJobStatus.State.SUCCESS);

        // The resync into the session's working copy happens on the apply-status page load
        // (a real request thread), not on the background apply thread - see ApplyJobStatus.
        mockMvc.perform(get("/admin/secrets/apply-status").session(session))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/secrets").session(session))
                .andExpect(model().attribute("secrets", hasItem(new Secret("FOO", "bar"))));
    }

    @Test
    void applyToClusterMarksTheJobFailedOnError() throws Exception {
        when(clusterSecretsApplier.apply(any(), anyString())).thenThrow(new IllegalStateException("boom"));

        mockMvc.perform(post("/admin/secrets/apply-to-cluster").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        awaitTerminalJobState();
        assertThat(applyJobStatus.current().state()).isEqualTo(ApplyJobStatus.State.ERROR);
        assertThat(applyJobStatus.current().message()).contains("boom");
    }

    /** apply-to-cluster runs on a background executor - poll briefly for it to leave RUNNING
     * rather than asserting immediately after the redirect. */
    private void awaitTerminalJobState() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            ApplyJobStatus.Job job = applyJobStatus.current();
            if (job != null && job.state() != ApplyJobStatus.State.RUNNING) {
                return;
            }
            Thread.sleep(25);
        }
    }
}
