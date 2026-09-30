package com.dasannn.socialblueprint.feature.duel;

import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.AuditEvent;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.duel.ActiveDuelSession;
import com.dasannn.socialblueprint.domain.duel.DisconnectClassification;
import com.dasannn.socialblueprint.domain.duel.DuelChallenge;
import com.dasannn.socialblueprint.domain.duel.DuelRecord;
import com.dasannn.socialblueprint.domain.duel.DuelState;
import com.dasannn.socialblueprint.feature.profile.PlayerLookup;
import com.dasannn.socialblueprint.storage.AuditRepository;
import com.dasannn.socialblueprint.storage.DuelRepository;
import com.dasannn.socialblueprint.storage.PsychosisRepository;
import net.kyori.adventure.text.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Service managing the duel lifecycle per SB-030 to SB-033 and T-060 to T-063:
 * - 1v1 and group duels with strict consent requirements.
 * - Group duels require EVERY member's consent (SB-030, T-060).
 * - Challenges expire on a configurable timer and leave no trace (zero orphaned rows).
 * - Disconnects mid-duel are classified as COMBAT_LOG or NORMAL_DISCONNECT (SB-033).
 * - Combat logging has social consequences (broadcast/notification, audit, forfeiture), never mechanical.
 * - Reconnect grace period allows players with normal disconnects to resume.
 */
public class DuelService {

    @FunctionalInterface
    public interface TimerScheduler {
        interface TaskHandle {
            void cancel();
        }

        TaskHandle schedule(Duration delay, Runnable task);
    }

    public sealed interface ChallengeResult {
        record Success(DuelChallenge challenge) implements ChallengeResult {}
        record AlreadyInDuel() implements ChallengeResult {}
        record AlreadyChallenging() implements ChallengeResult {}
        record TargetAlreadyInDuel(PlayerId target) implements ChallengeResult {}
        record CannotDuelSelf() implements ChallengeResult {}
    }

    public sealed interface AcceptResult {
        record DuelStarted(ActiveDuelSession session) implements AcceptResult {}
        record ConsentRecorded(DuelChallenge challenge, int acceptedCount, int totalCount) implements AcceptResult {}
        record AlreadyInDuel() implements AcceptResult {}
        record NoPendingChallenge() implements AcceptResult {}
    }

    public sealed interface DenyResult {
        record Denied(DuelChallenge challenge) implements DenyResult {}
        record NoPendingChallenge() implements DenyResult {}
    }

    public sealed interface LeaveResult {
        record LeftDuel(ActiveDuelSession session, boolean duelEnded, Set<PlayerId> winners) implements LeaveResult {}
        record ChallengeCancelled(DuelChallenge challenge) implements LeaveResult {}
        record NotInDuel() implements LeaveResult {}
    }

    private final DuelRepository duelRepository;
    private final AuditRepository auditRepository;
    private final PsychosisRepository psychosisRepository;
    private final ConfigManager configManager;
    private final MessageRegistry messageRegistry;
    private final PlayerLookup playerLookup;
    private final Consumer<Runnable> mainThreadRunner;
    private final TimerScheduler timerScheduler;
    private final BiConsumer<PlayerId, Component> messageSender;
    private final Consumer<Component> broadcastConsumer;

    // In-memory active duel tracking
    private final Map<String, ActiveDuelSession> activeDuels = new ConcurrentHashMap<>();
    private final Map<PlayerId, String> playerToActiveDuel = new ConcurrentHashMap<>();

    // In-memory pending challenge tracking
    private final Map<String, DuelChallenge> pendingChallenges = new ConcurrentHashMap<>();
    private final Map<PlayerId, String> playerOutgoingChallenge = new ConcurrentHashMap<>();
    private final Map<PlayerId, Set<String>> playerIncomingChallenges = new ConcurrentHashMap<>();
    private final Map<String, TimerScheduler.TaskHandle> challengeExpiryHandles = new ConcurrentHashMap<>();

    // Disconnect grace handles
    private final Map<PlayerId, TimerScheduler.TaskHandle> disconnectGraceHandles = new ConcurrentHashMap<>();

    // Recent combat damage tracking (victim -> timestamp, victim -> attacker)
    private final Map<PlayerId, Instant> recentDamageTime = new ConcurrentHashMap<>();
    private final Map<PlayerId, PlayerId> recentDamagers = new ConcurrentHashMap<>();

    public DuelService(
            DuelRepository duelRepository,
            AuditRepository auditRepository,
            PsychosisRepository psychosisRepository,
            ConfigManager configManager,
            MessageRegistry messageRegistry,
            PlayerLookup playerLookup,
            Consumer<Runnable> mainThreadRunner,
            TimerScheduler timerScheduler,
            BiConsumer<PlayerId, Component> messageSender,
            Consumer<Component> broadcastConsumer
    ) {
        this.duelRepository = Objects.requireNonNull(duelRepository, "duelRepository must not be null");
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository must not be null");
        this.psychosisRepository = Objects.requireNonNull(psychosisRepository, "psychosisRepository must not be null");
        this.configManager = Objects.requireNonNull(configManager, "configManager must not be null");
        this.messageRegistry = Objects.requireNonNull(messageRegistry, "messageRegistry must not be null");
        this.playerLookup = playerLookup;
        this.mainThreadRunner = mainThreadRunner != null ? mainThreadRunner : Runnable::run;
        this.timerScheduler = timerScheduler != null ? timerScheduler : (d, r) -> () -> {};
        this.messageSender = messageSender != null ? messageSender : (p, m) -> {};
        this.broadcastConsumer = broadcastConsumer != null ? broadcastConsumer : c -> {};
    }

    // -------------------------------------------------------------------------
    // Challenge Lifecycle (T-060, SB-030)
    // -------------------------------------------------------------------------

    public ChallengeResult challenge(PlayerId challenger, Map<String, Set<PlayerId>> sides, RuntimeSnapshot snapshot) {
        Objects.requireNonNull(challenger, "challenger must not be null");
        Objects.requireNonNull(sides, "sides must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        if (isInActiveDuel(challenger)) {
            return new ChallengeResult.AlreadyInDuel();
        }
        if (playerOutgoingChallenge.containsKey(challenger)) {
            return new ChallengeResult.AlreadyChallenging();
        }

        // Validate participants
        Set<PlayerId> allParticipants = new HashSet<>();
        for (Set<PlayerId> sideMembers : sides.values()) {
            for (PlayerId member : sideMembers) {
                if (isInActiveDuel(member)) {
                    return new ChallengeResult.TargetAlreadyInDuel(member);
                }
                allParticipants.add(member);
            }
        }

        // Cannot duel self (must have at least one opponent distinct from challenger)
        Set<PlayerId> opponents = new HashSet<>(allParticipants);
        opponents.remove(challenger);
        if (opponents.isEmpty()) {
            return new ChallengeResult.CannotDuelSelf();
        }

        String challengeId = UUID.randomUUID().toString();
        Duration timeout = snapshot.config().duel() != null
                ? snapshot.config().duel().challengeTimeout()
                : Duration.ofSeconds(60);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(timeout);

        DuelChallenge challenge = new DuelChallenge(challengeId, challenger, sides, now, expiresAt);
        pendingChallenges.put(challengeId, challenge);
        playerOutgoingChallenge.put(challenger, challengeId);

        for (PlayerId target : opponents) {
            playerIncomingChallenges.computeIfAbsent(target, k -> ConcurrentHashMap.newKeySet()).add(challengeId);
        }

        // Schedule clean expiration: leaves no trace if unaccepted
        TimerScheduler.TaskHandle handle = timerScheduler.schedule(timeout, () -> {
            expireChallenge(challengeId, configManager.snapshot());
        });
        challengeExpiryHandles.put(challengeId, handle);

        // Notify participants
        String challengerName = resolveName(challenger);
        String targetSummary = formatParticipantNames(opponents);

        messageSender.accept(challenger, messageRegistry.renderWithPrefix(snapshot, "duel.challenge-sent",
                Map.of("target", targetSummary)));

        for (PlayerId target : opponents) {
            messageSender.accept(target, messageRegistry.renderWithPrefix(snapshot, "duel.challenge-received",
                    Map.of("challenger", challengerName)));
        }

        return new ChallengeResult.Success(challenge);
    }

    public AcceptResult accept(PlayerId player, String optionalChallengerQuery, RuntimeSnapshot snapshot) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        if (isInActiveDuel(player)) {
            return new AcceptResult.AlreadyInDuel();
        }

        DuelChallenge challenge = findIncomingChallenge(player, optionalChallengerQuery);
        if (challenge == null) {
            return new AcceptResult.NoPendingChallenge();
        }

        challenge.accept(player);
        String playerName = resolveName(player);

        if (challenge.hasEveryMemberConsented()) {
            // All invited sides and participants have consented! Start duel!
            cleanUpChallenge(challenge.id());

            ActiveDuelSession session = new ActiveDuelSession(challenge.id(), challenge.sides(), Instant.now());
            activeDuels.put(session.id(), session);
            for (PlayerId participant : session.allParticipants()) {
                playerToActiveDuel.put(participant, session.id());
                // Cancel any lingering pending challenges for participants
                cancelOutgoingChallenge(participant);
            }

            // Persist active duel in SQLite on storage executor
            duelRepository.saveAsync(new DuelRecord(
                    session.id(),
                    DuelState.ACTIVE,
                    session.createdAt(),
                    session.toParticipantList()
            ));

            // Notify all participants of duel start
            Set<PlayerId> all = session.allParticipants();
            if (challenge.is1v1()) {
                List<PlayerId> list = new ArrayList<>(all);
                PlayerId p1 = list.get(0);
                PlayerId p2 = list.get(1);
                String n1 = resolveName(p1);
                String n2 = resolveName(p2);
                messageSender.accept(p1, messageRegistry.renderWithPrefix(snapshot, "duel.accepted", Map.of("opponent", n2)));
                messageSender.accept(p2, messageRegistry.renderWithPrefix(snapshot, "duel.accepted", Map.of("opponent", n1)));
            } else {
                for (PlayerId p : all) {
                    messageSender.accept(p, messageRegistry.renderWithPrefix(snapshot, "duel.group-accepted",
                            Map.of("count", String.valueOf(all.size()))));
                }
            }

            return new AcceptResult.DuelStarted(session);
        } else {
            // Group duel: participant consent recorded, still waiting for others
            int accepted = challenge.acceptedPlayers().size();
            int total = challenge.allParticipants().size();

            for (PlayerId p : challenge.allParticipants()) {
                messageSender.accept(p, messageRegistry.renderWithPrefix(snapshot, "duel.group-waiting-consent",
                        Map.of("player", playerName, "accepted", String.valueOf(accepted), "total", String.valueOf(total))));
            }

            return new AcceptResult.ConsentRecorded(challenge, accepted, total);
        }
    }

    public DenyResult deny(PlayerId player, String optionalChallengerQuery, RuntimeSnapshot snapshot) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        DuelChallenge challenge = findIncomingChallenge(player, optionalChallengerQuery);
        if (challenge == null) {
            return new DenyResult.NoPendingChallenge();
        }

        cleanUpChallenge(challenge.id());

        // Notify participants: duel denied
        for (PlayerId p : challenge.allParticipants()) {
            messageSender.accept(p, messageRegistry.renderWithPrefix(snapshot, "duel.denied"));
        }

        return new DenyResult.Denied(challenge);
    }

    public LeaveResult leave(PlayerId player, RuntimeSnapshot snapshot) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        // 1. If currently in an active duel: leave / surrender
        ActiveDuelSession session = getActiveDuel(player);
        if (session != null) {
            boolean concluded = session.eliminate(player);
            playerToActiveDuel.remove(player);
            recentDamageTime.remove(player);
            recentDamagers.remove(player);

            String playerName = resolveName(player);
            messageSender.accept(player, messageRegistry.renderWithPrefix(snapshot, "duel.left", Map.of("player", playerName)));

            for (PlayerId p : session.remainingPlayers()) {
                messageSender.accept(p, messageRegistry.renderWithPrefix(snapshot, "duel.left", Map.of("player", playerName)));
            }

            Set<PlayerId> winners = session.winningPlayers();
            if (concluded) {
                endDuel(session, DuelState.ENDED, snapshot);
            }

            return new LeaveResult.LeftDuel(session, concluded, winners);
        }

        // 2. If challenger with an outgoing pending challenge: cancel it
        String outgoingId = playerOutgoingChallenge.get(player);
        if (outgoingId != null) {
            DuelChallenge challenge = pendingChallenges.get(outgoingId);
            if (challenge != null) {
                cleanUpChallenge(outgoingId);
                for (PlayerId p : challenge.allParticipants()) {
                    messageSender.accept(p, messageRegistry.renderWithPrefix(snapshot, "duel.challenge-cancelled"));
                }
                return new LeaveResult.ChallengeCancelled(challenge);
            }
        }

        return new LeaveResult.NotInDuel();
    }

    public void expireChallenge(String challengeId, RuntimeSnapshot snapshot) {
        DuelChallenge challenge = pendingChallenges.remove(challengeId);
        if (challenge != null) {
            cleanUpChallenge(challengeId);
            for (PlayerId p : challenge.allParticipants()) {
                messageSender.accept(p, messageRegistry.renderWithPrefix(snapshot, "duel.expired"));
            }
        }
    }

    private void cleanUpChallenge(String challengeId) {
        DuelChallenge challenge = pendingChallenges.remove(challengeId);
        TimerScheduler.TaskHandle handle = challengeExpiryHandles.remove(challengeId);
        if (handle != null) {
            handle.cancel();
        }

        if (challenge != null) {
            playerOutgoingChallenge.remove(challenge.challenger(), challengeId);
            for (PlayerId p : challenge.allParticipants()) {
                Set<String> incoming = playerIncomingChallenges.get(p);
                if (incoming != null) {
                    incoming.remove(challengeId);
                    if (incoming.isEmpty()) {
                        playerIncomingChallenges.remove(p);
                    }
                }
            }
        }
    }

    private void cancelOutgoingChallenge(PlayerId player) {
        String challengeId = playerOutgoingChallenge.remove(player);
        if (challengeId != null) {
            cleanUpChallenge(challengeId);
        }
    }

    private DuelChallenge findIncomingChallenge(PlayerId player, String optionalChallengerQuery) {
        Set<String> incomingIds = playerIncomingChallenges.get(player);
        if (incomingIds == null || incomingIds.isEmpty()) {
            return null;
        }

        if (optionalChallengerQuery == null || optionalChallengerQuery.isBlank()) {
            // Default to most recently or first available challenge
            String firstId = incomingIds.iterator().next();
            return pendingChallenges.get(firstId);
        }

        for (String id : incomingIds) {
            DuelChallenge c = pendingChallenges.get(id);
            if (c != null) {
                String name = resolveName(c.challenger());
                if (name.equalsIgnoreCase(optionalChallengerQuery) || c.challenger().toString().equalsIgnoreCase(optionalChallengerQuery)) {
                    return c;
                }
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Combat Tracking & Kills (T-061, T-062, SB-031, SB-032)
    // -------------------------------------------------------------------------

    public void recordCombatDamage(PlayerId victim, PlayerId attacker, Instant timestamp) {
        Objects.requireNonNull(victim, "victim must not be null");
        Objects.requireNonNull(attacker, "attacker must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");

        if (isInActiveDuel(victim)) {
            recentDamageTime.put(victim, timestamp);
            recentDamagers.put(victim, attacker);
        }
    }

    public boolean areInSameActiveDuel(PlayerId p1, PlayerId p2) {
        if (p1 == null || p2 == null) return false;
        String d1 = playerToActiveDuel.get(p1);
        String d2 = playerToActiveDuel.get(p2);
        return d1 != null && d1.equals(d2);
    }

    public void handleDeath(PlayerId victim, PlayerId killer, Instant deathTime, RuntimeSnapshot snapshot) {
        Objects.requireNonNull(victim, "victim must not be null");
        Objects.requireNonNull(deathTime, "deathTime must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        ActiveDuelSession session = getActiveDuel(victim);
        if (session == null) {
            return;
        }

        boolean concluded = session.eliminate(victim);
        playerToActiveDuel.remove(victim);
        recentDamageTime.remove(victim);
        recentDamagers.remove(victim);

        if (concluded) {
            Set<PlayerId> winners = session.winningPlayers();
            String winnerNames = formatParticipantNames(winners);
            for (PlayerId p : session.allParticipants()) {
                messageSender.accept(p, messageRegistry.renderWithPrefix(snapshot, "duel.ended-winner",
                        Map.of("winner", winnerNames)));
            }
            endDuel(session, DuelState.ENDED, snapshot);
        }
    }

    // -------------------------------------------------------------------------
    // Disconnect Handling (T-063, SB-033)
    // -------------------------------------------------------------------------

    public Optional<DisconnectClassification> handlePlayerQuit(PlayerId player, Instant quitTime, RuntimeSnapshot snapshot) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(quitTime, "quitTime must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        ActiveDuelSession session = getActiveDuel(player);
        if (session == null) {
            return Optional.empty();
        }

        Duration combatLogWindow = snapshot.config().duel() != null
                ? snapshot.config().duel().disconnect().combatLogWindow()
                : Duration.ofSeconds(10);

        Instant lastDamage = recentDamageTime.get(player);
        boolean isCombatLog = (combatLogWindow.isZero() && lastDamage != null)
                || (lastDamage != null && Duration.between(lastDamage, quitTime).compareTo(combatLogWindow) <= 0);

        DisconnectClassification classification = isCombatLog
                ? DisconnectClassification.COMBAT_LOG
                : DisconnectClassification.NORMAL_DISCONNECT;

        String playerName = resolveName(player);

        if (classification == DisconnectClassification.COMBAT_LOG) {
            // Immediate forfeit due to combat log
            boolean concluded = session.eliminate(player);
            playerToActiveDuel.remove(player);
            recentDamageTime.remove(player);
            recentDamagers.remove(player);

            // Audit row per T-063 and ARCHITECTURE.md §4
            auditRepository.saveAsync(new AuditEvent(
                    0L,
                    player,
                    "DUEL_COMBAT_LOG",
                    player.toString(),
                    "ACTIVE",
                    "FORFEIT_COMBAT_LOG",
                    quitTime
            ));

            // Social consequence: configurable action (broadcast or notify)
            String action = snapshot.config().duel() != null
                    ? snapshot.config().duel().disconnect().action()
                    : "broadcast";

            PlayerId damagerId = recentDamagers.get(player);
            String opponentName = damagerId != null ? resolveName(damagerId) : "opponent";

            if ("notify".equalsIgnoreCase(action)) {
                for (PlayerId p : session.remainingPlayers()) {
                    messageSender.accept(p, messageRegistry.renderWithPrefix(snapshot, "duel.combat-log-notify",
                            Map.of("player", playerName)));
                }
            } else {
                // Default: public server broadcast exposing cowardice
                broadcastConsumer.accept(messageRegistry.renderWithPrefix(snapshot, "duel.combat-log-broadcast",
                        Map.of("player", playerName, "opponent", opponentName)));
            }

            if (concluded) {
                endDuel(session, DuelState.ENDED, snapshot);
            }

            return Optional.of(classification);
        } else {
            // Normal disconnect: check reconnect grace period
            Duration grace = snapshot.config().duel() != null
                    ? snapshot.config().duel().disconnect().reconnectGracePeriod()
                    : Duration.ofSeconds(30);

            if (grace.isZero()) {
                // Immediate clean forfeit
                boolean concluded = session.eliminate(player);
                playerToActiveDuel.remove(player);
                recentDamageTime.remove(player);
                recentDamagers.remove(player);

                for (PlayerId p : session.remainingPlayers()) {
                    messageSender.accept(p, messageRegistry.renderWithPrefix(snapshot, "duel.left",
                            Map.of("player", playerName)));
                }

                if (concluded) {
                    endDuel(session, DuelState.ENDED, snapshot);
                }
                return Optional.of(classification);
            } else {
                // Mark disconnected and schedule grace period
                session.markDisconnected(player, quitTime);

                String formattedTime = grace.toSeconds() + "s";
                for (PlayerId p : session.remainingPlayers()) {
                    messageSender.accept(p, messageRegistry.renderWithPrefix(snapshot, "duel.disconnect-grace",
                            Map.of("player", playerName, "time", formattedTime)));
                }

                TimerScheduler.TaskHandle graceHandle = timerScheduler.schedule(grace, () -> {
                    handleDisconnectTimeout(player, session.id(), configManager.snapshot());
                });
                disconnectGraceHandles.put(player, graceHandle);

                return Optional.of(classification);
            }
        }
    }

    public void handlePlayerJoin(PlayerId player, RuntimeSnapshot snapshot) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        TimerScheduler.TaskHandle handle = disconnectGraceHandles.remove(player);
        if (handle != null) {
            handle.cancel();
        }

        ActiveDuelSession session = getActiveDuel(player);
        if (session != null && session.isDisconnected(player)) {
            session.markReconnected(player);
            String playerName = resolveName(player);
            for (PlayerId p : session.allParticipants()) {
                messageSender.accept(p, messageRegistry.renderWithPrefix(snapshot, "duel.reconnected",
                        Map.of("player", playerName)));
            }
        }
    }

    private void handleDisconnectTimeout(PlayerId player, String sessionId, RuntimeSnapshot snapshot) {
        disconnectGraceHandles.remove(player);
        ActiveDuelSession session = activeDuels.get(sessionId);
        if (session != null && session.isDisconnected(player)) {
            boolean concluded = session.eliminate(player);
            playerToActiveDuel.remove(player);
            recentDamageTime.remove(player);
            recentDamagers.remove(player);

            String playerName = resolveName(player);
            for (PlayerId p : session.remainingPlayers()) {
                messageSender.accept(p, messageRegistry.renderWithPrefix(snapshot, "duel.disconnect-timeout",
                        Map.of("player", playerName)));
            }

            if (concluded) {
                endDuel(session, DuelState.ENDED, snapshot);
            }
        }
    }

    private void endDuel(ActiveDuelSession session, DuelState state, RuntimeSnapshot snapshot) {
        activeDuels.remove(session.id());
        for (PlayerId p : session.allParticipants()) {
            playerToActiveDuel.remove(p);
            recentDamageTime.remove(p);
            recentDamagers.remove(p);
            TimerScheduler.TaskHandle handle = disconnectGraceHandles.remove(p);
            if (handle != null) {
                handle.cancel();
            }
        }
        duelRepository.updateStateAsync(session.id(), state, Instant.now());
    }

    public void shutdown() {
        // Cancel all pending challenge expiry timers
        for (TimerScheduler.TaskHandle handle : challengeExpiryHandles.values()) {
            handle.cancel();
        }
        challengeExpiryHandles.clear();

        // Cancel all disconnect grace handles
        for (TimerScheduler.TaskHandle handle : disconnectGraceHandles.values()) {
            handle.cancel();
        }
        disconnectGraceHandles.clear();

        // End and persist all in-flight active duels as cancelled
        Instant now = Instant.now();
        for (ActiveDuelSession session : activeDuels.values()) {
            duelRepository.updateState(session.id(), DuelState.CANCELLED, now);
        }

        pendingChallenges.clear();
        playerOutgoingChallenge.clear();
        playerIncomingChallenges.clear();
        activeDuels.clear();
        playerToActiveDuel.clear();
        recentDamageTime.clear();
        recentDamagers.clear();
    }

    // -------------------------------------------------------------------------
    // Query & Lookup Helpers
    // -------------------------------------------------------------------------

    public boolean isInActiveDuel(PlayerId player) {
        return playerToActiveDuel.containsKey(player);
    }

    public ActiveDuelSession getActiveDuel(PlayerId player) {
        String id = playerToActiveDuel.get(player);
        return id != null ? activeDuels.get(id) : null;
    }

    public DuelChallenge getPendingChallenge(String id) {
        return pendingChallenges.get(id);
    }

    public int activeDuelCount() {
        return activeDuels.size();
    }

    public int pendingChallengeCount() {
        return pendingChallenges.size();
    }

    private String resolveName(PlayerId id) {
        if (playerLookup != null) {
            Optional<PlayerLookup.KnownPlayer> kp = playerLookup.lookup(id.toString());
            if (kp.isPresent()) {
                return kp.get().name();
            }
        }
        return id.toString();
    }

    private String formatParticipantNames(Collection<PlayerId> players) {
        if (players.isEmpty()) {
            return "none";
        }
        List<String> names = new ArrayList<>();
        for (PlayerId p : players) {
            names.add(resolveName(p));
        }
        return String.join(", ", names);
    }
}
