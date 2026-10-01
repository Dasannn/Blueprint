package com.dasannn.socialblueprint.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;
import java.util.Objects;

/**
 * Immutable configuration section for GitHub updates per SB-075, SB-076, and T-080.
 * - Startup check defaults to on (true).
 * - Automatic download defaults to off (false).
 * - Repository, channel and API URL are configurable.
 * - API URL must use HTTPS in production.
 * - Enforces hard byte cap on downloads.
 */
public record UpdateConfig(
        boolean checkOnStartup,
        boolean autoDownload,
        String repository,
        String channel,
        String apiUrl,
        long maxDownloadBytes
) {
    public static final String DEFAULT_REPOSITORY = "Dasannn/Blueprint";
    public static final String DEFAULT_CHANNEL = "stable";
    public static final String DEFAULT_API_URL = "https://api.github.com";
    public static final long DEFAULT_MAX_DOWNLOAD_BYTES = 10 * 1024 * 1024L; // 10 MiB

    private static volatile boolean allowInsecureHttpForTesting = false;

    public static void setAllowInsecureHttpForTesting(boolean allow) {
        allowInsecureHttpForTesting = allow;
    }

    public static boolean isAllowInsecureHttpForTesting() {
        return allowInsecureHttpForTesting;
    }

    public UpdateConfig(
            boolean checkOnStartup,
            boolean autoDownload,
            String repository,
            String channel,
            String apiUrl
    ) {
        this(checkOnStartup, autoDownload, repository, channel, apiUrl, DEFAULT_MAX_DOWNLOAD_BYTES);
    }

    public UpdateConfig {
        Objects.requireNonNull(repository, "repository must not be null");
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(apiUrl, "apiUrl must not be null");

        if (!allowInsecureHttpForTesting && !apiUrl.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new ConfigValidationException("update.api-url", "Update API URL must use HTTPS: " + apiUrl);
        }

        if (maxDownloadBytes <= 0) {
            maxDownloadBytes = DEFAULT_MAX_DOWNLOAD_BYTES;
        }
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

        long maxDownloadBytes = section.getLong("max-download-bytes", DEFAULT_MAX_DOWNLOAD_BYTES);
        if (maxDownloadBytes <= 0) {
            maxDownloadBytes = DEFAULT_MAX_DOWNLOAD_BYTES;
        }

        return new UpdateConfig(checkOnStartup, autoDownload, repository.trim(), channel.trim(), apiUrl.trim(), maxDownloadBytes);
    }

    public static UpdateConfig defaults() {
        return new UpdateConfig(true, false, DEFAULT_REPOSITORY, DEFAULT_CHANNEL, DEFAULT_API_URL, DEFAULT_MAX_DOWNLOAD_BYTES);
    }
}

