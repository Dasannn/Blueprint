package com.dasannn.socialblueprint.feature.effects;

import com.dasannn.socialblueprint.config.*;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CalmEffectsTest {
    @Test void newEffectsArePrivateWhileOldSharedSerenityStaysShared() {
        UUID subject = UUID.randomUUID(), observer = UUID.randomUUID();
        var nearby = List.of(new SereneEpisode.Candidate(observer, true, true, true, false, 1));
        for (String id : List.of("flowers", "clear-sky", "ambient-particles", "music", "warm-phrases", "glowing-animals")) {
            assertThat(CalmEffectDecision.privateEffect(id)).isTrue();
            assertThat(SereneEpisode.audience(subject, CalmEffectDecision.privateEffect(id), nearby, 16)).containsExactly(subject);
        }
        for (String id : List.of("particles", "apparition", "source-less-sounds"))
            assertThat(CalmEffectDecision.privateEffect(id)).isFalse();
    }

    @Test void clearSkyUsesEffectiveWeatherAndLeavesTimeOwnershipAlone() {
        assertThat(CalmEffectDecision.needsClear(null, false)).isFalse();
        assertThat(CalmEffectDecision.needsClear(null, true)).isTrue();
        assertThat(CalmEffectDecision.needsClear("CLEAR", true)).isFalse();
        assertThat(CalmEffectDecision.needsClear("DOWNFALL", false)).isTrue();
        var presentation = new SkyPresentation(4, true, "DOWNFALL", 0, false, "CLEAR", false, true);
        assertThat(presentation.ownsTime(0, false)).isFalse();
        assertThat(presentation.ownsWeather("CLEAR")).isTrue();
        assertThat(presentation.ownsWeather("DOWNFALL")).isFalse();
        assertThat(presentation.resetWeather(true)).isFalse();
        assertThat(presentation.resetWeather(false)).isTrue();
        assertThat(presentation.previousWeather()).isEqualTo("DOWNFALL");
    }

    @Test void flowersRejectOccupiedBlocksWrongSoilRangeAndInteractionReach() {
        assertThat(CalmEffectDecision.flowerSite(true, "grass_block", 25, 6, 25, 4)).isTrue();
        assertThat(CalmEffectDecision.flowerSite(true, "dirt", 25, 6, 25, 4)).isTrue();
        assertThat(CalmEffectDecision.flowerSite(false, "grass_block", 25, 6, 25, 4)).isFalse();
        assertThat(CalmEffectDecision.flowerSite(true, "stone", 25, 6, 25, 4)).isFalse();
        assertThat(CalmEffectDecision.flowerSite(true, "dirt", 37, 6, 25, 4)).isFalse();
        assertThat(CalmEffectDecision.flowerSite(true, "dirt", 25, 6, 16, 4)).isFalse();
        assertThat(CalmEffectDecision.flowerSite(true, "dirt", 25, 6, 25, Double.NaN)).isFalse();
    }

    @Test void particlesFollowPrivateTimeAndQuietPeriodIncludesClientTail() {
        for (long time : new long[]{0, 12999, 23000, 23999, 24000})
            assertThat(CalmEffectDecision.particle(time)).isEqualTo("cherry_leaves");
        for (long time : new long[]{13000, 22999, 37000})
            assertThat(CalmEffectDecision.particle(time)).isEqualTo("firefly");
        var config = SerenityEffectsConfig.defaults();
        var sounds = new SoundsConfigSection(Map.of());
        assertThat(SereneEpisode.durationTicks("ambient-particles", config, sounds))
                .isEqualTo(config.ambient().duration() + 400);
        for (String id : CalmEffectDecision.PRIVATE_EFFECTS)
            assertThat(SereneEpisode.reservationTicks(id, config, sounds)).isGreaterThan(SereneEpisode.durationTicks(id, config, sounds));
    }

    @Test void phraseRotationWrapsAndGlowPreservesEveryOtherSharedFlag() {
        for (int i = 0; i < 12; i++) assertThat(CalmEffectDecision.phraseIndex(i, 4)).isEqualTo(i % 4);
        assertThat(CalmEffectDecision.phraseIndex(Integer.MIN_VALUE, 3)).isBetween(0, 2);
        for (int i = 0; i < 256; i++) {
            int glowing = Byte.toUnsignedInt(CalmEffectDecision.glowingFlags((byte) i));
            assertThat(glowing & 0x40).isEqualTo(0x40);
            assertThat(glowing & ~0x40).isEqualTo(i & ~0x40);
        }
        assertThat(CalmEffectDecision.PASSIVE_ANIMALS).contains("COW", "SHEEP", "TURTLE")
                .doesNotContain("ZOMBIE", "HOGLIN", "WOLF", "POLAR_BEAR");
    }

    @Test void privateMusicStopsTheSelectedKeyOnExpiryOrCancellationAndSkipsWithoutScheduler() {
        for (boolean expire : List.of(true, false)) {
            var registry = new AmbientEntityRegistry();
            List<Runnable> scheduled = new ArrayList<>();
            List<String> calls = new ArrayList<>();
            UUID owner = UUID.randomUUID();
            Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> owner;
                        case "getLocation" -> null;
                        case "playSound", "stopSound" -> {
                            int key = method.getName().equals("playSound") ? 1 : 0;
                            assertThat(args[key + 1]).isEqualTo(SoundCategory.MUSIC);
                            calls.add(method.getName() + ":" + args[key]); yield null;
                        }
                        default -> throw new AssertionError("Unexpected access: " + method.getName());
                    });
            var dispatcher = new AmbientEffectDispatcher(null, MessageRegistry.fromMaps(Map.of(), Map.of(), "en", null),
                    null, new FakeSilverfishService(null, registry, null), (action, delay) -> {
                        assertThat(delay).isEqualTo(50); scheduled.add(action); return () -> {};
                    });
            var music = new SerenityEffectsConfig.Music(List.of("minecraft:music.overworld.meadow"), .3f, 50);
            assertThat(dispatcher.dispatchMusic(player, music)).isTrue();
            assertThat(calls).containsExactly("playSound:minecraft:music.overworld.meadow");
            if (expire) scheduled.getFirst().run(); else dispatcher.cancelPending(owner);
            dispatcher.cancelAllPending();
            assertThat(calls).containsExactly("playSound:minecraft:music.overworld.meadow", "stopSound:minecraft:music.overworld.meadow");
            var unavailable = new AmbientEffectDispatcher(null, MessageRegistry.fromMaps(Map.of(), Map.of(), "en", null),
                    null, new FakeSilverfishService(null, registry, null), (action, delay) -> null);
            assertThat(unavailable.dispatchMusic(player, music)).isFalse();
            assertThat(calls).hasSize(2);
        }
    }
}
