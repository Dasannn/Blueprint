package com.dasannn.socialblueprint.command;

import com.dasannn.socialblueprint.config.ColorParser;
import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.feature.update.ChecksumVerifier;
import com.dasannn.socialblueprint.feature.update.ReleaseAsset;
import com.dasannn.socialblueprint.feature.update.ReleaseInfo;
import com.dasannn.socialblueprint.feature.update.UpdateService;
import com.dasannn.socialblueprint.feature.update.VersionCheckResult;
import com.dasannn.socialblueprint.feature.update.VersionComparison;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import com.dasannn.socialblueprint.config.UpdateConfig;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class StatusCommandUpdateTest {

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

    private UpdateService updateService;
    private StatusCommandExecutor commandExecutor;
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
        testLogger = Logger.getLogger("StatusCommandUpdateTest-" + System.nanoTime());

        mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockServer.setExecutor(Executors.newCachedThreadPool());
        mockServer.start();
        serverPort = mockServer.getAddress().getPort();
        serverBaseUrl = "http://127.0.0.1:" + serverPort;

        asyncExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "socialblueprint-cmd-test-storage");
            t.setDaemon(true);
            return t;
        });

        updateFolder = new File(tempDir, "update");
        File pluginsFolder = new File(tempDir, "plugins");
        pluginsFolder.mkdirs();
        currentJarFile = new File(pluginsFolder, "SocialBlueprint.jar");
        Files.writeString(currentJarFile.toPath(), "placeholder");

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
        configManager.set("update.api-url", serverBaseUrl);

        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        updateService = new UpdateService(
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

        commandExecutor = new StatusCommandExecutor(
                configManager,
                messageRegistry,
                null, // ProfileService
                null, // HonorService
                null, // AuditRepository
                mainThreadQueue::add,
                Collections::emptyList,
                updateService
        );
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
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void drainMainThread() {
        Runnable r;
        while ((r = mainThreadQueue.poll()) != null) {
            r.run();
        }
    }

    private Player mockPlayer(String name, String... permissions) {
        Set<String> perms = new HashSet<>(List.of(permissions));
        List<String> messages = new ArrayList<>();
        UUID uuid = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));

        InvocationHandler handler = (proxy, method, args) -> {
            String mName = method.getName();
            if ("getName".equals(mName)) return name;
            if ("getUniqueId".equals(mName)) return uuid;
            if ("hasPermission".equals(mName)) {
                if (args != null && args.length > 0 && args[0] instanceof String perm) {
                    return perms.contains(perm);
                }
                return false;
            }
            if ("sendMessage".equals(mName)) {
                if (args != null && args.length > 0 && args[0] instanceof Component comp) {
                    messages.add(ColorParser.serialize(comp));
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

    private CommandSender mockConsole(List<String> messages) {
        InvocationHandler handler = (proxy, method, args) -> {
            String mName = method.getName();
            if ("getName".equals(mName)) return "CONSOLE";
            if ("hasPermission".equals(mName)) return true;
            if ("sendMessage".equals(mName)) {
                if (args != null && args.length > 0 && args[0] instanceof Component comp) {
                    messages.add(ColorParser.serialize(comp));
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

    private void copyResource(String resourceName, File destination) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            assertThat(in).isNotNull();
            Files.copy(in, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // =========================================================================
    // T-081: /status version
    // =========================================================================

    @Test
    @DisplayName("T-081: /status version reports unknown when check has not completed")
    void statusVersionReportsUnknownInitially() {
        Player player = mockPlayer("Alice", "socialblueprint.version");
        boolean handled = commandExecutor.onCommand(player, null, "status", new String[]{"version"});
        drainMainThread();
        commandExecutor.lastExecution().join();
        drainMainThread();

        assertThat(handled).isTrue();
        assertThat(messageRegistry.hasKey("updater.version-unknown")).isTrue();
        assertThat(messageRegistry.lastCall().placeholders()).containsEntry("current", "1.0");
    }

    @Test
    @DisplayName("T-081: /status version reports up to date when current version equals latest release")
    void statusVersionReportsUpToDate() {
        // Setup release with same version 1.0
        String releaseJson = """
                {
                  "tag_name": "v1.0",
                  "name": "SocialBlueprint 1.0",
                  "body": "Release 1.0",
                  "assets": []
                }
                """;
        mockServer.createContext("/repos/Dasannn/Blueprint/releases/latest", exchange -> {
            byte[] resp = releaseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });

        // Run check
        updateService.checkForUpdateAsync().join();

        Player player = mockPlayer("Alice", "socialblueprint.version");
        boolean handled = commandExecutor.onCommand(player, null, "status", new String[]{"version"});
        drainMainThread();
        commandExecutor.lastExecution().join();
        drainMainThread();

        assertThat(handled).isTrue();
        assertThat(messageRegistry.hasKey("updater.version-current")).isTrue();
        assertThat(messageRegistry.lastCall().placeholders()).containsEntry("current", "1.0");
    }

    @Test
    @DisplayName("T-081: /status version reports outdated when latest release is newer")
    void statusVersionReportsOutdated() {
        String releaseJson = """
                {
                  "tag_name": "v1.2",
                  "name": "SocialBlueprint 1.2",
                  "body": "Release 1.2",
                  "assets": []
                }
                """;
        mockServer.createContext("/repos/Dasannn/Blueprint/releases/latest", exchange -> {
            byte[] resp = releaseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });

        updateService.checkForUpdateAsync().join();

        Player player = mockPlayer("Alice", "socialblueprint.version");
        boolean handled = commandExecutor.onCommand(player, null, "status", new String[]{"version"});
        drainMainThread();
        commandExecutor.lastExecution().join();
        drainMainThread();

        assertThat(handled).isTrue();
        assertThat(messageRegistry.hasKey("updater.version-outdated")).isTrue();
        assertThat(messageRegistry.lastCall().placeholders())
                .containsEntry("current", "1.0")
                .containsEntry("latest", "v1.2");
    }

    @Test
    @DisplayName("T-081: /status version without permission is denied")
    void statusVersionDeniedWithoutPermission() {
        Player unpermitted = mockPlayer("Eve"); // No permissions
        boolean handled = commandExecutor.onCommand(unpermitted, null, "status", new String[]{"version"});
        drainMainThread();

        assertThat(handled).isTrue();
        assertThat(messageRegistry.hasKey("commands.no-permission")).isTrue();
    }

    @Test
    @DisplayName("T-081 / DoD 2: Console runs /status version without exception")
    void consoleRunsStatusVersionWithoutException() {
        List<String> consoleMsgs = new ArrayList<>();
        CommandSender console = mockConsole(consoleMsgs);

        assertThatCode(() -> {
            boolean handled = commandExecutor.onCommand(console, null, "status", new String[]{"version"});
            drainMainThread();
            commandExecutor.lastExecution().join();
            drainMainThread();
            assertThat(handled).isTrue();
        }).doesNotThrowAnyException();

        assertThat(messageRegistry.hasKey("updater.version-unknown")).isTrue();
    }

    // =========================================================================
    // T-082 & T-083: /status update
    // =========================================================================

    @Test
    @DisplayName("T-082: /status update without admin-update permission is denied")
    void statusUpdateDeniedWithoutPermission() {
        Player regularPlayer = mockPlayer("Bob", "socialblueprint.show", "socialblueprint.version");
        boolean handled = commandExecutor.onCommand(regularPlayer, null, "status", new String[]{"update"});
        drainMainThread();

        assertThat(handled).isTrue();
        assertThat(messageRegistry.hasKey("commands.no-permission")).isTrue();
    }

    @Test
    @DisplayName("T-082 / T-083: /status update with permission downloads, verifies, and reports restart-required")
    void statusUpdateSuccess() throws Exception {
        byte[] jarBytes = createValidPluginJarBytes("SocialBlueprint", "1.1");
        String hash = ChecksumVerifier.computeSha256(jarBytes);

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
                """.formatted(hash, serverBaseUrl, jarBytes.length);

        mockServer.createContext("/repos/Dasannn/Blueprint/releases/latest", exchange -> {
            byte[] resp = releaseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });

        mockServer.createContext("/download/SocialBlueprint.jar", exchange -> {
            exchange.sendResponseHeaders(200, jarBytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(jarBytes); }
        });

        Player admin = mockPlayer("AdminOp", "socialblueprint.admin.update");
        boolean handled = commandExecutor.onCommand(admin, null, "status", new String[]{"update"});
        drainMainThread();
        commandExecutor.lastExecution().join();
        drainMainThread();

        assertThat(handled).isTrue();

        // 1. Initial feedback: downloading
        assertThat(messageRegistry.hasKey("updater.downloading")).isTrue();

        // 2. Verified asset placed under update/ folder
        File staged = new File(updateFolder, "SocialBlueprint.jar");
        assertThat(staged).exists();
        assertThat(Files.readAllBytes(staged.toPath())).isEqualTo(jarBytes);

        // 3. Final feedback: restart required (never restarts server)
        assertThat(messageRegistry.hasKey("updater.restart-required")).isTrue();
    }

    @Test
    @DisplayName("DoD 2: Console runs /status update successfully without exception")
    void consoleRunsStatusUpdateSuccessfully() throws Exception {
        byte[] jarBytes = createValidPluginJarBytes("SocialBlueprint", "1.1");
        String hash = ChecksumVerifier.computeSha256(jarBytes);

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
                """.formatted(hash, serverBaseUrl, jarBytes.length);

        mockServer.createContext("/repos/Dasannn/Blueprint/releases/latest", exchange -> {
            byte[] resp = releaseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });

        mockServer.createContext("/download/SocialBlueprint.jar", exchange -> {
            exchange.sendResponseHeaders(200, jarBytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(jarBytes); }
        });

        List<String> consoleMsgs = new ArrayList<>();
        CommandSender console = mockConsole(consoleMsgs);

        assertThatCode(() -> {
            boolean handled = commandExecutor.onCommand(console, null, "status", new String[]{"update"});
            drainMainThread();
            commandExecutor.lastExecution().join();
            drainMainThread();
            assertThat(handled).isTrue();
        }).doesNotThrowAnyException();

        assertThat(messageRegistry.hasKey("updater.downloading")).isTrue();
        assertThat(messageRegistry.hasKey("updater.restart-required")).isTrue();
    }

    // =========================================================================
    // Tab Completion
    // =========================================================================

    @Test
    @DisplayName("T-081 / T-082: Tab completion suggests version and update when permissible")
    void tabCompletionSuggestsVersionAndUpdate() {
        Player admin = mockPlayer("AdminUser", "socialblueprint.version", "socialblueprint.admin.update");
        List<String> suggestions = commandExecutor.onTabComplete(admin, null, "status", new String[]{""});
        assertThat(suggestions).contains("version", "update");

        List<String> versionFiltered = commandExecutor.onTabComplete(admin, null, "status", new String[]{"ver"});
        assertThat(versionFiltered).containsExactly("version");

        List<String> updateFiltered = commandExecutor.onTabComplete(admin, null, "status", new String[]{"upd"});
        assertThat(updateFiltered).containsExactly("update");

        // Player with only version permission gets only version
        Player user = mockPlayer("NormalUser", "socialblueprint.version");
        List<String> userSuggestions = commandExecutor.onTabComplete(user, null, "status", new String[]{""});
        assertThat(userSuggestions).contains("version");
        assertThat(userSuggestions).doesNotContain("update");
    }
}
