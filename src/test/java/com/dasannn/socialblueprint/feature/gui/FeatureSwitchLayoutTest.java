package com.dasannn.socialblueprint.feature.gui;

import com.dasannn.socialblueprint.config.*;
import com.dasannn.socialblueprint.domain.*;
import com.dasannn.socialblueprint.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class FeatureSwitchLayoutTest {
    @TempDir Path folder;
    private ConfigManager manager() {
        var manager = new ConfigManager(folder.resolve("config.yml").toFile(),
                new MessageRegistry(folder.toFile(), "en", null), Runnable::run, null);
        manager.initialize();
        return manager;
    }

    @Test void completeCatalogueFitsSeparatedChestAndUsesBothLanguageMessageKeys() {
        var manager = manager();
        var view = FeatureSwitchLayout.compute(manager.snapshot());
        assertThat(view.layout().size()).isEqualTo(54);
        assertThat(view.layout().slots()).hasSize(54);
        Set<String> expected = new HashSet<>();
        for (MindInput input : MindInput.values()) expected.add("psychosis.inputs." + input.id() + ".enabled");
        expected.add("psychosis.inputs.honor-review.enabled");
        for (String id : List.of("sky", "particles", "screen-flash", "source-less-sounds", "block-change", "sign",
                "hurt-flash", "victim-ghost", "advancement-toast", "boss-bar", "false-death", "fake-connection", "private-chat", "silverfish"))
            expected.add("effects." + id + ".enabled");
        expected.add("psychosis.chat.enabled");
        for (String id : List.of("dawn", "source-less-sounds", "particles", "apparition")) expected.add("effects.serenity." + id + ".enabled");
        assertThat(view.switches().values().stream().map(FeatureSwitchLayout.Switch::key).toList())
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(view.switches()).hasSize(expected.size());
        assertThat(view.layout().get(0).titleKey()).isEqualTo("features.group.inputs");
        assertThat(view.layout().get(18).titleKey()).isEqualTo("features.group.madness");
        assertThat(view.layout().get(36).titleKey()).isEqualTo("features.group.serenity");
        assertThat(view.layout().get(17).iconKind()).isEqualTo(GuiIconKind.FILLER);
        for (String lang : List.of("en", "es")) {
            var messages = MessageRegistry.loadMessagesSnapshot(folder.toFile(), lang, null);
            for (GuiSlot slot : view.layout().slots().values()) {
                assertThat(messages.activeMessages()).containsKey(slot.titleKey());
                for (GuiLoreLine line : slot.lore()) assertThat(messages.activeMessages()).containsKey(line.key());
            }
        }
        for (var item : view.switches().values()) {
            assertThat(item.slot()).isBetween(0, 53);
            if (item.key().contains("honor-review")) {
                assertThat(item.enabled()).isNull();
                assertThat(view.layout().get(item.slot()).dyeKind()).isEqualTo(GuiDyeKind.WHITE);
                assertThat(view.layout().get(item.slot()).lore()).extracting(GuiLoreLine::key)
                        .contains("features.state.unavailable").doesNotContain("features.toggle");
            } else {
                assertThat(manager.isEditableKey(item.key())).as(item.key()).isTrue();
                assertThat(item.enabled()).isEqualTo(Boolean.valueOf(manager.get(item.key())));
            }
        }
    }

    @Test void refreshedLayoutReadsNewStateAndMissingHonorReviewIsUnavailable() {
        var manager = manager();
        String key = "psychosis.inputs.kill.enabled";
        var before = FeatureSwitchLayout.compute(manager.snapshot());
        var item = before.switches().values().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow();
        assertThat(item.enabled()).isTrue();
        assertThat(before.layout().get(item.slot()).dyeKind()).isEqualTo(GuiDyeKind.LIME);
        manager.set(key, "false");
        var after = FeatureSwitchLayout.compute(manager.snapshot());
        assertThat(after.switches().get(item.slot()).enabled()).isFalse();
        assertThat(after.layout().get(item.slot()).dyeKind()).isEqualTo(GuiDyeKind.RED);
        assertThat(after.layout().get(item.slot()).lore()).extracting(GuiLoreLine::key).contains("features.state.off", "features.toggle");
        assertThat(after.layout().get(item.slot()).lore().get(1).placeholders()).containsEntry("key", key);
        var leaves = new HashMap<>(manager.snapshot().leafValues());
        leaves.put("psychosis.inputs.honor-review.enabled", "false");
        var withHonor = FeatureSwitchLayout.compute(new RuntimeSnapshot(manager.config(), manager.snapshot().messages(), leaves));
        assertThat(withHonor.switches().values().stream().filter(s -> s.key().contains("honor-review")).findFirst().orElseThrow().enabled()).isFalse();
    }

    @Test void liveInputSwitchStopsNextEventAndReenableDoesNotSpendDisabledCap() {
        var manager = manager();
        try (var storage = StorageEngine.inMemory()) {
            storage.runMigrations();
            PlayerId player = PlayerId.of(UUID.randomUUID());
            Instant now = Instant.parse("2026-10-02T00:00:00Z");
            new ProfileRepository(storage).save(PlayerProfile.create(player, "Player", now));
            var mind = new MindRepository(storage);
            assertThat(mind.applyAsync(player, MindInput.SLEEP, manager.config().psychosis().input(MindInput.SLEEP), "test", now).join().enabled()).isTrue();
            double before = mind.value(player);
            int events = mind.events(player).size();
            manager.set("psychosis.inputs.sleep.enabled", "false");
            var result = mind.applyAsync(player, MindInput.SLEEP, manager.config().psychosis().input(MindInput.SLEEP), "test", now.plusSeconds(1)).join();
            assertThat(result.enabled()).isFalse();
            assertThat(mind.value(player)).isEqualTo(before);
            assertThat(mind.events(player)).hasSize(events);
            manager.set("psychosis.inputs.sleep.enabled", "true");
            assertThat(mind.applyAsync(player, MindInput.SLEEP, manager.config().psychosis().input(MindInput.SLEEP), "test", now.plusSeconds(2)).join().enabled()).isTrue();
        }
    }
}
