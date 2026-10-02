package com.dasannn.socialblueprint.feature.update;

import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.config.UpdateConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;

import com.dasannn.socialblueprint.domain.AuditEvent;
import com.dasannn.socialblueprint.domain.NonPlayerTarget;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.storage.AuditRepository;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
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
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;

/**
 * Service managing GitHub updates, version checks, and checksum-verified downloads per P7 (SB-070 to SB-076).
 * - All HTTP and disk I/O runs on the async/storage executor, NEVER on the main thread (SB-073, T-080).
 * - Checksum is verified in memory before writing anything to plugins/update/ (SB-074, T-082).
 * - Streamed downloads enforce hard byte caps before allocating or writing (Finding 1).
 * - All remote connections and redirects require HTTPS (Finding 2).
 * - Staging writes to a temp file outside Paper's scan directory, verifies JAR structure, and atomically moves (Finding 3).
 * - A mismatch or failure aborts and leaves any existing staged jar untouched.
 * - Failure is quiet: logged as a warning only (SB-073, T-085).
 * - Player-facing messages and Bukkit calls run on the main thread via mainThreadRunner.
 */
public class UpdateService {

    public static final Duration DEFAULT_HTTP_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_DOWNLOAD_TIMEOUT = Duration.ofSeconds(60);
    public static final long MAX_METADATA_BYTES = 2 * 1024 * 1024L; // 2 MiB
    public static final long MAX_CHECKSUM_BYTES = 512 * 1024L; // 512 KiB

    private final ConfigManager configManager;
    private final MessageRegistry messageRegistry;
    private final Executor asyncExecutor;
    private final Consumer<Runnable> mainThreadRunner;
    private final Supplier<File> updateFolderSupplier;
    private final Supplier<String> currentVersionSupplier;
    private final Supplier<File> currentJarSupplier;
    private final HttpClient httpClient;
    private final Logger logger;
    private final boolean allowInsecureHttpForTesting;
    private final AuditRepository auditRepository;

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
        this(configManager, messageRegistry, asyncExecutor, mainThreadRunner, updateFolderSupplier,
                currentVersionSupplier, currentJarSupplier, httpClient, logger, UpdateConfig.isAllowInsecureHttpForTesting(), null);
    }

    public UpdateService(
            ConfigManager configManager,
            MessageRegistry messageRegistry,
            Executor asyncExecutor,
            Consumer<Runnable> mainThreadRunner,
            File updateFolder,
            String currentVersion,
            File currentJar,
            HttpClient httpClient,
            Logger logger
    ) {
        this(configManager, messageRegistry, asyncExecutor, mainThreadRunner,
                () -> updateFolder, () -> currentVersion, () -> currentJar,
                httpClient, logger, UpdateConfig.isAllowInsecureHttpForTesting(), null);
    }

    public UpdateService(
            ConfigManager configManager,
            MessageRegistry messageRegistry,
            Executor asyncExecutor,
            Consumer<Runnable> mainThreadRunner,
            Supplier<File> updateFolderSupplier,
            Supplier<String> currentVersionSupplier,
            Supplier<File> currentJarSupplier,
            HttpClient httpClient,
            Logger logger,
            boolean allowInsecureHttpForTesting
    ) {
        this(configManager, messageRegistry, asyncExecutor, mainThreadRunner, updateFolderSupplier,
                currentVersionSupplier, currentJarSupplier, httpClient, logger, allowInsecureHttpForTesting, null);
    }

    public UpdateService(
            ConfigManager configManager,
            MessageRegistry messageRegistry,
            Executor asyncExecutor,
            Consumer<Runnable> mainThreadRunner,
            File updateFolder,
            String currentVersion,
            File currentJar,
            HttpClient httpClient,
            Logger logger,
            AuditRepository auditRepository
    ) {
        this(configManager, messageRegistry, asyncExecutor, mainThreadRunner,
                () -> updateFolder, () -> currentVersion, () -> currentJar,
                httpClient, logger, UpdateConfig.isAllowInsecureHttpForTesting(), auditRepository);
    }

    public UpdateService(
            ConfigManager configManager,
            MessageRegistry messageRegistry,
            Executor asyncExecutor,
            Consumer<Runnable> mainThreadRunner,
            Supplier<File> updateFolderSupplier,
            Supplier<String> currentVersionSupplier,
            Supplier<File> currentJarSupplier,
            HttpClient httpClient,
            Logger logger,
            AuditRepository auditRepository
    ) {
        this(configManager, messageRegistry, asyncExecutor, mainThreadRunner, updateFolderSupplier,
                currentVersionSupplier, currentJarSupplier, httpClient, logger, UpdateConfig.isAllowInsecureHttpForTesting(), auditRepository);
    }

    public UpdateService(
            ConfigManager configManager,
            MessageRegistry messageRegistry,
            Executor asyncExecutor,
            Consumer<Runnable> mainThreadRunner,
            Supplier<File> updateFolderSupplier,
            Supplier<String> currentVersionSupplier,
            Supplier<File> currentJarSupplier,
            HttpClient httpClient,
            Logger logger,
            boolean allowInsecureHttpForTesting,
            AuditRepository auditRepository
    ) {
        this.configManager = Objects.requireNonNull(configManager, "configManager must not be null");
        this.messageRegistry = Objects.requireNonNull(messageRegistry, "messageRegistry must not be null");
        this.asyncExecutor = Objects.requireNonNull(asyncExecutor, "asyncExecutor must not be null");
        this.mainThreadRunner = mainThreadRunner != null ? mainThreadRunner : Runnable::run;
        this.updateFolderSupplier = Objects.requireNonNull(updateFolderSupplier, "updateFolderSupplier must not be null");
        this.currentVersionSupplier = Objects.requireNonNull(currentVersionSupplier, "currentVersionSupplier must not be null");
        this.currentJarSupplier = currentJarSupplier != null ? currentJarSupplier : () -> null;
        // NORMAL follows GitHub's asset redirect to its download host but never
        // from HTTPS to HTTP. The default (NEVER) left every asset at a 302,
        // so no checksum or jar could ever be fetched from a real release.
        this.httpClient = httpClient != null ? httpClient : HttpClient.newBuilder()
                .connectTimeout(DEFAULT_HTTP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.logger = logger != null ? logger : Logger.getLogger(UpdateService.class.getName());
        this.allowInsecureHttpForTesting = allowInsecureHttpForTesting;
        this.auditRepository = auditRepository;
    }

    public String getCurrentVersion() {
        String ver = currentVersionSupplier.get();
        return ver != null && !ver.isBlank() ? ver : "unknown";
    }

    public VersionCheckResult getLastCheckResult() {
        return lastResult.get();
    }

    private boolean isSecureUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String lower = url.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("https://")) {
            return true;
        }
        // Only this instance's flag decides. The constructor captures the
        // static seam once; re-reading it here would let anything that
        // switches the static on disable HTTPS enforcement for every
        // service already built, including one constructed to require it.
        //
        // And the seam only ever reaches a loopback address. A test needs its
        // own mock server over http; nothing needs plain http to a remote
        // host, and allowing it would let a switched-on seam expose the real
        // download path to a network attacker.
        if (allowInsecureHttpForTesting && lower.startsWith("http://")) {
            try {
                String host = URI.create(url.trim()).getHost();
                return host != null
                        && (host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1"));
            } catch (IllegalArgumentException malformed) {
                return false;
            }
        }
        return false;
    }

    private void checkResponseSecurity(HttpResponse<?> response) throws IOException {
        if (!isSecureUrl(response.uri().toString())) {
            throw new IOException("Insecure HTTP response target rejected: " + response.uri());
        }
        int status = response.statusCode();
        if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
            Optional<String> location = response.headers().firstValue("Location");
            if (location.isPresent() && !isSecureUrl(location.get())) {
                throw new IOException("Insecure HTTP redirect target rejected: " + location.get());
            }
        }
    }

    private static String readBoundedString(InputStream in, long maxBytes) throws IOException {
        try (in) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buf)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("Response body exceeded maximum allowed limit of " + maxBytes + " bytes");
                }
                baos.write(buf, 0, read);
            }
            return baos.toString(StandardCharsets.UTF_8);
        }
    }

    public static boolean isReadablePluginJar(File file) {
        if (file == null || !file.exists() || file.length() == 0) {
            return false;
        }
        try (JarFile jar = new JarFile(file)) {
            JarEntry entry = jar.getJarEntry("plugin.yml");
            if (entry == null) {
                entry = jar.getJarEntry("paper-plugin.yml");
            }
            if (entry == null) {
                return false;
            }
            try (InputStream in = jar.getInputStream(entry)) {
                byte[] header = in.readNBytes(512);
                if (header.length == 0) {
                    return false;
                }
                String content = new String(header, StandardCharsets.UTF_8);
                return content.contains("name:") || content.contains("main:");
            }
        } catch (Exception e) {
            return false;
        }
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

            if (!isSecureUrl(endpoint)) {
                logger.warning("Rejecting insecure HTTP update API URL: " + endpoint);
                VersionCheckResult res = VersionCheckResult.unknown(runningVersion, "Insecure HTTP URL rejected");
                lastResult.set(res);
                return res;
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "SocialBlueprint-UpdateChecker")
                    .timeout(DEFAULT_HTTP_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            checkResponseSecurity(response);
            int status = response.statusCode();

            if (status == 403 || status == 429) {
                logger.warning("Could not check for updates from GitHub: rate limit reached (HTTP " + status + ")");
                VersionCheckResult res = VersionCheckResult.unknown(runningVersion, "Rate limited (HTTP " + status + ")");
                lastResult.set(res);
                return res;
            }
            if (status == 404) {
                logger.warning(PlainTextComponentSerializer.plainText().serialize(messageRegistry.render(
                        snapshot, "updater.repository-not-found", Map.of("repository", repo))));
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

            long cl = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (cl > MAX_METADATA_BYTES) {
                logger.warning("Update metadata response Content-Length (" + cl + ") exceeds limit of " + MAX_METADATA_BYTES + " bytes");
                VersionCheckResult res = VersionCheckResult.unknown(runningVersion, "Metadata response too large");
                lastResult.set(res);
                return res;
            }

            String body = readBoundedString(response.body(), MAX_METADATA_BYTES);
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
            File tempFile = null;
            try {
                // 1. Ensure latest release metadata is available
                // Always fresh: the cached result can predate a release published
                // after startup, and this runs off the main thread anyway.
                VersionCheckResult check = performCheckInternal();

                if (check.releaseInfo() == null) {
                    sendToSender(sender, currentSnapshot, "updater.failed", Map.of("error", "No release information available"));
                    return null;
                }

                ReleaseInfo release = check.releaseInfo();
                Optional<ReleaseAsset> optJar = release.findJarAsset();
                if (optJar.isEmpty()) {
                    logger.warning("No jar asset found in GitHub release " + release.tagName());
                    sendToSender(sender, currentSnapshot, "updater.failed", Map.of("error", "No jar asset found in release " + release.tagName()));
                    return null;
                }
                ReleaseAsset jarAsset = optJar.get();

                long maxDownloadBytes = currentSnapshot.config().update().maxDownloadBytes();
                if (jarAsset.size() > maxDownloadBytes) {
                    logger.warning("Rejecting update download: advertised asset size (" + jarAsset.size()
                            + " bytes) exceeds maximum configured limit (" + maxDownloadBytes + " bytes).");
                    sendToSender(sender, currentSnapshot, "updater.failed", Map.of("error", "Asset exceeds maximum allowed size"));
                    return null;
                }

                if (!isSecureUrl(jarAsset.downloadUrl())) {
                    logger.warning("Rejecting insecure HTTP download URL for asset " + jarAsset.name() + ": " + jarAsset.downloadUrl());
                    sendToSender(sender, currentSnapshot, "updater.failed", Map.of("error", "Insecure HTTP download URL rejected"));
                    return null;
                }

                // 2. Locate expected checksum
                Optional<String> optExpectedHash = findExpectedChecksum(release, jarAsset);
                if (optExpectedHash.isEmpty()) {
                    logger.warning("No SHA-256 checksum found for release asset " + jarAsset.name() + ". Aborting update for security.");
                    sendToSender(sender, currentSnapshot, "updater.failed", Map.of("error", "No SHA-256 checksum found for release"));
                    return null;
                }
                String expectedHash = optExpectedHash.get();

                // 3. Download release asset into temp file outside update folder with streamed hard byte cap
                tempFile = File.createTempFile("socialblueprint-update-", ".tmp");

                HttpRequest jarRequest = HttpRequest.newBuilder()
                        .uri(URI.create(jarAsset.downloadUrl()))
                        .header("Accept", "application/octet-stream")
                        .header("User-Agent", "SocialBlueprint-UpdateChecker")
                        .timeout(DEFAULT_DOWNLOAD_TIMEOUT)
                        .GET()
                        .build();

                HttpResponse<InputStream> jarResponse = httpClient.send(jarRequest, HttpResponse.BodyHandlers.ofInputStream());
                checkResponseSecurity(jarResponse);
                if (jarResponse.statusCode() != 200) {
                    logger.warning("Failed to download jar asset from GitHub: HTTP " + jarResponse.statusCode());
                    sendToSender(sender, currentSnapshot, "updater.failed", Map.of("error", "Download failed: HTTP " + jarResponse.statusCode()));
                    return null;
                }

                long cl = jarResponse.headers().firstValueAsLong("Content-Length").orElse(-1L);
                if (cl > maxDownloadBytes) {
                    logger.warning("Download Content-Length (" + cl + " bytes) exceeds maximum configured limit of " + maxDownloadBytes + " bytes");
                    sendToSender(sender, currentSnapshot, "updater.failed", Map.of("error", "Asset exceeds maximum allowed size"));
                    return null;
                }

                MessageDigest md;
                try {
                    md = MessageDigest.getInstance("SHA-256");
                } catch (NoSuchAlgorithmException e) {
                    throw new IllegalStateException("SHA-256 MessageDigest not available", e);
                }

                long totalBytes = 0;
                byte[] buf = new byte[8192];
                try (InputStream in = jarResponse.body();
                     FileOutputStream fos = new FileOutputStream(tempFile)) {
                    int read;
                    while ((read = in.read(buf)) != -1) {
                        totalBytes += read;
                        if (totalBytes > maxDownloadBytes) {
                            throw new IOException("Download exceeded maximum configured limit of " + maxDownloadBytes + " bytes");
                        }
                        md.update(buf, 0, read);
                        fos.write(buf, 0, read);
                    }
                    fos.flush();
                }

                if (totalBytes == 0) {
                    logger.warning("Downloaded empty asset from GitHub");
                    sendToSender(sender, currentSnapshot, "updater.failed", Map.of("error", "Empty download"));
                    return null;
                }

                // 4. Verify checksum BEFORE staging to plugins/update/ (SB-074, T-082)
                String computedHash = ChecksumVerifier.bytesToHex(md.digest());
                if (!computedHash.equalsIgnoreCase(expectedHash)) {
                    logger.severe("HOSTILE / CORRUPTED UPDATE: Checksum mismatch for " + jarAsset.name()
                            + "! Expected: " + expectedHash + ", computed: " + computedHash
                            + ". Aborting update. No files written to disk.");
                    sendToSender(sender, currentSnapshot, "updater.checksum-mismatch", Map.of());
                    return null;
                }

                // 5. Verify jar structure and plugin metadata on temp file (Finding 3)
                if (!isReadablePluginJar(tempFile)) {
                    logger.warning("Downloaded asset is not a valid plugin JAR or lacks required plugin metadata: " + jarAsset.name());
                    sendToSender(sender, currentSnapshot, "updater.failed", Map.of("error", "Invalid plugin jar"));
                    return null;
                }

                // 6. Checksum and JAR verified: write into plugins/update/
                File updateFolder = updateFolderSupplier.get();
                if (updateFolder == null) {
                    logger.warning("Update folder could not be determined. Aborting update.");
                    sendToSender(sender, currentSnapshot, "updater.failed", Map.of("error", "Update folder not available"));
                    return null;
                }
                if (!updateFolder.exists()) {
                    updateFolder.mkdirs();
                }

                File currentJar = currentJarSupplier.get();
                String targetFileName = (currentJar != null && currentJar.getName().endsWith(".jar"))
                        ? currentJar.getName()
                        : jarAsset.name();

                File targetFile = new File(updateFolder, targetFileName);
                try {
                    Files.move(tempFile.toPath(), targetFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tempFile.toPath(), targetFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                }

                logger.info("Successfully downloaded and verified SocialBlueprint update to "
                        + targetFile.getAbsolutePath() + ". Server restart is required to apply.");
                sendToSender(sender, currentSnapshot, "updater.restart-required", Map.of());
                return release;
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                logger.warning("Update download failed: " + msg);
                sendToSender(sender, currentSnapshot, "updater.failed", Map.of("error", msg));
                return null;
            } finally {
                if (tempFile != null) {
                    try {
                        Files.deleteIfExists(tempFile.toPath());
                    } catch (Exception ignored) {}
                }
            }
        }, asyncExecutor).thenCompose(release -> {
            if (release == null) {
                return CompletableFuture.completedFuture(false);
            }
            if (auditRepository == null) {
                return CompletableFuture.completedFuture(true);
            }
            PlayerId actor = (sender instanceof Player p)
                    ? PlayerId.of(p.getUniqueId())
                    : PlayerId.CONSOLE;
            String runningVersion = getCurrentVersion();
            AuditEvent event = new AuditEvent(
                    actor,
                    "update",
                    NonPlayerTarget.of("update"),
                    runningVersion,
                    release.tagName(),
                    Instant.now()
            );
            return auditRepository.saveAsync(event)
                    .handle((audit, auditEx) -> {
                        if (auditEx != null) {
                            logger.severe("[SocialBlueprint] Update was staged, but audit logging failed: " + auditEx.getMessage());
                        }
                        return true;
                    });
        }).whenComplete((res, ex) -> downloadInProgress.set(false));
    }

    private Optional<String> findExpectedChecksum(ReleaseInfo release, ReleaseAsset jarAsset) {
        // A. Companion asset <jar-name>.sha256 or <jar-name>.sha256sum
        String expectedAssetName1 = jarAsset.name() + ".sha256";
        String expectedAssetName2 = jarAsset.name() + ".sha256sum";
        for (ReleaseAsset asset : release.assets()) {
            if (asset.name().equalsIgnoreCase(expectedAssetName1) || asset.name().equalsIgnoreCase(expectedAssetName2)) {
                if (!isSecureUrl(asset.downloadUrl())) {
                    logger.warning("Rejecting insecure HTTP checksum asset URL: " + asset.downloadUrl());
                    continue;
                }
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(asset.downloadUrl()))
                            .header("User-Agent", "SocialBlueprint-UpdateChecker")
                            .timeout(DEFAULT_HTTP_TIMEOUT)
                            .GET()
                            .build();
                    HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
                    checkResponseSecurity(resp);
                    if (resp.statusCode() == 200) {
                        long cl = resp.headers().firstValueAsLong("Content-Length").orElse(-1L);
                        if (cl <= MAX_CHECKSUM_BYTES) {
                            String text = readBoundedString(resp.body(), MAX_CHECKSUM_BYTES);
                            Optional<String> hash = ChecksumVerifier.extractHashFromText(text, jarAsset.name());
                            if (hash.isPresent()) {
                                return hash;
                            }
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
                if (!isSecureUrl(asset.downloadUrl())) {
                    logger.warning("Rejecting insecure HTTP checksum asset URL: " + asset.downloadUrl());
                    continue;
                }
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(asset.downloadUrl()))
                            .header("User-Agent", "SocialBlueprint-UpdateChecker")
                            .timeout(DEFAULT_HTTP_TIMEOUT)
                            .GET()
                            .build();
                    HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
                    checkResponseSecurity(resp);
                    if (resp.statusCode() == 200) {
                        long cl = resp.headers().firstValueAsLong("Content-Length").orElse(-1L);
                        if (cl <= MAX_CHECKSUM_BYTES) {
                            String text = readBoundedString(resp.body(), MAX_CHECKSUM_BYTES);
                            Optional<String> hash = ChecksumVerifier.extractHashFromText(text, jarAsset.name());
                            if (hash.isPresent()) {
                                return hash;
                            }
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

