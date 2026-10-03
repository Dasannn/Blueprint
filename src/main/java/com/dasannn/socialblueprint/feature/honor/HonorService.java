package com.dasannn.socialblueprint.feature.honor;

import com.dasannn.socialblueprint.command.PermissionChecker;
import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.AuditEvent;
import com.dasannn.socialblueprint.domain.CompensationRecord;
import com.dasannn.socialblueprint.domain.HonorAllowanceTracker;
import com.dasannn.socialblueprint.domain.HonorCostCalculator;
import com.dasannn.socialblueprint.domain.HonorKind;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.ReputationEvent;
import com.dasannn.socialblueprint.domain.Status;
import com.dasannn.socialblueprint.feature.profile.ProfileService;
import com.dasannn.socialblueprint.feature.profile.ProfileService.TargetIdentity;
import com.dasannn.socialblueprint.storage.AuditRepository;
import com.dasannn.socialblueprint.storage.CompensationRepository;
import com.dasannn.socialblueprint.storage.ReputationRepository;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.command.CommandSender;
import com.dasannn.socialblueprint.domain.CompensationState;
import org.bukkit.entity.Player;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service managing the honor economy per SB-050 to SB-058 and Decision 0001:
 * - Cost is a base amount plus a percentage of the quoted actor balance.
 * - Cooldown per actor-target pair.
 * - Allowance cap of 3 positive and 3 negative ratings per pair inside the window.
 * - Pre-confirmation before money moves or events are written.
 * - Charge and event write commit together: failed charge writes nothing, failed write refunds.
 * - Admin give, take, and reset are free, ignore caps, and are audited.
 * - Reset writes a compensating event, never a delete.
 */
public class HonorService {

    private final ConfigManager configManager;
    private final MessageRegistry messageRegistry;
    private final ReputationRepository reputationRepository;
    private final AuditRepository auditRepository;
    private final CompensationRepository compensationRepository;
    private final ProfileService profileService;
    private final Consumer<Runnable> mainThreadRunner;
    private final Clock clock;
    private final Function<UUID, org.bukkit.OfflinePlayer> offlinePlayerResolver;
    private final Logger logger;
    private volatile Economy economy;
    private Function<PlayerId, String> worldLookup = id -> null;
    public void bindWorldLookup(Function<PlayerId, String> lookup) { worldLookup = Objects.requireNonNull(lookup); }

    private final ConcurrentMap<UUID, PendingConfirmation> pendingConfirmations = new ConcurrentHashMap<>();
    private final Set<UUID> busyActors = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<UUID, UUID> previewTickets = new ConcurrentHashMap<>();
    private final Set<Long> unstartedRefundClaims = ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<Void>> inFlightReconciliations = ConcurrentHashMap.newKeySet();

    public HonorService(
            ConfigManager configManager,
            MessageRegistry messageRegistry,
            ReputationRepository reputationRepository,
            AuditRepository auditRepository,
            CompensationRepository compensationRepository,
            ProfileService profileService,
            Economy economy,
            Consumer<Runnable> mainThreadRunner,
            Clock clock,
            Function<UUID, org.bukkit.OfflinePlayer> offlinePlayerResolver
    ) {
        this.configManager = Objects.requireNonNull(configManager, "configManager must not be null");
        this.messageRegistry = Objects.requireNonNull(messageRegistry, "messageRegistry must not be null");
        this.reputationRepository = Objects.requireNonNull(reputationRepository, "reputationRepository must not be null");
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository must not be null");
        this.compensationRepository = compensationRepository;
        this.profileService = Objects.requireNonNull(profileService, "profileService must not be null");
        this.economy = economy;
        this.mainThreadRunner = mainThreadRunner != null ? mainThreadRunner : Runnable::run;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.offlinePlayerResolver = offlinePlayerResolver != null ? offlinePlayerResolver : uuid -> {
            if (org.bukkit.Bukkit.getServer() != null) {
                return org.bukkit.Bukkit.getOfflinePlayer(uuid);
            }
            return null;
        };
        this.logger = Logger.getLogger(HonorService.class.getName());
    }

    public HonorService(
            ConfigManager configManager,
            MessageRegistry messageRegistry,
            ReputationRepository reputationRepository,
            AuditRepository auditRepository,
            CompensationRepository compensationRepository,
            ProfileService profileService,
            Economy economy,
            Consumer<Runnable> mainThreadRunner,
            Clock clock
    ) {
        this(configManager, messageRegistry, reputationRepository, auditRepository, compensationRepository, profileService, economy, mainThreadRunner, clock, null);
    }

    public HonorService(
            ConfigManager configManager,
            MessageRegistry messageRegistry,
            ReputationRepository reputationRepository,
            AuditRepository auditRepository,
            ProfileService profileService,
            Economy economy,
            Consumer<Runnable> mainThreadRunner,
            Clock clock
    ) {
        this(configManager, messageRegistry, reputationRepository, auditRepository, null, profileService, economy, mainThreadRunner, clock);
    }

    public HonorService(
            ConfigManager configManager,
            MessageRegistry messageRegistry,
            ReputationRepository reputationRepository,
            AuditRepository auditRepository,
            CompensationRepository compensationRepository,
            ProfileService profileService,
            Economy economy,
            Consumer<Runnable> mainThreadRunner
    ) {
        this(configManager, messageRegistry, reputationRepository, auditRepository, compensationRepository, profileService, economy, mainThreadRunner, Clock.systemUTC());
    }

    public HonorService(
            ConfigManager configManager,
            MessageRegistry messageRegistry,
            ReputationRepository reputationRepository,
            AuditRepository auditRepository,
            ProfileService profileService,
            Economy economy,
            Consumer<Runnable> mainThreadRunner
    ) {
        this(configManager, messageRegistry, reputationRepository, auditRepository, null, profileService, economy, mainThreadRunner, Clock.systemUTC());
    }

    public void setEconomy(Economy economy) {
        this.economy = economy;
    }

    public Economy getEconomy() {
        return economy;
    }

    public Optional<PendingConfirmation> getPendingConfirmation(UUID playerId) {
        return Optional.ofNullable(pendingConfirmations.get(playerId));
    }

    public void clearPendingConfirmation(UUID playerId) {
        pendingConfirmations.remove(playerId);
        previewTickets.remove(playerId);
    }

    /** Discard only this preview; an older chest must not cancel a newer request. */
    public void clearPendingConfirmation(UUID playerId, PendingConfirmation expected) {
        pendingConfirmations.computeIfPresent(playerId, (id, current) -> current == expected ? null : current);
    }

    /**
     * Prepares player honor issuance (trust or distrust):
     * validates constraints, checks cooldown, checks allowance cap, quotes balance-based cost,
     * verifies balance, registers pending confirmation, and sends cost preview per SB-052.
     */
    private CompletableFuture<Void> actorOperation(UUID actorId, java.util.function.Supplier<CompletableFuture<Void>> operation) {
        // Drop overlapping command/GUI requests; hold ownership through the async commit.
        if (!busyActors.add(actorId)) return CompletableFuture.completedFuture(null);
        try {
            return operation.get().whenComplete((v, ex) -> busyActors.remove(actorId));
        } catch (RuntimeException ex) {
            busyActors.remove(actorId);
            throw ex;
        }
    }

    public CompletableFuture<Void> preparePlayerHonor(Player actor, String targetInput, HonorKind kind,
                                                       String reason, RuntimeSnapshot snapshot) {
        return preparePlayerHonor(actor, targetInput, kind, reason, snapshot, null);
    }

    /** The optional presenter runs on the main thread, after the preview is registered. */
    public CompletableFuture<Void> preparePlayerHonor(Player actor, String targetInput, HonorKind kind,
                                                       String reason, RuntimeSnapshot snapshot,
                                                       Consumer<PendingConfirmation> presenter) {
        UUID actorUuid = actor.getUniqueId();
        return actorOperation(actorUuid, () -> preparePlayerHonorInternal(actor, targetInput, kind, reason, snapshot, presenter)
                .whenComplete((v, ex) -> previewTickets.remove(actorUuid)));
    }

    private CompletableFuture<Void> preparePlayerHonorInternal(
            Player actor,
            String targetInput,
            HonorKind kind,
            String reason,
            RuntimeSnapshot snapshot,
            Consumer<PendingConfirmation> presenter
    ) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(targetInput, "targetInput must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        PlayerId actorId = PlayerId.of(actor.getUniqueId());
        UUID ticket = UUID.randomUUID();
        previewTickets.put(actorId.uuid(), ticket);
        pendingConfirmations.remove(actorId.uuid());

        // Quick self-rating check by input
        if (targetInput.equalsIgnoreCase(actor.getName()) || targetInput.equalsIgnoreCase(actor.getUniqueId().toString())) {
            actor.sendMessage(messageRegistry.renderWithPrefix(snapshot, "honor.cannot-rate-self"));
            return CompletableFuture.completedFuture(null);
        }

        if (disabledWorld(actor, snapshot)) return CompletableFuture.completedFuture(null);

        // Every player honor rating requires a visible written reason (SB-150)
        if (reason == null || reason.isBlank()) {
            actor.sendMessage(messageRegistry.renderWithPrefix(snapshot, "honor.reason-required"));
            return CompletableFuture.completedFuture(null);
        }

        if (com.dasannn.socialblueprint.domain.ReasonRules.visibleLength(reason) < snapshot.config().honor().reasonMinLength()) {
            actor.sendMessage(messageRegistry.renderWithPrefix(snapshot, "honor.reason-too-short",
                    Map.of("min", String.valueOf(snapshot.config().honor().reasonMinLength()))));
            return CompletableFuture.completedFuture(null);
        }

        // Bounded reason length (Finding 5)
        if (reason != null && reason.length() > com.dasannn.socialblueprint.domain.ReputationEvent.MAX_REASON_LENGTH) {
            actor.sendMessage(messageRegistry.renderWithPrefix(snapshot, "honor.reason-too-long",
                    Map.of("max", String.valueOf(com.dasannn.socialblueprint.domain.ReputationEvent.MAX_REASON_LENGTH))));
            return CompletableFuture.completedFuture(null);
        }

        // Permission check
        String permKey = kind == HonorKind.POSITIVE ? "give-reputation" : "take-reputation";
        if (!PermissionChecker.hasPermission(actor, permKey, snapshot)) {
            actor.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.no-permission"));
            return CompletableFuture.completedFuture(null);
        }

        // Resolve target player asynchronously
        return profileService.resolveTargetIdentityAsync(targetInput)
                .thenCompose(optTarget -> {
                    if (optTarget.isEmpty()) {
                        mainThreadRunner.accept(() -> actor.sendMessage(messageRegistry.renderWithPrefix(snapshot, "status.not-found",
                                Map.of("player", targetInput))));
                        return CompletableFuture.completedFuture(null);
                    }

                    TargetIdentity target = optTarget.get();
                    if (actorId.equals(target.id())) {
                        mainThreadRunner.accept(() -> actor.sendMessage(messageRegistry.renderWithPrefix(snapshot, "honor.cannot-rate-self")));
                        return CompletableFuture.completedFuture(null);
                    }

                    // Query actor's ratings in rolling window on storage executor
                    Duration capWindow = snapshot.config().honor().capWindow();
                    Duration cooldown = snapshot.config().honor().cooldownPerPair();
                    Duration maxDuration = capWindow;
                    if (cooldown.compareTo(maxDuration) > 0) {
                        maxDuration = cooldown;
                    }
                    Instant now = clock.instant();
                    Instant since = now.minus(maxDuration);

                    return reputationRepository.findByActorSinceAsync(actorId, since)
                            .thenCompose(actorEvents -> {
                                CompletableFuture<Void> preview = new CompletableFuture<>();
                                mainThreadRunner.accept(() -> {
                                    try {
                                        if (previewTickets.remove(actorId.uuid(), ticket)) {
                                            PendingConfirmation pending = evaluate(actor, target, kind, reason, actorEvents, snapshot, clock.instant());
                                            if (pending != null) showPreview(actor, pending, snapshot, presenter);
                                        }
                                        preview.complete(null);
                                    } catch (RuntimeException ex) {
                                        preview.completeExceptionally(ex);
                                    }
                                });
                                return preview;
                            });
                });
    }

    private PendingConfirmation evaluate(
            Player actor,
            TargetIdentity target,
            HonorKind kind,
            String reason,
            List<ReputationEvent> actorEvents,
            RuntimeSnapshot snapshot,
            Instant now
    ) {
        if (disabledWorld(actor, snapshot)) return null;
        HonorAllowanceTracker tracker = new HonorAllowanceTracker(snapshot.config().honor().toAllowanceConfig());
        var wait = tracker.waitFor(PlayerId.of(actor.getUniqueId()), target.id(), kind, actorEvents, now,
                snapshot.config().honor().cooldownPerPair());
        if (!wait.remaining().isZero()) {
            actor.sendMessage(messageRegistry.renderWithPrefix(snapshot,
                    wait.cooldown().isZero() ? "honor.cap-reached" : "honor.cooldown",
                    Map.of("time", ratingWaitText(wait.remaining(), snapshot, messageRegistry))));
            return null;
        }

        // 3. Quote balance-based cost on the main thread (SB-050, Decision 0008)
        HonorCostCalculator costCalc = new HonorCostCalculator(snapshot.config().honor().toCostConfig());
        double cost = costCalc.calculateCost(economy != null ? economy.getBalance(actor) : 0);

        // 4. Balance check
        if (economy != null && !economy.has(actor, cost)) {
            actor.sendMessage(messageRegistry.renderWithPrefix(snapshot, "honor.insufficient-funds",
                    Map.of("cost", formatCost(cost))));
            return null;
        }

        // 5. Store pending confirmation (60-second validity)
        int delta = kind == HonorKind.POSITIVE ? 1 : -1;
        PendingConfirmation pending = new PendingConfirmation(
                PlayerId.of(actor.getUniqueId()),
                target.id(),
                target.name(),
                kind,
                delta,
                cost,
                reason,
                now.plusSeconds(60)
        );
        return pending;
    }

    private void showPreview(Player actor, PendingConfirmation pending, RuntimeSnapshot snapshot,
                             Consumer<PendingConfirmation> presenter) {
        pendingConfirmations.put(pending.actorId().uuid(), pending);
        if (presenter != null) {
            try {
                presenter.accept(pending);
            } catch (RuntimeException ex) {
                clearPendingConfirmation(pending.actorId().uuid(), pending);
                throw ex;
            }
            return;
        }

        // 6. Show cost preview with confirm click action (SB-052)
        Component previewComp = messageRegistry.renderWithPrefix(snapshot, "honor.cost-preview",
                Map.of("cost", formatCost(pending.cost())));
        previewComp = previewComp.clickEvent(ClickEvent.runCommand("/status confirm"));
        actor.sendMessage(previewComp);
    }

    /**
     * Confirms and commits a pending player honor action per SB-057:
     * charges the actor via Vault, verifies EconomyResponse, persists ReputationEvent asynchronously,
     * and refunds on write failure. Compensation is persisted to survive crashes and restarts.
     */
    public CompletableFuture<Void> confirmPlayerHonor(Player actor, RuntimeSnapshot snapshot) {
        return actorOperation(actor.getUniqueId(), () -> confirmPlayerHonorInternal(actor, snapshot, null));
    }

    /** GUI confirmation binds the click to the exact preview displayed, under the same actor lock. */
    public CompletableFuture<Void> confirmPlayerHonor(Player actor, RuntimeSnapshot snapshot,
                                                       PendingConfirmation expected,
                                                       Consumer<PendingConfirmation> presenter) {
        return actorOperation(actor.getUniqueId(), () -> {
            if (pendingConfirmations.get(actor.getUniqueId()) != expected) {
                return CompletableFuture.completedFuture(null);
            }
            return confirmPlayerHonorInternal(actor, snapshot, presenter);
        });
    }

    private CompletableFuture<Void> confirmPlayerHonorInternal(Player actor, RuntimeSnapshot snapshot,
                                                               Consumer<PendingConfirmation> presenter) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        PendingConfirmation pending = pendingConfirmations.remove(actor.getUniqueId());
        Instant now = clock.instant();
        if (disabledWorld(actor, snapshot)) return CompletableFuture.completedFuture(null);

        if (pending == null || pending.isExpired(now)) {
            actor.sendMessage(messageRegistry.renderWithPrefix(snapshot, "honor.no-pending"));
            return CompletableFuture.completedFuture(null);
        }

        // Re-check permission
        String permKey = pending.kind() == HonorKind.POSITIVE ? "give-reputation" : "take-reputation";
        if (!PermissionChecker.hasPermission(actor, permKey, snapshot)) {
            actor.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.no-permission"));
            return CompletableFuture.completedFuture(null);
        }

        if (!com.dasannn.socialblueprint.domain.ReasonRules.accepts(pending.reason(), snapshot.config().honor().reasonMinLength())) {
            actor.sendMessage(messageRegistry.renderWithPrefix(snapshot, "honor.reason-too-short",
                    Map.of("min", String.valueOf(snapshot.config().honor().reasonMinLength()))));
            return CompletableFuture.completedFuture(null);
        }
        Duration window = snapshot.config().honor().capWindow();
        if (snapshot.config().honor().cooldownPerPair().compareTo(window) > 0) window = snapshot.config().honor().cooldownPerPair();
        CompletableFuture<List<ReputationEvent>> history;
        try {
            history = reputationRepository.findByActorSinceAsync(pending.actorId(), now.minus(window));
        } catch (RuntimeException error) {
            // A closed engine throws synchronously; route it to the same handler.
            history = CompletableFuture.failedFuture(error);
        }
        return history.handle((events, error) -> {
            if (error == null) return events;
            // Nothing has been charged yet: report and stop, without a refund path.
            logger.log(Level.WARNING, "Failed to recheck honor history for " + pending.actorId(), error);
            mainThreadRunner.accept(() -> actor.sendMessage(messageRegistry.renderWithPrefix(snapshot, "honor.check-failed")));
            return null;
        }).thenCompose(events -> {
            if (events == null) return CompletableFuture.completedFuture(null);
            CompletableFuture<Void> result = new CompletableFuture<>();
            mainThreadRunner.accept(() -> {
                try {
                    Instant checkedAt = clock.instant();
                    if (pending.isExpired(checkedAt)) {
                        actor.sendMessage(messageRegistry.renderWithPrefix(snapshot, "honor.no-pending"));
                        result.complete(null);
                        return;
                    }
                    var allowance = new HonorAllowanceTracker(snapshot.config().honor().toAllowanceConfig())
                            .waitFor(pending.actorId(), pending.targetId(), pending.kind(), events, checkedAt,
                                    snapshot.config().honor().cooldownPerPair());
                    if (!allowance.remaining().isZero()) {
                        actor.sendMessage(messageRegistry.renderWithPrefix(snapshot,
                                allowance.cooldown().isZero() ? "honor.cap-reached" : "honor.cooldown",
                                Map.of("time", ratingWaitText(allowance.remaining(), snapshot, messageRegistry))));
                        result.complete(null);
                    } else {
                        chargePlayerHonor(actor, pending, snapshot, checkedAt).whenComplete((v, ex) -> {
                            if (ex == null) result.complete(null);
                            else result.completeExceptionally(ex);
                        });
                    }
                } catch (RuntimeException ex) {
                    result.completeExceptionally(ex);
                }
            });
            return result;
        });
    }

    private boolean disabledWorld(Player actor, RuntimeSnapshot snapshot) {
        if (actor.getWorld() == null || !snapshot.config().worldRules().isDisabled(actor.getWorld().getName())) return false;
        clearPendingConfirmation(actor.getUniqueId());
        actor.sendMessage(messageRegistry.renderWithPrefix(snapshot, "worlds.disabled"));
        return true;
    }

    private CompletableFuture<Void> chargePlayerHonor(Player actor, PendingConfirmation pending,
                                                      RuntimeSnapshot snapshot, Instant now) {
        if (disabledWorld(actor, snapshot)) return CompletableFuture.completedFuture(null);
        UUID actorUuid = pending.actorId().uuid();
        double exactCost = HonorCostCalculator.roundCurrency(pending.cost());

        // Re-check economy provider and balance
        if (economy == null || !economy.has(actor, exactCost)) {
            actor.sendMessage(messageRegistry.renderWithPrefix(snapshot, "honor.insufficient-funds",
                    Map.of("cost", formatCost(exactCost))));
            return CompletableFuture.completedFuture(null);
        }

        // 1. Write INTENT row before withdrawal (SB-057 / Blocking 1)
        CompletableFuture<Long> intentFuture;
        if (compensationRepository != null) {
            try {
                intentFuture = compensationRepository.saveIntentAsync(actorUuid, exactCost, "honor_charge", now);
            } catch (Exception ex) {
                intentFuture = CompletableFuture.failedFuture(ex);
            }
        } else {
            intentFuture = CompletableFuture.completedFuture(null);
        }

        return intentFuture.thenCompose(compId -> {
            CompletableFuture<Void> resultFuture = new CompletableFuture<>();
            mainThreadRunner.accept(() -> {
                if (disabledWorld(actor, snapshot)) {
                    deleteCompensationWithLogging(compId, "disabled world before withdrawal");
                    resultFuture.complete(null);
                    return;
                }
                var mindInput = pending.kind() == HonorKind.NEGATIVE
                        ? com.dasannn.socialblueprint.domain.MindInput.HONOR_REVIEW_NEGATIVE
                        : com.dasannn.socialblueprint.domain.MindInput.HONOR_REVIEW;
                var targetMindConfig = snapshot.config().psychosis().input(mindInput);
                boolean targetDisabled = snapshot.config().worldRules().isDisabled(worldLookup.apply(pending.targetId()));
                var mindConfig = targetDisabled ? new com.dasannn.socialblueprint.domain.MindInputConfig(false,
                        targetMindConfig.sereneAmount(), targetMindConfig.psychosisAmount(), targetMindConfig.cap()) : targetMindConfig;
                EconomyResponse response;
                try {
                    response = (economy != null) ? economy.withdrawPlayer(actor, exactCost) : null;
                } catch (Exception ex) {
                    deleteCompensationWithLogging(compId, "exception during withdraw");
                    actor.sendMessage(messageRegistry.renderWithPrefix(snapshot, "honor.write-failed"));
                    resultFuture.complete(null);
                    return;
                }

                if (response == null || !response.transactionSuccess()) {
                    deleteCompensationWithLogging(compId, "withdraw unsuccessful");
                    actor.sendMessage(messageRegistry.renderWithPrefix(snapshot, "honor.insufficient-funds",
                            Map.of("cost", formatCost(exactCost))));
                    resultFuture.complete(null);
                    return;
                }

                double actualCharged = response.amount;

                // SB-052 / Finding 3 / Blocking 3: withdrawal amount must match previewed cost exactly!
                if (!Double.isFinite(actualCharged) || actualCharged <= 0 || Math.abs(actualCharged - exactCost) >= 0.0001) {
                    CompletableFuture<Void> refund;
                    if (Double.isFinite(actualCharged) && actualCharged > 0 && compId != null && compensationRepository != null) {
                        refund = compensationRepository.markChargedWithAmountAsync(compId, actualCharged)
                                .thenCompose(recorded -> recorded
                                        ? claimAndRefund(actor, actorUuid, compId, actualCharged)
                                        : CompletableFuture.completedFuture(null));
                    } else if (Double.isFinite(actualCharged) && actualCharged > 0) {
                        refund = claimAndRefund(actor, actorUuid, compId, actualCharged);
                    } else {
                        deleteCompensationWithLogging(compId, "invalid charge mismatch");
                        refund = CompletableFuture.completedFuture(null);
                    }
                    actor.sendMessage(messageRegistry.renderWithPrefix(snapshot, "honor.write-failed"));
                    refund.whenComplete((v, ex) -> {
                        if (ex != null) logger.log(Level.WARNING, "Failed honor mismatch refund " + compId, ex);
                        resultFuture.complete(null);
                    });
                    return;
                }

                // Withdrawal succeeded with exact previewed amount!
                // Mark row charged
                CompletableFuture<Boolean> markChargedFuture = (compId != null && compensationRepository != null)
                        ? compensationRepository.markChargedAsync(compId)
                        : CompletableFuture.completedFuture(true);

                markChargedFuture.thenCompose(v -> {
                    ReputationEvent event = new ReputationEvent(
                            pending.actorId(),
                            pending.targetId(),
                            pending.delta(),
                            pending.kind(),
                            actualCharged,
                            pending.reason(),
                            now
                    );
                    return reputationRepository.commitPlayerHonorAsync(event, compId, compensationRepository, mindConfig);
                }).handle((saved, error) -> {
                    mainThreadRunner.accept(() -> {
                        if (error == null) {
                            String msgKey = pending.kind() == HonorKind.POSITIVE ? "honor.given" : "honor.removed";
                            actor.sendMessage(messageRegistry.renderWithPrefix(snapshot, msgKey,
                                    Map.of("target", pending.targetName())));
                        } else {
                            actor.sendMessage(messageRegistry.renderWithPrefix(snapshot, "honor.write-failed"));
                            claimAndRefund(actor, actorUuid, compId, actualCharged).whenComplete((v, ex) -> {
                                if (ex != null) logger.log(Level.WARNING, "Failed honor refund " + compId, ex);
                                resultFuture.complete(null);
                            });
                            return;
                        }
                        resultFuture.complete(null);
                    });
                    return null;
                });
            });
            return resultFuture;
        }).exceptionally(ex -> {
            mainThreadRunner.accept(() -> {
                actor.sendMessage(messageRegistry.renderWithPrefix(snapshot, "honor.write-failed"));
            });
            return null;
        });
    }

    private CompletableFuture<Void> claimAndRefund(org.bukkit.OfflinePlayer player, UUID playerUuid, Long compId, double amount) {
        if (player == null || economy == null) return CompletableFuture.completedFuture(null);
        CompletableFuture<Boolean> claim = compId != null && compensationRepository != null
                ? compensationRepository.claimForRefundAsync(compId)
                : CompletableFuture.completedFuture(true);
        return claim.thenCompose(claimed -> {
            if (!claimed) return CompletableFuture.completedFuture(null);
            CompletableFuture<Void> result = new CompletableFuture<>();
            mainThreadRunner.accept(() -> {
                CompletableFuture<?> persisted = CompletableFuture.completedFuture(null);
                try {
                    EconomyResponse refundResp = economy.depositPlayer(player, amount);
                    if (compId != null && compensationRepository != null) {
                        persisted = refundResp != null && refundResp.transactionSuccess()
                                && Math.abs(refundResp.amount - amount) < 0.0001
                                ? compensationRepository.markRefundedAsync(compId)
                                        .thenCompose(v -> compensationRepository.deleteCompensationAsync(compId))
                                : compensationRepository.revertToChargedAsync(compId);
                    }
                } catch (Exception ex) {
                    logger.log(Level.SEVERE, "Exception during refund deposit for player " + playerUuid + ", amount=" + amount, ex);
                    if (compId != null && compensationRepository != null) persisted = compensationRepository.markUncertainAsync(compId);
                }
                persisted.whenComplete((v, ex) -> {
                    if (ex == null) result.complete(null);
                    else result.completeExceptionally(ex);
                });
            });
            return result;
        });
    }

    private void deleteCompensationWithLogging(Long compId, String context) {
        if (compId != null && compensationRepository != null) {
            compensationRepository.deleteCompensationAsync(compId)
                    .exceptionally(ex -> {
                        logger.log(Level.WARNING, "[SocialBlueprint] Failed to delete compensation " + compId + " during " + context, ex);
                        return null;
                    });
        }
    }

    /**
     * Reconciles outstanding pending refund compensations across restarts per SB-057.
     */
    public synchronized CompletableFuture<Void> reconcileCompensationsAsync() {
        if (compensationRepository == null || economy == null) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> reconciliation = compensationRepository.findAllAsync().thenCompose(records -> {
            if (records.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (CompensationRecord record : records) {
                switch (record.state()) {
                    case INTENDED -> {
                        // Intended but never charged (discard)
                        futures.add(compensationRepository.deleteCompensationAsync(record.id())
                                .exceptionally(ex -> {
                                    logger.log(Level.WARNING, "[SocialBlueprint] Failed to delete INTENDED compensation " + record.id(), ex);
                                    return null;
                                }));
                    }
                    case EVENT_WRITTEN -> {
                        // Event written (delete the row)
                        futures.add(compensationRepository.deleteCompensationAsync(record.id())
                                .exceptionally(ex -> {
                                    logger.log(Level.WARNING, "[SocialBlueprint] Failed to delete EVENT_WRITTEN compensation " + record.id(), ex);
                                    return null;
                                }));
                    }
                    case REFUNDED -> {
                        // Refund deposit completed: delete row
                        futures.add(compensationRepository.deleteCompensationAsync(record.id())
                                .exceptionally(ex -> {
                                    logger.log(Level.WARNING, "[SocialBlueprint] Failed to delete REFUNDED compensation " + record.id(), ex);
                                    return null;
                                }));
                    }
                    case REFUNDING -> {
                        // Another pass in this service already queued this deposit.
                        if (unstartedRefundClaims.contains(record.id())) {
                            continue;
                        }
                        // Server crashed mid-deposit! Vault's outcome is genuinely unknown.
                        // Leave in explicit uncertain state and log for owner.
                        logger.warning("[SocialBlueprint] Pending compensation ID " + record.id() + " for player "
                                + record.playerUuid() + " ($" + record.amount()
                                + ") was in REFUNDING state; marking UNCERTAIN for manual operator review.");
                        futures.add(compensationRepository.markUncertainAsync(record.id())
                                .exceptionally(ex -> {
                                    logger.log(Level.WARNING, "[SocialBlueprint] Failed to mark REFUNDING compensation " + record.id() + " as UNCERTAIN", ex);
                                    return false;
                                })
                                .thenApply(b -> null));
                    }
                    case UNCERTAIN -> {
                        logger.warning("[SocialBlueprint] Pending compensation ID " + record.id() + " for player "
                                + record.playerUuid() + " ($" + record.amount()
                                + ") requires manual operator review (UNCERTAIN state).");
                    }
                    case CHARGED -> {
                        // Charged but no event (refund)
                        // Claim row before acting on it so two passes cannot both process it
                        CompletableFuture<Void> compFuture = compensationRepository.claimForRefundAsync(record.id())
                                .exceptionally(ex -> {
                                    logger.log(Level.WARNING, "[SocialBlueprint] Failed to claim compensation " + record.id() + " for refund", ex);
                                    return false;
                                })
                                .thenAccept(claimed -> {
                                    if (!claimed) {
                                        return;
                                    }
                                    unstartedRefundClaims.add(record.id());
                                    try {
                                        mainThreadRunner.accept(() -> {
                                            if (!unstartedRefundClaims.remove(record.id())) {
                                                // Deposit aborted, already started, or reverted on shutdown
                                                return;
                                            }
                                            org.bukkit.OfflinePlayer op = offlinePlayerResolver.apply(record.playerUuid());
                                            if (op == null) {
                                                logger.warning("[SocialBlueprint] Cannot resolve offline player "
                                                        + record.playerUuid() + " for refund compensation ID " + record.id());
                                                compensationRepository.revertToChargedAsync(record.id())
                                                        .exceptionally(ex -> {
                                                            logger.log(Level.WARNING, "[SocialBlueprint] Failed to revert compensation " + record.id() + " to CHARGED", ex);
                                                            return false;
                                                        });
                                                return;
                                            }
                                            try {
                                                EconomyResponse resp = economy.depositPlayer(op, record.amount());
                                                if (resp != null && resp.transactionSuccess()
                                                        && Math.abs(resp.amount - record.amount()) < 0.0001) {
                                                    compensationRepository.markRefundedAsync(record.id())
                                                            .thenCompose(v -> compensationRepository.deleteCompensationAsync(record.id()))
                                                            .exceptionally(ex -> {
                                                                logger.log(Level.WARNING, "[SocialBlueprint] Failed to mark/delete refunded compensation " + record.id(), ex);
                                                                return null;
                                                            });
                                                } else {
                                                    logger.severe("[SocialBlueprint] Reconcile refund deposit failed for player "
                                                            + record.playerUuid() + ", compensation ID " + record.id() + ": "
                                                            + (resp != null ? resp.errorMessage : "null response"));
                                                    compensationRepository.revertToChargedAsync(record.id())
                                                            .exceptionally(ex -> {
                                                                logger.log(Level.WARNING, "[SocialBlueprint] Failed to revert compensation " + record.id() + " to CHARGED", ex);
                                                                return false;
                                                            });
                                                }
                                            } catch (Exception ex) {
                                                logger.severe("[SocialBlueprint] Exception during reconcile refund for compensation ID "
                                                        + record.id() + ": " + ex.getMessage());
                                                compensationRepository.markUncertainAsync(record.id())
                                                        .exceptionally(markEx -> {
                                                            logger.log(Level.WARNING, "[SocialBlueprint] Failed to mark compensation " + record.id() + " as UNCERTAIN", markEx);
                                                            return false;
                                                        });
                                            }
                                        });
                                    } catch (Exception schedEx) {
                                        if (unstartedRefundClaims.remove(record.id())) {
                                            compensationRepository.revertToChargedAsync(record.id())
                                                    .exceptionally(ex -> {
                                                        logger.log(Level.WARNING, "[SocialBlueprint] Failed to revert compensation " + record.id() + " to CHARGED", ex);
                                                        return false;
                                                    });
                                        }
                                        logger.log(Level.WARNING, "[SocialBlueprint] Failed to schedule refund deposit for compensation ID " + record.id(), schedEx);
                                    }
                                });
                        futures.add(compFuture);
                    }
                }
            }
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        });
        inFlightReconciliations.add(reconciliation);
        reconciliation.whenComplete((ignored, failure) -> inFlightReconciliations.remove(reconciliation));
        return reconciliation;
    }

    /**
     * Reverts any claimed refund compensations whose deposit has not yet started back to CHARGED state on shutdown,
     * allowing subsequent server startup reconciliation to safely retry them.
     */
    public synchronized void shutdown() {
        if (compensationRepository == null) {
            return;
        }
        // Drain storage claims before sweeping: an in-flight claim must not be
        // registered after the sweep. These futures never wait for a Vault task.
        try {
            CompletableFuture.allOf(inFlightReconciliations.toArray(new CompletableFuture[0])).join();
        } catch (java.util.concurrent.CompletionException ex) {
            logger.log(Level.WARNING, "[SocialBlueprint] Reconciliation failed during shutdown", ex);
        }
        for (Long compId : unstartedRefundClaims) {
            if (unstartedRefundClaims.remove(compId)) {
                try {
                    boolean reverted = compensationRepository.revertToCharged(compId);
                    if (reverted) {
                        logger.info("[SocialBlueprint] Reverted unstarted refund compensation ID " + compId + " back to CHARGED on shutdown");
                    }
                } catch (Exception ex) {
                    logger.log(Level.WARNING, "[SocialBlueprint] Failed to revert unstarted compensation " + compId + " on shutdown", ex);
                }
            }
        }
    }

    /** Administrative revocation never moves money or restores allowance (SB-152). */
    public CompletableFuture<Void> adminRevoke(CommandSender sender, String targetInput, long ratingId,
                                                RuntimeSnapshot snapshot) {
        if (!PermissionChecker.hasPermission(sender, "admin-revoke", snapshot)) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.no-permission"));
            return CompletableFuture.completedFuture(null);
        }
        PlayerId admin = sender instanceof Player p ? PlayerId.of(p.getUniqueId()) : PlayerId.CONSOLE;
        String adminName = sender.getName(); // Bukkit access is captured before the storage hop.
        CompletableFuture<Optional<TargetIdentity>> identity = ratingId == -1
                ? profileService.resolveTargetIdentityAsync(targetInput)
                : CompletableFuture.completedFuture(Optional.empty());
        return identity.thenCompose(target -> {
            if (ratingId == -1 && target.isEmpty()) {
                mainThreadRunner.accept(() -> sender.sendMessage(messageRegistry.renderWithPrefix(snapshot,
                        "status.not-found", Map.of("player", targetInput))));
                return CompletableFuture.completedFuture(null);
            }
            return reputationRepository.revokeRatingAsync(admin, adminName,
                    target.map(TargetIdentity::id).orElse(null), ratingId, clock.instant(), auditRepository)
                    .thenCompose(result -> {
                        var response = new CompletableFuture<Void>();
                        // Identity lookup can touch Bukkit: initiate both lookups on the main thread.
                        mainThreadRunner.accept(() -> {
                            if (!result.revoked()) {
                                sender.sendMessage(messageRegistry.renderWithPrefix(snapshot,
                                        ratingId == -1 ? "honor.revoke-rejected" : result.rating() == null
                                                ? "honor.revoke-not-found" : "honor.revoke-not-revocable",
                                        Map.of("id", String.valueOf(ratingId))));
                                response.complete(null);
                                return;
                            }
                            ReputationEvent rating = result.rating();
                            var rater = rating.actor() == null
                                    ? CompletableFuture.completedFuture(Optional.<TargetIdentity>empty())
                                    : profileService.resolveTargetIdentityAsync(rating.actor().toString());
                            var recipient = profileService.resolveTargetIdentityAsync(rating.target().toString());
                            rater.thenCombine(recipient, (from, to) -> Map.of(
                                    "id", String.valueOf(rating.id()),
                                    "rater", from.map(TargetIdentity::name).orElseGet(() -> rating.actor() == null
                                            ? messageRegistry.getRaw(snapshot, "status.system-actor") : rating.actor().toString()),
                                    "player", to.map(TargetIdentity::name).orElse(rating.target().toString())))
                                    .whenComplete((placeholders, error) -> mainThreadRunner.accept(() -> {
                                        if (error == null) {
                                            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot,
                                                    "honor.revoke-success", placeholders));
                                            response.complete(null);
                                        } else response.completeExceptionally(error);
                                    }));
                        });
                        return response;
                    });
        }).exceptionally(error -> {
            mainThreadRunner.accept(() -> sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "honor.write-failed")));
            return null;
        });
    }

    public CompletableFuture<Void> adminGive(
            CommandSender sender,
            String targetInput,
            int amount,
            RuntimeSnapshot snapshot
    ) {
        Objects.requireNonNull(sender, "sender must not be null");
        Objects.requireNonNull(targetInput, "targetInput must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        if (!PermissionChecker.hasPermission(sender, "admin-adjust", snapshot)) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.no-permission"));
            return CompletableFuture.completedFuture(null);
        }

        PlayerId actorId = sender instanceof Player p ? PlayerId.of(p.getUniqueId()) : PlayerId.CONSOLE;

        return profileService.resolveTargetIdentityAsync(targetInput)
                .thenCompose(optTarget -> {
                    if (optTarget.isEmpty()) {
                        mainThreadRunner.accept(() -> sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "status.not-found",
                                Map.of("player", targetInput))));
                        return CompletableFuture.completedFuture(null);
                    }

                    TargetIdentity target = optTarget.get();
                    Instant now = clock.instant();

                    return reputationRepository.executeAdminAdjustmentAsync(
                            actorId,
                            target.id(),
                            HonorKind.ADMIN_GIVE,
                            amount,
                            "admin_give",
                            "admin give",
                            now,
                            auditRepository,
                            snapshot.config().decay().toDomain()
                    ).handle((res, ex) -> {
                        mainThreadRunner.accept(() -> {
                            if (ex == null) {
                                sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.admin.give-success",
                                        Map.of("amount", String.valueOf(amount), "target", target.name(), "status", String.valueOf(res.afterScore()))));
                            } else {
                                sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "honor.write-failed"));
                            }
                        });
                        return (Void) null;
                    });
                });
    }

    /**
     * Administrative take: free, no cooldown, no cap, audited (SB-058, T-055, T-056).
     */
    public CompletableFuture<Void> adminTake(
            CommandSender sender,
            String targetInput,
            int amount,
            RuntimeSnapshot snapshot
    ) {
        Objects.requireNonNull(sender, "sender must not be null");
        Objects.requireNonNull(targetInput, "targetInput must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        if (!PermissionChecker.hasPermission(sender, "admin-adjust", snapshot)) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.no-permission"));
            return CompletableFuture.completedFuture(null);
        }

        PlayerId actorId = sender instanceof Player p ? PlayerId.of(p.getUniqueId()) : PlayerId.CONSOLE;

        return profileService.resolveTargetIdentityAsync(targetInput)
                .thenCompose(optTarget -> {
                    if (optTarget.isEmpty()) {
                        mainThreadRunner.accept(() -> sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "status.not-found",
                                Map.of("player", targetInput))));
                        return CompletableFuture.completedFuture(null);
                    }

                    TargetIdentity target = optTarget.get();
                    Instant now = clock.instant();

                    return reputationRepository.executeAdminAdjustmentAsync(
                            actorId,
                            target.id(),
                            HonorKind.ADMIN_TAKE,
                            -amount,
                            "admin_take",
                            "admin take",
                            now,
                            auditRepository,
                            snapshot.config().decay().toDomain()
                    ).handle((res, ex) -> {
                        mainThreadRunner.accept(() -> {
                            if (ex == null) {
                                sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.admin.take-success",
                                        Map.of("amount", String.valueOf(amount), "target", target.name(), "status", String.valueOf(res.afterScore()))));
                            } else {
                                sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "honor.write-failed"));
                            }
                        });
                        return (Void) null;
                    });
                });
    }

    /**
     * Administrative reset: writes a compensating event, leaving underlying events intact (SB-058, Constitution §2.5).
     */
    public CompletableFuture<Void> adminReset(
            CommandSender sender,
            String targetInput,
            RuntimeSnapshot snapshot
    ) {
        Objects.requireNonNull(sender, "sender must not be null");
        Objects.requireNonNull(targetInput, "targetInput must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        if (!PermissionChecker.hasPermission(sender, "admin-adjust", snapshot)) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.no-permission"));
            return CompletableFuture.completedFuture(null);
        }

        PlayerId actorId = sender instanceof Player p ? PlayerId.of(p.getUniqueId()) : PlayerId.CONSOLE;

        return profileService.resolveTargetIdentityAsync(targetInput)
                .thenCompose(optTarget -> {
                    if (optTarget.isEmpty()) {
                        mainThreadRunner.accept(() -> sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "status.not-found",
                                Map.of("player", targetInput))));
                        return CompletableFuture.completedFuture(null);
                    }

                    TargetIdentity target = optTarget.get();
                    Instant now = clock.instant();

                    return reputationRepository.executeAdminAdjustmentAsync(
                            actorId,
                            target.id(),
                            HonorKind.ADMIN_RESET,
                            0,
                            "admin_reset",
                            "admin reset",
                            now,
                            auditRepository,
                            snapshot.config().decay().toDomain()
                    ).handle((res, ex) -> {
                        mainThreadRunner.accept(() -> {
                            if (ex == null) {
                                sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.admin.reset-success",
                                        Map.of("target", target.name())));
                            } else {
                                sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "honor.write-failed"));
                            }
                        });
                        return (Void) null;
                    });
                });
    }

    public static String ratingWaitText(Duration duration, RuntimeSnapshot snapshot, MessageRegistry messages) {
        long seconds = duration.getSeconds() + (duration.getNano() == 0 ? 0 : 1);
        String key = seconds >= 3600 ? "rating-wait.hours" : seconds >= 60 ? "rating-wait.minutes" : "rating-wait.seconds";
        return messages.getRaw(snapshot, key).replace("{hours}", Long.toString(seconds / 3600))
                .replace("{minutes}", Long.toString(seconds / 60 % 60))
                .replace("{seconds}", Long.toString(seconds % 60));
    }

    public static String formatCost(double cost) {
        cost = HonorCostCalculator.roundCurrency(cost);
        return String.format(Locale.ROOT, "%.2f", cost);
    }
}
