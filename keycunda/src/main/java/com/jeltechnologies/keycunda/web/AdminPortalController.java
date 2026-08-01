package com.jeltechnologies.keycunda.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Landing page for the Keycunda admin portal - a short welcome/about page explaining what
 * Keycunda is, not a dashboard. Reachable by any authenticated user (ROLE_USER or ROLE_ADMIN -
 * see SecurityConfig), unlike Users/Clients/Secrets which are ROLE_ADMIN only. No "back to
 * Camunda" link here - the app-switcher in the top bar (see admin/fragments/nav.html) already
 * covers that, and better, from every page rather than just this one.
 */
@Controller
public class AdminPortalController {

    @GetMapping("/admin")
    public String portal() {
        return "admin/portal";
    }
}
