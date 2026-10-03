package com.dasannn.socialblueprint.feature.gui;

import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.config.SerenityEffectsConfig;
import com.dasannn.socialblueprint.domain.MindInput;
import com.dasannn.socialblueprint.feature.effects.AmbientEffectType;
import java.util.*;

/** Slot, key and state decisions contain no Bukkit objects. */
public final class FeatureSwitchLayout {
    private FeatureSwitchLayout() {}

    public record Switch(int slot, String key, Boolean enabled) {}
    public record View(GuiLayout layout, Map<Integer, Switch> switches, int page) {
        public View { switches = Map.copyOf(switches); }
        public int pageAfterClick(int slot) {
            GuiSlot item = layout.get(slot);
            if (item == null) return page;
            return switch (item.iconKind()) {
                case PAGE_PREVIOUS_STAR -> Math.max(0, page - 1);
                case PAGE_NEXT_STAR -> Math.min(1, page + 1);
                default -> page;
            };
        }
    }

    private static List<String> inputs() {
        List<String> keys = new ArrayList<>();
        // Both honor-review directions share one id and therefore one switch.
        for (MindInput input : MindInput.values()) {
            String key = "psychosis.inputs." + input.id() + ".enabled";
            if (!keys.contains(key)) keys.add(key);
        }
        return keys;
    }

    private static List<String> madness() {
        List<String> keys = new ArrayList<>();
        for (AmbientEffectType type : AmbientEffectType.values()) {
            keys.add("effects." + type.configId() + ".enabled");
        }
        return keys;
    }

    private static List<String> serenity() {
        return SerenityEffectsConfig.EFFECTS.stream().sorted().map(id -> "effects.serenity." + id + ".enabled").toList();
    }

    public static Set<String> keys() {
        Set<String> keys = new LinkedHashSet<>(inputs());
        keys.addAll(madness());
        keys.addAll(serenity());
        keys.add("psychosis.chat.enabled");
        return Set.copyOf(keys);
    }

    public static View compute(RuntimeSnapshot snapshot) {
        return compute(snapshot, 0);
    }

    public static View compute(RuntimeSnapshot snapshot, int page) {
        page = Math.max(0, Math.min(1, page));
        Map<Integer, GuiSlot> slots = new LinkedHashMap<>();
        Map<Integer, Switch> switches = new LinkedHashMap<>();
        for (int slot = 0; slot < 54; slot++) slots.put(slot, GuiSlot.of(slot, GuiIconKind.FILLER, "features.separator"));
        if (page == 0) {
            group(snapshot, slots, switches, 0, "inputs", inputs());
            group(snapshot, slots, switches, 18, "madness", madness());
        } else {
            group(snapshot, slots, switches, 0, "serenity", serenity());
            group(snapshot, slots, switches, 18, "chat", List.of("psychosis.chat.enabled"));
        }
        Map<String, String> pageInfo = Map.of("current", String.valueOf(page + 1), "total", "2");
        List<GuiLoreLine> pageLore = List.of(GuiLoreLine.ofKey("gui.history.page-info", pageInfo));
        slots.put(45, GuiSlot.of(45, GuiIconKind.PAGE_PREVIOUS_STAR, "gui.history.page-previous", Map.of(), pageLore));
        slots.put(49, GuiSlot.of(49, GuiIconKind.PAGE_INFO, "gui.history.page-info", pageInfo));
        slots.put(53, GuiSlot.of(53, GuiIconKind.PAGE_NEXT_STAR, "gui.history.page-next", Map.of(), pageLore));
        return new View(new GuiLayout(54, slots), switches, page);
    }

    private static void group(RuntimeSnapshot snapshot, Map<Integer, GuiSlot> slots,
                              Map<Integer, Switch> switches, int header, String group, List<String> keys) {
        slots.put(header, GuiSlot.of(header, GuiIconKind.PAGE_INFO, "features.group." + group));
        for (int i = 0; i < keys.size(); i++) {
            int slot = header + i + 1;
            String key = keys.get(i);
            String raw = snapshot.getLeaf(key);
            Boolean enabled = "true".equals(raw) ? Boolean.TRUE : "false".equals(raw) ? Boolean.FALSE : null;
            switches.put(slot, new Switch(slot, key, enabled));
            String state = enabled == null ? "unavailable" : enabled ? "on" : "off";
            List<GuiLoreLine> lore = new ArrayList<>(List.of(
                    GuiLoreLine.ofKey("features.state." + state),
                    GuiLoreLine.ofKey("features.key", Map.of("key", key))));
            if (enabled != null) lore.add(GuiLoreLine.ofKey("features.toggle"));
            slots.put(slot, new GuiSlot(slot, GuiIconKind.TIER_DYE, null, null, null,
                    enabled == null ? GuiDyeKind.WHITE : enabled ? GuiDyeKind.LIME : GuiDyeKind.RED,
                    "features.name." + key.substring(0, key.length() - ".enabled".length()), Map.of(), lore));
        }
    }
}
