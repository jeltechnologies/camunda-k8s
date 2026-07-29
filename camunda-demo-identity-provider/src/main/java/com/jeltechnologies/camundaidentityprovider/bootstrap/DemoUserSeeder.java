package com.jeltechnologies.camundaidentityprovider.bootstrap;

import com.jeltechnologies.camundaidentityprovider.config.IdentityProviderProperties;
import com.jeltechnologies.camundaidentityprovider.user.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Replaces Keycloak's {@code identity.firstUser} bootstrap: on a brand-new install the users
 * table is empty, so seed the demo admin user from the install script's DEMO_NAME / DEMO_EMAIL /
 * DEMO_PASSWORD env vars. Only inserts once, but reconciles the is_default_admin flag on every
 * startup - see {@link com.jeltechnologies.camundaidentityprovider.user.UserRepository#markAsDefaultAdmin}.
 */
@Component
public class DemoUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoUserSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final IdentityProviderProperties identityProviderProperties;

    public DemoUserSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder, IdentityProviderProperties identityProviderProperties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.identityProviderProperties = identityProviderProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        IdentityProviderProperties.DemoUser demoUser = identityProviderProperties.demoUser();
        if (userRepository.count() == 0) {
            userRepository.insertDefaultAdmin(demoUser.name(), demoUser.email(),
                    passwordEncoder.encode(demoUser.password()));
            log.info("Seeded first admin user \"{}\" <{}>", demoUser.name(), demoUser.email());
            return;
        }
        // Runs on every later startup too (not just the first-ever seed): a database that already
        // had its demo user created before is_default_admin existed needs this row flagged too, and
        // insertDefaultAdmin() above only ever runs once.
        userRepository.markAsDefaultAdmin(demoUser.email());
    }
}
