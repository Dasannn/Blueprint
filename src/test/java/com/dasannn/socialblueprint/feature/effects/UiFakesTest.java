package com.dasannn.socialblueprint.feature.effects;

import com.dasannn.socialblueprint.config.*;
import com.dasannn.socialblueprint.domain.PsychosisLevel;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class UiFakesTest {
    private YamlConfiguration shipped() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("config.yml"), StandardCharsets.UTF_8));
    }

    @Test void mediumUiAndChatHighDeathAndToastSkip() {
        PresentationConfig config = PresentationConfig.load(shipped());
        for (AmbientEffectType type : List.of(AmbientEffectType.ADVANCEMENT_TOAST, AmbientEffectType.BOSS_BAR,
                AmbientEffectType.WHISPER, AmbientEffectType.FAKE_ANNOUNCEMENT)) {
            assertThat(config.rules().get(type).allows(PsychosisLevel.LOW)).isEqualTo(type.floor() == PsychosisLevel.LOW);
            assertThat(config.rules().get(type).allows(PsychosisLevel.MEDIUM)).isTrue();
        }
        assertThat(config.rules().get(AmbientEffectType.FALSE_DEATH).allows(PsychosisLevel.MEDIUM)).isFalse();
        assertThat(config.rules().get(AmbientEffectType.FALSE_DEATH).allows(PsychosisLevel.HIGH)).isTrue();
        ToastDecision toast = ToastDecision.describe("effects.advancement-toast.lines.0", config.toast());
        assertThat(toast.messageKey()).isEqualTo("effects.advancement-toast.lines.0");
        assertThat(toast.icon()).isEqualTo(config.toast().icon());
        assertThat(toast.durationTicks()).isEqualTo(config.toast().durationTicks());
        assertThat(toast.deliveryAvailable()).isFalse();
    }

    @Test void falseDeathRequiresOtherVisibleLivingNearbyNonVanishedPlayer() {
        UUID recipient = UUID.randomUUID();
        FalseDeathTarget good = new FalseDeathTarget(UUID.randomUUID(), "subject", true, true, true, false, 256);
        List<FalseDeathTarget> candidates = List.of(good,
                new FalseDeathTarget(recipient, "self", true, true, true, false, 0),
                new FalseDeathTarget(UUID.randomUUID(), "world", false, true, true, false, 0),
                new FalseDeathTarget(UUID.randomUUID(), "hidden", true, false, true, false, 0),
                new FalseDeathTarget(UUID.randomUUID(), "dead", true, true, false, false, 0),
                new FalseDeathTarget(UUID.randomUUID(), "vanished", true, true, true, true, 0),
                new FalseDeathTarget(UUID.randomUUID(), "far", true, true, true, false, 257),
                new FalseDeathTarget(UUID.randomUUID(), "invalid", true, true, true, false, Double.NaN));
        assertThat(FalseDeathTarget.eligible(recipient, candidates, 16)).containsExactly(good);
        assertThat(FalseDeathTarget.eligible(recipient, candidates.subList(1, candidates.size()), 16)).isEmpty();
        assertThat(FalseDeathTarget.eligible(recipient, List.of(), 16)).isEmpty();
    }

    @Test void customLinesCountVisibleCodePointsAfterColourAndSubstitution() {
        String key = CatalogueLines.CUSTOM;
        String emoji = new String(Character.toChars(0x1F47B));
        assertThatCode(() -> CatalogueLines.validateLine(key, 2, "&#202020" + emoji.repeat(160), Map.of(), 160, true))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> CatalogueLines.validateLine(key, 2, emoji.repeat(161), Map.of(), 160, true))
                .hasMessageContaining(key + "[2]");
        String template = "&8" + "x".repeat(145) + "{player}";
        assertThatCode(() -> CatalogueLines.validateLine(key, 0, template, Map.of("player", "x".repeat(15)), 160, true))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> CatalogueLines.validateLine(key, 0, template, Map.of("player", "x".repeat(16)), 160, true))
                .hasMessageContaining(key + "[0]");
        assertThatThrownBy(() -> CatalogueLines.validateLine(key, 4, "xxxx", Map.of(), 3, true))
                .hasMessageContaining(key + "[4]");
        assertThatThrownBy(() -> CatalogueLines.validateList(key, List.of(template), 160))
                .hasMessageContaining(key + "[0]");
        assertThat(CatalogueLines.validateList(key, List.of(), 160)).isEmpty();
        assertThat(CatalogueLines.editValue(key, "[]", 160)).isEqualTo("[]");
    }

    @Test void unsafeCustomLinesNameKeyAndIndex() {
        for (String line : List.of("x\ny", "x\ry", "x\u2028y", "<click:run_command:'/op'>x</click>",
                "<hover:show_text:'x'>y</hover>", "{player} joined the game", "{player} died", "Advancement earned",
                "You are banned", "Permission granted", "Balance: 100", "Economy payment", "{player} muri\u00f3")) {
            assertThatThrownBy(() -> CatalogueLines.validateList(CatalogueLines.CUSTOM, List.of("&8...", line), 160))
                    .isInstanceOf(ConfigValidationException.class).hasMessageContaining(CatalogueLines.CUSTOM + "[1]");
        }
        assertThatThrownBy(() -> CatalogueLines.validateLine(CatalogueLines.CUSTOM, 0, "{player}",
                Map.of("player", "server joined"), 160, true)).hasMessageContaining(CatalogueLines.CUSTOM + "[0]");
    }

    @Test void emptyCustomListDoesNotUseFallbackAndListsResolveByIndex() {
        MessagesSnapshot messages = new MessagesSnapshot("en", "es", Map.of(CatalogueLines.CUSTOM, ""),
                Map.of(CatalogueLines.CUSTOM, "x"), Map.of(), Map.of());
        assertThat(messages.lineKeys(CatalogueLines.CUSTOM)).isEmpty();
        MessagesSnapshot fallback = new MessagesSnapshot("en", "es", Map.of(),
                Map.of("effects.boss-bar.lines", "x\ny"), Map.of(), Map.of());
        assertThat(fallback.resolveRaw("effects.boss-bar.lines.1", new java.util.HashSet<>(), null)).isEqualTo("y");
    }

    @Test void uiBoundsAreValidatedWithPaths() {
        for (Map.Entry<String, Object> entry : Map.<String, Object>ofEntries(
                Map.entry("boss-bar.colour", "unknown"), Map.entry("boss-bar.style", "unknown"),
                Map.entry("boss-bar.progress", 1.1), Map.entry("boss-bar.duration-ticks", 101),
                Map.entry("advancement-toast.icon", "bad key"), Map.entry("advancement-toast.duration-ticks", 0),
                Map.entry("false-death.range-blocks", Double.POSITIVE_INFINITY),
                Map.entry("private-chat.max-visible-length", 161)).entrySet()) {
            YamlConfiguration yaml = shipped();
            yaml.set("effects." + entry.getKey(), entry.getValue());
            assertThatThrownBy(() -> PresentationConfig.load(yaml)).hasMessageContaining("effects." + entry.getKey());
        }
    }

    @Test void dispatchingAConcurrentPrivateLineOrSkippedRendererKeepsTheActiveBar() {
        UUID owner = UUID.randomUUID();
        var messages = MessageRegistry.fromMaps(Map.of("effects.boss-bar.lines", "{player}",
                "effects.private-chat.lines", "{player}"), Map.of(), "en", null);
        var snapshot = messages.snapshot();
        var registry = new AmbientEntityRegistry();
        List<Runnable> tasks = new java.util.ArrayList<>();
        var hidden = new java.util.concurrent.atomic.AtomicInteger();
        var dispatcher = new AmbientEffectDispatcher(null, messages, null, new FakeSilverfishService(null, registry, null),
                (action, delay) -> { tasks.add(action); return () -> {}; });
        var viewer = (org.bukkit.entity.Player) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{org.bukkit.entity.Player.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getUniqueId" -> owner;
                        case "getName" -> "Subject";
                        case "showBossBar", "sendMessage" -> null;
                        case "hideBossBar" -> { hidden.incrementAndGet(); yield null; }
                        default -> null;
                    };
                });
        var config = snapshot.config().effects();
        assertThat(dispatcher.dispatch(viewer, AmbientEffectType.BOSS_BAR, config, snapshot)).isTrue();
        assertThat(dispatcher.dispatch(viewer, AmbientEffectType.WHISPER, config, snapshot)).isTrue();
        assertThat(dispatcher.dispatch(viewer, AmbientEffectType.SKY, config, snapshot)).isFalse();
        assertThat(hidden).hasValue(0); assertThat(registry.presentationsFor(owner)).hasSize(1);
        assertThat(tasks).hasSize(1);
        tasks.getFirst().run();
        assertThat(hidden).hasValue(1); assertThat(registry.presentationsFor(owner)).isEmpty();
    }

    @Test void customUsesWhisperStateAndBarOnlyRemovesItsOwnInstance() throws Exception {
        EffectsConfigSection config = EffectsConfigSection.load(shipped());
        PlayerEffectState state = new PlayerEffectState();
        long now = 1_000_000;
        state.recordFired(AmbientEffectType.WHISPER, now);
        assertThat(state.canFire(AmbientEffectType.WHISPER, config.getEffect(AmbientEffectType.WHISPER), now + 1)).isFalse();
        for (int i = 1; i < config.getEffect(AmbientEffectType.WHISPER).sessionCap(); i++)
            state.recordFired(AmbientEffectType.WHISPER, now);
        assertThat(state.canFire(AmbientEffectType.WHISPER, config.getEffect(AmbientEffectType.WHISPER), Long.MAX_VALUE)).isFalse();
        String source = Files.readString(Path.of("src/main/java/com/dasannn/socialblueprint/feature/effects/AmbientEffectDispatcher.java"));
        assertThat(source).contains("keys.addAll(snapshot.messages().lineKeys(CatalogueLines.CUSTOM))",
                "() -> player.hideBossBar(bar)", "player.showBossBar(bar)").doesNotContain("activeBossBars()",
                "getAdvancementProgress", "awardCriteria", "incrementStatistic", ".broadcast(", ".callEvent(");
        assertThat(Set.of(AmbientEffectType.values()).stream().filter(t -> t.name().contains("CUSTOM"))).isEmpty();
    }
}
