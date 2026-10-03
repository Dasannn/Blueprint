package com.dasannn.socialblueprint.feature.gui;

import com.dasannn.socialblueprint.domain.Tier;
import net.kyori.adventure.text.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Plain-data descriptor for a single slot in the Social Profile & Rating History GUI.
 * Contains no Bukkit types, allowing full layout and flow verification in unit tests.
 * owningPlayerId stays server-side for clicks; only applyPlayerSkin permits a client profile.
 */
public record GuiSlot(
        int slot,
        GuiIconKind iconKind,
        UUID owningPlayerId,
        Long eventId,
        Tier tier,
        GuiDyeKind dyeKind,
        String titleKey,
        Map<String, String> titlePlaceholders,
        List<GuiLoreLine> lore,
        Component title,
        List<Component> renderedLore,
        boolean applyPlayerSkin
) {
    public GuiSlot {
        renderedLore = renderedLore != null ? List.copyOf(renderedLore) : List.of();
        Objects.requireNonNull(iconKind, "iconKind must not be null");
        titlePlaceholders = titlePlaceholders != null ? Collections.unmodifiableMap(titlePlaceholders) : Collections.emptyMap();
        lore = lore != null ? Collections.unmodifiableList(lore) : Collections.emptyList();
    }

    public GuiSlot(
            int slot, GuiIconKind iconKind, UUID owningPlayerId, Long eventId,
            Tier tier, GuiDyeKind dyeKind, String titleKey,
            Map<String, String> titlePlaceholders, List<GuiLoreLine> lore
    ) {
        this(slot, iconKind, owningPlayerId, eventId, tier, dyeKind, titleKey,
                titlePlaceholders, lore, iconKind == GuiIconKind.SUBJECT_HEAD && owningPlayerId != null);
    }

    public GuiSlot(
            int slot, GuiIconKind iconKind, UUID owningPlayerId, Long eventId,
            Tier tier, GuiDyeKind dyeKind, String titleKey,
            Map<String, String> titlePlaceholders, List<GuiLoreLine> lore, boolean applyPlayerSkin
    ) {
        this(slot, iconKind, owningPlayerId, eventId, tier, dyeKind, titleKey,
                titlePlaceholders, lore, null, List.of(), applyPlayerSkin);
    }

    public GuiSlot(
            int slot,
            GuiIconKind iconKind,
            UUID owningPlayerId,
            Tier tier,
            String titleKey,
            Map<String, String> titlePlaceholders,
            List<GuiLoreLine> lore
    ) {
        this(slot, iconKind, owningPlayerId, null, tier, tier != null ? GuiDyeKind.fromTier(tier) : null, titleKey, titlePlaceholders, lore);
    }

    public static GuiSlot of(int slot, GuiIconKind iconKind, String titleKey) {
        return new GuiSlot(slot, iconKind, null, null, null, null, titleKey, Collections.emptyMap(), Collections.emptyList());
    }

    public static GuiSlot of(int slot, GuiIconKind iconKind, String titleKey, Map<String, String> titlePlaceholders) {
        return new GuiSlot(slot, iconKind, null, null, null, null, titleKey, titlePlaceholders, Collections.emptyList());
    }

    public static GuiSlot of(int slot, GuiIconKind iconKind, String titleKey, Map<String, String> titlePlaceholders, List<GuiLoreLine> lore) {
        return new GuiSlot(slot, iconKind, null, null, null, null, titleKey, titlePlaceholders, lore);
    }
}
