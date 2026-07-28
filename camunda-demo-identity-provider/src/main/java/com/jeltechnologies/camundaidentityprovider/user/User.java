package com.jeltechnologies.camundaidentityprovider.user;

import java.time.Instant;
import java.util.UUID;

public record User(UUID id, String username, String email, String passwordHash, boolean admin,
        boolean enabled, Instant createdAt) {
}
