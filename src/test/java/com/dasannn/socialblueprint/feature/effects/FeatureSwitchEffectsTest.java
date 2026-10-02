package com.dasannn.socialblueprint.feature.effects;

import com.dasannn.socialblueprint.config.*;
import com.dasannn.socialblueprint.domain.*;
import com.dasannn.socialblueprint.platform.listener.AsyncChatListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class FeatureSwitchEffectsTest {
    @TempDir Path folder;
    private ConfigManager manager() {
        var manager = new ConfigManager(folder.resolve("config.yml").toFile(),
                new MessageRegistry(folder.toFile(), "en", null), Runnable::run, null);
        manager.initialize();
        return manager;
    }

    @Test void liveSwitchStopsNextSelectionIncludingPhantomsAndCanReenableWithoutRestart() {
        var manager = manager();
        var state = new PlayerEffectState();
        for (String key : List.of("effects.particles.enabled", "effects.silverfish.enabled", "effects.creeper.enabled")) {
            var type = key.contains("silverfish") ? AmbientEffectType.SILVERFISH
                    : key.contains("creeper") ? AmbientEffectType.CREEPER_SOUND : AmbientEffectType.PARTICLES;
            assertThat(AmbientEffectScheduler.availableEffects(manager.config().effects(), state, PsychosisLevel.HIGH, 1_000_000, new Random(0))).contains(type);
            manager.set(key, "false");
            assertThat(AmbientEffectScheduler.availableEffects(manager.config().effects(), state, PsychosisLevel.HIGH, 1_000_000, new Random(0))).doesNotContain(type);
            manager.set(key, "true");
            assertThat(AmbientEffectScheduler.availableEffects(manager.config().effects(), state, PsychosisLevel.HIGH, 1_000_000, new Random(0))).contains(type);
        }
        var rule = manager.config().effects().serenity().rules().get("dawn");
        assertThat(SereneEpisode.allows(PsychosisLevel.SERENITY, 50, rule)).isTrue();
        manager.set("effects.serenity.dawn.enabled", "false");
        assertThat(SereneEpisode.allows(PsychosisLevel.SERENITY, 50, manager.config().effects().serenity().rules().get("dawn"))).isFalse();
    }

    @Test void disabledChatNeverCorruptsOrDimsEvenWhenCallingEpisodeRendererDirectly() {
        var manager = manager();
        String text = "These several readable words should change during an episode";
        var enabled = manager.config().psychosis().chat();
        long sequence = java.util.stream.LongStream.range(0, 1000).map(i -> i * 2)
                .filter(i -> ChatCorruption.isEpisode(text, PsychosisLevel.EXTREME, 42, i, enabled)).findFirst().orElseThrow();
        assertThat(ChatCorruption.corruptEpisode(text, PsychosisLevel.EXTREME, 42, sequence, enabled)).isNotEqualTo(text);
        manager.set("psychosis.chat.enabled", "false");
        var disabled = manager.config().psychosis().chat();
        assertThat(disabled.enabled()).isFalse();
        assertThat(ChatCorruption.isEpisode(text, PsychosisLevel.EXTREME, 42, sequence, disabled)).isFalse();
        assertThat(ChatCorruption.corrupt(text, PsychosisLevel.EXTREME, 42, sequence, disabled)).isEqualTo(text);
        assertThat(ChatCorruption.corruptEpisode(text, PsychosisLevel.EXTREME, 42, sequence, disabled)).isEqualTo(text);
        assertThat(AsyncChatListener.messageBody(text, PsychosisLevel.EXTREME, 42, sequence, disabled).color()).isNull();
    }

    @Test void newSwitchesAreValidatedAtomicallyMergedAndPersisted() throws Exception {
        var manager = manager();
        for (String key : List.of("psychosis.chat.enabled", "effects.silverfish.enabled", "effects.creeper.enabled")) {
            var before = manager.snapshot();
            String file = Files.readString(folder.resolve("config.yml"));
            assertThatThrownBy(() -> manager.set(key, "sometimes")).hasMessageContaining(key);
            assertThat(manager.snapshot()).isSameAs(before);
            assertThat(Files.readString(folder.resolve("config.yml"))).isEqualTo(file);
            manager.set(key, "false");
        }
        var restarted = manager();
        assertThat(restarted.config().psychosis().chat().enabled()).isFalse();
        assertThat(restarted.config().effects().silverfish().enabled()).isFalse();
        assertThat(restarted.config().effects().creeper().enabled()).isFalse();
        var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(folder.resolve("config.yml").toFile());
        yaml.set("psychosis.chat.enabled", null);
        yaml.set("effects.silverfish.enabled", null);
        yaml.set("effects.creeper.enabled", null);
        yaml.set("permissions.admin-features", null);
        yaml.save(folder.resolve("config.yml").toFile());
        var upgraded = manager();
        assertThat(upgraded.get("psychosis.chat.enabled")).isEqualTo("true");
        assertThat(upgraded.get("effects.silverfish.enabled")).isEqualTo("true");
        assertThat(upgraded.config().effects().creeper().enabled()).isTrue();
        assertThat(upgraded.get("effects.creeper.enabled")).isEqualTo("true");
        assertThat(upgraded.config().permissions().node("admin-features")).isEqualTo("socialblueprint.admin.features");
        assertThatThrownBy(() -> upgraded.set("permissions.admin-features", "")).hasMessageContaining("permissions.admin-features");
    }
}
