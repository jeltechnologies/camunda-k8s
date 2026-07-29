package com.jeltechnologies.camundaidentityprovider.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

@Configuration
public class SecurityConfig {

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

    /** The login screen and the user-admin screen. */
    @Bean
    @Order(2)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/login", "/error", "/favicon.ico", "/css/**", "/images/**").permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated());
        // Users authenticate with email + password, not a separate username - see
        // AppUserDetailsService for why the "username" parameter is bound to email.
        http.formLogin(form -> form.loginPage("/login").usernameParameter("email").permitAll());
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
