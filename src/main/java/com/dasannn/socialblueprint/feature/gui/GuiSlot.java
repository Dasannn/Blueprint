package com.dasannn.socialblueprint.feature.gui;

import com.dasannn.socialblueprint.domain.Tier;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Plain-data descriptor for a single slot in the Social Profile & Rating History GUI.
 * Contains no Bukkit types, allowing full layout and flow verification in unit tests.
 */
public record GuiSlot(
        int slot,
        GuiIconKind iconKind,
        UUID owningPlayerId,
        Tier tier,
        String titleKey,
        Map<String, String> titlePlaceholders,
        List<GuiLoreLine> lore
) {
    public GuiSlot {
        Objects.requireNonNull(iconKind, "iconKind must not be null");
        titlePlaceholders = titlePlaceholders != null ? Collections.unmodifiableMap(titlePlaceholders) : Collections.emptyMap();
        lore = lore != null ? Collections.unmodifiableList(lore) : Collections.emptyList();
    }

    public static GuiSlot of(int slot, GuiIconKind iconKind, String titleKey) {
        return new GuiSlot(slot, iconKind, null, null, titleKey, Collections.emptyMap(), Collections.emptyList());
    }

    public static GuiSlot of(int slot, GuiIconKind iconKind, String titleKey, Map<String, String> titlePlaceholders) {
        return new GuiSlot(slot, iconKind, null, null, titleKey, titlePlaceholders, Collections.emptyList());
    }

    public static GuiSlot of(int slot, GuiIconKind iconKind, String titleKey, Map<String, String> titlePlaceholders, List<GuiLoreLine> lore) {
        return new GuiSlot(slot, iconKind, null, null, titleKey, titlePlaceholders, lore);
    }
}
