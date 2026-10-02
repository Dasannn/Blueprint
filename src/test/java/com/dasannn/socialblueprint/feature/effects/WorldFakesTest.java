package com.dasannn.socialblueprint.feature.effects;

import com.dasannn.socialblueprint.config.*;
import com.dasannn.socialblueprint.domain.*;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class WorldFakesTest {
    private BlockEquivalence.Candidate candidate(String data, boolean standing, boolean targeted, boolean held, boolean using) {
        return new BlockEquivalence.Candidate(new BlockEquivalence.Position(1, 2, 3), data, standing, targeted, held, using);
    }

    @Test void newEffectsHaveIndependentCooldownsAndSessionCaps() {
        PlayerEffectState state = new PlayerEffectState();
        SingleEffectConfig limits = SingleEffectConfig.of(java.time.Duration.ofSeconds(1), 1);
        for (AmbientEffectType type : List.of(AmbientEffectType.BLOCK_CHANGE, AmbientEffectType.SIGN,
                AmbientEffectType.HURT_FLASH, AmbientEffectType.VICTIM_GHOST)) {
            assertThat(state.canFire(type, limits, 100_000)).isTrue();
            state.recordFired(type, 100_000);
            assertThat(state.canFire(type, limits, 200_000)).isFalse();
            assertThat(state.canFire(type, SingleEffectConfig.of(java.time.Duration.ofSeconds(1), 2), 100_999)).isFalse();
            assertThat(state.canFire(type, SingleEffectConfig.of(java.time.Duration.ofSeconds(1), 2), 101_000)).isTrue();
        }
    }

    @Test void onlyProvenShapeAndInteractionEquivalentPairsPass() {
        for (List<String> pair : List.of(List.of("minecraft:stone", "minecraft:andesite"),
                List.of("minecraft:white_concrete", "minecraft:black_concrete"),
                List.of("minecraft:red_terracotta", "minecraft:blue_terracotta")))
            assertThat(BlockEquivalence.accepts(candidate(pair.getFirst(), false, false, false, false), pair.getLast(), false)).isTrue();
        for (List<String> pair : List.of(List.of("minecraft:stone", "minecraft:air"),
                List.of("minecraft:air", "minecraft:stone"), List.of("minecraft:stone_slab[type=bottom]", "minecraft:stone"),
                List.of("minecraft:stone", "minecraft:oak_sign[rotation=0,waterlogged=false]"),
                List.of("minecraft:chest", "minecraft:stone"), List.of("minecraft:ice", "minecraft:stone"),
                List.of("minecraft:white_concrete_powder", "minecraft:black_concrete"),
                List.of("minecraft:stone", "minecraft:diamond_block"), List.of("other:stone", "minecraft:stone"))) {
            assertThat(BlockEquivalence.accepts(candidate(pair.getFirst(), false, false, false, false), pair.getLast(), false)).isFalse();
        }
        String sign = "minecraft:oak_sign[rotation=0,waterlogged=false]";
        assertThat(BlockEquivalence.accepts(candidate(sign, false, false, false, false), sign, true)).isTrue();
        for (String data : List.of("minecraft:air", "minecraft:stone", "minecraft:oak_wall_sign[facing=north,waterlogged=false]",
                "minecraft:oak_sign[rotation=1,waterlogged=false]", "minecraft:oak_sign[rotation=0,waterlogged=true]"))
            assertThat(BlockEquivalence.accepts(candidate(data, false, false, false, false), sign, true)).isFalse();
        for (boolean[] excluded : List.of(new boolean[]{true, false, false, false}, new boolean[]{false, true, false, false},
                new boolean[]{false, false, true, false}, new boolean[]{false, false, false, true})) {
            assertThat(BlockEquivalence.accepts(candidate("minecraft:stone", excluded[0], excluded[1], excluded[2], excluded[3]),
                    "minecraft:andesite", false)).isFalse();
            assertThat(BlockEquivalence.accepts(candidate(sign, excluded[0], excluded[1], excluded[2], excluded[3]), sign, true)).isFalse();
        }
    }

    @Test void victimMustBelongToThisKillersEligibleHistoryAndHaveAKnownName() {
        PlayerId killer = PlayerId.of(java.util.UUID.randomUUID());
        PlayerId other = PlayerId.of(java.util.UUID.randomUUID());
        PlayerId victim = PlayerId.of(java.util.UUID.randomUUID());
        Instant now = Instant.parse("2026-10-01T00:00:00Z"), since = now.minusSeconds(60);
        List<PsychosisEvent> invalid = List.of(new PsychosisEvent(1, other, victim, CombatContext.OPEN, now),
                new PsychosisEvent(2, killer, victim, CombatContext.DUEL, now),
                new PsychosisEvent(3, killer, victim, CombatContext.OPEN, since),
                new PsychosisEvent(4, killer, victim, CombatContext.OPEN, now.plusSeconds(1)));
        assertThat(VictimGhost.select(killer, invalid, since, now, id -> Optional.of("Victim"))).isEmpty();
        List<PsychosisEvent> valid = List.of(new PsychosisEvent(5, killer, victim, CombatContext.OPEN, now));
        assertThat(VictimGhost.select(killer, valid, since, now, id -> Optional.empty())).isEmpty();
        assertThat(VictimGhost.select(killer, valid, since, now, id -> Optional.of(id.toString()))).isEmpty();
        assertThat(VictimGhost.select(killer, valid, since, now, id -> Optional.of("Victim")))
                .contains(new VictimGhost(victim, "Victim"));
    }

    @Test void everyEligibleVictimCanBeTheGhost() {
        PlayerId killer = PlayerId.of(java.util.UUID.randomUUID());
        PlayerId first = PlayerId.of(java.util.UUID.randomUUID());
        PlayerId second = PlayerId.of(java.util.UUID.randomUUID());
        Instant now = Instant.parse("2026-10-01T00:00:00Z"), since = now.minusSeconds(60);
        List<PsychosisEvent> rows = List.of(new PsychosisEvent(1, killer, first, CombatContext.OPEN, now),
                new PsychosisEvent(2, killer, second, CombatContext.OPEN, now));
        java.util.Set<PlayerId> seen = new java.util.HashSet<>();
        // One generator across draws: java.util.Random's first nextInt(2) is
        // the same for every small consecutive seed.
        java.util.Random random = new java.util.Random(42);
        for (int draw = 0; draw < 20; draw++)
            VictimGhost.select(killer, rows, since, now, id -> Optional.of("Victim"), random)
                    .ifPresent(ghost -> seen.add(ghost.victim()));
        assertThat(seen).containsExactlyInAnyOrder(first, second);
    }

    @Test void hurtIsOnlyAnAnimationAndSlotWithAFinalAudibleTail() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("effects.hurt-flash.enabled", true);
        PresentationConfig config = PresentationConfig.load(yaml);
        assertThat(java.util.Arrays.stream(PresentationConfig.Hurt.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("slot", "playbackTicks");
        assertThat(config.hurt().yaw()).isZero();
        assertThat(config.hurt().animationTicks()).isEqualTo(10);
        SoundsConfigSection slots = new SoundsConfigSection(Map.of("hurt", new SoundSlotConfig(List.of(
                new SoundLayerConfig("minecraft:entity.player.hurt", 1, 1, SoundCategory.PLAYERS, 80)))));
        config.validateHurtSounds(slots);
        assertThat(config.durationTicks(AmbientEffectType.HURT_FLASH, slots)).isEqualTo(100);
        yaml.set("effects.hurt-flash.playback-ticks", 1);
        PresentationConfig shortTail = PresentationConfig.load(yaml);
        SoundsConfigSection immediate = new SoundsConfigSection(Map.of("hurt", new SoundSlotConfig(List.of(
                new SoundLayerConfig("minecraft:entity.player.hurt", 1, 1, SoundCategory.PLAYERS, 0)))));
        assertThat(shortTail.durationTicks(AmbientEffectType.HURT_FLASH, immediate)).isEqualTo(10);
        assertThatThrownBy(() -> config.validateHurtSounds(new SoundsConfigSection(Map.of())))
                .hasMessageContaining("effects.hurt-flash.sound-slot");
        SoundsConfigSection tooLong = new SoundsConfigSection(Map.of("hurt", new SoundSlotConfig(List.of(
                new SoundLayerConfig("minecraft:entity.player.hurt", 1, 1, SoundCategory.PLAYERS, 81)))));
        assertThatThrownBy(() -> config.validateHurtSounds(tooLong)).hasMessageContaining("effects.hurt-flash.playback-ticks");
    }

    @Test void hurtRendererOnlySendsPrivateAnimationAndManagedSoundLayers() {
        MessageRegistry messages = new MessageRegistry(null, "en", null);
        RuntimeSnapshot snapshot = messages.snapshot();
        java.util.UUID id = java.util.UUID.randomUUID();
        java.util.concurrent.atomic.AtomicInteger animations = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger sounds = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<Runnable> scheduled = new java.util.ArrayList<>();
        org.bukkit.entity.Player viewer = (org.bukkit.entity.Player) java.lang.reflect.Proxy.newProxyInstance(
                org.bukkit.entity.Player.class.getClassLoader(), new Class<?>[]{org.bukkit.entity.Player.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getUniqueId" -> id;
                        case "isOnline" -> true;
                        case "sendHurtAnimation" -> { animations.incrementAndGet(); yield null; }
                        case "stopSound" -> null;
                        default -> throw new AssertionError("Unexpected player API: " + method.getName());
                    };
                });
        AmbientEntityRegistry registry = new AmbientEntityRegistry();
        AmbientEffectDispatcher dispatcher = new AmbientEffectDispatcher(null, messages, null,
                new FakeSilverfishService(null, registry, null), (action, ticks) -> {
                    scheduled.add(action);
                    return () -> {};
                }, (player, layer) -> {
                    assertThat(player).isSameAs(viewer);
                    sounds.incrementAndGet();
                });
        assertThat(dispatcher.dispatch(viewer, AmbientEffectType.HURT_FLASH, snapshot.config().effects(), snapshot)).isTrue();
        assertThat(animations).hasValue(1);
        assertThat(sounds).hasValue(1);
        assertThat(registry.presentationsFor(id)).singleElement()
                .satisfies(entry -> assertThat(entry.type()).isEqualTo(AmbientEffectType.HURT_FLASH));
        scheduled.getFirst().run();
        assertThat(registry.presentationsFor(id)).isEmpty();
        dispatcher.cancelPending(id);
        assertThat(animations).hasValue(1);
    }

    @Test void signLimitsFallbackAndGhostSubstitutionUseMessageKeys() {
        assertThat(ScreenLines.validate(List.of("&7" + "\uD83D\uDC7B".repeat(80)), "effects.sign.lines", 4, 80)).hasSize(1);
        assertThatThrownBy(() -> ScreenLines.validate(List.of("x", "x", "x", "x", "x"), "effects.sign.lines", 4, 80))
                .hasMessageContaining("effects.sign.lines");
        assertThatThrownBy(() -> ScreenLines.editValue("['" + "x".repeat(81) + "']", "effects.sign.lines"))
                .hasMessageContaining("effects.sign.lines");
        assertThatThrownBy(() -> ScreenLines.validateGhostLabel("{victim}")).hasMessageContaining("effects.victim-ghost.label");
        MessagesSnapshot messages = new MessagesSnapshot("en", "es", Map.of(),
                Map.of("effects.sign.lines", "x\ny"), Map.of(), Map.of());
        assertThat(messages.lineKeys("effects.sign.lines")).containsExactly("effects.sign.lines.0", "effects.sign.lines.1");
        assertThat(messages.resolveRaw("effects.sign.lines.1", new java.util.HashSet<>(), null)).isEqualTo("y");
    }

    @Test void renderersNeverMutateWorldCombatMetricsOrIdentity() throws Exception {
        for (String file : List.of("PrivateBlocks.java", "PrivateGhost.java", "AmbientEffectDispatcher.java")) {
            String source = Files.readString(Path.of("src/main/java/com/dasannn/socialblueprint/feature/effects", file));
            assertThat(source).doesNotContain(".damage(", ".setHealth(", ".setAbsorptionAmount(", ".setVelocity(",
                    ".setNoDamageTicks(", ".setType(", ".setBlockData(", ".update(", "addFreshEntity", "spawnEntity",
                    "GameProfile", "PlayerInfo", "ReputationRepository", "PsychosisRepository", "Economy");
        }
        String blocks = Files.readString(Path.of("src/main/java/com/dasannn/socialblueprint/feature/effects/PrivateBlocks.java"));
        assertThat(blocks).contains("current.getBlockData()", "current.getState()", "player.sendBlockUpdate(at, sign)");
    }
}
