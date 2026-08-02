package com.jeltechnologies.keycunda.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jeltechnologies.keycunda.secret.ApplyJobStatus;
import com.jeltechnologies.keycunda.secret.ClusterSecretsApplier;
import com.jeltechnologies.keycunda.secret.Secret;

/**
 * Kubernetes is Secrets Management's only source of truth for reads (see AdminSecretController) -
 * the list page always fetches live. Add/edit/delete/import happen entirely client-side in the
 * browser now (see secrets.html's inline JS), so there's nothing server-side to test for those;
 * the only mutating endpoint left is {@code /admin/secrets/apply-to-cluster}, which takes the
 * browser's whole current working set as a JSON body. {@link ClusterSecretsApplier} is a Mockito
 * mock ({@code @MockitoBean}) rather than exercised for real - see ClusterSecretsApplierTest for
 * that.
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void listReflectsWhateverIsCurrentlyLiveInKubernetes() throws Exception {
        when(clusterSecretsApplier.fetch(anyString())).thenReturn(Map.of("LIVE_KEY", "live-value"));

        mockMvc.perform(get("/admin/secrets"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("secrets", hasItem(new Secret("LIVE_KEY", "live-value"))))
                .andExpect(model().attribute("secretsMap", Map.of("LIVE_KEY", "live-value")));
    }

    /** Regression test: the page's client-side fetch() calls must use context-path-aware URLs
     * (Thymeleaf @{...}), not bare string literals - a bare '/admin/secrets/...' skips the app's
     * "/auth" servlet context-path, which nginx's ingress has no rule for at all, so the request
     * 404s at nginx itself before ever reaching this app. See secrets.html's IMPORT_FILE_URL/
     * IMPORT_TEXT_URL/APPLY_URL/APPLY_STATUS_URL constants.
     *
     * <p>{@code .contextPath("/auth")} is required here: MockMvc's fake requests report an empty
     * context path by default (there's no real embedded Tomcat applying {@code
     * server.servlet.context-path} the way a live deployment does), so without it Thymeleaf's
     * {@code @{...}} link-building would render with no prefix at all regardless of whether the
     * template is correct - true for every other {@code @{...}}-based form in this app too, not
     * just this one, so asserting against the default request would prove nothing. */
    @Test
    void pageScriptUsesContextPathAwareUrlsForItsFetchCalls() throws Exception {
        when(clusterSecretsApplier.fetch(anyString())).thenReturn(Map.of());

        // Thymeleaf's JavaScript inlining escapes every "/" as "\/" (to prevent a "</script>"
        // breakout if a secret value ever contained that literal text) - the rendered constant is
        // "\/auth\/admin\/secrets\/import", not the unescaped path.
        mockMvc.perform(get("/auth/admin/secrets").contextPath("/auth"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\\/auth\\/admin\\/secrets\\/import\"")))
                .andExpect(content().string(containsString("\\/auth\\/admin\\/secrets\\/import-text\"")))
                .andExpect(content().string(containsString("\\/auth\\/admin\\/secrets\\/apply-to-cluster\"")))
                .andExpect(content().string(containsString("\\/auth\\/admin\\/secrets\\/apply-status.json\"")));
    }

    @Test
    void importingAnEnvFileReturnsParsedEntriesWithoutTouchingKubernetes() throws Exception {
        String envContent = "EXISTING_KEY=updated-value\nBRAND_NEW_KEY=fresh-value\n";
        MockMultipartFile file = new MockMultipartFile("file", "secrets.env", "text/plain", envContent.getBytes());

        mockMvc.perform(multipart("/admin/secrets/import").file(file).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.EXISTING_KEY").value("updated-value"))
                .andExpect(jsonPath("$.BRAND_NEW_KEY").value("fresh-value"));

        verify(clusterSecretsApplier, never()).apply(any(), anyString());
    }

    @Test
    void importingPastedEnvTextReturnsParsedEntries() throws Exception {
        mockMvc.perform(post("/admin/secrets/import-text").with(csrf())
                        .param("pastedText", "EXISTING_KEY=updated-value\nBRAND_NEW_KEY=fresh-value\n"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.EXISTING_KEY").value("updated-value"))
                .andExpect(jsonPath("$.BRAND_NEW_KEY").value("fresh-value"));

        verify(clusterSecretsApplier, never()).apply(any(), anyString());
    }

    @Test
    void importingPastedYamlTextReturnsParsedEntries() throws Exception {
        String yaml = "apiVersion: v1\n"
                + "kind: Secret\n"
                + "metadata:\n"
                + "  name: connector-secrets\n"
                + "stringData:\n"
                + "  FROM_YAML: yaml-value\n";

        mockMvc.perform(post("/admin/secrets/import-text").with(csrf())
                        .param("pastedText", yaml))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.FROM_YAML").value("yaml-value"));
    }

    @Test
    void importingUnparseablePastedTextReturnsAnError() throws Exception {
        mockMvc.perform(post("/admin/secrets/import-text").with(csrf())
                        .param("pastedText", "not a key-value pair at all, just prose."))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void importingBlankPastedTextReturnsAnError() throws Exception {
        mockMvc.perform(post("/admin/secrets/import-text").with(csrf())
                        .param("pastedText", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void applyToClusterSubmitsTheWholeWorkingSetAndMarksTheJobSuccessful() throws Exception {
        when(clusterSecretsApplier.apply(any(), anyString())).thenReturn(Map.of());
        Map<String, String> desired = Map.of("MY_KEY", "my-value", "OTHER_KEY", "other-value");

        mockMvc.perform(post("/admin/secrets/apply-to-cluster").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(desired)))
                .andExpect(status().isAccepted());

        awaitTerminalJobState();
        assertThat(applyJobStatus.current().state()).isEqualTo(ApplyJobStatus.State.SUCCESS);

        ArgumentCaptor<List<Secret>> captor = captureApplied();
        assertThat(captor.getValue()).containsExactlyInAnyOrder(
                new Secret("MY_KEY", "my-value"), new Secret("OTHER_KEY", "other-value"));
    }

    @Test
    void applyToClusterRejectsAnInvalidKeyWithoutSubmittingAnything() throws Exception {
        Map<String, String> desired = Map.of("not a valid key", "whatever");

        mockMvc.perform(post("/admin/secrets/apply-to-cluster").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(desired)))
                .andExpect(status().isBadRequest());

        verify(clusterSecretsApplier, never()).apply(any(), anyString());
    }

    @Test
    void aFailedApplyMarksTheJobAsError() throws Exception {
        when(clusterSecretsApplier.apply(any(), anyString())).thenThrow(new IllegalStateException("boom"));

        mockMvc.perform(post("/admin/secrets/apply-to-cluster").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("MY_KEY", "my-value"))))
                .andExpect(status().isAccepted());

        awaitTerminalJobState();
        assertThat(applyJobStatus.current().state()).isEqualTo(ApplyJobStatus.State.ERROR);
        assertThat(applyJobStatus.current().message()).contains("boom");

        mockMvc.perform(get("/admin/secrets/apply-status"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/secrets/apply-status.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("boom")));
    }

    private ArgumentCaptor<List<Secret>> captureApplied() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Secret>> captor = ArgumentCaptor.forClass(List.class);
        verify(clusterSecretsApplier).apply(captor.capture(), eq("connector-secrets"));
        return captor;
    }

    /** Applies run on a background executor - poll briefly for the job to leave RUNNING rather
     * than asserting immediately after the response. */
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
