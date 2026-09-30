package com.dasannn.socialblueprint.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Objects;

/**
 * Immutable configuration section for GitHub updates per SB-075, SB-076, and T-080.
 * - Startup check defaults to on (true).
 * - Automatic download defaults to off (false).
 * - Repository, channel and API URL are configurable.
 */
public record UpdateConfig(
        boolean checkOnStartup,
        boolean autoDownload,
        String repository,
        String channel,
        String apiUrl
) {
    public static final String DEFAULT_REPOSITORY = "Dasannn/SocialBlueprint";
    public static final String DEFAULT_CHANNEL = "stable";
    public static final String DEFAULT_API_URL = "https://api.github.com";

    public UpdateConfig {
        Objects.requireNonNull(repository, "repository must not be null");
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(apiUrl, "apiUrl must not be null");
    }

    public static UpdateConfig load(ConfigurationSection root) {
        Objects.requireNonNull(root, "ConfigurationSection must not be null");
        ConfigurationSection section = root.getConfigurationSection("update");
        if (section == null) {
            return defaults();
        }

        boolean checkOnStartup = section.getBoolean("check-on-startup", true);
        boolean autoDownload = section.getBoolean("auto-download", false);
        String repository = section.getString("repository", DEFAULT_REPOSITORY);
        if (repository == null || repository.isBlank()) {
            repository = DEFAULT_REPOSITORY;
        }
        String channel = section.getString("channel", DEFAULT_CHANNEL);
        if (channel == null || channel.isBlank()) {
            channel = DEFAULT_CHANNEL;
        }
        String apiUrl = section.getString("api-url", DEFAULT_API_URL);
        if (apiUrl == null || apiUrl.isBlank()) {
            apiUrl = DEFAULT_API_URL;
        }
        if (apiUrl.endsWith("/")) {
            apiUrl = apiUrl.substring(0, apiUrl.length() - 1);
        }

        return new UpdateConfig(checkOnStartup, autoDownload, repository.trim(), channel.trim(), apiUrl.trim());
    }

    public static UpdateConfig defaults() {
        return new UpdateConfig(true, false, DEFAULT_REPOSITORY, DEFAULT_CHANNEL, DEFAULT_API_URL);
    }
}
