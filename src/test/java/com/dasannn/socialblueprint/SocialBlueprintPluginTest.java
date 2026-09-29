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

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class SocialBlueprintPluginTest {

    @TempDir
    File tempDir;

    private SocialBlueprintPlugin plugin;
    private Server mockServer;
    private final Queue<Runnable> scheduledMainTasks = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean disabledCalled = new AtomicBoolean(false);

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
        setField(plugin, JavaPlugin.class, "logger", Logger.getLogger("SocialBlueprintPluginTest"));
        PluginDescriptionFile desc = new PluginDescriptionFile("SocialBlueprint", "1.0", SocialBlueprintPlugin.class.getName());
        setField(plugin, JavaPlugin.class, "description", desc);
        setField(plugin, JavaPlugin.class, "pluginMeta", desc);
        setField(plugin, JavaPlugin.class, "isEnabled", true);
    }

    @AfterEach
    void tearDown() {
        if (plugin != null && plugin.getStorageEngine() != null) {
            plugin.getStorageEngine().close();
        }
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

        Runnable completeTask = scheduledMainTasks.poll();
        completeTask.run();

        // After running the scheduled task, completeInitialization must be done
        assertThat(plugin.getStorageEngine())
                .as("storageEngine must be fully initialized after scheduled main thread task runs")
                .isNotNull();
        assertThat(plugin.getProfileService()).isNotNull();
        assertThat(plugin.getReputationRepository()).isNotNull();
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
        if (returnType == boolean.class) return false;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == double.class) return 0.0;
        if (returnType == float.class) return 0.0f;
        return null;
    }
}
