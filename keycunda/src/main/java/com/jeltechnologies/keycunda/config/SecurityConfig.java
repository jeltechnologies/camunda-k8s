package com.jeltechnologies.keycunda.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import com.jeltechnologies.keycunda.web.RememberEmailAuthenticationSuccessHandler;

@Configuration
public class SecurityConfig {

    private final RememberEmailAuthenticationSuccessHandler rememberEmailAuthenticationSuccessHandler;

    public SecurityConfig(RememberEmailAuthenticationSuccessHandler rememberEmailAuthenticationSuccessHandler) {
        this.rememberEmailAuthenticationSuccessHandler = rememberEmailAuthenticationSuccessHandler;
    }

    /**
     * The OIDC/OAuth2 protocol endpoints (/oauth2/*, /userinfo, /.well-known/*), scoped with
     * securityMatcher() to just those paths so this chain and {@link #defaultSecurityFilterChain}
     * don't both try to claim "any request" (Spring Security 7 requires exactly one catch-all
     * chain, published last).
     */
    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer = new OAuth2AuthorizationServerConfigurer();
        http.securityMatcher(authorizationServerConfigurer.getEndpointsMatcher());
        http.with(authorizationServerConfigurer, authorizationServer -> authorizationServer.oidc(Customizer.withDefaults()));
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated());
        http.oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()));
        http.exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                new LoginUrlAuthenticationEntryPoint("/login"),
                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
        return http.build();
    }

    /**
     * HTTP Basic (email + password), for the Catalog endpoints only and only for requests that actually carry
     * a Basic Authorization header: the install script publishes the connector templates this way, as the
     * first admin. Browser requests to the same paths (session + CSRF) don't match and fall through to
     * {@link #defaultSecurityFilterChain}. Such requests have no session, so CSRF doesn't apply to them.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain catalogBasicAuthSecurityFilterChain(HttpSecurity http) throws Exception {
        RequestMatcher catalogPaths = PathPatternRequestMatcher.withDefaults().matcher("/admin/catalog/**");
        http.securityMatcher(request -> {
            String authorization = request.getHeader("Authorization");
            return authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6)
                    && catalogPaths.matches(request);
        });
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().hasRole("ADMIN"));
        http.httpBasic(Customizer.withDefaults());
        http.csrf(csrf -> csrf.disable());
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    /** The login screen, the portal landing page (any logged-in user), and the admin-only
     * management screens (Users, Clients, Secrets, Catalog - ROLE_ADMIN only, see AppUserDetailsService for
     * where that role comes from). Order matters here: the specific admin-only patterns must be
     * declared before the catch-all "/admin/**" rule, since authorizeHttpRequests uses first-match
     * semantics - a ROLE_USER hitting /admin/users would otherwise fall through to the permissive
     * rule below it. */
    @Bean
    @Order(3)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/login", "/error", "/favicon.ico", "/css/**", "/images/**").permitAll()
                .requestMatchers("/admin/users/**", "/admin/clients/**", "/admin/secrets/**",
                        "/admin/catalog/**").hasRole("ADMIN")
                .requestMatchers("/admin/**").authenticated()
                .anyRequest().authenticated());
        // Users authenticate with email + password, not a separate username - see
        // AppUserDetailsService for why the "username" parameter is bound to email.
        http.formLogin(form -> form.loginPage("/login").usernameParameter("email")
                .successHandler(rememberEmailAuthenticationSuccessHandler).permitAll());
        return http.build();
    }

    /**
     * Also used to encode OAuth2 client secrets: the authorization server's client
     * authentication provider expects the delegating {bcrypt}-prefixed format by default.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
