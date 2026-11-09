package com.jeltechnologies.keycunda.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** The real authorize endpoint: the simulator may use any https redirect on the allowed domain, other clients may not. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "keycunda.simulator-base-url=https://ticket.example.com")
@ActiveProfiles("test")
class SimulatorAuthorizeEndpointTest {

    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    /** The authorization endpoint reads a GET request's parameters from the raw query string. */
    private MockHttpServletRequestBuilder authorize(String clientId, String redirectUri) {
        java.net.URI uri = org.springframework.web.util.UriComponentsBuilder.fromPath("/oauth2/authorize")
                .queryParam("response_type", "code").queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri).queryParam("scope", "openid")
                .queryParam("state", "s").queryParam("code_challenge", CHALLENGE)
                .queryParam("code_challenge_method", "S256")
                .build().encode().toUri();
        return get(uri).with(user("demo@example.com"));
    }

    @Test
    void simulatorMayUseAnyHttpsRedirectOnTheAllowedDomain() throws Exception {
        mockMvc.perform(authorize("ticketing-simulator", "https://other-host.example.com/some/where"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", Matchers.startsWith("https://other-host.example.com/some/where?code=")));
    }

    @Test
    void simulatorStillCannotRedirectOutsideTheDomain() throws Exception {
        mockMvc.perform(authorize("ticketing-simulator", "https://evil.com/steal"))
                .andExpect(status().isBadRequest())
                .andExpect(status().reason(Matchers.containsString("redirect_uri")));
    }

    @Test
    void otherClientsKeepExactMatching() throws Exception {
        mockMvc.perform(authorize("web-modeler", "https://other-host.example.com/some/where"))
                .andExpect(status().isBadRequest())
                .andExpect(status().reason(Matchers.containsString("redirect_uri")));
    }
}
