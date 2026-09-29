package com.dasannn.socialblueprint.feature.honor;

import com.dasannn.socialblueprint.command.PermissionChecker;
import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.AuditEvent;
import com.dasannn.socialblueprint.domain.HonorAllowanceTracker;
import com.dasannn.socialblueprint.domain.HonorCostCalculator;
import com.dasannn.socialblueprint.domain.HonorKind;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.ReputationEvent;
import com.dasannn.socialblueprint.domain.Status;
import com.dasannn.socialblueprint.feature.profile.ProfileService;
import com.dasannn.socialblueprint.feature.profile.ProfileService.TargetIdentity;
import com.dasannn.socialblueprint.storage.AuditRepository;
import com.dasannn.socialblueprint.storage.ReputationRepository;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * Service managing the honor economy per SB-050 to SB-058 and Decision 0001:
 * - Cost is a fixed YAML amount times progressive multiplier.
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
    private final ProfileService profileService;
    private final Consumer<Runnable> mainThreadRunner;
    private final Clock clock;
    private volatile Economy economy;

    private final ConcurrentMap<UUID, PendingConfirmation> pendingConfirmations = new ConcurrentHashMap<>();

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
        this.configManager = Objects.requireNonNull(configManager, "configManager must not be null");
        this.messageRegistry = Objects.requireNonNull(messageRegistry, "messageRegistry must not be null");
        this.reputationRepository = Objects.requireNonNull(reputationRepository, "reputationRepository must not be null");
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository must not be null");
        this.profileService = Objects.requireNonNull(profileService, "profileService must not be null");
        this.economy = economy;
        this.mainThreadRunner = mainThreadRunner != null ? mainThreadRunner : Runnable::run;
        this.clock = clock != null ? clock : Clock.systemUTC();
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
        this(configManager, messageRegistry, reputationRepository, auditRepository, profileService, economy, mainThreadRunner, Clock.systemUTC());
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
    }

    /**
     * Prepares player honor issuance (trust or distrust):
     * validates constraints, checks cooldown, checks allowance cap, computes progressive cost,
     * verifies balance, registers pending confirmation, and sends cost preview per SB-052.
     */
    public CompletableFuture<Void> preparePlayerHonor(
            Player actor,
            String targetInput,
            HonorKind kind,
            String reason,
            RuntimeSnapshot snapshot
    ) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(targetInput, "targetInput must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        PlayerId actorId = PlayerId.of(actor.getUniqueId());

        // Quick self-rating check by input
        if (targetInput.equalsIgnoreCase(actor.getName()) || targetInput.equalsIgnoreCase(actor.getUniqueId().toString())) {
            actor.sendMessage(messageRegistry.renderWithPrefix(snapshot, "honor.cannot-rate-self"));
            return CompletableFuture.completedFuture(null);
        }

        // Negative honor requires a written reason (SB-056)
        if (kind == HonorKind.NEGATIVE && (reason == null || reason.isBlank())) {
            actor.sendMessage(messageRegistry.renderWithPrefix(snapshot, "honor.reason-required"));
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
                    CompletableFuture<Void> future = new CompletableFuture<>();
                    mainThreadRunner.accept(() -> {
                        if (optTarget.isEmpty()) {
                            actor.sendMessage(messageRegistry.renderWithPrefix(snapshot, "status.not-found",
                                    Map.of("player", targetInput)));
                            future.complete(null);
                            return;
                        }

                        TargetIdentity target = optTarget.get();
                        if (actorId.equals(target.id())) {
                            actor.sendMessage(messageRegistry.renderWithPrefix(snapshot, "honor.cannot-rate-self"));
                            future.complete(null);
                            return;
                        }

                        // Query actor's ratings in rolling window on storage executor
                        Duration window = snapshot.config().honor().window();
                        Duration cooldown = snapshot.config().honor().cooldownPerPair();
                        Duration maxDuration = window.compareTo(cooldown) >= 0 ? window : cooldown;
                        Instant now = clock.instant();
                        Instant since = now.minus(maxDuration);

                        reputationRepository.findByActorSinceAsync(actorId, since)
                                .thenAccept(actorEvents -> mainThreadRunner.accept(() -> {
                                    try {
                                        evaluateAndPreview(actor, target, kind, reason, actorEvents, snapshot, now);
                                    } finally {
                                        future.complete(null);
                                    }
                                }))
                                .exceptionally(ex -> {
                                    future.completeExceptionally(ex);
                                    return null;
                                });
                    });
                    return future;
                });
    }

    private void evaluateAndPreview(
            Player actor,
            TargetIdentity target,
            HonorKind kind,
            String reason,
            List<ReputationEvent> actorEvents,
            RuntimeSnapshot snapshot,
            Instant now
    ) {
        Duration cooldown = snapshot.config().honor().cooldownPerPair();

        // 1. Cooldown per actor-target pair (SB-053)
        Optional<ReputationEvent> lastRating = actorEvents.stream()
                .filter(e -> target.id().equals(e.target()) && e.kind().isPlayerHonor())
                .max(Comparator.comparing(ReputationEvent::createdAt));

        if (lastRating.isPresent()) {
            Instant expiresAt = lastRating.get().createdAt().plus(cooldown);
            if (now.isBefore(expiresAt)) {
                Duration remaining = Duration.between(now, expiresAt);
                actor.sendMessage(messageRegistry.renderWithPrefix(snapshot, "honor.cooldown",
                        Map.of("time", formatDuration(remaining))));
                return;
            }
        }

        // 2. Allowance cap per actor-target pair inside rolling window (SB-054)
        HonorAllowanceTracker allowanceTracker = new HonorAllowanceTracker(snapshot.config().honor().toAllowanceConfig());
        if (!allowanceTracker.canIssue(PlayerId.of(actor.getUniqueId()), target.id(), kind, actorEvents, now)) {
            actor.sendMessage(messageRegistry.renderWithPrefix(snapshot, "honor.cap-reached"));
            return;
        }

        // 3. Progressive cost calculation (SB-050, Decision 0001)
        HonorCostCalculator costCalc = new HonorCostCalculator(snapshot.config().honor().toCostConfig());
        double cost = costCalc.calculateCost(PlayerId.of(actor.getUniqueId()), actorEvents, now);

        // 4. Balance check
        if (economy != null && !economy.has(actor, cost)) {
            actor.sendMessage(messageRegistry.renderWithPrefix(snapshot, "honor.insufficient-funds",
                    Map.of("cost", formatCost(cost))));
            return;
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
        pendingConfirmations.put(actor.getUniqueId(), pending);

        // 6. Show cost preview with confirm click action (SB-052)
        Component previewComp = messageRegistry.renderWithPrefix(snapshot, "honor.cost-preview",
                Map.of("cost", formatCost(cost)));
        previewComp = previewComp.clickEvent(ClickEvent.runCommand("/status confirm"));
        actor.sendMessage(previewComp);
    }

    /**
     * Confirms and commits a pending player honor action per SB-057:
     * charges the actor via Vault, verifies EconomyResponse, persists ReputationEvent asynchronously,
     * and refunds on write failure.
     */
    public CompletableFuture<Void> confirmPlayerHonor(Player actor, RuntimeSnapshot snapshot) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        PendingConfirmation pending = pendingConfirmations.remove(actor.getUniqueId());
        Instant now = clock.instant();

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

        // Re-check economy provider and balance
        if (economy == null || !economy.has(actor, pending.cost())) {
            actor.sendMessage(messageRegistry.renderWithPrefix(snapshot, "honor.insufficient-funds",
                    Map.of("cost", formatCost(pending.cost()))));
            return CompletableFuture.completedFuture(null);
        }

        // Main thread charges player via Vault (SB-057, ARCHITECTURE.md §9)
        EconomyResponse response = economy.withdrawPlayer(actor, pending.cost());
        if (response == null || !response.transactionSuccess()) {
            actor.sendMessage(messageRegistry.renderWithPrefix(snapshot, "honor.insufficient-funds",
                    Map.of("cost", formatCost(pending.cost()))));
            return CompletableFuture.completedFuture(null);
        }

        // Charge succeeded; persist event asynchronously on storage executor
        ReputationEvent event = new ReputationEvent(
                pending.actorId(),
                pending.targetId(),
                pending.delta(),
                pending.kind(),
                pending.cost(),
                pending.reason(),
                now
        );

        CompletableFuture<ReputationEvent> saveFuture;
        try {
            saveFuture = reputationRepository.saveAsync(event);
        } catch (Exception ex) {
            saveFuture = CompletableFuture.failedFuture(ex);
        }

        return saveFuture
                .handle((saved, error) -> {
                    if (error != null) {
                        // Write failed: refund on main thread (SB-057)
                        mainThreadRunner.accept(() -> {
                            if (economy != null) {
                                economy.depositPlayer(actor, pending.cost());
                            }
                            actor.sendMessage(messageRegistry.renderWithPrefix(snapshot, "honor.write-failed"));
                        });
                    } else {
                        // Write succeeded: notify actor on main thread
                        mainThreadRunner.accept(() -> {
                            String msgKey = pending.kind() == HonorKind.POSITIVE ? "honor.given" : "honor.removed";
                            actor.sendMessage(messageRegistry.renderWithPrefix(snapshot, msgKey,
                                    Map.of("target", pending.targetName())));
                        });
                    }
                    return null;
                });
    }

    /**
     * Administrative give: free, no cooldown, no cap, audited (SB-058, T-055, T-056).
     */
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
                    CompletableFuture<Void> future = new CompletableFuture<>();
                    mainThreadRunner.accept(() -> {
                        if (optTarget.isEmpty()) {
                            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "status.not-found",
                                    Map.of("player", targetInput)));
                            future.complete(null);
                            return;
                        }

                        TargetIdentity target = optTarget.get();
                        Instant now = clock.instant();

                        reputationRepository.findByTargetAsync(target.id())
                                .thenCompose(events -> {
                                    Status before = Status.fromEvents(events);
                                    int afterScore = before.value() + amount;
                                    ReputationEvent repEvent = new ReputationEvent(
                                            actorId,
                                            target.id(),
                                            amount,
                                            HonorKind.ADMIN_GIVE,
                                            0.0,
                                            "admin give",
                                            now
                                    );

                                    return reputationRepository.saveAsync(repEvent)
                                            .thenCompose(saved -> {
                                                AuditEvent audit = new AuditEvent(
                                                        actorId,
                                                        "admin_give",
                                                        target.id(),
                                                        String.valueOf(before.value()),
                                                        String.valueOf(afterScore),
                                                        now
                                                );
                                                return auditRepository.saveAsync(audit)
                                                        .thenApply(a -> afterScore);
                                            });
                                })
                                .thenAccept(afterVal -> mainThreadRunner.accept(() -> {
                                    sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.admin.give-success",
                                            Map.of("amount", String.valueOf(amount), "target", target.name(), "status", String.valueOf(afterVal))));
                                    future.complete(null);
                                }))
                                .exceptionally(ex -> {
                                    mainThreadRunner.accept(() -> future.completeExceptionally(ex));
                                    return null;
                                });
                    });
                    return future;
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
                    CompletableFuture<Void> future = new CompletableFuture<>();
                    mainThreadRunner.accept(() -> {
                        if (optTarget.isEmpty()) {
                            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "status.not-found",
                                    Map.of("player", targetInput)));
                            future.complete(null);
                            return;
                        }

                        TargetIdentity target = optTarget.get();
                        Instant now = clock.instant();

                        reputationRepository.findByTargetAsync(target.id())
                                .thenCompose(events -> {
                                    Status before = Status.fromEvents(events);
                                    int afterScore = before.value() - amount;
                                    ReputationEvent repEvent = new ReputationEvent(
                                            actorId,
                                            target.id(),
                                            -amount,
                                            HonorKind.ADMIN_TAKE,
                                            0.0,
                                            "admin take",
                                            now
                                    );

                                    return reputationRepository.saveAsync(repEvent)
                                            .thenCompose(saved -> {
                                                AuditEvent audit = new AuditEvent(
                                                        actorId,
                                                        "admin_take",
                                                        target.id(),
                                                        String.valueOf(before.value()),
                                                        String.valueOf(afterScore),
                                                        now
                                                );
                                                return auditRepository.saveAsync(audit)
                                                        .thenApply(a -> afterScore);
                                            });
                                })
                                .thenAccept(afterVal -> mainThreadRunner.accept(() -> {
                                    sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.admin.take-success",
                                            Map.of("amount", String.valueOf(amount), "target", target.name(), "status", String.valueOf(afterVal))));
                                    future.complete(null);
                                }))
                                .exceptionally(ex -> {
                                    mainThreadRunner.accept(() -> future.completeExceptionally(ex));
                                    return null;
                                });
                    });
                    return future;
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
                    CompletableFuture<Void> future = new CompletableFuture<>();
                    mainThreadRunner.accept(() -> {
                        if (optTarget.isEmpty()) {
                            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "status.not-found",
                                    Map.of("player", targetInput)));
                            future.complete(null);
                            return;
                        }

                        TargetIdentity target = optTarget.get();
                        Instant now = clock.instant();

                        reputationRepository.findByTargetAsync(target.id())
                                .thenCompose(events -> {
                                    Status before = Status.fromEvents(events);
                                    int compensatingDelta = -before.value();
                                    ReputationEvent compensatingEvent = new ReputationEvent(
                                            actorId,
                                            target.id(),
                                            compensatingDelta,
                                            HonorKind.ADMIN_RESET,
                                            0.0,
                                            "admin reset",
                                            now
                                    );

                                    return reputationRepository.saveAsync(compensatingEvent)
                                            .thenCompose(saved -> {
                                                AuditEvent audit = new AuditEvent(
                                                        actorId,
                                                        "admin_reset",
                                                        target.id(),
                                                        String.valueOf(before.value()),
                                                        "0",
                                                        now
                                                );
                                                return auditRepository.saveAsync(audit);
                                            });
                                })
                                .thenAccept(auditSaved -> mainThreadRunner.accept(() -> {
                                    sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.admin.reset-success",
                                            Map.of("target", target.name())));
                                    future.complete(null);
                                }))
                                .exceptionally(ex -> {
                                    mainThreadRunner.accept(() -> future.completeExceptionally(ex));
                                    return null;
                                });
                    });
                    return future;
                });
    }

    public static String formatDuration(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m " + secs + "s";
        }
        return secs + "s";
    }

    public static String formatCost(double cost) {
        if (cost == Math.floor(cost)) {
            return String.format(Locale.ROOT, "%.0f", cost);
        }
        return String.format(Locale.ROOT, "%.2f", cost);
    }
}
