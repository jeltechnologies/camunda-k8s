package com.jeltechnologies.camundaidentityprovider.web;

import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Controller
public class LoginController {

    @GetMapping("/login")
    public String login(@CookieValue(name = "last_email", required = false, defaultValue = "") String lastEmail,
            HttpServletRequest request, HttpServletResponse response, Model model) {
        model.addAttribute("lastEmail", lastEmail);
        // Reading a fresh HttpSessionRequestCache here is fine even though it's a different
        // instance from whatever Spring Security's filter chain used to store it - both just read
        // and write the same well-known session attribute, so no shared bean is needed. This is
        // what lets the page visibly say "User Management" instead of a plain "Sign in" when the
        // user got here by clicking "Manage Users" (unauthenticated) rather than being denied
        // access to some other Camunda component mid OIDC-authorize flow - clicking that button
        // must produce a visible change, not silently redisplay the exact same-looking page.
        SavedRequest savedRequest = new HttpSessionRequestCache().getRequest(request, response);
        boolean manageUsersFlow = savedRequest != null && savedRequest.getRedirectUrl().contains("/admin/users");
        model.addAttribute("manageUsersFlow", manageUsersFlow);
        return "login";
    }
}
