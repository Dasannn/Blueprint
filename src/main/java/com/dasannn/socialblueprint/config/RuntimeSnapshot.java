package com.dasannn.socialblueprint.config;

import net.kyori.adventure.text.Component;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Single immutable runtime snapshot holding both configuration and messages (T-030, T-034).
 * Published atomically; captured once per request to guarantee complete consistency.
 */
public record RuntimeSnapshot(
        PluginConfig config,
        MessagesSnapshot messages,
        Component chatPrefixComponent,
        Map<String, String> leafValues
) {
    public RuntimeSnapshot(PluginConfig config, MessagesSnapshot messages) {
        this(
                Objects.requireNonNull(config, "config must not be null"),
                Objects.requireNonNull(messages, "messages must not be null"),
                ColorParser.parse(config.chatPrefix()),
                Collections.emptyMap()
        );
    }

    public RuntimeSnapshot(PluginConfig config, MessagesSnapshot messages, Component chatPrefixComponent) {
        this(
                Objects.requireNonNull(config, "config must not be null"),
                Objects.requireNonNull(messages, "messages must not be null"),
                Objects.requireNonNull(chatPrefixComponent, "chatPrefixComponent must not be null"),
                Collections.emptyMap()
        );
    }

    public RuntimeSnapshot(PluginConfig config, MessagesSnapshot messages, Map<String, String> leafValues) {
        this(
                Objects.requireNonNull(config, "config must not be null"),
                Objects.requireNonNull(messages, "messages must not be null"),
                ColorParser.parse(config.chatPrefix()),
                leafValues
        );
    }

    public RuntimeSnapshot {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(messages, "messages must not be null");
        Objects.requireNonNull(chatPrefixComponent, "chatPrefixComponent must not be null");
        CatalogueLines.validateSnapshot(messages, config.effects().presentation().maxVisibleLength());
        leafValues = leafValues != null ? Map.copyOf(leafValues) : Collections.emptyMap();
    }

    public String getLeaf(String path) {
        return leafValues.get(path);
    }
}
