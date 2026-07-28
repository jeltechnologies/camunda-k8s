package com.jeltechnologies.camundaidentityprovider.user;

import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Users log in with email + password, not the free-text display name. Spring Security's
 * {@link UserDetailsService} contract calls its parameter "username" regardless of what a given
 * app actually authenticates by - here that parameter holds an email address, and the returned
 * {@link UserDetails#getUsername()} (which becomes the OAuth2 principal/subject name) is the
 * email too, since that's the one stable, unique identifier. The free-text {@code name} is looked
 * up separately when needed (see AuthorizationServerConfig's token customizer).
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public AppUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("No such user: " + email));

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.email())
                .password(user.passwordHash())
                .disabled(!user.enabled())
                .authorities(AuthorityUtils.createAuthorityList(user.admin() ? "ROLE_ADMIN" : "ROLE_USER"))
                .build();
    }
}
