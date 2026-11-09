package com.jeltechnologies.keycunda.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** With authorizations disabled (the default) the group UI is hidden and existing memberships are left alone. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@WithMockUser(roles = "ADMIN")
class UserGroupsHiddenTest {

    @Autowired
    private WebApplicationContext webApplicationContext;
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

    @Test
    void pagesShowNoGroups() throws Exception {
        User user = userRepository.insert("Hidden", "h-" + UUID.randomUUID() + "@example.com", "{noop}x", false);
        for (String url : List.of("/admin/users", "/admin/users/new", "/admin/users/" + user.id() + "/edit")) {
            mockMvc.perform(get(url)).andExpect(status().isOk())
                    .andExpect(content().string(Matchers.not(Matchers.containsString("Groups"))))
                    .andExpect(content().string(Matchers.not(Matchers.containsString("task-users"))));
        }
    }

    @Test
    void editingAUserKeepsTheirGroups() throws Exception {
        User user = userRepository.insert("Keep", "k-" + UUID.randomUUID() + "@example.com", "{noop}x", false);
        userGroupRepository.setGroups(user.id(), List.of(Groups.TASK_USERS));

        mockMvc.perform(post("/admin/users/{id}/edit", user.id()).with(csrf())
                .param("name", "Keep 2").param("email", user.email()))
                .andExpect(redirectedUrl("/admin/users"));

        assertThat(userGroupRepository.findGroupNames(user.id())).containsExactly(Groups.TASK_USERS);
    }
}
