package com.dasannn.socialblueprint.feature.legacy;

import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.PlayerProfile;
import com.dasannn.socialblueprint.domain.ReputationEvent;
import com.dasannn.socialblueprint.feature.profile.PlayerLookup;
import com.dasannn.socialblueprint.storage.AuditRepository;
import com.dasannn.socialblueprint.storage.ProfileRepository;
import com.dasannn.socialblueprint.storage.ReputationRepository;
import com.dasannn.socialblueprint.storage.ReputationRepository.LegacyCandidate;
import com.dasannn.socialblueprint.storage.StorageEngine;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Service orchestrating legacy PlayerStatus imports per T-090, T-091, T-092.
 * - Reads legacy configuration files off-thread on the storage executor.
 * - Resolves name-keyed rows to UUIDs on calling thread; skips unresolvable names without guessing.
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
    private final PlayerLookup playerLookup;
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
            PlayerLookup playerLookup,
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
        this.playerLookup = playerLookup;
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

        CompletableFuture<Void> future = new CompletableFuture<>();

        try {
            File targetFile = resolveTargetFile(filePath);
            String fileDisplay = targetFile.getPath();
            deliverMessage(sender, snapshot, "commands.admin.import.started", Map.of("file", fileDisplay));

            if (!targetFile.exists() || !targetFile.isFile()) {
                deliverMessage(sender, snapshot, "commands.admin.import.file-not-found", Map.of("file", fileDisplay));
                isImporting.set(false);
                future.complete(null);
                return future;
            }

            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.load(targetFile);
            } catch (InvalidConfigurationException e) {
                deliverMessage(sender, snapshot, "commands.admin.import.invalid-yaml", Map.of("file", fileDisplay));
                isImporting.set(false);
                future.complete(null);
                return future;
            } catch (IOException e) {
                deliverMessage(sender, snapshot, "commands.admin.import.io-error",
                        Map.of("error", e.getMessage() != null ? e.getMessage() : "IO error"));
                isImporting.set(false);
                future.complete(null);
                return future;
            }

            PlayerId actorId = (sender instanceof Player p)
                    ? PlayerId.of(p.getUniqueId())
                    : PlayerId.CONSOLE;

            return processYamlAsync(yaml, targetFile.getName(), fileDisplay, actorId, sender, snapshot, future);
        } catch (Throwable t) {
            isImporting.set(false);
            String errMsg = t.getMessage() != null ? t.getMessage() : t.toString();
            deliverMessage(sender, snapshot, "commands.admin.import.io-error", Map.of("error", errMsg));
            future.complete(null);
            return future;
        }
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

        CompletableFuture<LegacyImportReport> future = new CompletableFuture<>();

        try {
            ConfigurationSection playerSection = yaml.getConfigurationSection("playerList");
            if (playerSection == null) {
                playerSection = yaml.getConfigurationSection("players");
            }

            if (playerSection == null) {
                if (sender != null) {
                    deliverMessage(sender, snapshot, "commands.admin.import.invalid-shape", Map.of("file", sourceName));
                }
                LegacyImportReport emptyReport = new LegacyImportReport(0, 0, 0, Collections.emptyList());
                future.complete(emptyReport);
                return future;
            }

            Set<String> keys = playerSection.getKeys(false);
            if (keys.isEmpty()) {
                if (sender != null) {
                    deliverMessage(sender, snapshot, "commands.admin.import.no-players-found", Map.of("file", sourceName));
                }
                LegacyImportReport emptyReport = new LegacyImportReport(0, 0, 0, Collections.emptyList());
                future.complete(emptyReport);
                return future;
            }

            ParsedResult parsed = parseEntriesOnCallingThread(playerSection, snapshot.config().legacyImport().trustNameLookup());

            storageEngine.supplyAsync(() -> executeStorageResolution(parsed, actorId, sourceName))
                    .thenCompose(res -> res)
                    .whenComplete((report, ex) -> {
                        if (ex != null) {
                            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                            if (sender != null) {
                                mainThreadRunner.accept(() -> deliverMessage(sender, snapshot, "commands.admin.import.io-error",
                                        Map.of("error", cause.getMessage() != null ? cause.getMessage() : cause.toString())));
                            }
                            future.completeExceptionally(cause);
                        } else {
                            if (sender != null) {
                                mainThreadRunner.accept(() -> deliverReportMessages(sender, snapshot, report));
                            }
                            future.complete(report);
                        }
                    });
            return future;
        } catch (Throwable t) {
            if (sender != null) {
                deliverMessage(sender, snapshot, "commands.admin.import.io-error",
                        Map.of("error", t.getMessage() != null ? t.getMessage() : t.toString()));
            }
            future.completeExceptionally(t);
            return future;
        }
    }

    private CompletableFuture<Void> processYamlAsync(
            YamlConfiguration yaml,
            String sourceName,
            String fileDisplay,
            PlayerId actorId,
            CommandSender sender,
            RuntimeSnapshot snapshot,
            CompletableFuture<Void> future
    ) {
        ConfigurationSection playerSection = yaml.getConfigurationSection("playerList");
        if (playerSection == null) {
            playerSection = yaml.getConfigurationSection("players");
        }

        if (playerSection == null) {
            deliverMessage(sender, snapshot, "commands.admin.import.invalid-shape", Map.of("file", fileDisplay));
            isImporting.set(false);
            future.complete(null);
            return future;
        }

        Set<String> keys = playerSection.getKeys(false);
        if (keys.isEmpty()) {
            deliverMessage(sender, snapshot, "commands.admin.import.no-players-found", Map.of("file", fileDisplay));
            isImporting.set(false);
            future.complete(null);
            return future;
        }

        ParsedResult parsed = parseEntriesOnCallingThread(playerSection, snapshot.config().legacyImport().trustNameLookup());

        storageEngine.supplyAsync(() -> executeStorageResolution(parsed, actorId, sourceName))
                .thenCompose(res -> res)
                .whenComplete((report, ex) -> {
                    isImporting.set(false);
                    try {
                        mainThreadRunner.accept(() -> {
                            try {
                                if (ex != null) {
                                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                                    deliverMessage(sender, snapshot, "commands.admin.import.io-error",
                                            Map.of("error", cause.getMessage() != null ? cause.getMessage() : cause.toString()));
                                } else if (report != null) {
                                    deliverReportMessages(sender, snapshot, report);
                                }
                                future.complete(null);
                            } catch (Throwable t) {
                                future.completeExceptionally(t);
                            }
                        });
                    } catch (Throwable t) {
                        future.completeExceptionally(t);
                    }
                });

        return future;
    }

    private ParsedResult parseEntriesOnCallingThread(ConfigurationSection playerSection, boolean trustNameLookup) {
        Set<String> keys = playerSection.getKeys(false);
        int totalRead = keys.size();
        List<LegacyImportReport.SkippedEntry> preSkipped = new ArrayList<>();
        List<LegacyCandidate> directCandidates = new ArrayList<>();
        List<NameCandidateToResolve> nameCandidates = new ArrayList<>();
        Map<String, Optional<PlayerLookup.KnownPlayer>> resolvedOnCallingThread = new HashMap<>();

        for (String rawKey : keys) {
            UUID keyUuid = null;
            try {
                keyUuid = UUID.fromString(rawKey.trim());
            } catch (IllegalArgumentException ignored) {}

            String name = null;
            Object scoreObj = null;

            if (playerSection.isConfigurationSection(rawKey)) {
                ConfigurationSection sub = playerSection.getConfigurationSection(rawKey);
                String explicitName = sub.getString("name");
                if (explicitName != null && !explicitName.isBlank()) {
                    name = explicitName.trim();
                } else if (keyUuid == null) {
                    name = rawKey.trim();
                }

                scoreObj = sub.get("reputation");
                if (scoreObj == null) scoreObj = sub.get("score");
                if (scoreObj == null) scoreObj = sub.get("rep");
                if (scoreObj == null) scoreObj = sub.get("status");
            } else {
                scoreObj = playerSection.get(rawKey);
                if (keyUuid == null) {
                    name = rawKey.trim();
                }
            }

            ScoreParseResult scoreResult = parseExactIntegralScore(scoreObj);
            if (!scoreResult.isValid()) {
                preSkipped.add(new LegacyImportReport.SkippedEntry(
                        name != null ? name : rawKey,
                        LegacyImportReport.SkipReason.INVALID_SCORE,
                        scoreResult.error()
                ));
                continue;
            }

            int score = scoreResult.score();

            if (keyUuid != null) {
                directCandidates.add(new LegacyCandidate(PlayerId.of(keyUuid), name, score));
            } else {
                if (!trustNameLookup) {
                    preSkipped.add(new LegacyImportReport.SkippedEntry(
                            name != null ? name : rawKey,
                            LegacyImportReport.SkipReason.UNVERIFIED_NAME,
                            "Name could not be verified (trust-name-lookup is false)"
                    ));
                } else {
                    String lookupName = name != null ? name : rawKey.trim();
                    if (!resolvedOnCallingThread.containsKey(lookupName)) {
                        Optional<PlayerLookup.KnownPlayer> kp = (playerLookup != null)
                                ? playerLookup.lookup(lookupName)
                                : Optional.empty();
                        resolvedOnCallingThread.put(lookupName, kp);
                    }
                    nameCandidates.add(new NameCandidateToResolve(lookupName, score));
                }
            }
        }

        return new ParsedResult(totalRead, directCandidates, nameCandidates, resolvedOnCallingThread, preSkipped);
    }

    private CompletableFuture<LegacyImportReport> executeStorageResolution(
            ParsedResult parsed,
            PlayerId actorId,
            String sourceName
    ) {
        List<LegacyCandidate> allCandidates = new ArrayList<>(parsed.directCandidates());
        List<LegacyImportReport.SkippedEntry> allPreSkipped = new ArrayList<>(parsed.preSkipped());

        for (NameCandidateToResolve ncr : parsed.nameCandidates()) {
            PlayerId resolvedId = null;
            String finalName = ncr.lookupName();

            if (profileRepository != null) {
                Optional<PlayerProfile> profile = profileRepository.findByName(ncr.lookupName());
                if (profile.isPresent()) {
                    resolvedId = profile.get().id();
                    finalName = profile.get().lastKnownName();
                }
            }

            if (resolvedId == null) {
                Optional<PlayerLookup.KnownPlayer> kp = parsed.resolvedOnCallingThread().get(ncr.lookupName());
                if (kp != null && kp.isPresent()) {
                    resolvedId = kp.get().id();
                    finalName = kp.get().name();
                }
            }

            if (resolvedId != null) {
                allCandidates.add(new LegacyCandidate(resolvedId, finalName, ncr.score()));
            } else {
                allPreSkipped.add(new LegacyImportReport.SkippedEntry(
                        ncr.lookupName(),
                        LegacyImportReport.SkipReason.UNRESOLVED_UUID,
                        "Unable to resolve UUID identity"
                ));
            }
        }

        return reputationRepository.executeLegacyImportAsync(
                allCandidates,
                allPreSkipped,
                parsed.totalRead(),
                actorId,
                sourceName,
                profileRepository,
                auditRepository,
                Instant.now()
        );
    }

    private static ScoreParseResult parseExactIntegralScore(Object scoreObj) {
        if (scoreObj == null) {
            return new ScoreParseResult(null, "Missing reputation score");
        }

        if (scoreObj instanceof Double || scoreObj instanceof Float || scoreObj instanceof java.math.BigDecimal) {
            return new ScoreParseResult(null, "Non-integer score: " + scoreObj);
        }

        if (scoreObj instanceof Integer i) {
            if (i < -ReputationEvent.MAX_DELTA || i > ReputationEvent.MAX_DELTA) {
                return new ScoreParseResult(null, "Score out of range: " + i);
            }
            return new ScoreParseResult(i, null);
        }

        if (scoreObj instanceof Long l) {
            if (l < -ReputationEvent.MAX_DELTA || l > ReputationEvent.MAX_DELTA) {
                return new ScoreParseResult(null, "Score out of range: " + l);
            }
            return new ScoreParseResult(l.intValue(), null);
        }

        if (scoreObj instanceof java.math.BigInteger bi) {
            long min = -ReputationEvent.MAX_DELTA;
            long max = ReputationEvent.MAX_DELTA;
            if (bi.compareTo(java.math.BigInteger.valueOf(min)) < 0 || bi.compareTo(java.math.BigInteger.valueOf(max)) > 0) {
                return new ScoreParseResult(null, "Score out of range: " + bi);
            }
            return new ScoreParseResult(bi.intValue(), null);
        }

        if (scoreObj instanceof Short s) {
            int i = s.intValue();
            if (i < -ReputationEvent.MAX_DELTA || i > ReputationEvent.MAX_DELTA) {
                return new ScoreParseResult(null, "Score out of range: " + i);
            }
            return new ScoreParseResult(i, null);
        }

        if (scoreObj instanceof Byte b) {
            return new ScoreParseResult(b.intValue(), null);
        }

        if (scoreObj instanceof String str) {
            String trimmed = str.trim();
            try {
                long val = Long.parseLong(trimmed);
                if (val < -ReputationEvent.MAX_DELTA || val > ReputationEvent.MAX_DELTA) {
                    return new ScoreParseResult(null, "Score out of range: " + val);
                }
                return new ScoreParseResult((int) val, null);
            } catch (NumberFormatException e) {
                return new ScoreParseResult(null, str);
            }
        }

        return new ScoreParseResult(null, scoreObj.toString());
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

    private void deliverReportMessages(CommandSender sender, RuntimeSnapshot snapshot, LegacyImportReport report) {
        for (LegacyImportReport.SkippedEntry entry : report.skippedEntries()) {
            switch (entry.reason()) {
                case UNVERIFIED_NAME -> deliverMessage(sender, snapshot, "commands.admin.import.skipped-unverified",
                        Map.of("player", entry.playerName()));
                case UNRESOLVED_UUID -> deliverMessage(sender, snapshot, "commands.admin.import.skipped-unresolved",
                        Map.of("player", entry.playerName()));
                case ALREADY_IMPORTED -> {
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("player", entry.playerName());
                    placeholders.put("kept", entry.keptScore() != null ? String.valueOf(entry.keptScore()) : "unknown");
                    placeholders.put("ignored", entry.ignoredScore() != null ? String.valueOf(entry.ignoredScore()) : "unknown");
                    deliverMessage(sender, snapshot, "commands.admin.import.skipped-already-imported", placeholders);
                }
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

    private record ScoreParseResult(Integer score, String error) {
        public boolean isValid() {
            return error == null && score != null;
        }
    }

    private record NameCandidateToResolve(String lookupName, int score) {}

    private record ParsedResult(
            int totalRead,
            List<LegacyCandidate> directCandidates,
            List<NameCandidateToResolve> nameCandidates,
            Map<String, Optional<PlayerLookup.KnownPlayer>> resolvedOnCallingThread,
            List<LegacyImportReport.SkippedEntry> preSkipped
    ) {}
}
