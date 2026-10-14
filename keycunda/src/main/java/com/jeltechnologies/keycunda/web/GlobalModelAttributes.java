package com.jeltechnologies.keycunda.web;

import com.jeltechnologies.keycunda.config.KeycundaProperties;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Makes {@code camundaDomain} and {@code isAdmin} available to every view rendered by any
 * {@code @Controller} in this app, without every controller repeating the same lookups.
 *
 * <p>{@code camundaDomain} - added for {@code admin/fragments/nav.html}'s app-switcher panel,
 * which needs to link to every Camunda component (Camunda Hub and its Clusters page, Orchestration
 * Admin/Operate/Tasklist, Optimize, Identity) and therefore needs the domain on
 * literally every admin page, not just one. The links target Camunda 8.10 paths only.
 *
 * <p>{@code isAdmin} - lets {@code nav.html} hide the Users/Clients/Secrets links for a
 * ROLE_USER (non-admin) visitor. This is UI polish only, not the actual access control - that's
 * enforced server-side in {@code SecurityConfig}'s {@code hasRole("ADMIN")} rules for those
 * paths regardless of what the nav shows, so this can never be the only thing standing between a
 * normal user and an admin-only page.
 */
@ControllerAdvice
public class GlobalModelAttributes {

    private final KeycundaProperties keycundaProperties;
    private final String camundaLine;

    public GlobalModelAttributes(KeycundaProperties keycundaProperties,
            @org.springframework.beans.factory.annotation.Value("${keycunda.camunda-line:8.10}") String camundaLine) {
        this.keycundaProperties = keycundaProperties;
        this.camundaLine = camundaLine;
    }

    /** True on the 8.10 line (Camunda Hub at /hub); false for legacy 8.9 (Web Modeler /modeler + Console). */
    @ModelAttribute("hub")
    public boolean hub() {
        return !"8.9".equals(camundaLine);
    }

    @ModelAttribute("camundaDomain")
    public String camundaDomain() {
        return keycundaProperties.camundaDomain();
    }

    @ModelAttribute("isAdmin")
    public boolean isAdmin() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
    }
}
