package com.jeltechnologies.keycunda.user;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code email} is the unique login identifier; {@code name} is free text shown for display only
 * (not unique, not used to authenticate). {@code defaultAdmin} marks the one user seeded by
 * {@link com.jeltechnologies.keycunda.bootstrap.DemoUserSeeder} from the install
 * script's DEMO_EMAIL - the admin UI blocks removing or changing that user's password so an
 * operator can't accidentally lock themselves out.
 */
public record User(UUID id, String name, String email, String passwordHash, boolean admin,
        boolean enabled, boolean defaultAdmin, Instant createdAt) {
}
