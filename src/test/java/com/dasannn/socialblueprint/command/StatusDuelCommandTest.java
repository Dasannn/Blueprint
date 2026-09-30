package com.dasannn.socialblueprint.command;

import com.dasannn.socialblueprint.config.ColorParser;
import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.duel.DuelChallenge;
import com.dasannn.socialblueprint.feature.duel.DuelService;
import com.dasannn.socialblueprint.feature.profile.PlayerLookup;
import com.dasannn.socialblueprint.feature.profile.ProfileService;
import com.dasannn.socialblueprint.storage.AuditRepository;
import com.dasannn.socialblueprint.storage.DuelRepository;
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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class StatusDuelCommandTest {

    @TempDir
    File tempDir;

    private StorageEngine storage;
    private ConfigManager configManager;
    private RecordingMessageRegistry messageRegistry;
    private DuelRepository duelRepo;
    private AuditRepository auditRepo;
    private PsychosisRepository psychosisRepo;
    private DuelService duelService;
    private StatusCommandExecutor commandExecutor;
    private StatusDuelCommand duelCommand;

    private final Map<String, PlayerLookup.KnownPlayer> knownPlayers = new HashMap<>();
    private final List<Player> onlinePlayers = new ArrayList<>();
    private final Map<UUID, List<String>> sentMessages = new HashMap<>();
    private final Map<UUID, List<RecordingMessageRegistry.RenderCall>> playerCalls = new HashMap<>();

    public static class RecordingMessageRegistry extends MessageRegistry {
        public record RenderCall(String key, Map<String, String> placeholders, boolean withPrefix) {}

        private final List<RenderCall> renderedCalls = new CopyOnWriteArrayList<>();
        private final Map<Component, RenderCall> componentCalls = new IdentityHashMap<>();

        public RecordingMessageRegistry(File dataFolder, String language, Logger logger) {
            super(dataFolder, language, logger);
        }

        public void clearCalls() {
            renderedCalls.clear();
            componentCalls.clear();
        }

        public List<RenderCall> renderedCalls() {
            return Collections.unmodifiableList(renderedCalls);
        }

        public RenderCall lastCall() {
            if (renderedCalls.isEmpty()) {
                throw new AssertionError("No messages were rendered");
            }
            return renderedCalls.getLast();
        }

        public boolean hasCall(String key) {
            return renderedCalls.stream().anyMatch(c -> c.key().equals(key));
        }

        public Optional<RenderCall> findLastCall(String key) {
            return renderedCalls.stream().filter(c -> c.key().equals(key)).reduce((first, second) -> second);
        }

        public RenderCall getCall(Component comp) {
            return componentCalls.get(comp);
        }

        @Override
        public Component renderWithPrefix(RuntimeSnapshot snapshot, String key, Map<String, String> placeholders) {
            RenderCall call = new RenderCall(key, placeholders != null ? Map.copyOf(placeholders) : Map.of(), true);
            renderedCalls.add(call);
            Component comp = super.renderWithPrefix(snapshot, key, placeholders);
            componentCalls.put(comp, call);
            return comp;
        }

        @Override
        public Component renderWithPrefix(RuntimeSnapshot snapshot, String key) {
            return renderWithPrefix(snapshot, key, Collections.emptyMap());
        }

        @Override
        public Component renderWithPrefix(String key, Map<String, String> placeholders) {
            return renderWithPrefix(snapshot(), key, placeholders);
        }

        @Override
        public Component renderWithPrefix(String key) {
            return renderWithPrefix(key, Collections.emptyMap());
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        File configFile = new File(tempDir, "config.yml");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertThat(in).isNotNull();
            Files.copy(in, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        copyResource("messages_en.yml", new File(tempDir, "messages_en.yml"));
        copyResource("messages_es.yml", new File(tempDir, "messages_es.yml"));

        Logger logger = Logger.getLogger("StatusDuelCommandTest-" + System.nanoTime());
        messageRegistry = new RecordingMessageRegistry(tempDir, "es", logger);
        configManager = new ConfigManager(configFile, messageRegistry, Runnable::run, logger);
        configManager.initialize();

        storage = StorageEngine.inMemory();
        storage.runMigrations();

        duelRepo = new DuelRepository(storage);
        auditRepo = new AuditRepository(storage);
        psychosisRepo = new PsychosisRepository(storage);

        PlayerLookup lookup = nameOrUuid -> {
            String key = nameOrUuid.toLowerCase(Locale.ROOT);
            return Optional.ofNullable(knownPlayers.get(key));
        };

        duelService = new DuelService(
                duelRepo,
                auditRepo,
                psychosisRepo,
                configManager,
                messageRegistry,
                lookup,
                Runnable::run,
                (d, r) -> () -> {},
                (pid, comp) -> {
                    RecordingMessageRegistry.RenderCall call = messageRegistry.getCall(comp);
                    if (call != null) {
                        playerCalls.computeIfAbsent(pid.uuid(), k -> new ArrayList<>()).add(call);
                    }
                    List<String> list = sentMessages.computeIfAbsent(pid.uuid(), k -> new ArrayList<>());
                    list.add(ColorParser.serialize(comp));
                },
                comp -> {}
        );

        duelCommand = new StatusDuelCommand(
                duelService,
                messageRegistry,
                lookup,
                () -> onlinePlayers
        );

        StatusCache statusCache = new StatusCache();
        ReputationRepository repRepo = new ReputationRepository(storage, statusCache);
        ProfileRepository profRepo = new ProfileRepository(storage);
        ProfileService profService = new ProfileService(
                storage,
                repRepo,
                psychosisRepo,
                profRepo,
                statusCache,
                configManager,
                lookup,
                logger
        );

        commandExecutor = new StatusCommandExecutor(
                configManager,
                messageRegistry,
                profService,
                null,
                duelService,
                auditRepo,
                Runnable::run,
                () -> onlinePlayers
        );
    }

    @AfterEach
    void tearDown() {
        if (duelService != null) {
            duelService.shutdown();
        }
        if (storage != null) {
            storage.close();
        }
    }

    private Player mockPlayer(String name, String... permissions) {
        Set<String> perms = new HashSet<>(List.of(permissions));
        UUID uuid = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        PlayerId pid = PlayerId.of(uuid);

        InvocationHandler handler = (proxy, method, args) -> {
            String mName = method.getName();
            if ("getName".equals(mName)) return name;
            if ("getUniqueId".equals(mName)) return uuid;
            if ("hasPermission".equals(mName)) {
                if (args != null && args.length > 0 && args[0] instanceof String p) {
                    return perms.contains(p);
                }
                return false;
            }
            if ("sendMessage".equals(mName)) {
                if (args != null && args.length > 0 && args[0] instanceof Component comp) {
                    RecordingMessageRegistry.RenderCall call = messageRegistry.getCall(comp);
                    if (call != null) {
                        playerCalls.computeIfAbsent(uuid, k -> new ArrayList<>()).add(call);
                    }
                    sentMessages.computeIfAbsent(uuid, k -> new ArrayList<>()).add(ColorParser.serialize(comp));
                }
                return null;
            }
            return defaultValue(method.getReturnType());
        };

        Player player = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                handler
        );

        knownPlayers.put(name.toLowerCase(Locale.ROOT), new PlayerLookup.KnownPlayer(pid, name, true));
        knownPlayers.put(uuid.toString().toLowerCase(Locale.ROOT), new PlayerLookup.KnownPlayer(pid, name, true));
        onlinePlayers.add(player);
        return player;
    }

    private CommandSender mockConsole(List<String> messages, List<RecordingMessageRegistry.RenderCall> calls) {
        InvocationHandler handler = (proxy, method, args) -> {
            String mName = method.getName();
            if ("getName".equals(mName)) return "CONSOLE";
            if ("hasPermission".equals(mName)) return true;
            if ("sendMessage".equals(mName)) {
                if (args != null && args.length > 0 && args[0] instanceof Component comp) {
                    RecordingMessageRegistry.RenderCall call = messageRegistry.getCall(comp);
                    if (call != null && calls != null) {
                        calls.add(call);
                    }
                    if (messages != null) {
                        messages.add(ColorParser.serialize(comp));
                    }
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

    private CommandSender mockConsole(List<String> messages) {
        return mockConsole(messages, null);
    }

    private List<String> getMessages(Player player) {
        return sentMessages.getOrDefault(player.getUniqueId(), Collections.emptyList());
    }

    private List<RecordingMessageRegistry.RenderCall> getCalls(Player player) {
        return playerCalls.getOrDefault(player.getUniqueId(), Collections.emptyList());
    }

    private RecordingMessageRegistry.RenderCall getLastCall(Player player) {
        List<RecordingMessageRegistry.RenderCall> calls = getCalls(player);
        if (calls.isEmpty()) {
            throw new AssertionError("No messages sent to player " + player.getName());
        }
        return calls.getLast();
    }

    @Test
    @DisplayName("DoD 2 / SB-065: Console running /status duel commands returns player-only error without throwing")
    void consoleRunningDuelReturnsPlayerOnlyWithoutThrowing() {
        List<RecordingMessageRegistry.RenderCall> consoleCalls = new ArrayList<>();
        CommandSender console = mockConsole(new ArrayList<>(), consoleCalls);

        assertThatCode(() -> {
            commandExecutor.onCommand(console, null, "status", new String[]{"duel", "Alice"});
            assertThat(consoleCalls.getLast().key()).isEqualTo("commands.player-only");

            commandExecutor.onCommand(console, null, "status", new String[]{"duel", "accept"});
            assertThat(consoleCalls.getLast().key()).isEqualTo("commands.player-only");

            commandExecutor.onCommand(console, null, "status", new String[]{"duel", "deny"});
            assertThat(consoleCalls.getLast().key()).isEqualTo("commands.player-only");

            commandExecutor.onCommand(console, null, "status", new String[]{"duel", "leave"});
            assertThat(consoleCalls.getLast().key()).isEqualTo("commands.player-only");
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("T-060: Player without socialblueprint.duel permission is rejected")
    void unprivilegedPlayerRejected() {
        Player player = mockPlayer("Unprivileged"); // No permission
        mockPlayer("Target", "socialblueprint.duel");

        commandExecutor.onCommand(player, null, "status", new String[]{"duel", "Target"});
        assertThat(getLastCall(player).key()).isEqualTo("commands.no-permission");
    }

    @Test
    @DisplayName("T-060: /status duel with no arguments displays usage")
    void duelNoArgsDisplaysUsage() {
        Player player = mockPlayer("Duelist", "socialblueprint.duel");

        commandExecutor.onCommand(player, null, "status", new String[]{"duel"});
        assertThat(getLastCall(player).key()).isEqualTo("duel.usage");
    }

    @Test
    @DisplayName("T-060: /status duel <unknown> displays player not found")
    void duelUnknownPlayerNotFound() {
        Player player = mockPlayer("Duelist", "socialblueprint.duel");

        commandExecutor.onCommand(player, null, "status", new String[]{"duel", "NonExistentUser123"});
        assertThat(getLastCall(player).key()).isEqualTo("status.not-found");
        assertThat(getLastCall(player).placeholders()).containsEntry("player", "NonExistentUser123");
    }

    @Test
    @DisplayName("T-060: /status duel <self> displays cannot duel self")
    void cannotDuelSelf() {
        Player player = mockPlayer("Duelist", "socialblueprint.duel");

        commandExecutor.onCommand(player, null, "status", new String[]{"duel", "Duelist"});
        assertThat(getLastCall(player).key()).isEqualTo("duel.cannot-duel-self");
    }

    @Test
    @DisplayName("T-060: /status duel <target> initiates challenge; target accepts; duel starts")
    void oneVsOneDuelChallengeAndAcceptFlow() {
        Player alice = mockPlayer("Alice", "socialblueprint.duel");
        Player bob = mockPlayer("Bob", "socialblueprint.duel");

        // Alice challenges Bob
        commandExecutor.onCommand(alice, null, "status", new String[]{"duel", "Bob"});
        assertThat(getLastCall(alice).key()).isEqualTo("duel.challenge-sent");
        assertThat(getLastCall(alice).placeholders()).containsEntry("target", "Bob");
        assertThat(getLastCall(bob).key()).isEqualTo("duel.challenge-received");
        assertThat(getLastCall(bob).placeholders()).containsEntry("challenger", "Alice");

        // Bob accepts challenge via shortcut /status accept
        commandExecutor.onCommand(bob, null, "status", new String[]{"accept"});
        assertThat(getLastCall(bob).key()).isEqualTo("duel.accepted");
        assertThat(getLastCall(bob).placeholders()).containsEntry("opponent", "Alice");
        assertThat(getLastCall(alice).key()).isEqualTo("duel.accepted");
        assertThat(getLastCall(alice).placeholders()).containsEntry("opponent", "Bob");

        assertThat(duelService.isInActiveDuel(PlayerId.of(alice.getUniqueId()))).isTrue();
        assertThat(duelService.isInActiveDuel(PlayerId.of(bob.getUniqueId()))).isTrue();
    }

    @Test
    @DisplayName("T-060: /status duel deny cancels pending challenge cleanly")
    void duelDenyCancelsChallenge() {
        Player alice = mockPlayer("Alice", "socialblueprint.duel");
        Player bob = mockPlayer("Bob", "socialblueprint.duel");

        commandExecutor.onCommand(alice, null, "status", new String[]{"duel", "Bob"});

        // Bob denies via /status duel deny
        commandExecutor.onCommand(bob, null, "status", new String[]{"duel", "deny"});
        assertThat(getLastCall(bob).key()).isEqualTo("duel.denied");
        assertThat(getLastCall(alice).key()).isEqualTo("duel.denied");

        assertThat(duelService.pendingChallengeCount()).isEqualTo(0);
        assertThat(duelService.activeDuelCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("T-060: Team duel syntax '/status duel p1 vs p2 p3' splits sides correctly")
    void teamDuelSyntaxSplitsSides() {
        Player challenger = mockPlayer("Leader1", "socialblueprint.duel");
        Player ally = mockPlayer("Ally1", "socialblueprint.duel");
        Player enemy1 = mockPlayer("Enemy1", "socialblueprint.duel");
        Player enemy2 = mockPlayer("Enemy2", "socialblueprint.duel");

        // Leader1 challenges with team syntax: /status duel Ally1 vs Enemy1 Enemy2
        commandExecutor.onCommand(challenger, null, "status", new String[]{"duel", "Ally1", "vs", "Enemy1", "Enemy2"});

        assertThat(getLastCall(challenger).key()).isEqualTo("duel.challenge-sent");
        assertThat(getLastCall(ally).key()).isEqualTo("duel.challenge-received");
        assertThat(getLastCall(ally).placeholders()).containsEntry("challenger", "Leader1");
        assertThat(getLastCall(enemy1).key()).isEqualTo("duel.challenge-received");
        assertThat(getLastCall(enemy1).placeholders()).containsEntry("challenger", "Leader1");
        assertThat(getLastCall(enemy2).key()).isEqualTo("duel.challenge-received");
        assertThat(getLastCall(enemy2).placeholders()).containsEntry("challenger", "Leader1");
        assertThat(duelService.pendingChallengeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("T-060: /status leave leaves active duel or cancels outgoing challenge")
    void leaveActiveDuelAndChallenge() {
        Player alice = mockPlayer("Alice", "socialblueprint.duel");
        Player bob = mockPlayer("Bob", "socialblueprint.duel");

        // 1. Leave with outgoing challenge cancels it
        commandExecutor.onCommand(alice, null, "status", new String[]{"duel", "Bob"});
        commandExecutor.onCommand(alice, null, "status", new String[]{"leave"});
        assertThat(getLastCall(alice).key()).isEqualTo("duel.challenge-cancelled");

        // 2. Leave while not in a duel displays error
        commandExecutor.onCommand(alice, null, "status", new String[]{"leave"});
        assertThat(getLastCall(alice).key()).isEqualTo("duel.not-in-duel");

        // 3. Leave while in active duel forfeits
        commandExecutor.onCommand(alice, null, "status", new String[]{"duel", "Bob"});
        commandExecutor.onCommand(bob, null, "status", new String[]{"accept"});

        commandExecutor.onCommand(alice, null, "status", new String[]{"leave"});
        assertThat(getLastCall(alice).key()).isEqualTo("duel.left");
        assertThat(getLastCall(alice).placeholders()).containsEntry("player", "Alice");
        assertThat(getLastCall(bob).key()).isEqualTo("duel.left");
        assertThat(getLastCall(bob).placeholders()).containsEntry("player", "Alice");
        assertThat(duelService.activeDuelCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("T-060: Tab completion suggests duel subcommands, players, and 'vs'")
    void tabCompletionForDuel() {
        Player alice = mockPlayer("Alice", "socialblueprint.duel");
        mockPlayer("Bob", "socialblueprint.duel");
        mockPlayer("Charlie", "socialblueprint.duel");

        // /status <tab>
        List<String> topCompletions = commandExecutor.onTabComplete(alice, null, "status", new String[]{""});
        assertThat(topCompletions).contains("duel");

        // /status duel <tab>
        List<String> duelCompletions = commandExecutor.onTabComplete(alice, null, "status", new String[]{"duel", ""});
        assertThat(duelCompletions).contains("accept", "deny", "leave", "Bob", "Charlie");
        assertThat(duelCompletions).doesNotContain("Alice"); // Filter out self

        // /status duel Bob <tab>
        List<String> multiCompletions = commandExecutor.onTabComplete(alice, null, "status", new String[]{"duel", "Bob", ""});
        assertThat(multiCompletions).contains("vs", "Charlie");
    }

    private void copyResource(String resourceName, File destination) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new IllegalStateException("Resource not found: " + resourceName);
            }
            Files.copy(in, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
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
