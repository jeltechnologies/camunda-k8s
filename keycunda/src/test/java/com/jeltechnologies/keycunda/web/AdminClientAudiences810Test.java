package com.jeltechnologies.keycunda.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** The audiences offered when creating an API client on Camunda 8.10 (the default line): Camunda Hub replaces Web Modeler and Console. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@WithMockUser(roles = "ADMIN")
class AdminClientAudiences810Test {

    @Autowired
    private WebApplicationContext context;

    @Test
    void offersCamundaHubAndNoConsole() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        @SuppressWarnings("unchecked")
        Map<String, String> known = (Map<String, String>) mockMvc.perform(get("/admin/clients/new"))
                .andExpect(status().isOk())
                .andReturn().getModelAndView().getModel().get("knownAudiences");

        assertThat(known).containsOnly(
                Map.entry("Camunda Identity", "camunda-identity-resource-server"),
                Map.entry("Orchestration", "orchestration-api"),
                Map.entry("Optimize", "optimize-api"),
                Map.entry("Camunda Hub (internal API)", "web-modeler-api"),
                Map.entry("Camunda Hub API", "web-modeler-public-api"));
    }
}
