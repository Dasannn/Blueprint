package com.dasannn.socialblueprint;

import org.bukkit.Server;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.milkbowl.vault.economy.Economy;
import com.dasannn.socialblueprint.storage.StorageEngine;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class SocialBlueprintPluginTest {

    @TempDir
    File tempDir;

    private SocialBlueprintPlugin plugin;
    private Server mockServer;
    private final Queue<Runnable> scheduledMainTasks = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean disabledCalled = new AtomicBoolean(false);
    private boolean vaultPluginPresent = true;
    private boolean economyServiceRegistered = true;
    private final List<LogRecord> loggedRecords = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings({"sunapi", "removal"})
    void setUp() throws Exception {
        // Prepare data folder with config.yml and language files
        copyResource("config.yml", new File(tempDir, "config.yml"));
        copyResource("messages_en.yml", new File(tempDir, "messages_en.yml"));
        copyResource("messages_es.yml", new File(tempDir, "messages_es.yml"));

        // Create BukkitScheduler proxy
        BukkitScheduler scheduler = (BukkitScheduler) Proxy.newProxyInstance(
                BukkitScheduler.class.getClassLoader(),
                new Class<?>[]{BukkitScheduler.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("runTask".equals(name) && args.length >= 2 && args[1] instanceof Runnable r) {
                        scheduledMainTasks.add(r);
                        return null;
                    }
                    if ("runTaskAsynchronously".equals(name) && args.length >= 2 && args[1] instanceof Runnable r) {
                        Thread t = new Thread(r, "mock-bukkit-async");
                        t.setDaemon(true);
                        t.start();
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                }
        );

        Plugin mockVaultPlugin = (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> defaultValue(method.getReturnType())
        );

        Economy mockEconomy = (Economy) Proxy.newProxyInstance(
                Economy.class.getClassLoader(),
                new Class<?>[]{Economy.class},
                (proxy, method, args) -> defaultValue(method.getReturnType())
        );

        RegisteredServiceProvider<Economy> rsp = new RegisteredServiceProvider<>(
                Economy.class,
                mockEconomy,
                ServicePriority.Normal,
                mockVaultPlugin
        );

        // Create PluginManager proxy
        PluginManager pluginManager = (PluginManager) Proxy.newProxyInstance(
                PluginManager.class.getClassLoader(),
                new Class<?>[]{PluginManager.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("disablePlugin".equals(name)) {
                        disabledCalled.set(true);
                        if (plugin != null) {
                            setField(plugin, JavaPlugin.class, "isEnabled", false);
                            plugin.onDisable();
                        }
                        return null;
                    }
                    if ("registerEvents".equals(name)) {
                        return null;
                    }
                    if ("getPlugin".equals(name) && args.length >= 1 && "Vault".equals(args[0])) {
                        return vaultPluginPresent ? mockVaultPlugin : null;
                    }
                    return defaultValue(method.getReturnType());
                }
        );

        // Create ServicesManager proxy
        ServicesManager servicesManager = (ServicesManager) Proxy.newProxyInstance(
                ServicesManager.class.getClassLoader(),
                new Class<?>[]{ServicesManager.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("getRegistration".equals(name) && args.length >= 1 && Economy.class.equals(args[0])) {
                        return economyServiceRegistered ? rsp : null;
                    }
                    return defaultValue(method.getReturnType());
                }
        );

        // Create Server proxy
        mockServer = (Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("getScheduler".equals(name)) {
                        return scheduler;
                    }
                    if ("getPluginManager".equals(name)) {
                        return pluginManager;
                    }
                    if ("getServicesManager".equals(name)) {
                        return servicesManager;
                    }
                    if ("getLogger".equals(name)) {
                        return Logger.getLogger("MockServer");
                    }
                    if ("getPluginCommand".equals(name)) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                }
        );

        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        plugin = (SocialBlueprintPlugin) unsafe.allocateInstance(SocialBlueprintPlugin.class);
        setField(plugin, JavaPlugin.class, "server", mockServer);
        setField(plugin, JavaPlugin.class, "dataFolder", tempDir);

        Logger logger = Logger.getLogger("SocialBlueprintPluginTest-" + System.nanoTime());
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                loggedRecords.add(record);
            }
            @Override
            public void flush() {}
            @Override
            public void close() throws SecurityException {}
        });
        setField(plugin, JavaPlugin.class, "logger", logger);

        PluginDescriptionFile desc = new PluginDescriptionFile("SocialBlueprint", "1.0", SocialBlueprintPlugin.class.getName());
        setField(plugin, JavaPlugin.class, "description", desc);
        setField(plugin, JavaPlugin.class, "pluginMeta", desc);
        setField(plugin, JavaPlugin.class, "isEnabled", true);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        // Initialisation publishes the engine asynchronously, so a test that
        // ends promptly can reach here before it appears. Closing nothing
        // leaves the SQLite file open and the temp directory undeletable on
        // Windows, which fails the test for a reason that has nothing to do
        // with what it asserts.
        if (plugin == null) {
            return;
        }
        for (int i = 0; i < 100 && plugin.getStorageEngine() == null && plugin.isEnabled(); i++) {
            Thread.sleep(20);
        }
        if (plugin.getStorageEngine() != null) {
            plugin.getStorageEngine().close();
        }
    }

    @Test
    @DisplayName("T-057 / SB-051: onEnable disables cleanly naming Vault when Vault plugin is absent")
    void onEnableDisablesPluginCleanlyWhenVaultPluginMissing() {
        vaultPluginPresent = false;
        plugin.onEnable();

        assertThat(disabledCalled.get()).isTrue();
        assertThat(loggedRecords)
                .extracting(LogRecord::getMessage)
                .anyMatch(msg -> msg.contains("Vault") && msg.contains("economy"));
        assertThat(plugin.getStorageEngine()).isNull();
    }

    @Test
    @DisplayName("T-057 / SB-051: onEnable disables cleanly naming Vault when Economy provider registration is absent")
    void onEnableDisablesPluginCleanlyWhenEconomyServiceMissing() {
        economyServiceRegistered = false;
        plugin.onEnable();

        assertThat(disabledCalled.get()).isTrue();
        assertThat(loggedRecords)
                .extracting(LogRecord::getMessage)
                .anyMatch(msg -> msg.contains("Vault") && msg.contains("economy"));
        assertThat(plugin.getStorageEngine()).isNull();
    }

    @Test
    @DisplayName("Round 1 Finding 1: onEnable returns immediately without blocking calling thread on database initialization")
    void onEnableReturnsImmediatelyWithoutBlockingOnStorageInit() throws Exception {
        long startTime = System.currentTimeMillis();
        plugin.onEnable();
        long elapsed = System.currentTimeMillis() - startTime;

        // onEnable must return immediately (< 250ms)
        assertThat(elapsed)
                .as("plugin.onEnable() must return promptly without synchronously waiting on SQLite storage or migrations")
                .isLessThan(250);

        // At the moment onEnable returns, the storage engine must not be set on the plugin yet
        // because initialization is offloaded to the background storage executor
        assertThat(plugin.getStorageEngine())
                .as("storageEngine must remain uninitialized on the calling thread when onEnable returns")
                .isNull();

        // Drain the scheduled main-thread task once async initialization completes
        // Poll for scheduled task
        long deadline = System.currentTimeMillis() + 3000;
        while (scheduledMainTasks.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        assertThat(scheduledMainTasks)
                .as("Async initialization must schedule completeInitialization on Bukkit main thread")
                .isNotEmpty();

        // Startup now hops twice: the coordinator's ready callback lands on the main
        // thread, and the stale-duel cleanup it starts schedules completeInitialization
        // when it finishes. Drain until the engine is set rather than assuming one task.
        long drainDeadline = System.currentTimeMillis() + 3000;
        while (plugin.getStorageEngine() == null && System.currentTimeMillis() < drainDeadline) {
            Runnable next = scheduledMainTasks.poll();
            if (next != null) {
                next.run();
            } else {
                Thread.sleep(20);
            }
        }

        // After running the scheduled task, completeInitialization must be done
        assertThat(plugin.getStorageEngine())
                .as("storageEngine must be fully initialized after scheduled main thread task runs")
                .isNotNull();
        assertThat(plugin.getProfileService()).isNotNull();
        assertThat(plugin.getReputationRepository()).isNotNull();
        assertThat(plugin.getDuelRepository()).isNotNull();
        assertThat(plugin.getDuelService()).isNotNull();
        assertThat(plugin.getAmbientEntityRegistry()).isNotNull();
        assertThat(plugin.getAmbientEffectScheduler()).isNotNull();
        assertThat(plugin.getRaterRevealRepository()).isNotNull();
        assertThat(plugin.getStatusGuiService()).isNotNull();
    }

    @Test
    @DisplayName("T-103 Finding 4: Error in stale duel cleanup schedules disablePlugin on main thread and closes engine")
    void staleDuelCleanupFailureSchedulesDisableOnMainThread() {
        StorageEngine engine = StorageEngine.inMemory();
        engine.runMigrations();

        // Drop duel table to cause cleanupStaleDuelsOnStartupAsync to fail with SQLException
        com.dasannn.socialblueprint.storage.StorageTestSupport.executeSql(engine, "DROP TABLE duel;");

        // Run the callback flow
        com.dasannn.socialblueprint.storage.DuelRepository startupDuelRepo = new com.dasannn.socialblueprint.storage.DuelRepository(engine);
        startupDuelRepo.cleanupStaleDuelsOnStartupAsync(java.time.Instant.now())
                .whenComplete((count, error) -> {
                    if (!plugin.isEnabled()) {
                        engine.close();
                        return;
                    }
                    if (error != null) {
                        plugin.getLogger().severe("Failed to clean up stale duels on startup: " + error.getMessage());
                        engine.close();
                        plugin.getServer().getScheduler().runTask(plugin, () -> plugin.getServer().getPluginManager().disablePlugin(plugin));
                        return;
                    }
                    plugin.getServer().getScheduler().runTask(plugin, () -> plugin.completeInitialization(engine));
                })
                // The cleanup is expected to fail here; the failure is the subject
                // of the test, so it is absorbed rather than rethrown by the join.
                .exceptionally(error -> null)
                .join();

        // Engine must be closed on storage thread
        assertThat(engine.isClosed()).isTrue();
        // Task to disable plugin must be queued on scheduler, NOT executed immediately
        assertThat(disabledCalled.get()).isFalse();
        assertThat(scheduledMainTasks).isNotEmpty();

        // Running the scheduled task calls disablePlugin
        scheduledMainTasks.poll().run();
        assertThat(disabledCalled.get()).isTrue();
    }

    private void copyResource(String resourceName, File destination) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new IllegalStateException("Resource not found: " + resourceName);
            }
            Files.copy(in, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void setField(Object target, Class<?> clazz, String fieldName, Object value) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object defaultValue(Class<?> returnType) {
        // A real server never hands back a null collection, so a fake that does
        // fails code that is correct: enable walks the players already online,
        // which is what a reload has.
        if (java.util.Collection.class.isAssignableFrom(returnType)) return java.util.List.of();
        if (returnType == java.util.Map.class) return java.util.Map.of();
        if (returnType == boolean.class) return false;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == double.class) return 0.0;
        if (returnType == float.class) return 0.0f;
        return null;
    }
}
