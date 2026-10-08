package com.dasannn.socialblueprint.platform.listener;

import com.dasannn.socialblueprint.config.*;
import com.dasannn.socialblueprint.domain.*;
import com.dasannn.socialblueprint.feature.profile.ProfileService;
import com.dasannn.socialblueprint.storage.*;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import static org.assertj.core.api.Assertions.*;

class PlayerPresentationListenerTest {
    @TempDir Path folder;
    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }
    private static Object fallback(java.lang.reflect.Method method) {
        if (method.getReturnType() == boolean.class) return false;
        if (method.getReturnType() == int.class) return 0;
        return null;
    }
    @Test void sharedProfileWritesRefreshTabAndPrivateNoticesWithReloadAndDisableCleanup() {
        var logger = Logger.getLogger("PlayerPresentationTest");
        var notices = new ArrayList<String>();
        var phrases = new ArrayList<String>();
        var order = new ArrayList<String>();
        var sent = new ArrayList<Component>();
        var messages = new MessageRegistry(folder.toFile(), "en", logger) {
            @Override public Component renderWithPrefix(RuntimeSnapshot snapshot, String key, Map<String, String> values) {
                if (key.startsWith("mind-notices.")) { notices.add(key); order.add(key); }
                return super.renderWithPrefix(snapshot, key, values);
            }
            @Override public Component render(RuntimeSnapshot snapshot, String key,
                                              Map<String, String> values, Map<String, Component> components) {
                if (key.startsWith("mind-levels.")) { phrases.add(key); order.add(key); }
                return super.render(snapshot, key, values, components);
            }
        };
        var configs = new ConfigManager(folder.resolve("config.yml").toFile(), messages, Runnable::run, logger);
        configs.initialize();
        var queue = new ConcurrentLinkedQueue<Runnable>();
        var tab = new AtomicReference<Component>();
        var online = new AtomicBoolean(true);
        var id = PlayerId.of(UUID.randomUUID());
        var worldName = new AtomicReference<>("survival");
        org.bukkit.World world = proxy(org.bukkit.World.class, (object, method, args) ->
                method.getName().equals("getName") ? worldName.get() : fallback(method));
        Thread main = Thread.currentThread();
        Player player = proxy(Player.class, (object, method, args) -> {
            assertThat(Thread.currentThread()).isSameAs(main);
            return switch (method.getName()) {
                case "getUniqueId" -> id.uuid();
                case "getName" -> "Alex";
                case "getWorld" -> world;
                case "isOnline" -> online.get();
                case "playerListName" -> { if (args != null && args.length == 1) tab.set((Component) args[0]); yield null; }
                case "sendMessage" -> { if (args[0] instanceof Component component) sent.add(component); yield null; }
                default -> fallback(method);
            };
        });
        BukkitScheduler scheduler = proxy(BukkitScheduler.class, (object, method, args) -> {
            if (method.getName().equals("runTask")) queue.add((Runnable) args[1]);
            return fallback(method);
        });
        Server server = proxy(Server.class, (object, method, args) -> switch (method.getName()) {
            case "getOnlinePlayers" -> online.get() ? List.of(player) : List.of();
            case "getPlayer" -> online.get() ? player : null;
            case "getScheduler" -> scheduler;
            default -> fallback(method);
        });
        Plugin plugin = proxy(Plugin.class, (object, method, args) -> switch (method.getName()) {
            case "isEnabled" -> true;
            case "getServer" -> server;
            default -> fallback(method);
        });
        try (var engine = StorageEngine.inMemory()) {
            engine.runMigrations();
            var reputation = new ReputationRepository(engine, new StatusCache());
            var profiles = new ProfileService(engine, reputation, new PsychosisRepository(engine), new ProfileRepository(engine),
                    new StatusCache(), configs, null, logger, java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));
            var presentation = new PlayerPresentationListener(plugin, profiles, configs, messages, new Random(204));
            profiles.mind().setAsync(id, -50, PlayerId.CONSOLE, "Owner", NOW).join();
            profiles.warmUp(id, "Alex", configs.snapshot()).join();
            drain(queue);
            assertThat(notices).isEmpty();
            assertThat(phrases).isEmpty();
            assertThat(sent).isEmpty();
            String initial = AsyncChatListener.extractPlainText(tab.get());
            assertThat(initial).endsWith(" Alex");
            profiles.mind().reduceAsync(id, 40, PlayerId.CONSOLE, "Console", NOW).join();
            profiles.loadViewAsync(id, "Alex", configs.snapshot()).join();
            drain(queue);
            assertThat(notices).containsExactly("mind-notices.psychosis-fell");
            String selected = "mind-levels.psychosis-fell.medium.lines." + new Random(204).nextInt(4);
            assertThat(phrases).containsExactly(selected);
            assertThat(order).containsExactly("mind-notices.psychosis-fell", selected);
            assertThat(sent).hasSize(2);
            assertThat(sent.getLast()).isEqualTo(ColorParser.renderTemplate(messages.getRaw(configs.snapshot(), selected), Map.of()));
            phrases.removeLast();
            order.removeLast();
            profiles.mind().setAsync(id, 10, PlayerId.CONSOLE, "Owner", NOW).join();
            profiles.loadViewAsync(id, "Alex", configs.snapshot()).join();
            drain(queue);
            assertThat(notices).containsExactly("mind-notices.psychosis-fell", "mind-notices.psychosis-fell", "mind-notices.serenity-rose");
            assertThat(phrases).isEmpty();
            profiles.mind().setAsync(id, -80, PlayerId.CONSOLE, "Owner", NOW).join();
            profiles.loadViewAsync(id, "Alex", configs.snapshot()).join();
            drain(queue);
            assertThat(phrases).hasSize(1);
            assertThat(phrases.getFirst()).startsWith("mind-levels.psychosis-rose.extreme.lines.");
            phrases.clear();
            worldName.set("minigames");
            profiles.mind().setAsync(id, -10, PlayerId.CONSOLE, "Owner", NOW).join();
            profiles.loadViewAsync(id, "Alex", configs.snapshot()).join();
            drain(queue);
            assertThat(phrases).isEmpty();
            worldName.set("survival");
            profiles.loadViewAsync(id, "Alex", configs.snapshot()).join();
            drain(queue);
            assertThat(phrases).isEmpty();
            profiles.mind().reduceAsync(id, 100, PlayerId.CONSOLE, "Console", NOW).join();
            profiles.loadViewAsync(id, "Alex", configs.snapshot()).join();
            drain(queue);
            assertThat(phrases).hasSize(1);
            assertThat(phrases.getFirst()).startsWith("mind-levels.psychosis-fell.neutral.lines.");
            phrases.clear();
            configs.set("mind.level-phrases.enabled", "false");
            drain(queue);
            profiles.mind().setAsync(id, -80, PlayerId.CONSOLE, "Owner", NOW).join();
            profiles.loadViewAsync(id, "Alex", configs.snapshot()).join();
            drain(queue);
            assertThat(phrases).isEmpty();
            configs.set("mind.level-phrases.enabled", "true");
            drain(queue);
            profiles.loadViewAsync(id, "Alex", configs.snapshot()).join();
            drain(queue);
            assertThat(phrases).isEmpty();
            profiles.mind().resetAsync(id, PlayerId.CONSOLE, NOW).join();
            profiles.loadViewAsync(id, "Alex", configs.snapshot()).join();
            drain(queue);
            assertThat(phrases).hasSize(1);
            assertThat(phrases.getFirst()).startsWith("mind-levels.psychosis-fell.neutral.lines.");
            phrases.clear();
            profiles.mind().applyAsync(id, MindInput.KILL, MindInput.KILL.defaults(), "input", NOW).join();
            profiles.loadViewAsync(id, "Alex", configs.snapshot()).join();
            drain(queue);
            assertThat(phrases).hasSize(1);
            assertThat(phrases.getFirst()).startsWith("mind-levels.psychosis-rose.low.lines.");
            phrases.clear();
            profiles.mind().applyAsync(id, MindInput.HONOR_REVIEW,
                    new MindInputConfig(true, 2, 10, 3), "rating", NOW).join();
            profiles.loadViewAsync(id, "Alex", configs.snapshot()).join();
            drain(queue);
            assertThat(phrases).hasSize(1);
            assertThat(phrases.getFirst()).startsWith("mind-levels.psychosis-fell.neutral.lines.");
            reputation.saveAsync(new ReputationEvent(PlayerId.CONSOLE, id, 50, HonorKind.ADMIN_GIVE, 0, "Owner", NOW)).join();
            profiles.loadViewAsync(id, "Alex", configs.snapshot()).join();
            drain(queue);
            assertThat(AsyncChatListener.extractPlainText(tab.get())).isNotEqualTo(initial);
            configs.set("tiers.tier4.prefix", "&a[NEW]");
            drain(queue);
            profiles.loadViewAsync(id, "Alex", configs.snapshot()).join();
            drain(queue);
            assertThat(AsyncChatListener.extractPlainText(tab.get())).isEqualTo("[NEW] Alex");
            configs.set("tab.enabled", "false");
            drain(queue);
            assertThat(tab.get()).isNull();
            profiles.mind().setAsync(id, -50, PlayerId.CONSOLE, "Owner", NOW).join();
            profiles.loadViewAsync(id, "Alex", configs.snapshot()).join();
            online.set(false);
            presentation.onQuit(new PlayerQuitEvent(player, Component.empty()));
            profiles.evict(id);
            int count = notices.size();
            int phraseCount = phrases.size();
            drain(queue);
            assertThat(notices).hasSize(count);
            assertThat(phrases).hasSize(phraseCount);
            online.set(true);
            tab.set(Component.text("temporary"));
            presentation.stop();
            assertThat(tab.get()).isNull();
        }
    }
    private static void drain(Queue<Runnable> queue) {
        Runnable action;
        while ((action = queue.poll()) != null) action.run();
    }
}
