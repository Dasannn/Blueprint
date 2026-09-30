package com.dasannn.socialblueprint.config;

import com.dasannn.socialblueprint.domain.DecayConfig;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Root typed immutable configuration record per T-030 and T-031.
 * One load, no scattered getString calls. Loaded once and validated strictly.
 */
public record PluginConfig(
        String language,
        String chatPrefix,
        TiersConfig tiers,
        ConfidenceConfigSection confidence,
        PsychosisConfigSection psychosis,
        HonorConfigSection honor,
        PermissionsConfig permissions,
        DecayConfigSection decay
) {
    public static final Set<String> SUPPORTED_LANGUAGES = Set.of("en", "es");

    public PluginConfig {
        Objects.requireNonNull(language, "language must not be null");
        Objects.requireNonNull(chatPrefix, "chatPrefix must not be null");
        Objects.requireNonNull(tiers, "tiers must not be null");
        Objects.requireNonNull(confidence, "confidence must not be null");
        Objects.requireNonNull(psychosis, "psychosis must not be null");
        Objects.requireNonNull(honor, "honor must not be null");
        Objects.requireNonNull(permissions, "permissions must not be null");
        Objects.requireNonNull(decay, "decay must not be null");
    }

    public PluginConfig(
            String language,
            String chatPrefix,
            TiersConfig tiers,
            ConfidenceConfigSection confidence,
            PsychosisConfigSection psychosis,
            HonorConfigSection honor,
            PermissionsConfig permissions
    ) {
        this(language, chatPrefix, tiers, confidence, psychosis, honor, permissions,
                new DecayConfigSection(true, DecayConfig.DEFAULT_HALF_LIFE, DecayConfig.DEFAULT_FLOOR, DecayConfig.DEFAULT_CACHE_TTL));
    }

    public static PluginConfig load(ConfigurationSection root) {
        return load(root, null);
    }

    public static PluginConfig load(ConfigurationSection root, Logger logger) {
        Objects.requireNonNull(root, "Root ConfigurationSection must not be null");

        // Validate language (SB-066, T-032a)
        if (!root.contains("language")) {
            throw new ConfigValidationException("language", "Missing required configuration key: 'language'");
        }
        String langRaw = root.getString("language");
        if (langRaw == null || langRaw.isBlank()) {
            throw new ConfigValidationException("language", "Configuration key 'language' must not be blank");
        }
        String language = langRaw.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_LANGUAGES.contains(language)) {
            throw new ConfigValidationException("language",
                    "Unsupported language '" + langRaw + "'. Supported languages: " + SUPPORTED_LANGUAGES);
        }

        // Validate chat prefix (SB-062): exactly one accepted key: 'chat-prefix'
        if (root.contains("prefix")) {
            throw new ConfigValidationException("prefix",
                    "Configuration key 'prefix' is not supported; use 'chat-prefix' instead");
        }
        if (!root.contains("chat-prefix")) {
            throw new ConfigValidationException("chat-prefix", "Missing required configuration key: 'chat-prefix'");
        }
        String chatPrefix = root.getString("chat-prefix");
        if (chatPrefix == null || chatPrefix.isBlank()) {
            throw new ConfigValidationException("chat-prefix", "Configuration key 'chat-prefix' must not be blank");
        }
        ColorParser.validate(chatPrefix, "chat-prefix");

        // Validate sections
        TiersConfig tiers = TiersConfig.load(root, logger);
        ConfidenceConfigSection confidence = ConfidenceConfigSection.load(root);
        PsychosisConfigSection psychosis = PsychosisConfigSection.load(root);
        HonorConfigSection honor = HonorConfigSection.load(root);
        PermissionsConfig permissions = PermissionsConfig.load(root);
        DecayConfigSection decay = DecayConfigSection.load(root);

        return new PluginConfig(language, chatPrefix, tiers, confidence, psychosis, honor, permissions, decay);
    }

    public PluginConfig withLanguage(String newLanguage) {
        return new PluginConfig(
                Objects.requireNonNull(newLanguage, "language must not be null"),
                chatPrefix,
                tiers,
                confidence,
                psychosis,
                honor,
                permissions,
                decay
        );
    }

    public PluginConfig withChatPrefix(String newChatPrefix) {
        return new PluginConfig(
                language,
                Objects.requireNonNull(newChatPrefix, "chatPrefix must not be null"),
                tiers,
                confidence,
                psychosis,
                honor,
                permissions,
                decay
        );
    }
}
