package com.jeltechnologies.keycunda.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.util.List;
import java.util.UUID;

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

/** Group memberships: many-to-many, only the fixed {@link Groups#ALL} names, the default admin is always an admin. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "keycunda.authorizations-enabled=true")
@ActiveProfiles("test")
@WithMockUser(roles = "ADMIN")
class UserGroupsTest {

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

    private User newUser() {
        return userRepository.insert("Test", "t-" + UUID.randomUUID() + "@example.com", "{noop}x", false);
    }

    @Test
    void userCanBelongToSeveralGroupsAndUnknownNamesAreIgnored() {
        User user = newUser();
        userGroupRepository.setGroups(user.id(), List.of(Groups.TASK_USERS, Groups.DEVELOPERS, "hackers"));

        assertThat(userGroupRepository.findGroupNames(user.id()))
                .containsExactly(Groups.DEVELOPERS, Groups.TASK_USERS);
        assertThat(userGroupRepository.findGroupNamesByEmail(user.email()))
                .containsExactly(Groups.DEVELOPERS, Groups.TASK_USERS);

        userGroupRepository.setGroups(user.id(), List.of(Groups.ADMINS));
        assertThat(userGroupRepository.findGroupNames(user.id())).containsExactly(Groups.ADMINS);
    }

    @Test
    void groupsAreRemovedWithTheUser() {
        User user = newUser();
        userGroupRepository.setGroups(user.id(), List.of(Groups.TASK_USERS));
        userRepository.deleteById(user.id());
        assertThat(userGroupRepository.findGroupNames(user.id())).isEmpty();
    }

    @Test
    void defaultAdminIsSeededInTheAdminsGroup() {
        User admin = userRepository.findByEmail("demo@example.com").orElseThrow();
        assertThat(userGroupRepository.findGroupNames(admin.id())).contains(Groups.ADMINS);
    }

    @Test
    void addAndEditPagesStoreTheCheckedGroups() throws Exception {
        String email = "web-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/admin/users").with(csrf())
                .param("name", "Web").param("email", email).param("password", "secret-secret")
                .param("groups", Groups.TASK_USERS).param("groups", Groups.DEVELOPERS))
                .andExpect(redirectedUrl("/admin/users"));
        User user = userRepository.findByEmail(email).orElseThrow();
        assertThat(userGroupRepository.findGroupNames(user.id())).containsExactly(Groups.DEVELOPERS, Groups.TASK_USERS);

        mockMvc.perform(post("/admin/users/{id}/edit", user.id()).with(csrf())
                .param("name", "Web").param("email", email).param("groups", Groups.TASK_USERS))
                .andExpect(redirectedUrl("/admin/users"));
        assertThat(userGroupRepository.findGroupNames(user.id())).containsExactly(Groups.TASK_USERS);

        // No checkbox checked: the user ends up in no group.
        mockMvc.perform(post("/admin/users/{id}/edit", user.id()).with(csrf())
                .param("name", "Web").param("email", email))
                .andExpect(redirectedUrl("/admin/users"));
        assertThat(userGroupRepository.findGroupNames(user.id())).isEmpty();
    }

    @Test
    void addFormHasEveryGroupPreChecked() throws Exception {
        String html = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/admin/users/new"))
                .andReturn().getResponse().getContentAsString();
        for (String group : Groups.ALL) {
            assertThat(html).containsPattern("id=\"group-" + group + "\"[^>]*checked");
        }
    }

    @Test
    void defaultAdminCannotBeRemovedFromAdminsByTheEditForm() throws Exception {
        User admin = userRepository.findByEmail("demo@example.com").orElseThrow();
        mockMvc.perform(post("/admin/users/{id}/edit", admin.id()).with(csrf())
                .param("name", admin.name()).param("email", admin.email()))
                .andExpect(redirectedUrl("/admin/users"));
        assertThat(userGroupRepository.findGroupNames(admin.id())).contains(Groups.ADMINS);
    }
}
