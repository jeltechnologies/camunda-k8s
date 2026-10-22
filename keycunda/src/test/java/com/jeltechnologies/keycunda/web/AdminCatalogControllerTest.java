package com.jeltechnologies.keycunda.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jeltechnologies.keycunda.catalog.CatalogPublisher;
import com.jeltechnologies.keycunda.catalog.ConnectorTemplate;
import com.jeltechnologies.keycunda.catalog.ConnectorTemplateSource;
import com.jeltechnologies.keycunda.catalog.HubCatalogClient.CatalogAsset;

/**
 * The Catalog page and its JSON endpoints. GitHub and Hub are mocked ({@code @MockitoBean}); the
 * download/filter and Hub client logic have their own tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@WithMockUser(roles = "ADMIN")
class AdminCatalogControllerTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @MockitoBean
    private ConnectorTemplateSource source;

    @MockitoBean
    private CatalogPublisher publisher;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws IOException {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        when(source.snapshot()).thenReturn(new ConnectorTemplateSource.Snapshot("8.10.1", List.of(
                new ConnectorTemplate("io.camunda.rest", "REST Outbound Connector", "Call a REST API",
                        "https://docs/rest", 3, "{}", List.of()),
                new ConnectorTemplate("io.camunda.slack", "Slack Outbound Connector", "Post to Slack", "", 2, "{}", List.of()))));
    }

    @Test
    void pageListsTheTemplatesWithTheirCatalogState() throws Exception {
        when(publisher.publishedVersions()).thenReturn(Map.of("io.camunda.rest", 2));

        mockMvc.perform(get("/admin/catalog"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/catalog"))
                .andExpect(content().string(containsString("REST Outbound Connector")))
                .andExpect(content().string(containsString("Version 2")))
                .andExpect(content().string(containsString("Publish all standard connector templates")))
                .andExpect(content().string(containsString("Publish selected connector templates")));
    }

    @Test
    void pageStillRendersWhenHubIsNotReachable() throws Exception {
        when(publisher.publishedVersions()).thenThrow(new IOException("connection refused"));

        mockMvc.perform(get("/admin/catalog"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Camunda Hub is not available yet: connection refused")));
    }

    @Test
    void pageShowsTheGitHubError() throws Exception {
        when(source.snapshot()).thenThrow(new IOException("no internet"));
        when(publisher.publishedVersions()).thenReturn(Map.of());

        mockMvc.perform(get("/admin/catalog"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("no internet")));
    }

    @Test
    void planInAllModeCoversEveryTemplateAndReportsWhatChanges() throws Exception {
        CatalogAsset stale = new CatalogAsset("k", "io.camunda.old", "Old template", 1, false);
        when(publisher.plan(any())).thenReturn(new CatalogPublisher.Plan(
                List.of(new CatalogPublisher.Sendable("io.camunda.rest", "REST", 3, "{}", "r")), List.of(), List.of(stale),
                List.of("Foreign")));

        mockMvc.perform(post("/admin/catalog/plan").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"all\",\"selectedIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publish").value(1))
                .andExpect(jsonPath("$.needsConfirmation").value(true))
                .andExpect(jsonPath("$.willDelete[0]").value("Old template"))
                .andExpect(jsonPath("$.willUnpublish[0]").value("Foreign"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> selected = ArgumentCaptor.forClass(Set.class);
        verify(publisher).plan(selected.capture());
        org.assertj.core.api.Assertions.assertThat(selected.getValue())
                .containsExactlyInAnyOrder("io.camunda.rest", "io.camunda.slack");
    }

    @Test
    void planInSelectionModeUsesOnlyTheSelectedIds() throws Exception {
        when(publisher.plan(any())).thenReturn(new CatalogPublisher.Plan(List.of(), List.of(), List.of(), List.of()));

        mockMvc.perform(post("/admin/catalog/plan").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"selection\",\"selectedIds\":[\"io.camunda.slack\"]}"))
                .andExpect(status().isOk());

        verify(publisher).plan(Set.of("io.camunda.slack"));
    }

    @Test
    void planReportsHubFailuresAsBadGateway() throws Exception {
        when(publisher.plan(any())).thenThrow(new IOException("Hub is down"));

        mockMvc.perform(post("/admin/catalog/plan").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"all\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("Hub is down"));
    }

    @Test
    void publishRunsInTheBackgroundAndStatusReportsTheResult() throws Exception {
        when(publisher.publish(any(), any())).thenReturn(new CatalogPublisher.Result(2, 0, 0));

        mockMvc.perform(post("/admin/catalog/publish").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"all\"}"))
                .andExpect(status().isAccepted());

        for (int i = 0; i < 50; i++) {
            String body = mockMvc.perform(get("/admin/catalog/publish-status.json")).andReturn().getResponse()
                    .getContentAsString();
            if (body.contains("SUCCESS")) {
                org.assertj.core.api.Assertions.assertThat(body).contains("Published 2 connector templates");
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("publish job did not finish");
    }

    @Test
    @WithMockUser(roles = "USER")
    void nonAdminsAreRefused() throws Exception {
        mockMvc.perform(get("/admin/catalog")).andExpect(status().isForbidden());
    }
}
