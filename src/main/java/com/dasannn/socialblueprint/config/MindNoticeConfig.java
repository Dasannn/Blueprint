package com.dasannn.socialblueprint.config;

import org.bukkit.configuration.ConfigurationSection;

public record MindNoticeConfig(boolean enabled, double step, boolean rises, boolean falls) {
    public MindNoticeConfig {
        if (!Double.isFinite(step) || step <= 0)
            throw new ConfigValidationException("mind.notices.step", "Must be finite and positive");
    }
    public static MindNoticeConfig defaults() { return new MindNoticeConfig(true, 5, true, true); }
    public static boolean bool(ConfigurationSection root, String key, boolean fallback) {
        if (root.contains(key) && !root.isBoolean(key)) throw new ConfigValidationException(key, "Must be a boolean");
        return root.getBoolean(key, fallback);
    }
    public static MindNoticeConfig load(ConfigurationSection root) {
        Object raw = root.get("mind.notices.step", 5);
        if (!(raw instanceof Number number)) throw new ConfigValidationException("mind.notices.step", "Must be a number");
        return new MindNoticeConfig(bool(root, "mind.notices.enabled", true), number.doubleValue(),
                bool(root, "mind.notices.rises", true), bool(root, "mind.notices.falls", true));
    }
}
