package com.dasannn.socialblueprint.feature.update;

import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.config.UpdateConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Service managing GitHub updates, version checks, and checksum-verified downloads per P7 (SB-070 to SB-076).
 * - All HTTP and disk I/O runs on the async/storage executor, NEVER on the main thread (SB-073, T-080).
 * - Checksum is verified in memory before writing anything to plugins/update/ (SB-074, T-082).
 * - A mismatch aborts and leaves the update directory untouched.
 * - Failure is quiet: logged as a warning only (SB-073, T-085).
 * - Player-facing messages and Bukkit calls run on the main thread via mainThreadRunner.
 */
public class UpdateService {

    public static final Duration DEFAULT_HTTP_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_DOWNLOAD_TIMEOUT = Duration.ofSeconds(60);

    private final ConfigManager configManager;
    private final MessageRegistry messageRegistry;
    private final Executor asyncExecutor;
    private final Consumer<Runnable> mainThreadRunner;
    private final Supplier<File> updateFolderSupplier;
    private final Supplier<String> currentVersionSupplier;
    private final Supplier<File> currentJarSupplier;
    private final HttpClient httpClient;
    private final Logger logger;

    private final AtomicReference<VersionCheckResult> lastResult = new AtomicReference<>(null);
    private final AtomicBoolean checkInProgress = new AtomicBoolean(false);
    private final AtomicBoolean downloadInProgress = new AtomicBoolean(false);

    public UpdateService(
            ConfigManager configManager,
            MessageRegistry messageRegistry,
            Executor asyncExecutor,
            Consumer<Runnable> mainThreadRunner,
            Supplier<File> updateFolderSupplier,
            Supplier<String> currentVersionSupplier,
            Supplier<File> currentJarSupplier,
            HttpClient httpClient,
            Logger logger
    ) {
        this.configManager = Objects.requireNonNull(configManager, "configManager must not be null");
        this.messageRegistry = Objects.requireNonNull(messageRegistry, "messageRegistry must not be null");
        this.asyncExecutor = Objects.requireNonNull(asyncExecutor, "asyncExecutor must not be null");
        this.mainThreadRunner = mainThreadRunner != null ? mainThreadRunner : Runnable::run;
        this.updateFolderSupplier = Objects.requireNonNull(updateFolderSupplier, "updateFolderSupplier must not be null");
        this.currentVersionSupplier = Objects.requireNonNull(currentVersionSupplier, "currentVersionSupplier must not be null");
        this.currentJarSupplier = currentJarSupplier != null ? currentJarSupplier : () -> null;
        this.httpClient = httpClient != null ? httpClient : HttpClient.newBuilder()
                .connectTimeout(DEFAULT_HTTP_TIMEOUT)
                .build();
        this.logger = logger != null ? logger : Logger.getLogger(UpdateService.class.getName());
    }

    public String getCurrentVersion() {
        String ver = currentVersionSupplier.get();
        return ver != null && !ver.isBlank() ? ver : "unknown";
    }

    public VersionCheckResult getLastCheckResult() {
        return lastResult.get();
    }

    /**
     * Executes the GitHub version check asynchronously on the storage executor (T-080).
     */
    public CompletableFuture<VersionCheckResult> checkForUpdateAsync() {
        checkInProgress.set(true);
        return CompletableFuture.supplyAsync(this::performCheckInternal, asyncExecutor)
                .whenComplete((res, ex) -> checkInProgress.set(false));
    }

    private VersionCheckResult performCheckInternal() {
        String runningVersion = getCurrentVersion();
        try {
            RuntimeSnapshot snapshot = configManager.snapshot();
            UpdateConfig config = snapshot.config().update();

            String repo = config.repository();
            String apiUrl = config.apiUrl();
            String channel = config.channel();

            String endpoint;
            if ("beta".equalsIgnoreCase(channel) || "prerelease".equalsIgnoreCase(channel)) {
                endpoint = apiUrl + "/repos/" + repo + "/releases";
            } else {
                endpoint = apiUrl + "/repos/" + repo + "/releases/latest";
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "SocialBlueprint-UpdateChecker")
                    .timeout(DEFAULT_HTTP_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            if (status == 403 || status == 429) {
                logger.warning("Could not check for updates from GitHub: rate limit reached (HTTP " + status + ")");
                VersionCheckResult res = VersionCheckResult.unknown(runningVersion, "Rate limited (HTTP " + status + ")");
                lastResult.set(res);
                return res;
            }
            if (status == 404) {
                logger.warning("Could not check for updates from GitHub: repository or release not found (HTTP 404)");
                VersionCheckResult res = VersionCheckResult.unknown(runningVersion, "Not found (HTTP 404)");
                lastResult.set(res);
                return res;
            }
            if (status != 200) {
                logger.warning("Could not check for updates from GitHub: HTTP " + status);
                VersionCheckResult res = VersionCheckResult.unknown(runningVersion, "HTTP " + status);
                lastResult.set(res);
                return res;
            }

            String body = response.body();
            ReleaseInfo release;
            if ("beta".equalsIgnoreCase(channel) || "prerelease".equalsIgnoreCase(channel)) {
                JsonArray array = JsonParser.parseString(body).getAsJsonArray();
                if (array.isEmpty()) {
                    VersionCheckResult res = VersionCheckResult.unknown(runningVersion, "No releases found");
                    lastResult.set(res);
                    return res;
                }
                release = ReleaseParser.parseRelease(array.get(0).toString());
            } else {
                release = ReleaseParser.parseRelease(body);
            }

            VersionComparison comparison = VersionComparator.compare(runningVersion, release.tagName());
            VersionCheckResult result = VersionCheckResult.evaluated(comparison, runningVersion, release.tagName(), release);
            lastResult.set(result);
            return result;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            logger.warning("Could not check for updates from GitHub: " + msg);
            VersionCheckResult res = VersionCheckResult.unknown(runningVersion, msg);
            lastResult.set(res);
            return res;
        }
    }

    /**
     * Downloads the latest release jar, verifies checksum before writing anything, and writes
     * into plugins/update/ on the async executor per SB-071, SB-074, and T-082.
     */
    public CompletableFuture<Boolean> downloadUpdateAsync(CommandSender sender, RuntimeSnapshot snapshot) {
        RuntimeSnapshot currentSnapshot = snapshot != null ? snapshot : configManager.snapshot();
        if (!downloadInProgress.compareAndSet(false, true)) {
            sendToSender(sender, currentSnapshot, "updater.downloading", Map.of());
            return CompletableFuture.completedFuture(false);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                // 1. Ensure latest release metadata is available
                VersionCheckResult check = lastResult.get();
                if (check == null || check.releaseInfo() == null) {
                    check = performCheckInternal();
                }

                if (check.releaseInfo() == null) {
                    sendToSender(sender, currentSnapshot, "updater.failed", Map.of("error", "No release information available"));
                    return false;
                }

                ReleaseInfo release = check.releaseInfo();
                Optional<ReleaseAsset> optJar = release.findJarAsset();
                if (optJar.isEmpty()) {
                    logger.warning("No jar asset found in GitHub release " + release.tagName());
                    sendToSender(sender, currentSnapshot, "updater.failed", Map.of("error", "No jar asset found in release " + release.tagName()));
                    return false;
                }
                ReleaseAsset jarAsset = optJar.get();

                // 2. Locate expected checksum
                Optional<String> optExpectedHash = findExpectedChecksum(release, jarAsset);
                if (optExpectedHash.isEmpty()) {
                    logger.warning("No SHA-256 checksum found for release asset " + jarAsset.name() + ". Aborting update for security.");
                    sendToSender(sender, currentSnapshot, "updater.failed", Map.of("error", "No SHA-256 checksum found for release"));
                    return false;
                }
                String expectedHash = optExpectedHash.get();

                // 3. Download release asset into memory (byte array)
                HttpRequest jarRequest = HttpRequest.newBuilder()
                        .uri(URI.create(jarAsset.downloadUrl()))
                        .header("Accept", "application/octet-stream")
                        .header("User-Agent", "SocialBlueprint-UpdateChecker")
                        .timeout(DEFAULT_DOWNLOAD_TIMEOUT)
                        .GET()
                        .build();

                HttpResponse<byte[]> jarResponse = httpClient.send(jarRequest, HttpResponse.BodyHandlers.ofByteArray());
                if (jarResponse.statusCode() != 200) {
                    logger.warning("Failed to download jar asset from GitHub: HTTP " + jarResponse.statusCode());
                    sendToSender(sender, currentSnapshot, "updater.failed", Map.of("error", "Download failed: HTTP " + jarResponse.statusCode()));
                    return false;
                }
                byte[] jarBytes = jarResponse.body();

                // 4. Verify checksum BEFORE writing anything to disk (SB-074, T-082)
                String computedHash = ChecksumVerifier.computeSha256(jarBytes);
                if (!computedHash.equalsIgnoreCase(expectedHash)) {
                    // Checksum mismatch: treat as hostile, abort, log loudly, write nothing!
                    logger.severe("HOSTILE / CORRUPTED UPDATE: Checksum mismatch for " + jarAsset.name()
                            + "! Expected: " + expectedHash + ", computed: " + computedHash
                            + ". Aborting update. No files written to disk.");
                    sendToSender(sender, currentSnapshot, "updater.checksum-mismatch", Map.of());
                    return false;
                }

                // 5. Checksum verified: write into plugins/update/
                File updateFolder = updateFolderSupplier.get();
                if (updateFolder == null) {
                    logger.warning("Update folder could not be determined. Aborting update.");
                    sendToSender(sender, currentSnapshot, "updater.failed", Map.of("error", "Update folder not available"));
                    return false;
                }
                if (!updateFolder.exists()) {
                    updateFolder.mkdirs();
                }

                File currentJar = currentJarSupplier.get();
                String targetFileName = (currentJar != null && currentJar.getName().endsWith(".jar"))
                        ? currentJar.getName()
                        : jarAsset.name();

                File targetFile = new File(updateFolder, targetFileName);
                Files.write(targetFile.toPath(), jarBytes);

                logger.info("Successfully downloaded and verified SocialBlueprint update to "
                        + targetFile.getAbsolutePath() + ". Server restart is required to apply.");
                sendToSender(sender, currentSnapshot, "updater.restart-required", Map.of());
                return true;
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                logger.warning("Update download failed: " + msg);
                sendToSender(sender, currentSnapshot, "updater.failed", Map.of("error", msg));
                return false;
            } finally {
                downloadInProgress.set(false);
            }
        }, asyncExecutor);
    }

    private Optional<String> findExpectedChecksum(ReleaseInfo release, ReleaseAsset jarAsset) {
        // A. Companion asset <jar-name>.sha256 or <jar-name>.sha256sum
        String expectedAssetName1 = jarAsset.name() + ".sha256";
        String expectedAssetName2 = jarAsset.name() + ".sha256sum";
        for (ReleaseAsset asset : release.assets()) {
            if (asset.name().equalsIgnoreCase(expectedAssetName1) || asset.name().equalsIgnoreCase(expectedAssetName2)) {
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(asset.downloadUrl()))
                            .header("User-Agent", "SocialBlueprint-UpdateChecker")
                            .timeout(DEFAULT_HTTP_TIMEOUT)
                            .GET()
                            .build();
                    HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() == 200) {
                        Optional<String> hash = ChecksumVerifier.extractHashFromText(resp.body(), jarAsset.name());
                        if (hash.isPresent()) {
                            return hash;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }

        // B. Companion asset sha256sums.txt, checksums.txt, or SHA256SUMS
        for (ReleaseAsset asset : release.assets()) {
            String an = asset.name().toLowerCase(Locale.ROOT);
            if (an.equals("sha256sums.txt") || an.equals("checksums.txt") || an.equals("sha256sums") || an.equals("checksums")) {
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(asset.downloadUrl()))
                            .header("User-Agent", "SocialBlueprint-UpdateChecker")
                            .timeout(DEFAULT_HTTP_TIMEOUT)
                            .GET()
                            .build();
                    HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() == 200) {
                        Optional<String> hash = ChecksumVerifier.extractHashFromText(resp.body(), jarAsset.name());
                        if (hash.isPresent()) {
                            return hash;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }

        // C. Check release description body
        return ChecksumVerifier.extractHashFromText(release.body(), jarAsset.name());
    }

    private void sendToSender(CommandSender sender, RuntimeSnapshot snapshot, String messageKey, Map<String, String> placeholders) {
        if (sender == null) return;
        mainThreadRunner.accept(() -> {
            RuntimeSnapshot snap = snapshot != null ? snapshot : configManager.snapshot();
            sender.sendMessage(messageRegistry.renderWithPrefix(snap, messageKey, placeholders));
        });
    }

    /**
     * Called on plugin startup per SB-075 and T-084.
     */
    public void onStartup(RuntimeSnapshot snapshot) {
        UpdateConfig config = snapshot.config().update();
        if (!config.checkOnStartup()) {
            return;
        }

        checkForUpdateAsync().thenAccept(result -> {
            if (result.comparison() == VersionComparison.OUTDATED) {
                logger.info("A new version of SocialBlueprint is available: " + result.latestVersion()
                        + " (running: " + result.runningVersion() + "). Run '/status update' to download.");

                if (config.autoDownload()) {
                    logger.info("Automatic update download is enabled. Downloading update in background...");
                    downloadUpdateAsync(null, snapshot);
                }
            }
        });
    }
}
