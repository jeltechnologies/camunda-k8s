package com.jeltechnologies.camundaidentityprovider.web;

import java.io.IOException;
import java.time.Duration;

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

    private final AuthenticationSuccessHandler delegate = new SavedRequestAwareAuthenticationSuccessHandler();

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
