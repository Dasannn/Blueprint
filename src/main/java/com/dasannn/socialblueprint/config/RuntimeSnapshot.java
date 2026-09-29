package com.dasannn.socialblueprint.config;

import net.kyori.adventure.text.Component;

import java.util.Objects;

/**
 * Single immutable runtime snapshot holding both configuration and messages (T-030, T-034).
 * Published atomically; captured once per request to guarantee complete consistency.
 */
public record RuntimeSnapshot(
        PluginConfig config,
        MessagesSnapshot messages,
        Component chatPrefixComponent
) {
    public RuntimeSnapshot(PluginConfig config, MessagesSnapshot messages) {
        this(
                Objects.requireNonNull(config, "config must not be null"),
                Objects.requireNonNull(messages, "messages must not be null"),
                ColorParser.parse(config.chatPrefix())
        );
    }

    public RuntimeSnapshot {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(messages, "messages must not be null");
        Objects.requireNonNull(chatPrefixComponent, "chatPrefixComponent must not be null");
    }
}
