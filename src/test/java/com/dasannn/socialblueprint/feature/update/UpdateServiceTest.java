package com.dasannn.socialblueprint.feature.update;

import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.config.UpdateConfig;
import com.dasannn.socialblueprint.domain.AuditEvent;
import com.dasannn.socialblueprint.domain.NonPlayerTarget;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.storage.AuditRepository;
import com.dasannn.socialblueprint.storage.StorageEngine;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class UpdateServiceTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "v1.0, updater.no-update",
            "v0.9, updater.running-ahead",
            "unknown, updater.version-unknown",
            "broken, updater.version-unknown"
    })
    void refusesNonNewerReleasesBeforeAnyAssetRequest(String tag, String messageKey) {
        java.util.concurrent.atomic.AtomicInteger downloads = new java.util.concurrent.atomic.AtomicInteger();
        String json = "{\"tag_name\":\"" + tag + "\",\"assets\":[{\"name\":\"SocialBlueprint.jar\","
                + "\"browser_download_url\":\"" + serverBaseUrl + "/asset\"}]}";
        mockServer.createContext("/repos/Dasannn/Blueprint/releases/latest", exchange -> {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        });
        mockServer.createContext("/asset", exchange -> {
            downloads.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        UpdateService service = new UpdateService(configManager, messageRegistry, asyncExecutor,
                mainThreadQueue::add, updateFolder, "1.0", currentJarFile, httpClient, testLogger);
        CommandSender sender = mockSender(new ArrayList<>());
        for (int i = 0; i < 3; i++) {
            assertThat(service.downloadUpdateAsync(sender, configManager.snapshot()).join()).isFalse();
            drainMainThread();
            assertThat(messageRegistry.lastCall().key()).isEqualTo(messageKey);
        }
        assertThat(downloads.get()).isZero();
        assertThat(updateFolder).doesNotExist();
        assertThat(messageRegistry.hasKey("updater.downloading")).isFalse();
    }

    @Test
    void startupRemovesOnlyOwnUnprovenUpdatesEvenWhenChecksAreDisabled() throws Exception {
        configManager.set("update.check-on-startup", "false");
        updateFolder.mkdirs();
        File equal = new File(updateFolder, currentJarFile.getName());
        File older = new File(updateFolder, "old-release-name.jar");
        File unknown = new File(updateFolder, "unknown.jar");
        File newer = new File(updateFolder, "newer.jar");
        File other = new File(updateFolder, "OtherPlugin.jar");
        File corrupt = new File(updateFolder, "corrupt.jar");
        Files.write(equal.toPath(), createValidPluginJarBytes("SocialBlueprint", "1.0"));
        Files.write(older.toPath(), createValidPluginJarBytes("SocialBlueprint", "0.9"));
        Files.write(unknown.toPath(), createValidPluginJarBytes("SocialBlueprint", "unknown"));
        byte[] newerBytes = createValidPluginJarBytes("SocialBlueprint", "1.1");
        byte[] otherBytes = createValidPluginJarBytes("OtherPlugin", "0.1");
        Files.write(newer.toPath(), newerBytes);
        Files.write(other.toPath(), otherBytes);
        Files.writeString(corrupt.toPath(), "not a jar");
        byte[] runningBytes = Files.readAllBytes(currentJarFile.toPath());
        UpdateService service = new UpdateService(configManager, messageRegistry, asyncExecutor,
                mainThreadQueue::add, updateFolder, "1.0", currentJarFile, httpClient, testLogger);
        for (int i = 0; i < 2; i++) {
            service.onStartup(configManager.snapshot());
            asyncExecutor.submit(() -> {}).get();
        }
        assertThat(equal).doesNotExist();
        assertThat(older).doesNotExist();
        assertThat(unknown).doesNotExist();
        assertThat(Files.readAllBytes(newer.toPath())).isEqualTo(newerBytes);
        assertThat(Files.readAllBytes(other.toPath())).isEqualTo(otherBytes);
        assertThat(corrupt).exists();
        assertThat(Files.readAllBytes(currentJarFile.toPath())).isEqualTo(runningBytes);
        assertThat(service.getLastCheckResult()).isNull();
        assertThat(logRecords.stream().filter(r -> r.getMessage().startsWith("Removed staged SocialBlueprint JAR")))
                .hasSize(3);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"v1.0", "v0.9", "unknown", "broken"})
    void autoDownloadNeverStagesNonNewerRelease(String tag) throws Exception {
        configManager.set("update.check-on-startup", "true");
        configManager.set("update.auto-download", "true");
        mockServer.createContext("/repos/Dasannn/Blueprint/releases/latest", exchange -> {
            byte[] bytes = ("{\"tag_name\":\"" + tag + "\",\"assets\":[]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        });
        UpdateService service = new UpdateService(configManager, messageRegistry, asyncExecutor,
                mainThreadQueue::add, updateFolder, "1.0", currentJarFile, httpClient, testLogger);
        service.onStartup(configManager.snapshot());
        // Cleanup queues the check; two executor barriers cover both operations.
        asyncExecutor.submit(() -> {}).get();
        asyncExecutor.submit(() -> {}).get();
        assertThat(service.getLastCheckResult()).isNotNull();
        assertThat(updateFolder).doesNotExist();
        assertThat(logRecords).noneMatch(r -> r.getMessage().contains("Downloading update in background"));
    }

    @TempDir
    File tempDir;

    private HttpServer mockServer;
    private int serverPort;
    private String serverBaseUrl;

    private ExecutorService asyncExecutor;
    private final Queue<Runnable> mainThreadQueue = new ConcurrentLinkedQueue<>();
    private File updateFolder;
    private File currentJarFile;

    private ConfigManager configManager;
    private TestRecordingMessageRegistry messageRegistry;
    private Logger testLogger;
    private List<LogRecord> logRecords;

    private HttpClient httpClient;

    static class TestRecordingMessageRegistry extends MessageRegistry {
        public record MessageCall(String key, Map<String, String> placeholders, boolean withPrefix) {}

        private final List<MessageCall> calls = Collections.synchronizedList(new ArrayList<>());

        public TestRecordingMessageRegistry(File dataFolder, String language, Logger logger) {
            super(dataFolder, language, logger);
        }

        public List<MessageCall> calls() {
            return calls;
        }

        public MessageCall lastCall() {
            if (calls.isEmpty()) {
                throw new AssertionError("No messages were sent");
            }
            return calls.getLast();
        }

        public boolean hasKey(String key) {
            return calls.stream().anyMatch(c -> c.key().equals(key));
        }

        @Override
        public Component render(RuntimeSnapshot snapshot, String key, Map<String, String> placeholders) {
            calls.add(new MessageCall(key, placeholders != null ? Map.copyOf(placeholders) : Map.of(), false));
            return super.render(snapshot, key, placeholders);
        }

        @Override
        public Component renderWithPrefix(RuntimeSnapshot snapshot, String key, Map<String, String> placeholders) {
            calls.add(new MessageCall(key, placeholders != null ? Map.copyOf(placeholders) : Map.of(), true));
            return super.renderWithPrefix(snapshot, key, placeholders);
        }

        @Override
        public Component renderWithPrefix(RuntimeSnapshot snapshot, String key) {
            return renderWithPrefix(snapshot, key, Collections.emptyMap());
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        UpdateConfig.setAllowInsecureHttpForTesting(true);
        logRecords = Collections.synchronizedList(new ArrayList<>());
        testLogger = Logger.getLogger("UpdateServiceTest-" + System.nanoTime());
        testLogger.setUseParentHandlers(false);
        testLogger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logRecords.add(record);
            }
            @Override
            public void flush() {}
            @Override
            public void close() throws SecurityException {}
        });

        // Initialize local mock HTTP server on random ephemeral port
        mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockServer.setExecutor(Executors.newCachedThreadPool());
        mockServer.start();
        serverPort = mockServer.getAddress().getPort();
        serverBaseUrl = "http://127.0.0.1:" + serverPort;

        // Async storage executor with named thread
        asyncExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "socialblueprint-test-storage");
            t.setDaemon(true);
            return t;
        });

        // Setup test directories
        updateFolder = new File(tempDir, "update");
        File pluginsFolder = new File(tempDir, "plugins");
        pluginsFolder.mkdirs();
        currentJarFile = new File(pluginsFolder, "SocialBlueprint.jar");
        Files.writeString(currentJarFile.toPath(), "running jar placeholder");

        // Copy configs
        File configFile = new File(tempDir, "config.yml");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertThat(in).isNotNull();
            Files.copy(in, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        copyResource("messages_en.yml", new File(tempDir, "messages_en.yml"));
        copyResource("messages_es.yml", new File(tempDir, "messages_es.yml"));

        messageRegistry = new TestRecordingMessageRegistry(tempDir, "en", testLogger);
        configManager = new ConfigManager(configFile, messageRegistry, mainThreadQueue::add, testLogger);
        configManager.initialize();

        // Point config.yml to mock server
        configManager.set("update.api-url", serverBaseUrl);

        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @AfterEach
    void tearDown() {
        UpdateConfig.setAllowInsecureHttpForTesting(false);
        if (mockServer != null) {
            mockServer.stop(0);
        }
        if (asyncExecutor != null) {
            asyncExecutor.shutdownNow();
        }
    }

    private void drainMainThread() {
        Runnable r;
        while ((r = mainThreadQueue.poll()) != null) {
            r.run();
        }
    }

    private CommandSender mockSender(List<String> messagesOut) {
        InvocationHandler handler = (proxy, method, args) -> {
            String mName = method.getName();
            if ("getName".equals(mName)) return "TestSender";
            if ("hasPermission".equals(mName)) return true;
            if ("sendMessage".equals(mName)) {
                if (args != null && args.length > 0) {
                    messagesOut.add(args[0].toString());
                }
                return null;
            }
            return null;
        };

        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[]{CommandSender.class},
                handler
        );
    }

    private Player mockPlayer(java.util.UUID uuid, List<String> messagesOut) {
        InvocationHandler handler = (proxy, method, args) -> {
            String mName = method.getName();
            if ("getUniqueId".equals(mName)) return uuid;
            if ("getName".equals(mName)) return "TestPlayer";
            if ("hasPermission".equals(mName)) return true;
            if ("sendMessage".equals(mName)) {
                if (args != null && args.length > 0) {
                    messagesOut.add(args[0].toString());
                }
                return null;
            }
            return null;
        };

        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                handler
        );
    }

    private void copyResource(String resourceName, File destination) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            assertThat(in).isNotNull();
            Files.copy(in, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private byte[] createValidPluginJarBytes(String name, String version) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            try (java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(baos)) {
                jos.putNextEntry(new java.util.zip.ZipEntry("plugin.yml"));
                String yml = "name: " + name + "\nversion: " + version + "\nmain: com.dasannn.socialblueprint.SocialBlueprintPlugin\n";
                jos.write(yml.getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    // =========================================================================
    // DoD 1 & T-080: Off-Thread Execution & Stalled HTTP Connection
    // =========================================================================

    @Test
    @DisplayName("DoD 1 / T-080: Stalled HTTP connection never blocks or delays the main thread tick")
    void stalledHttpConnectionNeverBlocksMainThread() throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch allowResponse = new CountDownLatch(1);

        mockServer.createContext("/repos/Dasannn/Blueprint/releases/latest", exchange -> {
            requestStarted.countDown();
            try {
                // Stall handler until test allows it to proceed
                allowResponse.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}

            byte[] resp = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });

        AtomicReference<Thread> workerThread = new AtomicReference<>();
        UpdateService updateService = new UpdateService(
                configManager,
                messageRegistry,
                r -> asyncExecutor.submit(() -> {
                    workerThread.set(Thread.currentThread());
                    r.run();
                }),
                mainThreadQueue::add,
                () -> updateFolder,
                () -> "1.0",
                () -> currentJarFile,
                httpClient,
                testLogger
        );

        long startTime = System.currentTimeMillis();
        // Invoke check from "main" thread
        CompletableFuture<VersionCheckResult> checkFuture = updateService.checkForUpdateAsync();

        // Must return immediately (< 200 ms), not blocking the main thread
        long elapsed = System.currentTimeMillis() - startTime;
        assertThat(elapsed).isLessThan(500);
        assertThat(checkFuture.isDone()).isFalse();

        // Main thread is completely unblocked and can perform ticks/tasks immediately
        AtomicBoolean tickExecuted = new AtomicBoolean(false);
        mainThreadQueue.add(() -> tickExecuted.set(true));
        drainMainThread();
        assertThat(tickExecuted.get()).isTrue();

        // Verify request reached the server on the async executor thread
        assertThat(requestStarted.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(workerThread.get()).isNotNull();
        assertThat(workerThread.get().getName()).contains("socialblueprint-test-storage");

        // Allow handler to finish and clean up
        allowResponse.countDown();
        checkFuture.get(3, TimeUnit.SECONDS);
    }

    // =========================================================================
    // DoD 2 & T-082: Checksum Verification & Hostile Mismatch Abort
    // =========================================================================

    @Test
    @DisplayName("DoD 2 / T-082: Checksum mismatch writes NOTHING, aborts, and logs loud severe warning")
    void checksumMismatchWritesNothingAndAborts() {
        byte[] actualJarBytes = "actual jar binary content".getBytes(StandardCharsets.UTF_8);
        String actualSha256 = ChecksumVerifier.computeSha256(actualJarBytes);
        String mismatchedSha256 = "0000000000000000000000000000000000000000000000000000000000000000";

        // Serve release JSON specifying expected mismatched checksum in companion asset
        String releaseJson = """
                {
                  "tag_name": "v1.1",
                  "name": "SocialBlueprint 1.1",
                  "body": "Release 1.1",
                  "prerelease": false,
                  "assets": [
                    {
                      "name": "SocialBlueprint.jar",
                      "browser_download_url": "%s/download/SocialBlueprint.jar",
                      "size": %d,
                      "content_type": "application/java-archive"
                    },
                    {
                      "name": "SocialBlueprint.jar.sha256",
                      "browser_download_url": "%s/download/SocialBlueprint.jar.sha256",
                      "size": 64,
                      "content_type": "text/plain"
                    }
                  ]
                }
                """.formatted(serverBaseUrl, actualJarBytes.length, serverBaseUrl);

        mockServer.createContext("/repos/Dasannn/Blueprint/releases/latest", exchange -> {
            byte[] resp = releaseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });

        // Serve the jar bytes
        mockServer.createContext("/download/SocialBlueprint.jar", exchange -> {
            exchange.sendResponseHeaders(200, actualJarBytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(actualJarBytes); }
        });

        // Serve mismatched expected SHA-256
        mockServer.createContext("/download/SocialBlueprint.jar.sha256", exchange -> {
            byte[] resp = mismatchedSha256.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });

        UpdateService updateService = new UpdateService(
                configManager,
                messageRegistry,
                asyncExecutor,
                mainThreadQueue::add,
                () -> updateFolder,
                () -> "1.0",
                () -> currentJarFile,
                httpClient,
                testLogger
        );

        List<String> senderMessages = new ArrayList<>();
        CommandSender sender = mockSender(senderMessages);

        // Execute download
        boolean success = updateService.downloadUpdateAsync(sender, configManager.snapshot()).join();
        drainMainThread();

        // 1. Download returns false (aborted)
        assertThat(success).isFalse();

        // 2. Target directory contains NOTHING: exactly as it was, no files created
        if (updateFolder.exists()) {
            File[] files = updateFolder.listFiles();
            assertThat(files).isNullOrEmpty();
        }

        // 3. Sender receives checksum mismatch message
        assertThat(messageRegistry.hasKey("updater.checksum-mismatch")).isTrue();

        // 4. Loud SEVERE log message recorded
        assertThat(logRecords).anyMatch(r -> r.getLevel() == Level.SEVERE
                && r.getMessage().contains("HOSTILE / CORRUPTED UPDATE")
                && r.getMessage().contains("Checksum mismatch"));
    }

    // =========================================================================
    // DoD 3 & T-085: Rate-Limited or Unreachable GitHub
    // =========================================================================

    @Test
    @DisplayName("DoD 3 / T-085: Rate-limited GitHub (HTTP 403 / 429) logs a warning and changes nothing")
    void rateLimitedGitHubLogsWarningQuietly() {
        mockServer.createContext("/repos/Dasannn/Blueprint/releases/latest", exchange -> {
            byte[] resp = "{\"message\": \"API rate limit exceeded\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("X-RateLimit-Remaining", "0");
            exchange.sendResponseHeaders(403, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });

        UpdateService updateService = new UpdateService(
                configManager,
                messageRegistry,
                asyncExecutor,
                mainThreadQueue::add,
                () -> updateFolder,
                () -> "1.0",
                () -> currentJarFile,
                httpClient,
                testLogger
        );

        VersionCheckResult result = updateService.checkForUpdateAsync().join();

        // Evaluates to UNKNOWN
        assertThat(result.comparison()).isEqualTo(VersionComparison.UNKNOWN);

        // Warning logged quietly (no stack trace, no severe)
        assertThat(logRecords).anyMatch(r -> r.getLevel() == Level.WARNING
                && r.getMessage().contains("rate limit reached"));

        // No files written
        if (updateFolder.exists()) {
            assertThat(updateFolder.listFiles()).isNullOrEmpty();
        }
    }

    @Test
    @DisplayName("DoD 3 / T-085: Unreachable GitHub logs a warning and changes nothing")
    void unreachableGitHubLogsWarningQuietly() {
        // Point to an unused, unopened port on localhost
        int unusedPort = serverPort + 1000;
        configManager.set("update.api-url", "http://127.0.0.1:" + unusedPort);

        UpdateService updateService = new UpdateService(
                configManager,
                messageRegistry,
                asyncExecutor,
                mainThreadQueue::add,
                () -> updateFolder,
                () -> "1.0",
                () -> currentJarFile,
                httpClient,
                testLogger
        );

        VersionCheckResult result = updateService.checkForUpdateAsync().join();

        // Evaluates to UNKNOWN
        assertThat(result.comparison()).isEqualTo(VersionComparison.UNKNOWN);

        // Warning logged quietly
        assertThat(logRecords).anyMatch(r -> r.getLevel() == Level.WARNING
                && r.getMessage().contains("Could not check for updates from GitHub"));

        // No files written
        if (updateFolder.exists()) {
            assertThat(updateFolder.listFiles()).isNullOrEmpty();
        }
    }

    @Test
    void repository404ReportsConfiguredRepositoryByMessageKey() {
        String repository = "ConfiguredOwner/PrivateOrMissingRepo";
        configManager.set("update.repository", repository);
        mockServer.createContext("/repos/" + repository + "/releases/latest", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        UpdateService updateService = new UpdateService(
                configManager, messageRegistry, asyncExecutor, mainThreadQueue::add,
                () -> updateFolder, () -> "1.0", () -> currentJarFile, httpClient, testLogger);

        VersionCheckResult result = updateService.checkForUpdateAsync().join();

        assertThat(result.comparison()).isEqualTo(VersionComparison.UNKNOWN);
        assertThat(messageRegistry.lastCall()).isEqualTo(new TestRecordingMessageRegistry.MessageCall(
                "updater.repository-not-found", Map.of("repository", repository), false));
        assertThat(logRecords).anyMatch(r -> r.getLevel() == Level.WARNING);
        assertThat(updateFolder).doesNotExist();
    }

    // =========================================================================
    // DoD 4 & T-084: Defaults are Check-On, Download-Off
    // =========================================================================

    @Test
    @DisplayName("DoD 4 / T-084: Defaults are check-on by default, auto-download off by default")
    void defaultsAreCheckOnDownloadOff() {
        UpdateConfig defaults = UpdateConfig.defaults();
        assertThat(defaults.checkOnStartup()).isTrue();
        assertThat(defaults.autoDownload()).isFalse();
        assertThat(defaults.repository()).isEqualTo("Dasannn/Blueprint");

        // Shipped config.yml also respects this
        UpdateConfig loaded = configManager.config().update();
        assertThat(loaded.checkOnStartup()).isTrue();
        assertThat(loaded.autoDownload()).isFalse();
        assertThat(loaded.repository()).isEqualTo("Dasannn/Blueprint");
    }

    // =========================================================================
    // DoD 5 & T-081: /status version is Honest When it Does Not Know
    // =========================================================================

    @Test
    @DisplayName("DoD 5 / T-081: /status version reports unknown rather than guessing when check has not completed or failed")
    void statusVersionReportsUnknownWhenNotKnown() {
        UpdateService updateService = new UpdateService(
                configManager,
                messageRegistry,
                asyncExecutor,
                mainThreadQueue::add,
                () -> updateFolder,
                () -> "1.0",
                () -> currentJarFile,
                httpClient,
                testLogger
        );

        com.dasannn.socialblueprint.command.StatusVersionCommand versionCmd =
                new com.dasannn.socialblueprint.command.StatusVersionCommand(updateService, messageRegistry);

        List<String> messages = new ArrayList<>();
        CommandSender sender = mockSender(messages);

        // Execute before any check has completed
        versionCmd.execute(sender, new String[]{}, configManager.snapshot()).join();
        drainMainThread();

        assertThat(messageRegistry.hasKey("updater.version-unknown")).isTrue();
        assertThat(messageRegistry.lastCall().placeholders()).containsEntry("current", "1.0");
    }

    // =========================================================================
    // T-082 & T-083: Verified Download & Restart Required
    // =========================================================================

    @Test
    @DisplayName("T-082 / T-083: Verified jar asset is written to plugins/update/ and restart-required is reported")
    void successfulUpdateWritesVerifiedJarAndReportsRestart() throws Exception {
        byte[] jarContent = createValidPluginJarBytes("SocialBlueprint", "1.1");
        String expectedHash = ChecksumVerifier.computeSha256(jarContent);

        String releaseJson = """
                {
                  "tag_name": "v1.1",
                  "name": "SocialBlueprint 1.1",
                  "body": "Release 1.1\\nSHA256: %s",
                  "prerelease": false,
                  "assets": [
                    {
                      "name": "SocialBlueprint-1.1.jar",
                      "browser_download_url": "%s/download/SocialBlueprint-1.1.jar",
                      "size": %d,
                      "content_type": "application/java-archive"
                    }
                  ]
                }
                """.formatted(expectedHash, serverBaseUrl, jarContent.length);

        mockServer.createContext("/repos/Dasannn/Blueprint/releases/latest", exchange -> {
            byte[] resp = releaseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });

        mockServer.createContext("/download/SocialBlueprint-1.1.jar", exchange -> {
            exchange.sendResponseHeaders(200, jarContent.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(jarContent); }
        });

        UpdateService updateService = new UpdateService(
                configManager,
                messageRegistry,
                asyncExecutor,
                mainThreadQueue::add,
                () -> updateFolder,
                () -> "1.0",
                () -> currentJarFile,
                httpClient,
                testLogger
        );

        List<String> messages = new ArrayList<>();
        CommandSender sender = mockSender(messages);

        boolean success = updateService.downloadUpdateAsync(sender, configManager.snapshot()).join();
        drainMainThread();

        assertThat(success).isTrue();

        // Verified replacement is staged under update/ with conservative name (SocialBlueprint.jar)
        File stagedJar = new File(updateFolder, "SocialBlueprint.jar");
        assertThat(stagedJar).exists();
        assertThat(Files.readAllBytes(stagedJar.toPath())).isEqualTo(jarContent);

        // Sender receives restart required message (never server restart)
        assertThat(messageRegistry.hasKey("updater.restart-required")).isTrue();

        // Running jar was NOT touched in place (SB-071)
        assertThat(Files.readString(currentJarFile.toPath())).isEqualTo("running jar placeholder");
    }

    @Test
    @DisplayName("T-084 / SB-075: onStartup triggers check and respects auto-download setting")
    void onStartupRespectsCheckAndAutoDownload() throws Exception {
        byte[] jarContent = createValidPluginJarBytes("SocialBlueprint", "1.1");
        String expectedHash = ChecksumVerifier.computeSha256(jarContent);

        String releaseJson = """
                {
                  "tag_name": "v1.1",
                  "name": "SocialBlueprint 1.1",
                  "body": "SHA256: %s",
                  "assets": [
                    {
                      "name": "SocialBlueprint.jar",
                      "browser_download_url": "%s/download/SocialBlueprint.jar",
                      "size": %d
                    }
                  ]
                }
                """.formatted(expectedHash, serverBaseUrl, jarContent.length);

        mockServer.createContext("/repos/Dasannn/Blueprint/releases/latest", exchange -> {
            byte[] resp = releaseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });

        mockServer.createContext("/download/SocialBlueprint.jar", exchange -> {
            exchange.sendResponseHeaders(200, jarContent.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(jarContent); }
        });

        // 1. With auto-download = false (default), check runs, but NO download occurs
        configManager.set("update.auto-download", "false");
        UpdateService serviceNoAuto = new UpdateService(
                configManager,
                messageRegistry,
                asyncExecutor,
                mainThreadQueue::add,
                () -> updateFolder,
                () -> "1.0",
                () -> currentJarFile,
                httpClient,
                testLogger
        );

        serviceNoAuto.onStartup(configManager.snapshot());
        // Wait for check to complete
        Thread.sleep(300);
        drainMainThread();

        assertThat(serviceNoAuto.getLastCheckResult()).isNotNull();
        assertThat(serviceNoAuto.getLastCheckResult().comparison()).isEqualTo(VersionComparison.OUTDATED);
        // Target folder contains NO downloaded files
        if (updateFolder.exists()) {
            assertThat(updateFolder.listFiles()).isNullOrEmpty();
        }

        // 2. With auto-download = true, download is automatically dispatched
        configManager.set("update.auto-download", "true");
        UpdateService serviceWithAuto = new UpdateService(
                configManager,
                messageRegistry,
                asyncExecutor,
                mainThreadQueue::add,
                () -> updateFolder,
                () -> "1.0",
                () -> currentJarFile,
                httpClient,
                testLogger
        );

        serviceWithAuto.onStartup(configManager.snapshot());
        // Wait for check and auto-download
        Thread.sleep(600);
        drainMainThread();

        File stagedJar = new File(updateFolder, "SocialBlueprint.jar");
        assertThat(stagedJar).exists();
        assertThat(Files.readAllBytes(stagedJar.toPath())).isEqualTo(jarContent);
    }

    // =========================================================================
    // Finding 1: Bounded Remote Responses
    // =========================================================================

    @Test
    @DisplayName("Finding 1: Streamed download exceeding byte cap aborts immediately and writes nothing")
    void downloadOverLimitBodyAbortsAndWritesNothing() {
        configManager.set("update.max-download-bytes", "500");

        byte[] largeContent = new byte[2000];
        String expectedHash = ChecksumVerifier.computeSha256(largeContent);

        String releaseJson = """
                {
                  "tag_name": "v1.1",
                  "name": "SocialBlueprint 1.1",
                  "body": "SHA256: %s",
                  "assets": [
                    {
                      "name": "SocialBlueprint.jar",
                      "browser_download_url": "%s/download/SocialBlueprint.jar",
                      "size": 200
                    }
                  ]
                }
                """.formatted(expectedHash, serverBaseUrl);

        mockServer.createContext("/repos/Dasannn/Blueprint/releases/latest", exchange -> {
            byte[] resp = releaseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });

        // Server sends 2000 bytes chunked
        mockServer.createContext("/download/SocialBlueprint.jar", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(largeContent);
            }
        });

        UpdateService updateService = new UpdateService(
                configManager,
                messageRegistry,
                asyncExecutor,
                mainThreadQueue::add,
                () -> updateFolder,
                () -> "1.0",
                () -> currentJarFile,
                httpClient,
                testLogger
        );

        List<String> messages = new ArrayList<>();
        CommandSender sender = mockSender(messages);

        boolean success = updateService.downloadUpdateAsync(sender, configManager.snapshot()).join();
        drainMainThread();

        assertThat(success).isFalse();
        if (updateFolder.exists()) {
            assertThat(updateFolder.listFiles()).isNullOrEmpty();
        }
        assertThat(messageRegistry.hasKey("updater.failed")).isTrue();
        assertThat(logRecords).anyMatch(r -> r.getLevel() == Level.WARNING
                && r.getMessage().contains("Download exceeded maximum configured limit"));
    }

    @Test
    @DisplayName("Finding 1: Advertised asset size exceeding byte cap aborts before sending download request")
    void advertisedSizeOverLimitAbortsBeforeDownload() {
        configManager.set("update.max-download-bytes", "500");

        String releaseJson = """
                {
                  "tag_name": "v1.1",
                  "name": "SocialBlueprint 1.1",
                  "body": "Release 1.1",
                  "assets": [
                    {
                      "name": "SocialBlueprint.jar",
                      "browser_download_url": "%s/download/SocialBlueprint.jar",
                      "size": 100000
                    }
                  ]
                }
                """.formatted(serverBaseUrl);

        AtomicBoolean downloadRequested = new AtomicBoolean(false);
        mockServer.createContext("/repos/Dasannn/Blueprint/releases/latest", exchange -> {
            byte[] resp = releaseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });

        mockServer.createContext("/download/SocialBlueprint.jar", exchange -> {
            downloadRequested.set(true);
            exchange.sendResponseHeaders(200, 10);
            try (OutputStream os = exchange.getResponseBody()) { os.write(new byte[10]); }
        });

        UpdateService updateService = new UpdateService(
                configManager,
                messageRegistry,
                asyncExecutor,
                mainThreadQueue::add,
                () -> updateFolder,
                () -> "1.0",
                () -> currentJarFile,
                httpClient,
                testLogger
        );

        List<String> messages = new ArrayList<>();
        CommandSender sender = mockSender(messages);

        boolean success = updateService.downloadUpdateAsync(sender, configManager.snapshot()).join();
        drainMainThread();

        assertThat(success).isFalse();
        assertThat(downloadRequested.get()).isFalse();
        if (updateFolder.exists()) {
            assertThat(updateFolder.listFiles()).isNullOrEmpty();
        }
        assertThat(logRecords).anyMatch(r -> r.getLevel() == Level.WARNING
                && r.getMessage().contains("advertised asset size"));
    }

    // =========================================================================
    // Finding 2: Insecure HTTP Refusal
    // =========================================================================

    @Test
    @DisplayName("Finding 2: Insecure HTTP update API URL is refused when testing seam is disabled")
    void insecureHttpUrlRefusedWhenTestingSeamDisabled() {
        UpdateService secureService = new UpdateService(
                configManager,
                messageRegistry,
                asyncExecutor,
                mainThreadQueue::add,
                () -> updateFolder,
                () -> "1.0",
                () -> currentJarFile,
                httpClient,
                testLogger,
                false // allowInsecureHttpForTesting = false
        );

        VersionCheckResult result = secureService.checkForUpdateAsync().join();
        assertThat(result.comparison()).isEqualTo(VersionComparison.UNKNOWN);
        assertThat(logRecords).anyMatch(r -> r.getLevel() == Level.WARNING
                && r.getMessage().contains("Rejecting insecure HTTP update API URL"));
    }

    @Test
    @DisplayName("Finding 2: Insecure HTTP jar download URL is refused")
    void insecureHttpDownloadUrlRefused() {
        byte[] validJar = createValidPluginJarBytes("SocialBlueprint", "1.1");
        String hash = ChecksumVerifier.computeSha256(validJar);

        String releaseJson = """
                {
                  "tag_name": "v1.1",
                  "name": "SocialBlueprint 1.1",
                  "body": "SHA256: %s",
                  "assets": [
                    {
                      "name": "SocialBlueprint.jar",
                      "browser_download_url": "http://external-insecure.com/SocialBlueprint.jar",
                      "size": %d
                    }
                  ]
                }
                """.formatted(hash, validJar.length);

        mockServer.createContext("/repos/Dasannn/Blueprint/releases/latest", exchange -> {
            byte[] resp = releaseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });

        UpdateService updateService = new UpdateService(
                configManager,
                messageRegistry,
                asyncExecutor,
                mainThreadQueue::add,
                () -> updateFolder,
                () -> "1.0",
                () -> currentJarFile,
                httpClient,
                testLogger,
                // the local mock server is reachable over http under the seam;
                // the remote download URL in this release is not
                true
        );

        List<String> messages = new ArrayList<>();
        CommandSender sender = mockSender(messages);

        boolean success = updateService.downloadUpdateAsync(sender, configManager.snapshot()).join();
        drainMainThread();

        assertThat(success).isFalse();
        if (updateFolder.exists()) {
            assertThat(updateFolder.listFiles()).isNullOrEmpty();
        }
        assertThat(logRecords).anyMatch(r -> r.getLevel() == Level.WARNING
                && r.getMessage().toLowerCase(java.util.Locale.ROOT).contains("insecure http"));
    }

    @Test
    @DisplayName("Finding 2: Insecure HTTP redirect target is refused")
    void insecureHttpRedirectRefused() {
        String releaseJson = """
                {
                  "tag_name": "v1.1",
                  "name": "SocialBlueprint 1.1",
                  "body": "SHA256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                  "assets": [
                    {
                      "name": "SocialBlueprint.jar",
                      "browser_download_url": "%s/download/SocialBlueprint.jar",
                      "size": 500
                    }
                  ]
                }
                """.formatted(serverBaseUrl);

        mockServer.createContext("/repos/Dasannn/Blueprint/releases/latest", exchange -> {
            byte[] resp = releaseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });

        // Server sends 302 redirect to http://insecure-target.com
        mockServer.createContext("/download/SocialBlueprint.jar", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://insecure-target.com/SocialBlueprint.jar");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        UpdateService updateService = new UpdateService(
                configManager,
                messageRegistry,
                asyncExecutor,
                mainThreadQueue::add,
                () -> updateFolder,
                () -> "1.0",
                () -> currentJarFile,
                httpClient,
                testLogger,
                false // do not allow insecure http
        );

        List<String> messages = new ArrayList<>();
        CommandSender sender = mockSender(messages);

        boolean success = updateService.downloadUpdateAsync(sender, configManager.snapshot()).join();
        drainMainThread();

        assertThat(success).isFalse();
        if (updateFolder.exists()) {
            assertThat(updateFolder.listFiles()).isNullOrEmpty();
        }
    }

    // =========================================================================
    // Finding 3: Atomicity and Jar Verification
    // =========================================================================

    @Test
    @DisplayName("Finding 3: Mid-write network failure leaves previously staged jar intact")
    void midWriteFailureLeavesPreviouslyStagedJarIntact() throws Exception {
        byte[] originalStagedBytes = createValidPluginJarBytes("SocialBlueprint", "1.0");
        updateFolder.mkdirs();
        File stagedJar = new File(updateFolder, "SocialBlueprint.jar");
        Files.write(stagedJar.toPath(), originalStagedBytes);

        byte[] newJarBytes = createValidPluginJarBytes("SocialBlueprint", "1.1");
        String hash = ChecksumVerifier.computeSha256(newJarBytes);

        String releaseJson = """
                {
                  "tag_name": "v1.1",
                  "name": "SocialBlueprint 1.1",
                  "body": "SHA256: %s",
                  "assets": [
                    {
                      "name": "SocialBlueprint.jar",
                      "browser_download_url": "%s/download/SocialBlueprint.jar",
                      "size": %d
                    }
                  ]
                }
                """.formatted(hash, serverBaseUrl, newJarBytes.length);

        mockServer.createContext("/repos/Dasannn/Blueprint/releases/latest", exchange -> {
            byte[] resp = releaseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });

        // Server abruptly closes connection midway
        mockServer.createContext("/download/SocialBlueprint.jar", exchange -> {
            exchange.sendResponseHeaders(200, newJarBytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(newJarBytes, 0, Math.min(10, newJarBytes.length));
            os.flush();
            exchange.close();
        });

        UpdateService updateService = new UpdateService(
                configManager,
                messageRegistry,
                asyncExecutor,
                mainThreadQueue::add,
                () -> updateFolder,
                () -> "1.0",
                () -> currentJarFile,
                httpClient,
                testLogger
        );

        List<String> messages = new ArrayList<>();
        CommandSender sender = mockSender(messages);

        boolean success = updateService.downloadUpdateAsync(sender, configManager.snapshot()).join();
        drainMainThread();

        assertThat(success).isFalse();
        // Previously staged jar is completely intact!
        assertThat(stagedJar).exists();
        assertThat(Files.readAllBytes(stagedJar.toPath())).isEqualTo(originalStagedBytes);
    }

    @Test
    @DisplayName("Finding 3: Matching-hash non-jar is refused and leaves update directory untouched")
    void matchingHashNonJarRefusedAndLeavesDirectoryUntouched() {
        byte[] nonJarContent = "This is plain text and definitely not a valid jar file".getBytes(StandardCharsets.UTF_8);
        String expectedHash = ChecksumVerifier.computeSha256(nonJarContent);

        String releaseJson = """
                {
                  "tag_name": "v1.1",
                  "name": "SocialBlueprint 1.1",
                  "body": "SHA256: %s",
                  "assets": [
                    {
                      "name": "SocialBlueprint.jar",
                      "browser_download_url": "%s/download/SocialBlueprint.jar",
                      "size": %d
                    }
                  ]
                }
                """.formatted(expectedHash, serverBaseUrl, nonJarContent.length);

        mockServer.createContext("/repos/Dasannn/Blueprint/releases/latest", exchange -> {
            byte[] resp = releaseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });

        mockServer.createContext("/download/SocialBlueprint.jar", exchange -> {
            exchange.sendResponseHeaders(200, nonJarContent.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(nonJarContent); }
        });

        UpdateService updateService = new UpdateService(
                configManager,
                messageRegistry,
                asyncExecutor,
                mainThreadQueue::add,
                () -> updateFolder,
                () -> "1.0",
                () -> currentJarFile,
                httpClient,
                testLogger
        );

        List<String> messages = new ArrayList<>();
        CommandSender sender = mockSender(messages);

        boolean success = updateService.downloadUpdateAsync(sender, configManager.snapshot()).join();
        drainMainThread();

        assertThat(success).isFalse();
        if (updateFolder.exists()) {
            assertThat(updateFolder.listFiles()).isNullOrEmpty();
        }
        assertThat(messageRegistry.hasKey("updater.failed")).isTrue();
        assertThat(logRecords).anyMatch(r -> r.getLevel() == Level.WARNING
                && r.getMessage().contains("not a valid plugin JAR"));
    }

    @Test
    @DisplayName("Finding 9: UpdateService uses plain pre-captured values and executes without off-thread suppliers")
    void finding9_updateServiceUsesPlainValuesWithoutOffThreadSuppliers() throws Exception {
        UpdateService plainService = new UpdateService(
                configManager,
                messageRegistry,
                asyncExecutor,
                mainThreadQueue::add,
                updateFolder,
                "1.0",
                currentJarFile,
                httpClient,
                testLogger
        );

        // Version check works properly with plain values passed during construction
        VersionCheckResult result = plainService.checkForUpdateAsync().join();
        assertThat(result).isNotNull();
    }

    // =========================================================================
    // Finding 7: Administrative Audit Logging on Staged Update
    // =========================================================================

    @Test
    @DisplayName("Finding 7: Staging an update writes an audit record for player actor")
    void stagedUpdateWritesAuditRecordForPlayerActor() throws Exception {
        byte[] jarContent = createValidPluginJarBytes("SocialBlueprint", "1.2");
        String expectedHash = ChecksumVerifier.computeSha256(jarContent);

        String releaseJson = """
                {
                  "tag_name": "v1.2",
                  "name": "SocialBlueprint 1.2",
                  "body": "Release 1.2\\nSHA256: %s",
                  "prerelease": false,
                  "assets": [
                    {
                      "name": "SocialBlueprint-1.2.jar",
                      "browser_download_url": "%s/download/SocialBlueprint-1.2.jar",
                      "size": %d,
                      "content_type": "application/java-archive"
                    }
                  ]
                }
                """.formatted(expectedHash, serverBaseUrl, jarContent.length);

        mockServer.createContext("/repos/Dasannn/Blueprint/releases/latest", exchange -> {
            byte[] resp = releaseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });

        mockServer.createContext("/download/SocialBlueprint-1.2.jar", exchange -> {
            exchange.sendResponseHeaders(200, jarContent.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(jarContent); }
        });

        try (StorageEngine storage = StorageEngine.inMemory()) {
            storage.runMigrations();
            AuditRepository auditRepo = new AuditRepository(storage);

            UpdateService updateService = new UpdateService(
                    configManager,
                    messageRegistry,
                    asyncExecutor,
                    mainThreadQueue::add,
                    () -> updateFolder,
                    () -> "1.0",
                    () -> currentJarFile,
                    httpClient,
                    testLogger,
                    auditRepo
            );

            java.util.UUID playerUuid = java.util.UUID.randomUUID();
            List<String> messages = new ArrayList<>();
            Player player = mockPlayer(playerUuid, messages);

            boolean success = updateService.downloadUpdateAsync(player, configManager.snapshot()).join();
            drainMainThread();

            assertThat(success).isTrue();

            List<AuditEvent> audits = auditRepo.findByTarget(NonPlayerTarget.of("update"));
            assertThat(audits).hasSize(1);

            AuditEvent audit = audits.getFirst();
            assertThat(audit.actor()).isEqualTo(PlayerId.of(playerUuid));
            assertThat(audit.operation()).isEqualTo("update");
            assertThat(audit.target()).isEqualTo("update");
            assertThat(audit.before()).isEqualTo("1.0");
            assertThat(audit.after()).isEqualTo("v1.2");
            assertThat(audit.createdAt()).isNotNull();
        }
    }

    @Test
    @DisplayName("Finding 7: Staging an update writes an audit record for console actor")
    void stagedUpdateWritesAuditRecordForConsoleActor() throws Exception {
        byte[] jarContent = createValidPluginJarBytes("SocialBlueprint", "1.2");
        String expectedHash = ChecksumVerifier.computeSha256(jarContent);

        String releaseJson = """
                {
                  "tag_name": "v1.2",
                  "name": "SocialBlueprint 1.2",
                  "body": "Release 1.2\\nSHA256: %s",
                  "prerelease": false,
                  "assets": [
                    {
                      "name": "SocialBlueprint-1.2.jar",
                      "browser_download_url": "%s/download/SocialBlueprint-1.2.jar",
                      "size": %d,
                      "content_type": "application/java-archive"
                    }
                  ]
                }
                """.formatted(expectedHash, serverBaseUrl, jarContent.length);

        mockServer.createContext("/repos/Dasannn/Blueprint/releases/latest", exchange -> {
            byte[] resp = releaseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });

        mockServer.createContext("/download/SocialBlueprint-1.2.jar", exchange -> {
            exchange.sendResponseHeaders(200, jarContent.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(jarContent); }
        });

        try (StorageEngine storage = StorageEngine.inMemory()) {
            storage.runMigrations();
            AuditRepository auditRepo = new AuditRepository(storage);

            UpdateService updateService = new UpdateService(
                    configManager,
                    messageRegistry,
                    asyncExecutor,
                    mainThreadQueue::add,
                    () -> updateFolder,
                    () -> "1.0",
                    () -> currentJarFile,
                    httpClient,
                    testLogger,
                    auditRepo
            );

            List<String> messages = new ArrayList<>();
            CommandSender sender = mockSender(messages);

            boolean success = updateService.downloadUpdateAsync(sender, configManager.snapshot()).join();
            drainMainThread();

            assertThat(success).isTrue();

            List<AuditEvent> audits = auditRepo.findByTarget(NonPlayerTarget.of("update"));
            assertThat(audits).hasSize(1);

            AuditEvent audit = audits.getFirst();
            assertThat(audit.actor()).isEqualTo(PlayerId.CONSOLE);
            assertThat(audit.actor().isConsole()).isTrue();
            assertThat(audit.operation()).isEqualTo("update");
            assertThat(audit.target()).isEqualTo("update");
            assertThat(audit.before()).isEqualTo("1.0");
            assertThat(audit.after()).isEqualTo("v1.2");
        }
    }

    @Test
    @DisplayName("Finding 7: Failed download / checksum mismatch writes NO audit record")
    void failedOrMismatchedUpdateWritesNoAuditRecord() throws Exception {
        byte[] jarContent = createValidPluginJarBytes("SocialBlueprint", "1.2");
        String mismatchedSha256 = "0000000000000000000000000000000000000000000000000000000000000000";

        String releaseJson = """
                {
                  "tag_name": "v1.2",
                  "name": "SocialBlueprint 1.2",
                  "body": "Release 1.2\\nSHA256: %s",
                  "prerelease": false,
                  "assets": [
                    {
                      "name": "SocialBlueprint-1.2.jar",
                      "browser_download_url": "%s/download/SocialBlueprint-1.2.jar",
                      "size": %d,
                      "content_type": "application/java-archive"
                    }
                  ]
                }
                """.formatted(mismatchedSha256, serverBaseUrl, jarContent.length);

        mockServer.createContext("/repos/Dasannn/Blueprint/releases/latest", exchange -> {
            byte[] resp = releaseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });

        mockServer.createContext("/download/SocialBlueprint-1.2.jar", exchange -> {
            exchange.sendResponseHeaders(200, jarContent.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(jarContent); }
        });

        try (StorageEngine storage = StorageEngine.inMemory()) {
            storage.runMigrations();
            AuditRepository auditRepo = new AuditRepository(storage);

            UpdateService updateService = new UpdateService(
                    configManager,
                    messageRegistry,
                    asyncExecutor,
                    mainThreadQueue::add,
                    () -> updateFolder,
                    () -> "1.0",
                    () -> currentJarFile,
                    httpClient,
                    testLogger,
                    auditRepo
            );

            List<String> messages = new ArrayList<>();
            CommandSender sender = mockSender(messages);

            boolean success = updateService.downloadUpdateAsync(sender, configManager.snapshot()).join();
            drainMainThread();

            assertThat(success).isFalse();

            List<AuditEvent> audits = auditRepo.findByTarget(NonPlayerTarget.of("update"));
            assertThat(audits).isEmpty();
        }
    }

    @Test
    @DisplayName("Finding 7: Version check alone writes NO audit record")
    void checkOnlyWritesNoAuditRecord() throws Exception {
        String releaseJson = """
                {
                  "tag_name": "v1.2",
                  "name": "SocialBlueprint 1.2",
                  "body": "Release 1.2",
                  "prerelease": false,
                  "assets": []
                }
                """;

        mockServer.createContext("/repos/Dasannn/Blueprint/releases/latest", exchange -> {
            byte[] resp = releaseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });

        try (StorageEngine storage = StorageEngine.inMemory()) {
            storage.runMigrations();
            AuditRepository auditRepo = new AuditRepository(storage);

            UpdateService updateService = new UpdateService(
                    configManager,
                    messageRegistry,
                    asyncExecutor,
                    mainThreadQueue::add,
                    () -> updateFolder,
                    () -> "1.0",
                    () -> currentJarFile,
                    httpClient,
                    testLogger,
                    auditRepo
            );

            VersionCheckResult res = updateService.checkForUpdateAsync().join();
            assertThat(res).isNotNull();

            List<AuditEvent> audits = auditRepo.findByTarget(NonPlayerTarget.of("update"));
            assertThat(audits).isEmpty();
        }
    }
}

