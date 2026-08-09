package com.jeltechnologies.keycunda.web;

import java.io.IOException;
import java.time.Duration;

import com.jeltechnologies.keycunda.config.KeycundaProperties;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Sets a long-lived "last_email" cookie on every successful login, so the login page (see
 * {@link LoginController}) can pre-fill the email field and put focus straight on the password
 * field for a returning user. HttpOnly since only the server ever reads it; Secure is derived
 * from the request rather than hardcoded so local dev over plain HTTP still works (Tomcat's
 * RemoteIpValve makes request.isSecure() reflect X-Forwarded-Proto correctly behind the ingress).
 */
@Component
public class RememberEmailAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private static final String COOKIE_NAME = "last_email";
    private static final Duration MAX_AGE = Duration.ofDays(180);

    private final AuthenticationSuccessHandler delegate;

    public RememberEmailAuthenticationSuccessHandler(KeycundaProperties keycundaProperties) {
        SavedRequestAwareAuthenticationSuccessHandler handler = new SavedRequestAwareAuthenticationSuccessHandler();
        // Only used when there's no saved request to bounce back to - i.e. someone logged in by
        // navigating straight to /login by hand, rather than being redirected here from some
        // protected resource (mid OIDC-authorize flow, or via camunda-root-redirect-ingress in
        // template-keycunda-ingress.yaml, which - since it redirects to /auth/admin, itself
        // protected - does leave a saved request, so it bounces back to /auth/admin on success and
        // never reaches this fallback). Was Console, then Web Modeler; now Keycunda's own portal
        // alias (/keycunda - see keycunda-portal-alias-ingress in the same template), since a
        // direct login with no saved request has no more specific destination in mind and this app
        // is the one thing guaranteed relevant regardless of which Camunda component the admin
        // actually wants.
        handler.setDefaultTargetUrl("https://" + keycundaProperties.camundaDomain() + "/keycunda");
        this.delegate = handler;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        String contextPath = request.getContextPath();
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, authentication.getName())
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Lax")
                .path(contextPath.isEmpty() ? "/" : contextPath)
                .maxAge(MAX_AGE)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        delegate.onAuthenticationSuccess(request, response, authentication);
    }
}
