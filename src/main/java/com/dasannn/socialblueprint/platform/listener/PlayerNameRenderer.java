package com.dasannn.socialblueprint.platform.listener;

import com.dasannn.socialblueprint.config.ColorParser;
import com.dasannn.socialblueprint.domain.PlayerNameFormat;
import net.kyori.adventure.text.Component;

/** Shared tier-prefix/name renderer used by chat and vanilla tab. */
public final class PlayerNameRenderer {
    private PlayerNameRenderer() {}
    public static Component name(String prefix, String name) {
        return join(ColorParser.parse(PlayerNameFormat.prefix(prefix)), Component.text(name));
    }
    public static Component join(Component prefix, Component name) {
        return prefix == null || prefix.equals(Component.empty()) ? Component.empty().append(name)
                : Component.empty().append(prefix).append(Component.space()).append(name);
    }
}
