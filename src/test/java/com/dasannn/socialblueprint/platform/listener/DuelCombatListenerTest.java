package com.dasannn.socialblueprint.platform.listener;

import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.CombatContext;
import com.dasannn.socialblueprint.domain.HonorKind;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.PsychosisEvent;
import com.dasannn.socialblueprint.domain.ReputationEvent;
import com.dasannn.socialblueprint.domain.Status;
import com.dasannn.socialblueprint.domain.duel.DisconnectClassification;
import com.dasannn.socialblueprint.feature.duel.DuelService;
import com.dasannn.socialblueprint.feature.profile.PlayerLookup;
import com.dasannn.socialblueprint.storage.AuditRepository;
import com.dasannn.socialblueprint.storage.DuelRepository;
import com.dasannn.socialblueprint.storage.PsychosisRepository;
import com.dasannn.socialblueprint.storage.ReputationRepository;
import com.dasannn.socialblueprint.storage.StatusCache;
import com.dasannn.socialblueprint.storage.StorageEngine;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class DuelCombatListenerTest {

    @TempDir
    File tempDir;

    private StorageEngine storage;
    private StatusCache statusCache;
    private ReputationRepository reputationRepo;
    private PsychosisRepository psychosisRepo;
    private AuditRepository auditRepo;
    private DuelRepository duelRepo;
    private ConfigManager configManager;
    private MessageRegistry messageRegistry;
    private DuelService duelService;
    private DuelCombatListener listener;

    private final Map<UUID, Player> mockPlayers = new HashMap<>();
    private final Map<String, PlayerLookup.KnownPlayer> knownPlayers = new HashMap<>();
    private final Instant baseTime = Instant.parse("2026-09-30T12:00:00Z");

    @BeforeEach
    void setUp() throws Exception {
        File configFile = new File(tempDir, "config.yml");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertThat(in).isNotNull();
            Files.copy(in, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        copyResource("messages_en.yml", new File(tempDir, "messages_en.yml"));
        copyResource("messages_es.yml", new File(tempDir, "messages_es.yml"));

        Logger logger = Logger.getLogger("DuelCombatListenerTest-" + System.nanoTime());
        messageRegistry = new MessageRegistry(tempDir, "en", logger);
        configManager = new ConfigManager(configFile, messageRegistry, Runnable::run, logger);
        configManager.initialize();

        storage = StorageEngine.inMemory();
        storage.runMigrations();

        statusCache = new StatusCache();
        reputationRepo = new ReputationRepository(storage, statusCache);
        psychosisRepo = new PsychosisRepository(storage);
        auditRepo = new AuditRepository(storage);
        duelRepo = new DuelRepository(storage);

        PlayerLookup lookup = nameOrUuid -> Optional.ofNullable(knownPlayers.get(nameOrUuid.toLowerCase()));

        duelService = new DuelService(
                duelRepo,
                auditRepo,
                psychosisRepo,
                configManager,
                messageRegistry,
                lookup,
                Runnable::run,
                (d, r) -> () -> {},
                (p, c) -> {},
                c -> {}
        );

        listener = new DuelCombatListener(duelService, psychosisRepo, configManager, reputationRepo);
    }

    @AfterEach
    void tearDown() {
        if (duelService != null) {
            duelService.shutdown();
        }
        if (storage != null) {
            storage.close();
        }
    }

    private Player createMockPlayer(String name) {
        UUID uuid = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        PlayerId pid = PlayerId.of(uuid);

        InvocationHandler handler = (proxy, method, args) -> {
            String mName = method.getName();
            if ("getName".equals(mName)) return name;
            if ("getUniqueId".equals(mName)) return uuid;
            if ("getKiller".equals(mName)) return null;
            return defaultValue(method.getReturnType());
        };

        Player player = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                handler
        );

        mockPlayers.put(uuid, player);
        knownPlayers.put(name.toLowerCase(), new PlayerLookup.KnownPlayer(pid, name, true));
        knownPlayers.put(uuid.toString(), new PlayerLookup.KnownPlayer(pid, name, true));
        return player;
    }

    @Test
    @DisplayName("DoD 1 / T-061 / SB-031: A duel kill changes neither status nor Psychosis")
    void duelKillChangesNeitherStatusNorPsychosis() {
        Player killer = createMockPlayer("GladiatorA");
        Player victim = createMockPlayer("GladiatorB");
        PlayerId killerId = PlayerId.of(killer.getUniqueId());
        PlayerId victimId = PlayerId.of(victim.getUniqueId());

        // Establish initial status for both players via a distinct third-party rater
        PlayerId rater = PlayerId.of(UUID.randomUUID());
        reputationRepo.save(new ReputationEvent(rater, killerId, 10, HonorKind.POSITIVE, 500.0, null, baseTime.minusSeconds(100)));
        reputationRepo.save(new ReputationEvent(rater, victimId, 5, HonorKind.POSITIVE, 500.0, null, baseTime.minusSeconds(100)));

        Status killerStatusBefore = reputationRepo.getStatus(killerId);
        Status victimStatusBefore = reputationRepo.getStatus(victimId);
        assertThat(killerStatusBefore.value()).isEqualTo(10);
        assertThat(victimStatusBefore.value()).isEqualTo(5);

        // Verify initial open kills count
        assertThat(psychosisRepo.countOpenKillsSince(killerId, baseTime.minusSeconds(200))).isZero();

        // Put players in an active duel
        RuntimeSnapshot snapshot = configManager.snapshot();
        duelService.challenge(killerId, Map.of("s1", Set.of(killerId), "s2", Set.of(victimId)), snapshot);
        duelService.accept(victimId, null, snapshot);
        assertThat(duelService.areInSameActiveDuel(killerId, victimId)).isTrue();

        // Pass combat death facts directly to listener
        listener.handleDeath(victimId, killerId, baseTime, snapshot);

        // 1. Assert Psychosis: duel kill recorded with CombatContext.DUEL, open kills remains 0!
        List<PsychosisEvent> kills = psychosisRepo.findKillsByKillerSince(killerId, baseTime.minusSeconds(200));
        assertThat(kills).hasSize(1);
        assertThat(kills.getFirst().context()).isEqualTo(CombatContext.DUEL);
        assertThat(psychosisRepo.countOpenKillsSince(killerId, baseTime.minusSeconds(200)))
                .as("Open kills must remain zero so Killing Psychosis does not move")
                .isZero();

        // 2. Assert Social Status: neither status moved!
        Status killerStatusAfter = reputationRepo.getStatus(killerId);
        Status victimStatusAfter = reputationRepo.getStatus(victimId);
        assertThat(killerStatusAfter.value())
                .as("Killer status must not change from duel kill")
                .isEqualTo(10);
        assertThat(victimStatusAfter.value())
                .as("Victim status must not change from duel kill")
                .isEqualTo(5);

        // No new reputation events exist for either player
        List<ReputationEvent> victimEvents = reputationRepo.findByTarget(victimId);
        assertThat(victimEvents).hasSize(1); // Only the initial event
    }

    @Test
    @DisplayName("T-130 / SB-032: A kill outside a duel raises Psychosis and applies kill penalty to killer status")
    void killOutsideDuelChangesOnlyPsychosisAndNeverTouchesStatus() {
        Player killer = createMockPlayer("Murderer");
        Player victim = createMockPlayer("Innocent");
        PlayerId killerId = PlayerId.of(killer.getUniqueId());
        PlayerId victimId = PlayerId.of(victim.getUniqueId());

        // Establish initial status for both players via a distinct third-party rater
        PlayerId rater = PlayerId.of(UUID.randomUUID());
        reputationRepo.save(new ReputationEvent(rater, killerId, 10, HonorKind.POSITIVE, 500.0, null, baseTime.minusSeconds(100)));
        reputationRepo.save(new ReputationEvent(rater, victimId, 5, HonorKind.POSITIVE, 500.0, null, baseTime.minusSeconds(100)));

        Status killerStatusBefore = reputationRepo.getStatus(killerId);
        Status victimStatusBefore = reputationRepo.getStatus(victimId);
        assertThat(killerStatusBefore.value()).isEqualTo(10);
        assertThat(victimStatusBefore.value()).isEqualTo(5);

        assertThat(psychosisRepo.countOpenKillsSince(killerId, baseTime.minusSeconds(200))).isZero();

        // Neither player is in a duel
        assertThat(duelService.isInActiveDuel(killerId)).isFalse();
        assertThat(duelService.isInActiveDuel(victimId)).isFalse();

        // Pass combat death facts directly to listener
        RuntimeSnapshot snapshot = configManager.snapshot();
        listener.handleDeath(victimId, killerId, baseTime, snapshot).join();

        // 1. Assert Psychosis: recorded with CombatContext.OPEN, open kills increases by 1!
        List<PsychosisEvent> kills = psychosisRepo.findKillsByKillerSince(killerId, baseTime.minusSeconds(200));
        assertThat(kills).hasSize(1);
        assertThat(kills.getFirst().context()).isEqualTo(CombatContext.OPEN);
        assertThat(psychosisRepo.countOpenKillsSince(killerId, baseTime.minusSeconds(200)))
                .as("Open kills must increase by 1, raising Killing Psychosis")
                .isEqualTo(1);

        // 2. Assert Social Status: Killer loses 1 status (T-130), victim status is NEVER deducted
        Status killerStatusAfter = reputationRepo.getStatus(killerId);
        Status victimStatusAfter = reputationRepo.getStatus(victimId);
        assertThat(killerStatusAfter.value())
                .as("Killer status drops by configured delta (-1)")
                .isEqualTo(9);
        assertThat(victimStatusAfter.value())
                .as("Victim status must never be deducted on open kill")
                .isEqualTo(5);

        // Killer received a SYSTEM_KILL event
        List<ReputationEvent> killerEvents = reputationRepo.findByTarget(killerId);
        assertThat(killerEvents).hasSize(2);
        ReputationEvent penaltyEvent = killerEvents.get(1);
        assertThat(penaltyEvent.actor()).isNull();
        assertThat(penaltyEvent.kind()).isEqualTo(HonorKind.SYSTEM_KILL);
        assertThat(penaltyEvent.delta()).isEqualTo(-1);
        assertThat(penaltyEvent.cost()).isEqualTo(0.0);
        assertThat(penaltyEvent.reason()).isEqualTo("kill-penalty.reason");

        // Absolutely no reputation event was added to victim
        List<ReputationEvent> victimEvents = reputationRepo.findByTarget(victimId);
        assertThat(victimEvents).hasSize(1); // Still only the initial event
    }

    @Test
    @DisplayName("T-063: Combat damage tracking records recent combat damage and identifies combat logging")
    void combatDamageTrackingIdentifiesCombatLogging() {
        Player attacker = createMockPlayer("FighterA");
        Player victim = createMockPlayer("FighterB");
        PlayerId attackerId = PlayerId.of(attacker.getUniqueId());
        PlayerId victimId = PlayerId.of(victim.getUniqueId());

        RuntimeSnapshot snapshot = configManager.snapshot();
        duelService.challenge(attackerId, Map.of("s1", Set.of(attackerId), "s2", Set.of(victimId)), snapshot);
        duelService.accept(victimId, null, snapshot);

        // Direct player attack
                listener.handleDamage(attacker, victim);

        // Victim immediately quits -> classified as COMBAT_LOG
        Optional<DisconnectClassification> disc = duelService.handlePlayerQuit(victimId, Instant.now(), snapshot);
        assertThat(disc).contains(DisconnectClassification.COMBAT_LOG);
    }

    @Test
    @DisplayName("T-063: Projectile damage correctly resolves player shooter and records combat damage")
    void projectileDamageResolvesPlayerShooter() {
        Player archer = createMockPlayer("Archer");
        Player target = createMockPlayer("Target");
        PlayerId archerId = PlayerId.of(archer.getUniqueId());
        PlayerId targetId = PlayerId.of(target.getUniqueId());

        RuntimeSnapshot snapshot = configManager.snapshot();
        duelService.challenge(archerId, Map.of("s1", Set.of(archerId), "s2", Set.of(targetId)), snapshot);
        duelService.accept(targetId, null, snapshot);

        // Mock Arrow projectile with archer as shooter
        InvocationHandler arrowHandler = (proxy, method, args) -> {
            String mName = method.getName();
            if ("getShooter".equals(mName)) return archer;
            return defaultValue(method.getReturnType());
        };
        Arrow arrow = (Arrow) Proxy.newProxyInstance(
                Arrow.class.getClassLoader(),
                new Class<?>[]{Arrow.class},
                arrowHandler
        );

                listener.handleDamage(arrow, target);

        // Target quits shortly after taking arrow damage -> classified as COMBAT_LOG
        Optional<DisconnectClassification> disc = duelService.handlePlayerQuit(targetId, Instant.now(), snapshot);
        assertThat(disc).contains(DisconnectClassification.COMBAT_LOG);
    }

    @Test
    @DisplayName("T-063: Self damage does not record combat damage from another player")
    void selfDamageIgnored() {
        Player player = createMockPlayer("SelfDamager");
        PlayerId playerId = PlayerId.of(player.getUniqueId());
        Player opponent = createMockPlayer("Opponent");
        PlayerId opponentId = PlayerId.of(opponent.getUniqueId());

        RuntimeSnapshot snapshot = configManager.snapshot();
        duelService.challenge(playerId, Map.of("s1", Set.of(playerId), "s2", Set.of(opponentId)), snapshot);
        duelService.accept(opponentId, null, snapshot);

        // Self-damage event
                listener.handleDamage(player, player);

        // Quit is NORMAL_DISCONNECT because self-damage was ignored
        Optional<DisconnectClassification> disc = duelService.handlePlayerQuit(playerId, Instant.now(), snapshot);
        assertThat(disc).contains(DisconnectClassification.NORMAL_DISCONNECT);
    }

    @Test
    @DisplayName("T-063: Player quit and join events delegate cleanly to DuelService")
    void quitAndJoinEventsDelegateCleanly() {
        Player player = createMockPlayer("Reconnector");
        Player opponent = createMockPlayer("Opponent2");
        PlayerId playerId = PlayerId.of(player.getUniqueId());
        PlayerId opponentId = PlayerId.of(opponent.getUniqueId());

        RuntimeSnapshot snapshot = configManager.snapshot();
        duelService.challenge(playerId, Map.of("s1", Set.of(playerId), "s2", Set.of(opponentId)), snapshot);
        duelService.accept(opponentId, null, snapshot);

        PlayerQuitEvent quitEvent = new PlayerQuitEvent(player, "quit");
        listener.onPlayerQuit(quitEvent);

        assertThat(duelService.getActiveDuel(playerId).isDisconnected(playerId)).isTrue();

        PlayerJoinEvent joinEvent = new PlayerJoinEvent(player, "join");
        listener.onPlayerJoin(joinEvent);

        assertThat(duelService.getActiveDuel(playerId).isDisconnected(playerId)).isFalse();
    }

    @Test
    @DisplayName("P2: Environmental death where getKiller() is set leaves killer uncounted and does not raise Psychosis")
    void environmentalDeathLeavesKillerUncountedAndDoesNotRaisePsychosis() {
        Player attacker = createMockPlayer("Attacker");
        Player victim = createMockPlayer("Victim");
        PlayerId attackerId = PlayerId.of(attacker.getUniqueId());
        PlayerId victimId = PlayerId.of(victim.getUniqueId());

        // Attacker hits victim in open combat
        listener.handleDamage(attacker, victim);

        // Victim later dies to fall/void: victim.getKiller() returns attacker,
        // but DamageSource has no player causing entity and no projectile direct entity
        DamageSource environmentalSource = (DamageSource) Proxy.newProxyInstance(
                DamageSource.class.getClassLoader(),
                new Class<?>[]{DamageSource.class},
                (proxy, method, args) -> {
                    if ("getCausingEntity".equals(method.getName())) return null;
                    if ("getDirectEntity".equals(method.getName())) return null;
                    return defaultValue(method.getReturnType());
                }
        );

        // Configure victim mock so getKiller returns attacker
        Player victimWithKiller = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) return "Victim";
                    if ("getUniqueId".equals(method.getName())) return victim.getUniqueId();
                    if ("getKiller".equals(method.getName())) return attacker;
                    return defaultValue(method.getReturnType());
                }
        );

        Player resolved = listener.resolveKiller(victimWithKiller, environmentalSource);
        assertThat(resolved).isNull();

        // Passing null killerId leaves ambiguous environmental deaths uncounted
        RuntimeSnapshot snapshot = configManager.snapshot();
        listener.handleDeath(victimId, null, baseTime, snapshot);
        assertThat(psychosisRepo.countOpenKillsSince(attackerId, baseTime.minusSeconds(200))).isZero();
    }

    @Test
    @DisplayName("P2: Fatal projectile death where shooter is unresolved falls back to getKiller()")
    void fatalProjectileDeathFallsBackToGetKiller() {
        Player attacker = createMockPlayer("Archer");
        Player victim = createMockPlayer("Target");

        Player victimWithKiller = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) return "Target";
                    if ("getUniqueId".equals(method.getName())) return victim.getUniqueId();
                    if ("getKiller".equals(method.getName())) return attacker;
                    return defaultValue(method.getReturnType());
                }
        );

        Projectile projectile = (Projectile) Proxy.newProxyInstance(
                Projectile.class.getClassLoader(),
                new Class<?>[]{Projectile.class},
                (proxy, method, args) -> {
                    if ("getShooter".equals(method.getName())) return null; // Unresolved shooter
                    return defaultValue(method.getReturnType());
                }
        );

        DamageSource projectileSource = (DamageSource) Proxy.newProxyInstance(
                DamageSource.class.getClassLoader(),
                new Class<?>[]{DamageSource.class},
                (proxy, method, args) -> {
                    if ("getCausingEntity".equals(method.getName())) return null;
                    if ("getDirectEntity".equals(method.getName())) return projectile;
                    return defaultValue(method.getReturnType());
                }
        );

        Player resolved = listener.resolveKiller(victimWithKiller, projectileSource);
        // Compare identity, not the proxy: the invocation handler does not implement
        // equals, so two references to the same proxy still compare unequal.
        assertThat(resolved).isNotNull();
        assertThat(resolved.getUniqueId()).isEqualTo(attacker.getUniqueId());
    }

    private void copyResource(String resourceName, File destination) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new IllegalStateException("Resource not found: " + resourceName);
            }
            Files.copy(in, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) return false;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == double.class) return 0.0;
        if (returnType == float.class) return 0.0f;
        return null;
    }
}
