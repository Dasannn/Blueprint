package com.dasannn.socialblueprint.feature.legacy;

import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.storage.AuditRepository;
import com.dasannn.socialblueprint.storage.ProfileRepository;
import com.dasannn.socialblueprint.storage.ReputationRepository;
import com.dasannn.socialblueprint.storage.ReputationRepository.LegacyCandidate;
import com.dasannn.socialblueprint.storage.StorageEngine;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Service orchestrating legacy PlayerStatus imports per T-090, T-091, T-092.
 * - Reads legacy configuration files off-thread on the storage executor.
 * - Resolves name-keyed rows to UUIDs; skips unresolvable names without guessing.
 * - Writes one ReputationEvent per player (HonorKind.LEGACY_IMPORT, cost 0.0, actor null, reason message key).
 * - Enforces idempotence: players with an existing legacy_import event are skipped.
 * - Dispatches all player and console replies on the main thread.
 */
public class LegacyImportService {

    public static final String REASON_MESSAGE_KEY = "commands.admin.import.reason";

    private final StorageEngine storageEngine;
    private final ReputationRepository reputationRepository;
    private final ProfileRepository profileRepository;
    private final AuditRepository auditRepository;
    private final MessageRegistry messageRegistry;
    private final LegacyPlayerResolver resolver;
    private final File dataFolder;
    private final Consumer<Runnable> mainThreadRunner;
    private final Supplier<ConsoleCommandSender> consoleSenderSupplier;
    private final Logger logger;

    private final AtomicBoolean isImporting = new AtomicBoolean(false);

    public LegacyImportService(
            StorageEngine storageEngine,
            ReputationRepository reputationRepository,
            ProfileRepository profileRepository,
            AuditRepository auditRepository,
            MessageRegistry messageRegistry,
            LegacyPlayerResolver resolver,
            File dataFolder,
            Consumer<Runnable> mainThreadRunner,
            Supplier<ConsoleCommandSender> consoleSenderSupplier,
            Logger logger
    ) {
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine must not be null");
        this.reputationRepository = Objects.requireNonNull(reputationRepository, "reputationRepository must not be null");
        this.profileRepository = profileRepository;
        this.auditRepository = auditRepository;
        this.messageRegistry = Objects.requireNonNull(messageRegistry, "messageRegistry must not be null");
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.dataFolder = dataFolder;
        this.mainThreadRunner = mainThreadRunner != null ? mainThreadRunner : Runnable::run;
        this.consoleSenderSupplier = consoleSenderSupplier != null ? consoleSenderSupplier : () -> null;
        this.logger = logger != null ? logger : Logger.getLogger(LegacyImportService.class.getName());
    }

    public boolean isImporting() {
        return isImporting.get();
    }

    /**
     * Executes legacy import asynchronously given a target file path and invoking sender.
     */
    public CompletableFuture<Void> importLegacyAsync(CommandSender sender, String filePath, RuntimeSnapshot snapshot) {
        Objects.requireNonNull(sender, "sender must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        if (!isImporting.compareAndSet(false, true)) {
            sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.admin.import.already-running"));
            return CompletableFuture.completedFuture(null);
        }

        File targetFile = resolveTargetFile(filePath);
        String fileDisplay = targetFile.getPath();
        sender.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.admin.import.started",
                Map.of("file", fileDisplay)));

        PlayerId actorId = (sender instanceof Player p)
                ? PlayerId.of(p.getUniqueId())
                : PlayerId.CONSOLE;

        CompletableFuture<Void> future = new CompletableFuture<>();

        storageEngine.supplyAsync(() -> {
            try {
                if (!targetFile.exists() || !targetFile.isFile()) {
                    return CompletableFuture.completedFuture(new ServiceOutcome(OutcomeType.FILE_NOT_FOUND, fileDisplay, null, null));
                }

                YamlConfiguration yaml;
                try {
                    yaml = YamlConfiguration.loadConfiguration(targetFile);
                } catch (Exception e) {
                    return CompletableFuture.completedFuture(new ServiceOutcome(OutcomeType.IO_ERROR, fileDisplay, e.getMessage(), null));
                }

                return processYamlInternal(yaml, targetFile.getName(), actorId);
            } catch (Exception e) {
                return CompletableFuture.completedFuture(new ServiceOutcome(OutcomeType.IO_ERROR, fileDisplay, e.getMessage() != null ? e.getMessage() : "Unknown error", null));
            }
        }).thenCompose(result -> result).whenComplete((outcome, ex) -> {
            isImporting.set(false);
            try {
                mainThreadRunner.accept(() -> {
                    try {
                        if (ex != null) {
                            String errMsg = ex.getMessage() != null ? ex.getMessage() : ex.toString();
                            deliverMessage(sender, snapshot, "commands.admin.import.io-error", Map.of("error", errMsg));
                        } else if (outcome != null) {
                            deliverOutcome(sender, snapshot, outcome);
                        }
                        future.complete(null);
                    } catch (RuntimeException error) {
                        future.completeExceptionally(error);
                    }
                });
            } catch (RuntimeException error) {
                future.completeExceptionally(error);
            }
        });

        return future;
    }

    /**
     * Directly processes a parsed YamlConfiguration for testing or programmatic import.
     */
    public CompletableFuture<LegacyImportReport> importFromYamlAsync(
            YamlConfiguration yaml,
            String sourceName,
            CommandSender sender,
            RuntimeSnapshot snapshot
    ) {
        Objects.requireNonNull(yaml, "yaml must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        PlayerId actorId = (sender instanceof Player p)
                ? PlayerId.of(p.getUniqueId())
                : PlayerId.CONSOLE;

        return storageEngine.supplyAsync(() -> processYamlInternal(yaml, sourceName, actorId))
                .thenCompose(result -> result)
                .thenApply(outcome -> outcome.report() != null ? outcome.report() : new LegacyImportReport(0, 0, 0, Collections.emptyList()))
                .thenApply(report -> {
                    if (sender != null) {
                        mainThreadRunner.accept(() -> deliverReportMessages(sender, snapshot, report));
                    }
                    return report;
                });
    }

    private CompletableFuture<ServiceOutcome> processYamlInternal(YamlConfiguration yaml, String sourceName, PlayerId actorId) {
        List<ParsedEntry> rawEntries = parseEntries(yaml);
        if (rawEntries.isEmpty()) {
            return CompletableFuture.completedFuture(new ServiceOutcome(OutcomeType.NO_PLAYERS_FOUND, sourceName, null, null));
        }

        int totalRead = rawEntries.size();
        List<LegacyImportReport.SkippedEntry> preSkipped = new ArrayList<>();
        List<LegacyCandidate> candidates = new ArrayList<>();

        for (ParsedEntry entry : rawEntries) {
            if (!entry.isValid()) {
                preSkipped.add(new LegacyImportReport.SkippedEntry(
                        entry.rawKey(),
                        LegacyImportReport.SkipReason.INVALID_SCORE,
                        entry.parseError() != null ? entry.parseError() : "Invalid score"
                ));
                continue;
            }

            Optional<PlayerId> resolvedId = resolveEntry(entry.rawKey(), entry.name());
            if (resolvedId.isEmpty()) {
                preSkipped.add(new LegacyImportReport.SkippedEntry(
                        entry.name() != null ? entry.name() : entry.rawKey(),
                        LegacyImportReport.SkipReason.UNRESOLVED_UUID,
                        "Unable to resolve UUID identity"
                ));
                continue;
            }

            candidates.add(new LegacyCandidate(resolvedId.get(), entry.name(), entry.score()));
        }

        try {
            return reputationRepository.executeLegacyImportAsync(
                    candidates,
                    preSkipped,
                    totalRead,
                    actorId,
                    sourceName,
                    profileRepository,
                    auditRepository,
                    Instant.now()
            ).handle((report, error) -> {
                if (error != null) {
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    return new ServiceOutcome(OutcomeType.IO_ERROR, sourceName, cause.getMessage(), null);
                }
                return new ServiceOutcome(OutcomeType.SUCCESS, sourceName, null, report);
            });
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return CompletableFuture.completedFuture(new ServiceOutcome(OutcomeType.IO_ERROR, sourceName, cause.getMessage(), null));
        }
    }

    private Optional<PlayerId> resolveEntry(String rawKey, String name) {
        // 1. Direct UUID string check
        try {
            UUID uuid = UUID.fromString(rawKey.trim());
            return Optional.of(PlayerId.of(uuid));
        } catch (IllegalArgumentException ignored) {
        }

        // 2. Off-thread resolver
        String lookupName = (name != null && !name.isBlank()) ? name.trim() : rawKey.trim();
        return resolver.resolve(lookupName);
    }

    private List<ParsedEntry> parseEntries(YamlConfiguration yaml) {
        ConfigurationSection playerSection = yaml.getConfigurationSection("playerList");
        if (playerSection == null) {
            playerSection = yaml.getConfigurationSection("players");
        }
        if (playerSection == null) {
            return Collections.emptyList();
        }

        List<ParsedEntry> result = new ArrayList<>();
        for (String key : playerSection.getKeys(false)) {
            if (playerSection.isConfigurationSection(key)) {
                ConfigurationSection sub = playerSection.getConfigurationSection(key);
                String explicitName = sub.getString("name");
                String name;
                if (explicitName != null && !explicitName.isBlank()) {
                    name = explicitName.trim();
                } else {
                    boolean isUuid = false;
                    try {
                        UUID.fromString(key.trim());
                        isUuid = true;
                    } catch (IllegalArgumentException ignored) {}
                    name = isUuid ? null : key.trim();
                }

                Object scoreObj = sub.get("reputation");
                if (scoreObj == null) scoreObj = sub.get("score");
                if (scoreObj == null) scoreObj = sub.get("rep");
                if (scoreObj == null) scoreObj = sub.get("status");

                if (scoreObj == null) {
                    result.add(new ParsedEntry(key, name, null, "Missing reputation score"));
                } else {
                    Integer parsedScore = null;
                    if (scoreObj instanceof Number num) {
                        parsedScore = num.intValue();
                    } else {
                        try {
                            parsedScore = Integer.parseInt(scoreObj.toString().trim());
                        } catch (NumberFormatException e) {
                            result.add(new ParsedEntry(key, name, null, scoreObj.toString()));
                        }
                    }
                    if (parsedScore != null) {
                        if (Math.abs(parsedScore) > com.dasannn.socialblueprint.domain.ReputationEvent.MAX_DELTA) {
                            result.add(new ParsedEntry(key, name, null, "Score out of range: " + parsedScore));
                        } else {
                            result.add(new ParsedEntry(key, name, parsedScore, null));
                        }
                    }
                }
            } else if (playerSection.get(key) instanceof Number num) {
                boolean isUuid = false;
                try {
                    UUID.fromString(key.trim());
                    isUuid = true;
                } catch (IllegalArgumentException ignored) {}
                String name = isUuid ? null : key.trim();
                int score = num.intValue();
                if (Math.abs(score) > com.dasannn.socialblueprint.domain.ReputationEvent.MAX_DELTA) {
                    result.add(new ParsedEntry(key, name, null, "Score out of range: " + score));
                } else {
                    result.add(new ParsedEntry(key, name, score, null));
                }
            } else if (playerSection.isString(key)) {
                boolean isUuid = false;
                try {
                    UUID.fromString(key.trim());
                    isUuid = true;
                } catch (IllegalArgumentException ignored) {}
                String name = isUuid ? null : key.trim();
                try {
                    int score = Integer.parseInt(playerSection.getString(key).trim());
                    if (Math.abs(score) > com.dasannn.socialblueprint.domain.ReputationEvent.MAX_DELTA) {
                        result.add(new ParsedEntry(key, name, null, "Score out of range: " + score));
                    } else {
                        result.add(new ParsedEntry(key, name, score, null));
                    }
                } catch (NumberFormatException e) {
                    result.add(new ParsedEntry(key, name, null, playerSection.getString(key)));
                }
            } else {
                result.add(new ParsedEntry(key, key, null, "Unknown entry shape"));
            }
        }
        return result;
    }

    private File resolveTargetFile(String filePath) {
        if (filePath != null && !filePath.isBlank()) {
            File direct = new File(filePath.trim());
            if (direct.isAbsolute() || direct.exists()) {
                return direct;
            }
            if (dataFolder != null) {
                File relData = new File(dataFolder, filePath.trim());
                if (relData.exists()) {
                    return relData;
                }
                File relPlugins = new File(dataFolder.getParentFile(), filePath.trim());
                if (relPlugins.exists()) {
                    return relPlugins;
                }
            }
            return direct;
        }

        // Default legacy location
        if (dataFolder != null && dataFolder.getParentFile() != null) {
            File defaultOld = new File(dataFolder.getParentFile(), "PlayerStatus/config.yml");
            if (defaultOld.exists()) {
                return defaultOld;
            }
            File fallback = new File(dataFolder, "legacy-config.yml");
            if (fallback.exists()) {
                return fallback;
            }
            return defaultOld;
        }

        return new File("plugins/PlayerStatus/config.yml");
    }

    private void deliverOutcome(CommandSender sender, RuntimeSnapshot snapshot, ServiceOutcome outcome) {
        switch (outcome.type()) {
            case FILE_NOT_FOUND -> deliverMessage(sender, snapshot, "commands.admin.import.file-not-found",
                    Map.of("file", outcome.file()));
            case NO_PLAYERS_FOUND -> deliverMessage(sender, snapshot, "commands.admin.import.no-players-found",
                    Map.of("file", outcome.file()));
            case IO_ERROR -> deliverMessage(sender, snapshot, "commands.admin.import.io-error",
                    Map.of("error", outcome.details() != null ? outcome.details() : "IO error"));
            case SUCCESS -> {
                if (outcome.report() != null) {
                    deliverReportMessages(sender, snapshot, outcome.report());
                }
            }
        }
    }

    private void deliverReportMessages(CommandSender sender, RuntimeSnapshot snapshot, LegacyImportReport report) {
        for (LegacyImportReport.SkippedEntry entry : report.skippedEntries()) {
            switch (entry.reason()) {
                case UNRESOLVED_UUID -> deliverMessage(sender, snapshot, "commands.admin.import.skipped-unresolved",
                        Map.of("player", entry.playerName()));
                case ALREADY_IMPORTED -> deliverMessage(sender, snapshot, "commands.admin.import.skipped-already-imported",
                        Map.of("player", entry.playerName()));
                case INVALID_SCORE -> deliverMessage(sender, snapshot, "commands.admin.import.skipped-invalid-score",
                        Map.of("player", entry.playerName(), "score", entry.details() != null ? entry.details() : "unknown"));
            }
        }

        deliverMessage(sender, snapshot, "commands.admin.import.summary", Map.of(
                "read", String.valueOf(report.readCount()),
                "imported", String.valueOf(report.importedCount()),
                "skipped", String.valueOf(report.skippedCount())
        ));
    }

    private void deliverMessage(CommandSender sender, RuntimeSnapshot snapshot, String key, Map<String, String> substitutions) {
        Component component = messageRegistry.renderWithPrefix(snapshot, key, substitutions);
        sender.sendMessage(component);

        ConsoleCommandSender console = consoleSenderSupplier.get();
        if (console != null && !(sender instanceof ConsoleCommandSender) && !sender.equals(console)) {
            console.sendMessage(component);
        }
    }

    private enum OutcomeType {
        SUCCESS,
        FILE_NOT_FOUND,
        NO_PLAYERS_FOUND,
        IO_ERROR
    }

    private record ServiceOutcome(
            OutcomeType type,
            String file,
            String details,
            LegacyImportReport report
    ) {}

    private record ParsedEntry(
            String rawKey,
            String name,
            Integer score,
            String parseError
    ) {
        public boolean isValid() {
            return parseError == null && score != null;
        }
    }
}
