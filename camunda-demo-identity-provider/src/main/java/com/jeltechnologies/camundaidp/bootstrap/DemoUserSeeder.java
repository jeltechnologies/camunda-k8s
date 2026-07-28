package com.jeltechnologies.camundaidp.bootstrap;

import com.jeltechnologies.camundaidp.config.IdpProperties;
import com.jeltechnologies.camundaidp.user.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Replaces Keycloak's {@code identity.firstUser} bootstrap: on a brand-new install the users
 * table is empty, so seed the demo admin user from the install script's DEMO_USERNAME /
 * DEMO_EMAIL / DEMO_PASSWORD env vars. No-op on every later startup.
 */
@Component
public class DemoUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoUserSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final IdpProperties idpProperties;

    public DemoUserSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder, IdpProperties idpProperties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.idpProperties = idpProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            return;
        }
        IdpProperties.DemoUser demoUser = idpProperties.demoUser();
        userRepository.insert(demoUser.username(), demoUser.email(),
                passwordEncoder.encode(demoUser.password()), true);
        log.info("Seeded first admin user \"{}\"", demoUser.username());
    }
}
