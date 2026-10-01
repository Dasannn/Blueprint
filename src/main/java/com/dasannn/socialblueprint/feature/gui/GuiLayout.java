package com.dasannn.socialblueprint.feature.gui;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable plain-data layout of the chest GUI, mapping slot indices to {@link GuiSlot}s.
 */
public record GuiLayout(
        int size,
        Map<Integer, GuiSlot> slots
) {
    public GuiLayout {
        slots = slots != null ? Collections.unmodifiableMap(slots) : Collections.emptyMap();
    }

    public Optional<GuiSlot> slot(int index) {
        return Optional.ofNullable(slots.get(index));
    }

    public GuiSlot get(int index) {
        return slots.get(index);
    }
}
