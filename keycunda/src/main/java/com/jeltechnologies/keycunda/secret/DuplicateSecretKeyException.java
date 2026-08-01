package com.jeltechnologies.keycunda.secret;

/** Thrown by {@link SecretsWorkingCopy} when an add/rename would collide with an existing key. */
public class DuplicateSecretKeyException extends RuntimeException {

    public DuplicateSecretKeyException(String key) {
        super("A secret with key \"" + key + "\" already exists.");
    }
}
