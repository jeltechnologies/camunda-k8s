package com.jeltechnologies.camundaidentityprovider.web;

import java.io.IOException;
import java.time.Duration;

import com.jeltechnologies.camundaidentityprovider.config.IdentityProviderProperties;

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

    public RememberEmailAuthenticationSuccessHandler(IdentityProviderProperties identityProviderProperties) {
        SavedRequestAwareAuthenticationSuccessHandler handler = new SavedRequestAwareAuthenticationSuccessHandler();
        // Only used when there's no saved request to bounce back to - i.e. someone logged in by
        // navigating straight to /login rather than being redirected here mid OIDC-authorize flow.
        // The app has no page of its own worth landing on, so send them into the actual platform
        // (Web Modeler's contextPath is "/modeler" - see the "webModeler:" block in
        // template-values-camunda.yaml) rather than the framework default of "/" (bare context
        // root, /auth/, which has no handler and 404'd). Was Console; changed to Web Modeler as
        // the more useful default landing spot.
        handler.setDefaultTargetUrl("https://" + identityProviderProperties.camundaDomain() + "/modeler");
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
