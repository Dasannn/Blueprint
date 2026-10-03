package com.dasannn.socialblueprint.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Root typed immutable configuration record per T-030 and T-031.
 * One load, no scattered getString calls. Loaded once and validated strictly.
 *
 * <p>Every feature section is a component here. There is one canonical
 * constructor and one convenience constructor that defaults every section:
 * each phase used to add its own overload, and once merged together three of
 * them became ambiguous on a null argument.
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
        LegacyImportConfig legacyImport,
        DecayConfigSection decay,
        KillPenaltyConfigSection killPenalty,
        SoundsConfigSection sounds,
        HistoryConfig history,
        ChatFilterConfig chatFilter,
        boolean tabEnabled,
        MindNoticeConfig mindNotices,
        WorldRules worldRules
) {
    public static final Set<String> SUPPORTED_LANGUAGES = Set.of("en", "es");

    /** Core settings only; every feature section takes its shipped default. */
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
                null,
                EffectsConfigSection.defaults(),
                UpdateConfig.defaults(),
                LegacyImportConfig.DEFAULT,
                DecayConfigSection.defaults(),
                KillPenaltyConfigSection.defaults(),
                SoundsConfigSection.defaults(),
                HistoryConfig.defaults());
    }

    public PluginConfig(String language, String chatPrefix, TiersConfig tiers,
            ConfidenceConfigSection confidence, PsychosisConfigSection psychosis, HonorConfigSection honor,
            PermissionsConfig permissions, DuelConfigSection duel, EffectsConfigSection effects,
            UpdateConfig update, LegacyImportConfig legacyImport, DecayConfigSection decay,
            KillPenaltyConfigSection killPenalty, SoundsConfigSection sounds, HistoryConfig history) {
        this(language, chatPrefix, tiers, confidence, psychosis, honor, permissions, duel,
                effects, update, legacyImport, decay, killPenalty, sounds, history, ChatFilterConfig.defaults());
    }

    public PluginConfig(String language, String chatPrefix, TiersConfig tiers,
            ConfidenceConfigSection confidence, PsychosisConfigSection psychosis, HonorConfigSection honor,
            PermissionsConfig permissions, DuelConfigSection duel, EffectsConfigSection effects,
            UpdateConfig update, LegacyImportConfig legacyImport, DecayConfigSection decay,
            KillPenaltyConfigSection killPenalty, SoundsConfigSection sounds, HistoryConfig history, ChatFilterConfig chatFilter) {
        this(language, chatPrefix, tiers, confidence, psychosis, honor, permissions, duel,
                effects, update, legacyImport, decay, killPenalty, sounds, history, chatFilter, true, MindNoticeConfig.defaults());
    }

    public PluginConfig(String language, String chatPrefix, TiersConfig tiers,
            ConfidenceConfigSection confidence, PsychosisConfigSection psychosis, HonorConfigSection honor,
            PermissionsConfig permissions, DuelConfigSection duel, EffectsConfigSection effects,
            UpdateConfig update, LegacyImportConfig legacyImport, DecayConfigSection decay,
            KillPenaltyConfigSection killPenalty, SoundsConfigSection sounds, HistoryConfig history,
            ChatFilterConfig chatFilter, boolean tabEnabled, MindNoticeConfig mindNotices) {
        this(language, chatPrefix, tiers, confidence, psychosis, honor, permissions, duel, effects,
                update, legacyImport, decay, killPenalty, sounds, history, chatFilter, tabEnabled,
                mindNotices, WorldRules.defaults());
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
        Objects.requireNonNull(update, "update must not be null");
        Objects.requireNonNull(legacyImport, "legacyImport must not be null");
        Objects.requireNonNull(decay, "decay must not be null");
        Objects.requireNonNull(killPenalty, "killPenalty must not be null");
        Objects.requireNonNull(sounds, "sounds must not be null");
        effects.serenity().validateCeiling(psychosis.serenity().ceiling());
        if (effects.presentation().rules().get(com.dasannn.socialblueprint.feature.effects.AmbientEffectType.SOURCE_LESS_SOUNDS).enabled())
            effects.presentation().validateSounds(sounds);
        Objects.requireNonNull(history, "history must not be null");
        Objects.requireNonNull(mindNotices, "mindNotices must not be null");
        Objects.requireNonNull(worldRules, "worldRules must not be null");
        Objects.requireNonNull(chatFilter, "chatFilter must not be null");
    }

    public static PluginConfig load(ConfigurationSection root) {
        Objects.requireNonNull(root, "Root ConfigurationSection must not be null");

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
        LegacyImportConfig legacyImport = LegacyImportConfig.load(root);
        DecayConfigSection decay = DecayConfigSection.load(root);
        KillPenaltyConfigSection killPenalty = root.contains("kill-penalty")
                ? KillPenaltyConfigSection.load(root)
                : KillPenaltyConfigSection.defaults();
        SoundsConfigSection sounds = root.contains("sounds")
                ? SoundsConfigSection.load(root)
                : SoundsConfigSection.defaults();
        HistoryConfig history = HistoryConfig.load(root);
        if (root.contains("effects.source-less-sounds")) effects.presentation().validateSounds(sounds);
        if (root.contains("effects.hurt-flash")) effects.presentation().validateHurtSounds(sounds);
        if (root.contains("effects.serenity")) effects.serenity().validateSounds(sounds);

        return new PluginConfig(language, chatPrefix, tiers, confidence, psychosis, honor, permissions,
                duel, effects, update, legacyImport, decay, killPenalty, sounds, history, ChatFilterConfig.load(root), MindNoticeConfig.bool(root, "tab.enabled", true), MindNoticeConfig.load(root), WorldRules.load(root));
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
                legacyImport,
                decay,
                killPenalty,
                sounds,
                history,
                chatFilter, tabEnabled, mindNotices, worldRules
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
                legacyImport,
                decay,
                killPenalty,
                sounds,
                history,
                chatFilter, tabEnabled, mindNotices, worldRules
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
                legacyImport,
                decay,
                killPenalty,
                sounds,
                history,
                chatFilter, tabEnabled, mindNotices, worldRules
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
                legacyImport,
                decay,
                killPenalty,
                sounds,
                history,
                chatFilter, tabEnabled, mindNotices, worldRules
        );
    }

    public PluginConfig withLegacyImport(LegacyImportConfig newLegacyImport) {
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
                Objects.requireNonNull(newLegacyImport, "legacyImport must not be null"),
                decay,
                killPenalty,
                sounds,
                history,
                chatFilter, tabEnabled, mindNotices, worldRules
        );
    }

    public PluginConfig withDecay(DecayConfigSection newDecay) {
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
                legacyImport,
                Objects.requireNonNull(newDecay, "decay must not be null"),
                killPenalty,
                sounds,
                history,
                chatFilter, tabEnabled, mindNotices, worldRules
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
                legacyImport,
                decay,
                Objects.requireNonNull(newKillPenalty, "killPenalty must not be null"),
                sounds,
                history,
                chatFilter, tabEnabled, mindNotices, worldRules
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
                legacyImport,
                decay,
                killPenalty,
                Objects.requireNonNull(newSounds, "sounds must not be null"),
                history,
                chatFilter, tabEnabled, mindNotices, worldRules
        );
    }

    public PluginConfig withHistory(HistoryConfig newHistory) {
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
                legacyImport,
                decay,
                killPenalty,
                sounds,
                Objects.requireNonNull(newHistory, "history must not be null"),
                chatFilter, tabEnabled, mindNotices, worldRules
        );
    }
}
