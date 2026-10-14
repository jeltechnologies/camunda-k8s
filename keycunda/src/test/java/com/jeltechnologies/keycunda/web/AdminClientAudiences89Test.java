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

/** The audiences offered when creating an API client on a legacy Camunda 8.9 install stay as before. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "keycunda.camunda-line=8.9")
@ActiveProfiles("test")
@WithMockUser(roles = "ADMIN")
class AdminClientAudiences89Test {

    @Autowired
    private WebApplicationContext context;

    @Test
    void keepsWebModelerAndConsole() throws Exception {
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
                Map.entry("Web Modeler (client API)", "web-modeler-api"),
                Map.entry("Web Modeler (public API)", "web-modeler-public-api"),
                Map.entry("Console", "console-api"));
    }
}
