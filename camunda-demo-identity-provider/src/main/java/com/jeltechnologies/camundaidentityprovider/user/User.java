package com.jeltechnologies.camundaidentityprovider.user;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code email} is the unique login identifier; {@code name} is free text shown for display only
 * (not unique, not used to authenticate).
 */
public record User(UUID id, String name, String email, String passwordHash, boolean admin,
        boolean enabled, Instant createdAt) {
}
