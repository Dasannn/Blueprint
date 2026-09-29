package com.dasannn.socialblueprint.config;

import java.util.Objects;

/**
 * Thrown when configuration validation fails per T-031, naming the offending key.
 * Never a silent default, never a zero standing in for an absent number.
 */
public class ConfigValidationException extends RuntimeException {

    private final String key;

    public ConfigValidationException(String key, String reason) {
        super("Configuration error at '" + Objects.requireNonNull(key, "Key must not be null") + "': "
                + Objects.requireNonNull(reason, "Reason must not be null"));
        this.key = key;
    }

    public String key() {
        return key;
    }
}
