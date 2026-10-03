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
        var snapshot = manager.snapshot();
        var first = FeatureSwitchLayout.compute(snapshot, 0);
        var second = FeatureSwitchLayout.compute(snapshot, 1);
        var views = List.of(first, second);
        Set<String> expected = new HashSet<>();
        for (MindInput input : MindInput.values()) expected.add("psychosis.inputs." + input.id() + ".enabled");
        expected.add("psychosis.inputs.honor-review.enabled");
        for (String id : List.of("sky", "particles", "screen-flash", "source-less-sounds", "block-change", "sign",
                "hurt-flash", "victim-ghost", "advancement-toast", "boss-bar", "false-death", "fake-connection", "private-chat", "silverfish", "creeper", "footsteps", "watcher", "nearby-noises", "torch-flicker", "subliminal", "red-vignette", "fake-lightning"))
            expected.add("effects." + id + ".enabled");
        expected.add("psychosis.chat.enabled");
        for (String id : List.of("dawn", "source-less-sounds", "particles", "apparition", "flowers", "clear-sky",
                "ambient-particles", "music", "warm-phrases", "glowing-animals")) expected.add("effects.serenity." + id + ".enabled");
        assertThat(views.stream().flatMap(view -> view.switches().values().stream())
                .map(FeatureSwitchLayout.Switch::key).toList())
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(FeatureSwitchLayout.keys()).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(first.layout().get(0).titleKey()).isEqualTo("features.group.inputs");
        assertThat(first.layout().get(18).titleKey()).isEqualTo("features.group.madness");
        assertThat(second.layout().get(0).titleKey()).isEqualTo("features.group.serenity");
        assertThat(second.layout().get(18).titleKey()).isEqualTo("features.group.chat");
        assertThat(first.switches().values()).allSatisfy(item -> {
            if (item.key().startsWith("psychosis.inputs.")) assertThat(item.slot()).isBetween(1, 16);
            else assertThat(item.slot()).isBetween(19, 44);
        });
        assertThat(second.switches().values()).allSatisfy(item -> {
            if (item.key().startsWith("effects.serenity.")) assertThat(item.slot()).isBetween(1, 16);
            else {
                assertThat(item.key()).isEqualTo("psychosis.chat.enabled");
                assertThat(item.slot()).isEqualTo(19);
            }
        });
        for (var view : views) {
            assertThat(view.layout().size()).isEqualTo(54);
            assertThat(view.layout().slots()).hasSize(54);
            assertThat(view.layout().slots().keySet()).allSatisfy(slot -> assertThat(slot).isBetween(0, 53));
            assertThat(view.layout().get(17).iconKind()).isEqualTo(GuiIconKind.FILLER);
            assertThat(view.layout().get(45).iconKind()).isEqualTo(GuiIconKind.PAGE_PREVIOUS_STAR);
            assertThat(view.layout().get(53).iconKind()).isEqualTo(GuiIconKind.PAGE_NEXT_STAR);
            assertThat(view.layout().get(49).titleKey()).isEqualTo("gui.history.page-info");
            var pageInfo = Map.of("current", String.valueOf(view.page() + 1), "total", "2");
            assertThat(view.layout().get(49).titlePlaceholders()).isEqualTo(pageInfo);
            for (int slot : List.of(45, 53)) {
                assertThat(view.layout().get(slot).lore().getFirst().key()).isEqualTo("gui.history.page-info");
                assertThat(view.layout().get(slot).lore().getFirst().placeholders()).isEqualTo(pageInfo);
                assertThat(view.switches()).doesNotContainKey(slot);
            }
            assertThat(view.switches()).doesNotContainKey(49);
        }
        for (String lang : List.of("en", "es")) {
            var messages = MessageRegistry.loadMessagesSnapshot(folder.toFile(), lang, null);
            for (var view : views) {
                for (GuiSlot slot : view.layout().slots().values()) {
                    assertThat(messages.activeMessages()).containsKey(slot.titleKey());
                    for (GuiLoreLine line : slot.lore()) assertThat(messages.activeMessages()).containsKey(line.key());
                }
            }
        }
        for (var view : views) {
            for (var item : view.switches().values()) {
                assertThat(item.slot()).isBetween(0, 44);
                assertThat(view.layout().get(item.slot()).iconKind()).isEqualTo(GuiIconKind.TIER_DYE);
                assertThat(manager.isEditableKey(item.key())).as(item.key()).isTrue();
                assertThat(item.enabled()).isEqualTo(Boolean.valueOf(manager.get(item.key())));
            }
        }
    }

    @Test void navigationReachesBothPagesAndStopsAtBoundaries() {
        var snapshot = manager().snapshot();
        var first = FeatureSwitchLayout.compute(snapshot);
        assertThat(first.pageAfterClick(45)).isZero();
        var second = FeatureSwitchLayout.compute(snapshot, first.pageAfterClick(53));
        assertThat(second.page()).isEqualTo(1);
        assertThat(second.pageAfterClick(53)).isEqualTo(1);
        assertThat(second.pageAfterClick(45)).isZero();
        assertThat(FeatureSwitchLayout.compute(snapshot, -1).page()).isZero();
        assertThat(FeatureSwitchLayout.compute(snapshot, 2).page()).isEqualTo(1);
        for (var view : List.of(first, second)) {
            for (int slot = -1; slot <= 54; slot++) {
                if (slot != 45 && slot != 53) assertThat(view.pageAfterClick(slot)).isEqualTo(view.page());
            }
        }
    }

    @Test void secondPageRefreshKeepsPageAndReadsNewState() {
        var manager = manager();
        String key = "effects.serenity.dawn.enabled";
        var before = FeatureSwitchLayout.compute(manager.snapshot(), 1);
        var item = before.switches().values().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow();
        manager.set(key, "false");
        var after = FeatureSwitchLayout.compute(manager.snapshot(), before.page());
        assertThat(after.page()).isEqualTo(1);
        assertThat(after.switches().get(item.slot()).enabled()).isFalse();
        assertThat(after.layout().get(item.slot()).dyeKind()).isEqualTo(GuiDyeKind.RED);
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
