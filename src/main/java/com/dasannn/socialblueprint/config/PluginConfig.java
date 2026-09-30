package com.dasannn.socialblueprint.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

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
        DuelConfigSection duel,
        EffectsConfigSection effects,
        UpdateConfig update,
        KillPenaltyConfigSection killPenalty,
        SoundsConfigSection sounds
) {
    public static final Set<String> SUPPORTED_LANGUAGES = Set.of("en", "es");

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
                null, EffectsConfigSection.defaults(), UpdateConfig.defaults(),
                KillPenaltyConfigSection.defaults(), SoundsConfigSection.defaults());
    }

    /** P5 call sites: duel supplied, effects defaulted. */
    public PluginConfig(
            String language,
            String chatPrefix,
            TiersConfig tiers,
            ConfidenceConfigSection confidence,
            PsychosisConfigSection psychosis,
            HonorConfigSection honor,
            PermissionsConfig permissions,
            DuelConfigSection duel
    ) {
        this(language, chatPrefix, tiers, confidence, psychosis, honor, permissions,
                duel, EffectsConfigSection.defaults(), UpdateConfig.defaults(),
                KillPenaltyConfigSection.defaults(), SoundsConfigSection.defaults());
    }

    /** P6 call sites: effects supplied, no duel configured. */
    public PluginConfig(
            String language,
            String chatPrefix,
            TiersConfig tiers,
            ConfidenceConfigSection confidence,
            PsychosisConfigSection psychosis,
            HonorConfigSection honor,
            PermissionsConfig permissions,
            EffectsConfigSection effects
    ) {
        this(language, chatPrefix, tiers, confidence, psychosis, honor, permissions,
                null, effects, UpdateConfig.defaults(),
                KillPenaltyConfigSection.defaults(), SoundsConfigSection.defaults());
    }

    public PluginConfig(
            String language,
            String chatPrefix,
            TiersConfig tiers,
            ConfidenceConfigSection confidence,
            PsychosisConfigSection psychosis,
            HonorConfigSection honor,
            PermissionsConfig permissions,
            DuelConfigSection duel,
            EffectsConfigSection effects,
            UpdateConfig update
    ) {
        this(language, chatPrefix, tiers, confidence, psychosis, honor, permissions,
                duel, effects, update, KillPenaltyConfigSection.defaults(), SoundsConfigSection.defaults());
    }

    public PluginConfig {
        Objects.requireNonNull(language, "language must not be null");
        Objects.requireNonNull(chatPrefix, "chatPrefix must not be null");
        Objects.requireNonNull(tiers, "tiers must not be null");
        Objects.requireNonNull(confidence, "confidence must not be null");
        Objects.requireNonNull(psychosis, "psychosis must not be null");
        Objects.requireNonNull(honor, "honor must not be null");
        Objects.requireNonNull(permissions, "permissions must not be null");
        Objects.requireNonNull(effects, "effects must not be null");
        if (update == null) {
            update = UpdateConfig.defaults();
        }
        if (killPenalty == null) {
            killPenalty = KillPenaltyConfigSection.defaults();
        }
        if (sounds == null) {
            sounds = SoundsConfigSection.defaults();
        }
    }

    public static PluginConfig load(ConfigurationSection root) {
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
        TiersConfig tiers = TiersConfig.load(root);
        ConfidenceConfigSection confidence = ConfidenceConfigSection.load(root);
        PsychosisConfigSection psychosis = PsychosisConfigSection.load(root);
        HonorConfigSection honor = HonorConfigSection.load(root);
        PermissionsConfig permissions = PermissionsConfig.load(root);
        DuelConfigSection duel = root.contains("duel") ? DuelConfigSection.load(root) : null;
        EffectsConfigSection effects = root.contains("effects")
                ? EffectsConfigSection.load(root)
                : EffectsConfigSection.defaults();
        UpdateConfig update = UpdateConfig.load(root);
        KillPenaltyConfigSection killPenalty = root.contains("kill-penalty")
                ? KillPenaltyConfigSection.load(root)
                : KillPenaltyConfigSection.defaults();
        SoundsConfigSection sounds = root.contains("sounds")
                ? SoundsConfigSection.load(root)
                : SoundsConfigSection.defaults();

        return new PluginConfig(language, chatPrefix, tiers, confidence, psychosis, honor, permissions, duel, effects, update, killPenalty, sounds);
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
                duel,
                effects,
                update,
                killPenalty,
                sounds
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
                duel,
                effects,
                update,
                killPenalty,
                sounds
        );
    }

    public PluginConfig withEffects(EffectsConfigSection newEffects) {
        return new PluginConfig(
                language,
                chatPrefix,
                tiers,
                confidence,
                psychosis,
                honor,
                permissions,
                duel,
                Objects.requireNonNull(newEffects, "effects must not be null"),
                update,
                killPenalty,
                sounds
        );
    }

    public PluginConfig withUpdate(UpdateConfig newUpdate) {
        return new PluginConfig(
                language,
                chatPrefix,
                tiers,
                confidence,
                psychosis,
                honor,
                permissions,
                duel,
                effects,
                Objects.requireNonNull(newUpdate, "update must not be null"),
                killPenalty,
                sounds
        );
    }

    public PluginConfig withKillPenalty(KillPenaltyConfigSection newKillPenalty) {
        return new PluginConfig(
                language,
                chatPrefix,
                tiers,
                confidence,
                psychosis,
                honor,
                permissions,
                duel,
                effects,
                update,
                Objects.requireNonNull(newKillPenalty, "killPenalty must not be null"),
                sounds
        );
    }

    public PluginConfig withSounds(SoundsConfigSection newSounds) {
        return new PluginConfig(
                language,
                chatPrefix,
                tiers,
                confidence,
                psychosis,
                honor,
                permissions,
                duel,
                effects,
                update,
                killPenalty,
                Objects.requireNonNull(newSounds, "sounds must not be null")
        );
    }
}
