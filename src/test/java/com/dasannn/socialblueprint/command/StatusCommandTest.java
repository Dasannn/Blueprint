package com.dasannn.socialblueprint.command;

import com.dasannn.socialblueprint.config.ColorParser;
import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.domain.CombatContext;
import com.dasannn.socialblueprint.domain.HonorKind;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.PlayerSocialView;
import com.dasannn.socialblueprint.domain.ConfidenceLevel;
import com.dasannn.socialblueprint.domain.Tier;
import com.dasannn.socialblueprint.domain.PsychosisEvent;
import com.dasannn.socialblueprint.domain.ReputationEvent;
import com.dasannn.socialblueprint.feature.profile.PlayerLookup;
import com.dasannn.socialblueprint.feature.profile.ProfileService;
import com.dasannn.socialblueprint.storage.ProfileRepository;
import com.dasannn.socialblueprint.storage.PsychosisRepository;
import com.dasannn.socialblueprint.storage.ReputationRepository;
import com.dasannn.socialblueprint.storage.StatusCache;
import com.dasannn.socialblueprint.storage.StorageEngine;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Collections;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class StatusCommandTest {

    @Test
    void mindSetSupportsOfflineConsoleFiniteRangeAuditAndImmediateProfileRefresh() {
        var target = PlayerId.of(UUID.randomUUID());
        profileRepo.save(com.dasannn.socialblueprint.domain.PlayerProfile.create(target, "OfflineMind", Instant.now()));
        var snapshot = configManager.snapshot();
        profileService.loadViewAsync(target, "OfflineMind", snapshot).join();
        var console = mockConsole(new ArrayList<>());
        for (String value : List.of("-100", "42.5", "100", "0")) {
            commandExecutor.onCommand(console, null, "status", new String[]{"admin", "mind", "set", "OfflineMind", value});
            commandExecutor.lastExecution().join();
            assertThat(profileService.mind().value(target)).isEqualTo(Double.parseDouble(value));
            assertThat(profileService.findViewCached(target, snapshot)).isEmpty();
            assertThat(profileService.loadViewAsync(target, "OfflineMind", snapshot).join().psychosisMagnitude())
                    .isEqualTo(Math.abs(Double.parseDouble(value)));
            var event = profileService.mind().events(target).getLast();
            assertThat(event.kind()).isEqualTo("admin-set");
            assertThat(event.actor()).isEqualTo(PlayerId.CONSOLE);
            assertThat(event.source()).isEqualTo(console.getName());
        }
        assertThat(messageRegistry.hasCall("mind-admin.set")).isTrue();
        assertThat(new com.dasannn.socialblueprint.storage.AuditRepository(storage).findByTarget(target)).hasSize(4);
        for (String value : List.of("-101", "100.1", "NaN", "Infinity", "garbage")) {
            messageRegistry.clearCalls();
            commandExecutor.onCommand(console, null, "status", new String[]{"admin", "mind", "set", "OfflineMind", value});
            commandExecutor.lastExecution().join();
            assertThat(messageRegistry.hasCall("mind-admin.invalid-value")).isTrue();
        }
        var denied = mockPlayer("Denied", "socialblueprint.admin.adjust");
        commandExecutor.onCommand(denied, null, "status", new String[]{"admin", "mind", "set", "OfflineMind", "10"});
        commandExecutor.lastExecution().join();
        assertThat(messageRegistry.hasCall("commands.no-permission")).isTrue();
        assertThat(profileService.mind().events(target)).hasSize(4);
        commandExecutor.onCommand(console, null, "status", new String[]{"admin", "mind", "set", "Nobody", "10"});
        commandExecutor.lastExecution().join();
        assertThat(messageRegistry.hasCall("status.not-found")).isTrue();
        var admin = mockPlayer("Admin", "socialblueprint.admin.mind");
        assertThat(commandExecutor.onTabComplete(admin, null, "status", new String[]{"admin", "mind", "s"})).containsExactly("set");
        assertThat(commandExecutor.onTabComplete(admin, null, "status", new String[]{"admin", "mind", "set", "Online"})).contains("OnlineAlice");
        assertThat(commandExecutor.onTabComplete(admin, null, "status", new String[]{"admin", "mind", "set", "OfflineMind", "-"})).containsExactly("-100");
    }

    @Test
    void psychosisDetailUsesExistingProfilePermissionsAndMessageLine() {
        Player player = mockPlayer("Peaceful", "socialblueprint.show");
        commandExecutor.onCommand(player, null, "status", new String[]{"psychosis"});
        commandExecutor.lastExecution().join();
        assertThat(messageRegistry.renderedCalls()).anySatisfy(call -> {
            assertThat(call.key()).isEqualTo("status.profile-mental-state-neutral");
            assertThat(call.placeholders()).isEmpty();
        });
        assertThat(getMessages(player)).hasSize(1);
        messageRegistry.clearCalls();
        Player denied = mockPlayer("Denied");
        commandExecutor.onCommand(denied, null, "status", new String[]{"psychosis"});
        assertThat(messageRegistry.renderedCalls()).anySatisfy(call -> assertThat(call.key()).isEqualTo("commands.no-permission"));
    }

    @Test
    void psychosisDetailShowsOneDecimalMagnitudeForOfflineConsoleTarget() {
        PlayerId target = PlayerId.of(UUID.randomUUID());
        onlineLookupMap.put("offlinemind", new PlayerLookup.KnownPlayer(target, "OfflineMind", false));
        profileService.mind().applyAsync(target, com.dasannn.socialblueprint.domain.MindInput.KILL,
                new com.dasannn.socialblueprint.domain.MindInputConfig(true, 0, 34, 100), "test", Instant.now()).join();
        List<String> output = new ArrayList<>();
        CommandSender console = mockConsole(output);
        commandExecutor.onCommand(console, null, "status", new String[]{"psychosis", "OfflineMind"});
        commandExecutor.lastExecution().join();
        assertThat(output).hasSize(1);
        assertThat(messageRegistry.renderedCalls()).anySatisfy(call -> {
            assertThat(call.key()).isEqualTo("status.profile-mental-state-psychosis-detail");
            assertThat(call.placeholders()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "psychosis", messageRegistry.getRaw(configManager.snapshot(), "psychosis.medium"), "value", "34.0"));
        });
    }

    @Test
    void mindResetSupportsOfflineTargetsAndConsoleWithDedicatedPermissionAndAudit() {
        PlayerId target = PlayerId.of(UUID.randomUUID());
        profileRepo.save(com.dasannn.socialblueprint.domain.PlayerProfile.create(target, "OfflineMind", Instant.now()));
        profileService.mind().applyAsync(target, com.dasannn.socialblueprint.domain.MindInput.KILL,
                com.dasannn.socialblueprint.domain.MindInput.KILL.defaults(), "test", Instant.now()).join();
        Player denied = mockPlayer("Denied", "socialblueprint.admin.adjust");
        commandExecutor.onCommand(denied, null, "status", new String[]{"admin", "mind", "reset", "OfflineMind"});
        commandExecutor.lastExecution().join();
        assertThat(messageRegistry.hasCall("commands.no-permission")).isTrue();
        assertThat(profileService.mind().value(target)).isEqualTo(-10);
        messageRegistry.clearCalls();
        CommandSender console = mockConsole(new ArrayList<>());
        commandExecutor.onCommand(console, null, "status", new String[]{"admin", "mind", "reset", "OfflineMind"});
        commandExecutor.lastExecution().join();
        assertThat(profileService.mind().value(target)).isZero();
        assertThat(messageRegistry.renderedCalls()).anySatisfy(call -> {
            assertThat(call.key()).isEqualTo("mind-admin.reset");
            assertThat(call.placeholders()).containsEntry("player", "OfflineMind");
        });
        assertThat(profileService.mind().events(target).getLast().actor()).isEqualTo(PlayerId.CONSOLE);
        assertThat(new com.dasannn.socialblueprint.storage.AuditRepository(storage).findByTarget(target)).hasSize(1);
        messageRegistry.clearCalls();
        commandExecutor.onCommand(console, null, "status", new String[]{"admin", "mind", "reset", "NoSuchPlayer"});
        commandExecutor.lastExecution().join();
        assertThat(messageRegistry.hasCall("status.not-found")).isTrue();
    }

    @Test
    void mindResetAllRequiresSameActorConfirmationWithinThirtySecondsAndIsConsumed() {
        PlayerId target = PlayerId.of(UUID.randomUUID());
        profileService.mind().applyAsync(target, com.dasannn.socialblueprint.domain.MindInput.SLEEP,
                com.dasannn.socialblueprint.domain.MindInput.SLEEP.defaults(), "test", Instant.now()).join();
        java.util.concurrent.atomic.AtomicReference<Instant> time = new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-10-02T00:00:00Z"));
        java.time.Clock clock = new java.time.Clock() {
            public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() { return time.get(); }
        };
        var command = new StatusMindCommand(profileService, messageRegistry, Runnable::run, clock);
        var snapshot = configManager.snapshot();
        Player admin = mockPlayer("MindAdmin", "socialblueprint.admin.mind");
        Player other = mockPlayer("OtherAdmin", "socialblueprint.admin.mind");
        command.execute(admin, new String[]{"reset-all", "confirm"}, snapshot).join();
        assertThat(messageRegistry.hasCall("mind-admin.confirm-expired")).isTrue();
        command.execute(admin, new String[]{"reset-all"}, snapshot).join();
        command.execute(other, new String[]{"reset-all", "confirm"}, snapshot).join();
        assertThat(profileService.mind().value(target)).isEqualTo(0.5);
        time.set(time.get().plusSeconds(30));
        command.execute(admin, new String[]{"reset-all", "confirm"}, snapshot).join();
        assertThat(profileService.mind().value(target)).isEqualTo(0.5);
        command.execute(admin, new String[]{"reset-all"}, snapshot).join();
        time.set(time.get().plusSeconds(29));
        command.execute(admin, new String[]{"reset-all", "confirm"}, snapshot).join();
        assertThat(profileService.mind().value(target)).isZero();
        assertThat(messageRegistry.hasCall("mind-admin.reset-all")).isTrue();
        int count = profileService.mind().events(target).size();
        command.execute(admin, new String[]{"reset-all", "confirm"}, snapshot).join();
        assertThat(profileService.mind().events(target)).hasSize(count);
    }

    @Test
    void mindCommandsAndConfigCompleteTheirNewPaths() {
        Player admin = mockPlayer("MindAdmin", "socialblueprint.admin.mind", "socialblueprint.admin.config");
        assertThat(commandExecutor.onTabComplete(admin, null, "status", new String[]{"a"})).contains("admin");
        assertThat(commandExecutor.onTabComplete(admin, null, "status", new String[]{"admin", "m"})).contains("mind");
        assertThat(commandExecutor.onTabComplete(admin, null, "status", new String[]{"admin", "mind", "r"})).containsExactly("reset", "reset-all");
        assertThat(commandExecutor.onTabComplete(admin, null, "status", new String[]{"admin", "mind", "reset-all", "c"})).containsExactly("confirm");
        assertThat(commandExecutor.onTabComplete(admin, null, "status", new String[]{"admin", "mind", "reset", "Online"})).contains("OnlineAlice", "OnlineBob");
        assertThat(commandExecutor.onTabComplete(admin, null, "status", new String[]{"config", "set", "psychosis.inputs.kill."}))
                .contains("psychosis.inputs.kill.enabled", "psychosis.inputs.kill.serene-drain", "psychosis.inputs.kill.psychosis-weight");
        assertThat(commandExecutor.onTabComplete(admin, null, "status", new String[]{"config", "set", "psychosis.inputs.honor-review."}))
                .containsExactlyInAnyOrder("psychosis.inputs.honor-review.enabled", "psychosis.inputs.honor-review.gain",
                        "psychosis.inputs.honor-review.cure", "psychosis.inputs.honor-review.serene-drain",
                        "psychosis.inputs.honor-review.psychosis-weight", "psychosis.inputs.honor-review.cap");
        assertThat(commandExecutor.onTabComplete(admin, null, "status", new String[]{"config", "get", "psychosis."}))
                .doesNotContain("psychosis.window", "psychosis.medium-threshold", "psychosis.serenity.active-hours-to-ceiling");
    }

    @TempDir
    File tempDir;

    private StorageEngine storage;
    private ConfigManager configManager;
    private RecordingMessageRegistry messageRegistry;
    private ReputationRepository reputationRepo;
    private PsychosisRepository psychosisRepo;
    private ProfileRepository profileRepo;
    private ProfileService profileService;
    private StatusCommandExecutor commandExecutor;

    private final Map<String, PlayerLookup.KnownPlayer> onlineLookupMap = new HashMap<>();

    private final AtomicReference<Thread> lastLookupThread = new AtomicReference<>();

    public record RenderCall(String key, Map<String, String> placeholders, boolean withPrefix) {}

    public static class RecordingMessageRegistry extends MessageRegistry {
        private final List<RenderCall> renderedCalls = new CopyOnWriteArrayList<>();

        public RecordingMessageRegistry(File dataFolder, String language, Logger logger) {
            super(dataFolder, language, logger);
        }

        public void clearCalls() {
            renderedCalls.clear();
        }

        public List<RenderCall> renderedCalls() {
            return Collections.unmodifiableList(renderedCalls);
        }

        public boolean hasCall(String key) {
            return renderedCalls.stream().anyMatch(c -> c.key().equals(key));
        }

        @Override
        public Component renderWithPrefix(com.dasannn.socialblueprint.config.RuntimeSnapshot snapshot, String key, Map<String, String> placeholders) {
            renderedCalls.add(new RenderCall(key, placeholders != null ? Map.copyOf(placeholders) : Map.of(), true));
            return super.renderWithPrefix(snapshot, key, placeholders);
        }

        @Override
        public Component renderWithPrefix(com.dasannn.socialblueprint.config.RuntimeSnapshot snapshot, String key) {
            return renderWithPrefix(snapshot, key, Collections.emptyMap());
        }

        @Override
        public Component renderWithPrefix(String key, Map<String, String> placeholders) {
            renderedCalls.add(new RenderCall(key, placeholders != null ? Map.copyOf(placeholders) : Map.of(), true));
            return super.renderWithPrefix(key, placeholders);
        }

        @Override
        public Component renderWithPrefix(String key) {
            return renderWithPrefix(key, Collections.emptyMap());
        }

        @Override
        public Component render(com.dasannn.socialblueprint.config.RuntimeSnapshot snapshot, String key, Map<String, String> placeholders,
                                Map<String, Component> components) {
            renderedCalls.add(new RenderCall(key, placeholders != null ? Map.copyOf(placeholders) : Map.of(), false));
            return super.render(snapshot, key, placeholders, components);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        File configFile = new File(tempDir, "config.yml");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertThat(in).isNotNull();
            Files.copy(in, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        Logger logger = Logger.getLogger("StatusCommandTest-" + System.nanoTime());
        messageRegistry = new RecordingMessageRegistry(tempDir, "en", logger);
        configManager = new ConfigManager(configFile, messageRegistry, Runnable::run, logger);
        configManager.initialize();
        configManager.set("language", "en");

        storage = StorageEngine.inMemory();
        storage.runMigrations();

        StatusCache statusCache = new StatusCache();
        reputationRepo = new ReputationRepository(storage, statusCache);
        psychosisRepo = new PsychosisRepository(storage);
        profileRepo = new ProfileRepository(storage);

        PlayerLookup testLookup = nameOrUuid -> {
            lastLookupThread.set(Thread.currentThread());
            if (Thread.currentThread().getName().contains("socialblueprint-db")) {
                throw new IllegalStateException("Bukkit lookup must not be called from the storage executor thread!");
            }
            if (onlineLookupMap.containsKey(nameOrUuid.toLowerCase())) {
                return Optional.of(onlineLookupMap.get(nameOrUuid.toLowerCase()));
            }
            return Optional.empty();
        };

        profileService = new ProfileService(
                storage,
                reputationRepo,
                psychosisRepo,
                profileRepo,
                statusCache,
                configManager,
                testLookup,
                logger
        );

        commandExecutor = new StatusCommandExecutor(
                configManager,
                messageRegistry,
                profileService,
                Runnable::run,
                () -> List.of(mockPlayer("OnlineAlice"), mockPlayer("OnlineBob"))
        );
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.close();
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
            if ("sentMessages".equals(mName)) return messages;
            return defaultValue(method.getReturnType());
        };

        Player player = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class, MessageRecorder.class},
                handler
        );
        onlineLookupMap.put(name.toLowerCase(), new PlayerLookup.KnownPlayer(PlayerId.of(uuid), name, true));
        return player;
    }

    private CommandSender mockConsole(List<String> messages) {
        return mockConsole(messages, new ArrayList<>());
    }

    private CommandSender mockConsole(List<String> messages, List<Component> components) {
        InvocationHandler handler = (proxy, method, args) -> {
            String mName = method.getName();
            if ("getName".equals(mName)) return "CONSOLE";
            if ("hasPermission".equals(mName)) return true; // Console has all permissions
            if ("sendMessage".equals(mName)) {
                if (args != null && args.length > 0 && args[0] instanceof Component comp) {
                    messages.add(ColorParser.serialize(comp));
                    components.add(comp);
                }
                return null;
            }
            return defaultValue(method.getReturnType());
        };

        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[]{CommandSender.class},
                handler
        );
    }

    private static boolean hasColor(Component component, net.kyori.adventure.text.format.TextColor targetColor) {
        if (targetColor.equals(component.color())) {
            return true;
        }
        for (Component child : component.children()) {
            if (hasColor(child, targetColor)) {
                return true;
            }
        }
        return false;
    }

    private interface MessageRecorder {
        List<String> sentMessages();
    }

    @SuppressWarnings("unchecked")
    private List<String> getMessages(Player player) {
        return ((MessageRecorder) player).sentMessages();
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) return false;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == double.class) return 0.0;
        return null;
    }

    @Test
    @DisplayName("T-045: Player /status with no args shows own profile in English")
    void playerViewOwnProfileInEnglish() {
        Player player = mockPlayer("Steve", "socialblueprint.show");

        boolean result = commandExecutor.onCommand(player, null, "status", new String[]{});
        assertThat(result).isTrue();
        commandExecutor.lastExecution().join();

        List<String> messages = getMessages(player);
        assertThat(messages).hasSize(6);

        // Line 1: Header
        assertThat(messages.get(0)).contains("&8--- &bSocial Status: &eSteve &8---");
        // Line 2: Tier
        assertThat(messages.get(1)).contains("&7Tier: [&f|&7] &fCitizen");
        // Line 3: Status
        assertThat(messages.get(2)).contains("&7Status Score: &f0");
        // Line 4: Confidence
        assertThat(messages.get(3)).contains("&7Confidence: &fUnknown");
        // Line 5: Mental state
        assertThat(messages.get(4)).contains("&7Mental state: &fNeutral");
        // Line 6: Distinct raters
        assertThat(messages.get(5)).contains("&7Rated by: &f0 players");

        assertPinnedMentalStateMessages(player, "Mental state: Psychosis Medium (34.0/100)", "Mental state: Serenity 43.7/100");
    }

    @Test
    @DisplayName("T-045: Player /status with no args shows own profile in Spanish when language is es")
    void playerViewOwnProfileInSpanish() {
        configManager.set("language", "es");
        Player player = mockPlayer("Steve", "socialblueprint.show");

        boolean result = commandExecutor.onCommand(player, null, "status", new String[]{});
        assertThat(result).isTrue();
        commandExecutor.lastExecution().join();

        List<String> messages = getMessages(player);
        assertThat(messages).hasSize(6);

        // Line 1: Header
        assertThat(messages.get(0)).contains("&8--- &bEstatus Social: &eSteve &8---");
        // Line 2: Tier
        assertThat(messages.get(1)).contains("&7Rango: [&f|&7] &fParticular");
        // Line 3: Status
        assertThat(messages.get(2)).contains("&7Puntuaci\u00f3n de Estatus: &f0");
        // Line 4: Confidence
        assertThat(messages.get(3)).contains("&7Confianza: &fDesconocida");
        // Line 5: Mental state
        assertThat(messages.get(4)).contains("&7Estado mental: &fNeutral");
        // Line 6: Distinct raters
        assertThat(messages.get(5)).contains("&7Valorado por: &f0 jugadores");

        assertPinnedMentalStateMessages(player, "Estado mental: Psicosis Media (34.0/100)", "Estado mental: Serenidad 43.7/100");
    }

    private void assertPinnedMentalStateMessages(Player player, String psychosis, String serenity) {
        var snapshot = configManager.snapshot();
        var view = new PlayerSocialView(PlayerId.of(UUID.randomUUID()), "Subject", 0, Tier.PARTICULAR,
                ConfidenceLevel.UNKNOWN, com.dasannn.socialblueprint.domain.PsychosisLevel.MEDIUM, 0, 34);
        var plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        for (String prefix : List.of("chat.hover-mental-state", "status.profile-mental-state")) {
            assertThat(plain.serialize(messageRegistry.renderMentalState(snapshot,
                    messageRegistry.mentalStateLine(snapshot, view, prefix, false)))).isEqualTo(psychosis);
            var serene = new PlayerSocialView(view.playerId(), view.name(), view.status(), view.tier(),
                    view.confidence(), com.dasannn.socialblueprint.domain.PsychosisLevel.SERENITY, 0, 43.7);
            assertThat(plain.serialize(messageRegistry.renderMentalState(snapshot,
                    messageRegistry.mentalStateLine(snapshot, serene, prefix, false)))).isEqualTo(serenity);
        }
        assertThat(plain.serialize(messageRegistry.renderMentalState(snapshot,
                messageRegistry.mentalStateLine(snapshot, view, "status.profile-mental-state", true))))
                .isEqualTo(psychosis);
        getMessages(player).clear();
        commandExecutor.sendProfile(player, view, snapshot);
        assertThat(getMessages(player)).hasSize(6);
        assertThat(getMessages(player).get(4)).contains("34.0/100"); // the magnitude shows on the profile too
    }

    @Test
    @DisplayName("T-045: /status without show permission denies access")
    void permissionDeniedSelf() {
        Player player = mockPlayer("Unprivileged"); // No permissions

        boolean result = commandExecutor.onCommand(player, null, "status", new String[]{});
        assertThat(result).isTrue();

        List<String> messages = getMessages(player);
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst()).contains("&cYou do not have permission to execute this command.");
    }

    @Test
    @DisplayName("T-045: Console running /status with no args receives player-only error message")
    void consoleNoArgsReceivesPlayerOnly() {
        List<String> consoleMessages = new ArrayList<>();
        CommandSender console = mockConsole(consoleMessages);

        boolean result = commandExecutor.onCommand(console, null, "status", new String[]{});
        assertThat(result).isTrue();

        assertThat(consoleMessages).hasSize(1);
        assertThat(consoleMessages.getFirst()).contains("&cThis command can only be executed by players.");
    }

    @Test
    @DisplayName("T-045: /status <player> works from Console and displays profile")
    void consoleViewPlayerProfile() {
        PlayerId targetId = PlayerId.of(UUID.randomUUID());
        PlayerId rater = PlayerId.of(UUID.randomUUID());

        // Add reputation events for target
        reputationRepo.save(new ReputationEvent(rater, targetId, 15, HonorKind.POSITIVE, 500.0, null, Instant.now().minusSeconds(60)));

        onlineLookupMap.put("alex", new PlayerLookup.KnownPlayer(targetId, "Alex", false));

        List<String> consoleMessages = new ArrayList<>();
        List<Component> consoleComponents = new ArrayList<>();
        CommandSender console = mockConsole(consoleMessages, consoleComponents);

        Thread commandThread = Thread.currentThread();
        boolean result = commandExecutor.onCommand(console, null, "status", new String[]{"Alex"});
        assertThat(result).isTrue();
        commandExecutor.lastExecution().join();

        assertThat(lastLookupThread.get())
                .as("Bukkit player lookup must be executed on the command thread, never on the storage thread")
                .isSameAs(commandThread);

        assertThat(consoleMessages).hasSize(6);
        assertThat(consoleMessages.get(0)).contains("&8--- &bSocial Status: &eAlex &8---");
        assertThat(consoleMessages.get(1)).contains("&7Tier: [&a||&7] &fHonorable");
        assertThat(consoleMessages.get(2)).contains("&7Status Score: &f15");
        assertThat(messageRegistry.renderedCalls()).anySatisfy(call -> {
            assertThat(call.key()).isEqualTo("status.profile-contributors");
            assertThat(call.placeholders()).containsEntry("contributors", "1");
        });
        assertThat(hasColor(consoleComponents.get(1), net.kyori.adventure.text.format.NamedTextColor.GREEN))
                .as("Configured tier prefix in /status must retain its parsed color components")
                .isTrue();
    }

    @Test
    @DisplayName("T-045: /status <unknown-player> displays not-found message")
    void playerNotFoundDisplaysError() {
        Player player = mockPlayer("Inspector", "socialblueprint.show.others");

        boolean result = commandExecutor.onCommand(player, null, "status", new String[]{"GhostPlayerXYZ"});
        assertThat(result).isTrue();
        commandExecutor.lastExecution().join();

        assertThat(messageRegistry.renderedCalls()).anySatisfy(call -> {
            assertThat(call.key()).isEqualTo("status.not-found");
            assertThat(call.placeholders()).containsEntry("player", "GhostPlayerXYZ");
        });
    }

    @Test
    @DisplayName("T-045 / SB-005: Querying a valid UUID of an unrecorded player returns neutral metrics")
    void validUuidUnrecordedPlayerReturnsNeutral() {
        Player player = mockPlayer("Inspector", "socialblueprint.show.others");
        UUID randomUuid = UUID.randomUUID();

        boolean result = commandExecutor.onCommand(player, null, "status", new String[]{randomUuid.toString()});
        assertThat(result).isTrue();
        commandExecutor.lastExecution().join();

        List<String> messages = getMessages(player);
        assertThat(messages).hasSize(6);
        assertThat(messages.get(2)).contains("&7Status Score: &f0");
        assertThat(messages.get(3)).contains("&7Confidence: &fUnknown");
        assertThat(messageRegistry.renderedCalls()).anySatisfy(call -> {
            assertThat(call.key()).isEqualTo("status.profile-mental-state-neutral");
            assertThat(call.placeholders()).isEmpty();
        }).anySatisfy(call -> {
            assertThat(call.key()).isEqualTo("status.profile-contributors");
            assertThat(call.placeholders()).containsEntry("contributors", "0");
        });
    }

    @Test
    @DisplayName("T-045: Tab completion suggests 'config' and online player names")
    void tabCompletionSuggestsConfigAndPlayers() {
        Player admin = mockPlayer("Admin", "socialblueprint.admin.config");

        List<String> completions = commandExecutor.onTabComplete(admin, null, "status", new String[]{""});
        assertThat(completions).contains("config", "OnlineAlice", "OnlineBob");

        List<String> filtered = commandExecutor.onTabComplete(admin, null, "status", new String[]{"onlinea"});
        assertThat(filtered).containsExactly("OnlineAlice");
    }

    @Test
    @DisplayName("A read that fails for a reason other than a bad row reports status.read-failed to the sender and logs at SEVERE")
    void readFailureReportsStatusReadFailedAndLogsSevere() {
        PlayerId targetId = PlayerId.of(UUID.randomUUID());
        onlineLookupMap.put("alex", new PlayerLookup.KnownPlayer(targetId, "Alex", false));

        // Close storage engine so profile reading fails asynchronously
        storage.close();

        List<LogRecord> logs = new ArrayList<>();
        Handler testHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logs.add(record);
            }
            @Override
            public void flush() {}
            @Override
            public void close() {}
        };
        Logger executorLogger = Logger.getLogger(StatusCommandExecutor.class.getName());
        executorLogger.addHandler(testHandler);

        List<String> consoleMessages = new ArrayList<>();
        CommandSender console = mockConsole(consoleMessages);

        try {
            boolean result = commandExecutor.onCommand(console, null, "status", new String[]{"Alex"});
            assertThat(result).isTrue();
            commandExecutor.lastExecution().join();

            // Assert on the message key, never on rendered text
            assertThat(messageRegistry.hasCall("status.read-failed"))
                    .as("Sender must receive status.read-failed key on read failure")
                    .isTrue();

            // Assert logs at SEVERE
            assertThat(logs).anyMatch(r -> r.getLevel() == Level.SEVERE
                    && r.getMessage().contains("Failed to read the profile of 'Alex'"));
        } finally {
            executorLogger.removeHandler(testHandler);
        }
    }

    @Test
    @DisplayName("Player with a corrupt row still reads successfully with remaining rows counted")
    void playerWithCorruptRowStillReadsSuccessfully() {
        PlayerId targetId = PlayerId.of(UUID.randomUUID());
        PlayerId rater = PlayerId.of(UUID.randomUUID());

        // Valid row (+15)
        reputationRepo.save(new ReputationEvent(rater, targetId, 15, HonorKind.POSITIVE, 500.0, null, Instant.now().minusSeconds(60)));

        // Directly insert a corrupt row: the repository write path refuses
        // cost 0.0 on player honor, which is exactly what makes this row
        // only reachable through raw SQL. StorageEngine#run and
        // StorageTimestamps are package private to storage, so the shared
        // test helper does the write and the timestamp is written in the
        // nine-digit UTC form the storage layer parses.
        com.dasannn.socialblueprint.storage.StorageTestSupport.executeSql(storage,
                "INSERT INTO reputation_event (actor_uuid, target_uuid, delta, kind, cost, reason, created_at) VALUES ("
                        + "'" + rater + "', '" + targetId + "', 10, 'positive', 0.0, NULL, '"
                        + java.time.format.DateTimeFormatter.ISO_INSTANT.format(
                                Instant.now().minusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
                        + "')");

        onlineLookupMap.put("alex", new PlayerLookup.KnownPlayer(targetId, "Alex", false));

        List<String> consoleMessages = new ArrayList<>();
        CommandSender console = mockConsole(consoleMessages);

        boolean result = commandExecutor.onCommand(console, null, "status", new String[]{"Alex"});
        assertThat(result).isTrue();
        commandExecutor.lastExecution().join();

        assertThat(consoleMessages).hasSize(6);
        assertThat(consoleMessages.get(0)).contains("&8--- &bSocial Status: &eAlex &8---");
        // Status Score is derived only from valid event (+15)
        assertThat(consoleMessages.get(2)).contains("&7Status Score: &f15");
        assertThat(messageRegistry.renderedCalls()).anySatisfy(call -> {
            assertThat(call.key()).isEqualTo("status.profile-contributors");
            assertThat(call.placeholders()).containsEntry("contributors", "1");
        });

        // Sender did not receive read-failed
        assertThat(messageRegistry.hasCall("status.read-failed")).isFalse();
    }
}
