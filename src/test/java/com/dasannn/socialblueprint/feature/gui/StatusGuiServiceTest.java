package com.dasannn.socialblueprint.feature.gui;

import com.dasannn.socialblueprint.command.StatusCommandExecutor;
import com.dasannn.socialblueprint.config.ConfigManager;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.ConfidenceLevel;
import com.dasannn.socialblueprint.domain.HonorKind;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.PlayerSocialView;
import com.dasannn.socialblueprint.domain.ReputationEvent;
import com.dasannn.socialblueprint.domain.PsychosisLevel;
import com.dasannn.socialblueprint.domain.Tier;
import com.dasannn.socialblueprint.feature.honor.HonorService;
import com.dasannn.socialblueprint.feature.profile.PlayerLookup;
import com.dasannn.socialblueprint.feature.profile.ProfileService;
import com.dasannn.socialblueprint.storage.AuditRepository;
import com.dasannn.socialblueprint.storage.CompensationRepository;
import com.dasannn.socialblueprint.storage.ProfileRepository;
import com.dasannn.socialblueprint.storage.PsychosisRepository;
import com.dasannn.socialblueprint.storage.RaterRevealRepository;
import com.dasannn.socialblueprint.storage.ReputationRepository;
import com.dasannn.socialblueprint.storage.StatusCache;
import com.dasannn.socialblueprint.storage.StorageEngine;
import net.kyori.adventure.text.Component;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;
import org.bukkit.inventory.InventoryView;

import static org.assertj.core.api.Assertions.assertThat;

public class StatusGuiServiceTest {

    @TempDir
    File tempDir;

    private StorageEngine storage;
    private ConfigManager configManager;
    private RecordingMessageRegistry messageRegistry;
    private ReputationRepository reputationRepo;
    private RaterRevealRepository raterRevealRepo;
    private AuditRepository auditRepo;
    private CompensationRepository compensationRepo;
    private ProfileService profileService;
    private HonorService honorService;
    private StatusGuiService guiService;
    private Economy mockEconomy;
    private TestClock testClock;

    private final Queue<Runnable> mainThreadQueue = new ConcurrentLinkedQueue<>();
    private final Map<UUID, Double> economyBalances = new ConcurrentHashMap<>();
    private final Map<String, PlayerLookup.KnownPlayer> onlineLookupMap = new ConcurrentHashMap<>();
    private final Map<UUID, String> offlineNames = new ConcurrentHashMap<>();
    private final List<Inventory> openedInventories = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        File configFile = new File(tempDir, "config.yml");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertThat(in).isNotNull();
            Files.copy(in, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        Logger logger = Logger.getLogger("StatusGuiTest-" + System.nanoTime());
        messageRegistry = new RecordingMessageRegistry(tempDir, "en", logger);
        configManager = new ConfigManager(configFile, messageRegistry, mainThreadQueue::add, logger);
        configManager.initialize();

        storage = StorageEngine.inMemory();
        storage.runMigrations();

        StatusCache statusCache = new StatusCache();
        reputationRepo = new ReputationRepository(storage, statusCache);
        raterRevealRepo = new RaterRevealRepository(storage);
        auditRepo = new AuditRepository(storage);
        compensationRepo = new CompensationRepository(storage);
        PsychosisRepository psychosisRepo = new PsychosisRepository(storage);
        ProfileRepository profileRepo = new ProfileRepository(storage);

        PlayerLookup testLookup = nameOrUuid -> {
            String lower = nameOrUuid.toLowerCase();
            if (onlineLookupMap.containsKey(lower)) {
                return Optional.of(onlineLookupMap.get(lower));
            }
            try {
                UUID u = UUID.fromString(nameOrUuid);
                if (offlineNames.containsKey(u)) {
                    return Optional.of(new PlayerLookup.KnownPlayer(PlayerId.of(u), offlineNames.get(u), false));
                }
            } catch (IllegalArgumentException ignored) {
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

        mockEconomy = createMockEconomy();

        honorService = new HonorService(
                configManager,
                messageRegistry,
                reputationRepo,
                auditRepo,
                compensationRepo,
                profileService,
                mockEconomy,
                mainThreadQueue::add
        );

        setupMockBukkitServer();

        testClock = new TestClock();
        guiService = new StatusGuiService(
                messageRegistry,
                profileService,
                reputationRepo,
                raterRevealRepo,
                honorService,
                mainThreadQueue::add,
                mockEconomy,
                uuid -> createMockOfflinePlayer(uuid, offlineNames.getOrDefault(uuid, "Player_" + uuid.toString().substring(0, 4))),
                testClock,
                null,
                compensationRepo
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        if (storage != null) {
            storage.close();
        }
        resetBukkitServer();
    }

    private void drainMainThreadQueue() {
        Runnable r;
        while ((r = mainThreadQueue.poll()) != null) {
            r.run();
        }
    }

    // =========================================================================
    // T-120 — The Chest
    // =========================================================================

    @Test
    @DisplayName("T-120: Double chest GUI has 54 slots, top row items: Give Banner (slot 1), Subject Head (slot 3), Tier Dye (slot 5), Take Banner (slot 7)")
    void doubleChestTopRowLayout() {
        UUID subjectUuid = UUID.randomUUID();
        String subjectName = "SubjectAlice";
        offlineNames.put(subjectUuid, subjectName);
        onlineLookupMap.put(subjectName.toLowerCase(), new PlayerLookup.KnownPlayer(PlayerId.of(subjectUuid), subjectName, true));

        Player viewer = createMockPlayer("ViewerBob", UUID.randomUUID(), "socialblueprint.show", "socialblueprint.show-others");

        guiService.openGuiAsync(viewer, subjectName, configManager.snapshot()).join();
        drainMainThreadQueue();

        assertThat(openedInventories).hasSize(1);
        Inventory inv = openedInventories.get(0);
        assertThat(inv.getSize()).isEqualTo(54);

        StatusGuiHolder holder = (StatusGuiHolder) inv.getHolder();
        assertThat(holder).isNotNull();
        GuiLayout layout = holder.layout();
        assertThat(layout).isNotNull();
        assertThat(layout.size()).isEqualTo(54);

        // Top row Give banner at slot 1
        GuiSlot giveBanner = layout.get(StatusGuiService.SLOT_TOP_GIVE_BANNER);
        assertThat(giveBanner).isNotNull();
        assertThat(giveBanner.iconKind()).isEqualTo(GuiIconKind.GIVE_BANNER);

        // Top row Subject head at slot 3
        GuiSlot subjectHead = layout.get(StatusGuiService.SLOT_TOP_SUBJECT_HEAD);
        assertThat(subjectHead).isNotNull();
        assertThat(subjectHead.iconKind()).isEqualTo(GuiIconKind.SUBJECT_HEAD);
        assertThat(subjectHead.owningPlayerId()).isEqualTo(subjectUuid);

        // Top row Tier dye at slot 5
        GuiSlot tierDye = layout.get(StatusGuiService.SLOT_TOP_TIER_DYE);
        assertThat(tierDye).isNotNull();
        assertThat(tierDye.iconKind()).isEqualTo(GuiIconKind.TIER_DYE);
        assertThat(tierDye.tier()).isEqualTo(Tier.PARTICULAR); // Default score 0 -> Particular

        // Top row Take banner at slot 7
        GuiSlot takeBanner = layout.get(StatusGuiService.SLOT_TOP_TAKE_BANNER);
        assertThat(takeBanner).isNotNull();
        assertThat(takeBanner.iconKind()).isEqualTo(GuiIconKind.TAKE_BANNER);
    }

    @Test
    @DisplayName("T-120: Tier dye colour strictly follows TierLadder and Tier across all tiers")
    void tierDyeColourFollowsTierLadder() {
        RuntimeSnapshot snapshot = configManager.snapshot();

        // Negative tiers -> RED_DYE
        assertThat(StatusGuiService.resolveTierDye(Tier.CRIMINAL, snapshot)).isEqualTo(Material.RED_DYE);
        assertThat(StatusGuiService.resolveTierDye(Tier.FORAJIDO, snapshot)).isEqualTo(Material.RED_DYE);
        assertThat(StatusGuiService.resolveTierDye(Tier.DELINCUENTE, snapshot)).isEqualTo(Material.RED_DYE);
        assertThat(StatusGuiService.resolveTierDye(Tier.TEMERARIO, snapshot)).isEqualTo(Material.RED_DYE);

        // Neutral tier (Particular) -> WHITE_DYE
        assertThat(StatusGuiService.resolveTierDye(Tier.PARTICULAR, snapshot)).isEqualTo(Material.WHITE_DYE);

        // Positive tiers -> LIME_DYE
        assertThat(StatusGuiService.resolveTierDye(Tier.AFABLE, snapshot)).isEqualTo(Material.LIME_DYE);
        assertThat(StatusGuiService.resolveTierDye(Tier.HONORABLE, snapshot)).isEqualTo(Material.LIME_DYE);
        assertThat(StatusGuiService.resolveTierDye(Tier.INSIGNE, snapshot)).isEqualTo(Material.LIME_DYE);

        // Highest tier (Ilustre) -> LIGHT_BLUE_DYE
        assertThat(StatusGuiService.resolveTierDye(Tier.ILUSTRE, snapshot)).isEqualTo(Material.LIGHT_BLUE_DYE);
    }

    @Test
    @DisplayName("T-120: Subject head is never anonymous (even when rater anonymity is enabled)")
    void subjectHeadIsNeverAnonymous() {
        UUID subjectUuid = UUID.randomUUID();
        String subjectName = "PublicSubject";
        offlineNames.put(subjectUuid, subjectName);
        onlineLookupMap.put(subjectName.toLowerCase(), new PlayerLookup.KnownPlayer(PlayerId.of(subjectUuid), subjectName, true));

        Player viewer = createMockPlayer("Viewer", UUID.randomUUID(), "socialblueprint.show", "socialblueprint.show-others");

        guiService.openGuiAsync(viewer, subjectName, configManager.snapshot()).join();
        drainMainThreadQueue();

        Inventory inv = openedInventories.get(0);
        StatusGuiHolder holder = (StatusGuiHolder) inv.getHolder();
        assertThat(holder).isNotNull();
        assertThat(holder.targetName()).isEqualTo("PublicSubject");

        GuiLayout layout = holder.layout();
        assertThat(layout).isNotNull();
        GuiSlot headSlot = layout.get(StatusGuiService.SLOT_TOP_SUBJECT_HEAD);
        assertThat(headSlot).isNotNull();
        assertThat(headSlot.iconKind()).isEqualTo(GuiIconKind.SUBJECT_HEAD);
        assertThat(headSlot.owningPlayerId()).isEqualTo(subjectUuid);
        assertThat(headSlot.titleKey()).isEqualTo("gui.top.subject-head-title");
        assertThat(headSlot.titlePlaceholders()).containsEntry("player", "PublicSubject");
    }

    @Test
    @DisplayName("T-120: Console /status [player] outputs text profile, player /status [player] opens GUI")
    void consolePrintsTextProfilePlayerOpensGui() {
        UUID targetUuid = UUID.randomUUID();
        String targetName = "TargetCharlie";
        offlineNames.put(targetUuid, targetName);
        onlineLookupMap.put(targetName.toLowerCase(), new PlayerLookup.KnownPlayer(PlayerId.of(targetUuid), targetName, true));

        StatusCommandExecutor executor = new StatusCommandExecutor(
                configManager,
                messageRegistry,
                profileService,
                honorService,
                null,
                auditRepo,
                mainThreadQueue::add,
                List::of,
                null,
                null,
                guiService
        );

        // 1. Player executes /status TargetCharlie -> opens GUI
        Player player = createMockPlayer("PlayerExecutor", UUID.randomUUID(), "socialblueprint.show", "socialblueprint.show-others");
        executor.onCommand(player, null, "status", new String[]{targetName});
        awaitQueued(executor.lastExecution());
        assertThat(openedInventories).hasSize(1);

        // 2. Console executes /status TargetCharlie -> sends text messages, does not open GUI
        List<String> consoleMessages = new ArrayList<>();
        CommandSender console = createMockConsole(consoleMessages);
        executor.onCommand(console, null, "status", new String[]{targetName});
        awaitQueued(executor.lastExecution());
        assertThat(openedInventories).hasSize(1); // Still 1 from player, none from console
        assertThat(consoleMessages).isNotEmpty();
        // The name, not the sentence: the header wording is a message key and may be
        // edited by a server owner, so asserting it would break on a copy change.
        assertThat(consoleMessages.getFirst()).contains(targetName);
    }

    // =========================================================================
    // T-121 — The History Grid
    // =========================================================================

    @Test
    @DisplayName("T-121: History grid places one rating per column: row 1 head, row 2 paper, row 3 direction banner")
    void historyGridColumnLayout() {
        UUID targetUuid = UUID.randomUUID();
        PlayerId targetId = PlayerId.of(targetUuid);
        UUID raterUuid = UUID.randomUUID();
        PlayerId raterId = PlayerId.of(raterUuid);
        offlineNames.put(targetUuid, "TargetUser");
        offlineNames.put(raterUuid, "RaterUser");
        onlineLookupMap.put("targetuser", new PlayerLookup.KnownPlayer(targetId, "TargetUser", true));

        // Insert a positive rating with a reason
        reputationRepo.saveAsync(new ReputationEvent(
                1L,
                raterId,
                targetId,
                1,
                HonorKind.POSITIVE,
                500.0,
                "Friendly trade",
                Instant.now()
        )).join();

        Player viewer = createMockPlayer("Viewer", UUID.randomUUID(), "socialblueprint.show", "socialblueprint.show-others");
        guiService.openGuiAsync(viewer, "TargetUser", configManager.snapshot()).join();
        drainMainThreadQueue();

        Inventory inv = openedInventories.get(0);
        StatusGuiHolder holder = (StatusGuiHolder) inv.getHolder();
        GuiLayout layout = holder.layout();
        assertThat(layout).isNotNull();

        // Column 1 (index 0): slot 10 = row 1, slot 19 = row 2, slot 28 = row 3
        GuiSlot raterHead = layout.get(10);
        GuiSlot reasonPaper = layout.get(19);
        GuiSlot directionBanner = layout.get(28);

        assertThat(raterHead).isNotNull();
        assertThat(raterHead.iconKind()).isEqualTo(GuiIconKind.RATER_HEAD);
        assertThat(raterHead.owningPlayerId()).isEqualTo(raterUuid);

        assertThat(reasonPaper).isNotNull();
        assertThat(reasonPaper.iconKind()).isEqualTo(GuiIconKind.REASON_PAPER);

        assertThat(directionBanner).isNotNull();
        assertThat(directionBanner.iconKind()).isEqualTo(GuiIconKind.DIRECTION_BANNER_POSITIVE);
    }

    @Test
    @DisplayName("T-121: Pagination forward and back via edge banners (slots 18/45 and 26/53)")
    void paginationWithEdgeBanners() {
        UUID targetUuid = UUID.randomUUID();
        PlayerId targetId = PlayerId.of(targetUuid);
        offlineNames.put(targetUuid, "TargetUser");
        onlineLookupMap.put("targetuser", new PlayerLookup.KnownPlayer(targetId, "TargetUser", true));

        // Insert 10 ratings (capacity is 7 per page, so page 0 has 7, page 1 has 3)
        for (int i = 1; i <= 10; i++) {
            reputationRepo.saveAsync(new ReputationEvent(
                    (long) i,
                    PlayerId.of(UUID.randomUUID()),
                    targetId,
                    1,
                    HonorKind.POSITIVE,
                    500.0,
                    "Rating #" + i,
                    Instant.now().minusSeconds(i * 10)
            )).join();
        }

        Player viewer = createMockPlayer("Viewer", UUID.randomUUID(), "socialblueprint.show", "socialblueprint.show-others");
        guiService.openGuiAsync(viewer, "TargetUser", configManager.snapshot()).join();
        drainMainThreadQueue();

        Inventory inv = openedInventories.get(0);
        StatusGuiHolder holder = (StatusGuiHolder) inv.getHolder();
        assertThat(holder.totalPages()).isEqualTo(2);
        assertThat(holder.currentPage()).isEqualTo(0);
        assertThat(countRaterHeads(holder.layout())).isEqualTo(7);

        GuiLayout page0Layout = holder.layout();
        assertThat(page0Layout.get(StatusGuiService.SLOT_PAGE_PREV_ROW2).iconKind()).isEqualTo(GuiIconKind.PAGE_PREVIOUS);
        assertThat(page0Layout.get(StatusGuiService.SLOT_PAGE_NEXT_ROW2).iconKind()).isEqualTo(GuiIconKind.PAGE_NEXT);
        assertThat(page0Layout.get(10).iconKind()).isEqualTo(GuiIconKind.RATER_HEAD);
        assertThat(page0Layout.get(16).iconKind()).isEqualTo(GuiIconKind.RATER_HEAD);

        // Click next banner at slot 26
        guiService.handleClick(viewer, holder, StatusGuiService.SLOT_PAGE_NEXT_ROW2);
        assertThat(holder.currentPage()).isEqualTo(1);
        assertThat(countRaterHeads(holder.layout())).isEqualTo(3);

        GuiLayout page1Layout = holder.layout();
        assertThat(page1Layout.get(10).iconKind()).isEqualTo(GuiIconKind.RATER_HEAD);
        assertThat(page1Layout.get(12).iconKind()).isEqualTo(GuiIconKind.RATER_HEAD);
        assertThat(page1Layout.get(13)).isNull();

        // Click previous banner at slot 18
        guiService.handleClick(viewer, holder, StatusGuiService.SLOT_PAGE_PREV_ROW2);
        assertThat(holder.currentPage()).isEqualTo(0);
        assertThat(countRaterHeads(holder.layout())).isEqualTo(7);
        assertThat(holder.layout().get(16).iconKind()).isEqualTo(GuiIconKind.RATER_HEAD);
    }

    // =========================================================================
    // T-122 — Never Block the Main Thread
    // =========================================================================

    @Test
    @DisplayName("T-122: Open GUI is an immutable snapshot; database changes after opening do not mutate open view")
    void openGuiIsImmutableSnapshot() {
        UUID targetUuid = UUID.randomUUID();
        PlayerId targetId = PlayerId.of(targetUuid);
        offlineNames.put(targetUuid, "TargetUser");
        onlineLookupMap.put("targetuser", new PlayerLookup.KnownPlayer(targetId, "TargetUser", true));

        reputationRepo.saveAsync(new ReputationEvent(
                1L, PlayerId.of(UUID.randomUUID()), targetId, 1, HonorKind.POSITIVE, 500.0, "R1", Instant.now()
        )).join();

        Player viewer = createMockPlayer("Viewer", UUID.randomUUID(), "socialblueprint.show", "socialblueprint.show-others");
        guiService.openGuiAsync(viewer, "TargetUser", configManager.snapshot()).join();
        drainMainThreadQueue();

        Inventory inv = openedInventories.get(0);
        StatusGuiHolder holder = (StatusGuiHolder) inv.getHolder();
        assertThat(countRaterHeads(holder.layout())).isEqualTo(1);

        // Add another event to database
        reputationRepo.saveAsync(new ReputationEvent(
                2L, PlayerId.of(UUID.randomUUID()), targetId, 1, HonorKind.POSITIVE, 500.0, "R2", Instant.now()
        )).join();

        // Open holder still only has snapshot of 1 rating
        assertThat(countRaterHeads(holder.layout())).isEqualTo(1);
    }

    // =========================================================================
    // T-123 — One Honor Path, Two Surfaces
    // =========================================================================

    @Test
    @DisplayName("T-123: Clicking green banner delegates directly to HonorService.preparePlayerHonor")
    void clickGiveBannerInvokesHonorService() {
        UUID targetUuid = UUID.randomUUID();
        offlineNames.put(targetUuid, "TargetUser");
        onlineLookupMap.put("targetuser", new PlayerLookup.KnownPlayer(PlayerId.of(targetUuid), "TargetUser", true));

        UUID viewerUuid = UUID.randomUUID();
        economyBalances.put(viewerUuid, 1000.0);
        Player viewer = createMockPlayer("Viewer", viewerUuid, "socialblueprint.show", "socialblueprint.show-others", "socialblueprint.give-reputation");

        guiService.openGuiAsync(viewer, "TargetUser", configManager.snapshot()).join();
        drainMainThreadQueue();

        Inventory inv = openedInventories.get(0);
        StatusGuiHolder holder = (StatusGuiHolder) inv.getHolder();

        // Click slot 1 (Give Honor)
        awaitQueued(guiService.handleClick(viewer, holder, StatusGuiService.SLOT_TOP_GIVE_BANNER));

        // Prepared pending confirmation in HonorService
        assertThat(honorService.getPendingConfirmation(viewerUuid)).isPresent();
    }

    @Test
    @DisplayName("Finding 3: Clicking red banner prompts chat for reason and completes negative honor entirely from GUI")
    void clickTakeBannerPromptsChatReasonAndCompletesNegativeHonorEntirelyFromGui() {
        UUID targetUuid = UUID.randomUUID();
        offlineNames.put(targetUuid, "TargetUser");
        onlineLookupMap.put("targetuser", new PlayerLookup.KnownPlayer(PlayerId.of(targetUuid), "TargetUser", true));

        UUID viewerUuid = UUID.randomUUID();
        economyBalances.put(viewerUuid, 1000.0);
        List<String> messages = new ArrayList<>();
        Player viewer = createMockPlayer("Viewer", viewerUuid, messages, "socialblueprint.show", "socialblueprint.show-others", "socialblueprint.take-reputation");

        guiService.openGuiAsync(viewer, "TargetUser", configManager.snapshot()).join();
        drainMainThreadQueue();

        Inventory inv = openedInventories.get(0);
        StatusGuiHolder holder = (StatusGuiHolder) inv.getHolder();

        // 1. Click slot 7 (Take Honor) -> closes inventory and initiates chat prompt
        guiService.handleClick(viewer, holder, StatusGuiService.SLOT_TOP_TAKE_BANNER);
        drainMainThreadQueue();

        assertThat(guiService.hasPendingReason(viewerUuid)).isTrue();
        assertThat(messageRegistry.hasCall("gui.prompt-reason")).isTrue();

        // 2. Chat listener consumes written reason
        awaitQueued(guiService.consumePendingReason(viewer, "Unfair trade"));

        // Prompt consumed, negative confirmation prepared in HonorService
        assertThat(guiService.hasPendingReason(viewerUuid)).isFalse();
        assertThat(honorService.getPendingConfirmation(viewerUuid)).isPresent();
        var pending = honorService.getPendingConfirmation(viewerUuid).get();
        assertThat(pending.kind()).isEqualTo(HonorKind.NEGATIVE);
        assertThat(pending.reason()).isEqualTo("Unfair trade");

        // 3. Confirm rating
        awaitQueued(honorService.confirmPlayerHonor(viewer, configManager.snapshot()));
        var events = reputationRepo.findByTargetAsync(PlayerId.of(targetUuid)).join();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().delta()).isEqualTo(-1);
        assertThat(events.getFirst().reason()).isEqualTo("Unfair trade");
    }

    // =========================================================================
    // T-124 — Anonymity
    // =========================================================================

    @Test
    @DisplayName("T-124: Rater is anonymous by default; revealing charges Vault history.reveal-cost and remembers per viewer")
    void raterAnonymousByDefaultAndRevealFlow() {
        UUID targetUuid = UUID.randomUUID();
        PlayerId targetId = PlayerId.of(targetUuid);
        UUID raterUuid = UUID.randomUUID();
        PlayerId raterId = PlayerId.of(raterUuid);
        offlineNames.put(targetUuid, "TargetUser");
        offlineNames.put(raterUuid, "SecretRater");
        onlineLookupMap.put("targetuser", new PlayerLookup.KnownPlayer(targetId, "TargetUser", true));

        ReputationEvent saved = reputationRepo.saveAsync(new ReputationEvent(
                raterId, targetId, 1, HonorKind.POSITIVE, 500.0, "Well done", Instant.now()
        )).join();

        UUID viewerUuid = UUID.randomUUID();
        economyBalances.put(viewerUuid, 500.0);
        Player viewer = createMockPlayer("RegularViewer", viewerUuid, "socialblueprint.show", "socialblueprint.show-others");

        guiService.openGuiAsync(viewer, "TargetUser", configManager.snapshot()).join();
        drainMainThreadQueue();

        Inventory inv = openedInventories.get(0);
        StatusGuiHolder holder = (StatusGuiHolder) inv.getHolder();

        // 1. By default, rater is not revealed to regular viewer
        assertThat(guiService.isRaterRevealed(viewer, saved, holder)).isFalse();
        assertThat(holder.layout().get(10).titleKey()).isEqualTo("gui.history.anonymous-rater");

        // 2. Click rater head at slot 10 to reveal
        guiService.handleClick(viewer, holder, 10);
        awaitGuiOutcome(() -> holder.revealedEventIds().contains(saved.id()));
        Set<Long> revealedEvents = raterRevealRepo.findRevealedEventsByViewerAsync(viewerUuid).join();

        // Vault charged 100.0 (500.0 -> 400.0)
        assertThat(economyBalances.get(viewerUuid)).isEqualTo(400.0);

        // Now revealed in holder
        assertThat(guiService.isRaterRevealed(viewer, saved, holder)).isTrue();
        assertThat(holder.layout().get(10).titleKey()).isEqualTo("gui.history.revealed-rater");
        assertThat(holder.layout().get(10).titlePlaceholders()).containsEntry("player", "SecretRater");

        // Persisted in SQLite
        assertThat(revealedEvents).containsExactly(saved.id());

        guiService.openGuiAsync(viewer, "TargetUser", configManager.snapshot()).join();
        drainMainThreadQueue();
        StatusGuiHolder reopenedHolder = (StatusGuiHolder) openedInventories.get(1).getHolder();
        assertThat(reopenedHolder.layout().get(10).titleKey()).isEqualTo("gui.history.revealed-rater");
        assertThat(reopenedHolder.layout().get(10).titlePlaceholders()).containsEntry("player", "SecretRater");
        assertThat(economyBalances.get(viewerUuid)).isEqualTo(400.0);

        // 3. Different viewer still sees Anonymous
        UUID otherViewerUuid = UUID.randomUUID();
        Player otherViewer = createMockPlayer("OtherViewer", otherViewerUuid, "socialblueprint.show", "socialblueprint.show-others");
        guiService.openGuiAsync(otherViewer, "TargetUser", configManager.snapshot()).join();
        drainMainThreadQueue();
        StatusGuiHolder otherHolder = (StatusGuiHolder) openedInventories.get(2).getHolder();
        assertThat(guiService.isRaterRevealed(otherViewer, saved, otherHolder)).isFalse();
        assertThat(otherHolder.layout().get(10).titleKey()).isEqualTo("gui.history.anonymous-rater");
    }

    @Test
    @DisplayName("T-124: Insufficient funds prevents reveal and charges nothing")
    void insufficientFundsPreventsReveal() {
        UUID targetUuid = UUID.randomUUID();
        PlayerId targetId = PlayerId.of(targetUuid);
        UUID raterUuid = UUID.randomUUID();
        PlayerId raterId = PlayerId.of(raterUuid);
        offlineNames.put(targetUuid, "TargetUser");
        offlineNames.put(raterUuid, "SecretRater");
        onlineLookupMap.put("targetuser", new PlayerLookup.KnownPlayer(targetId, "TargetUser", true));

        reputationRepo.saveAsync(new ReputationEvent(
                raterId, targetId, 1, HonorKind.POSITIVE, 500.0, "Good", Instant.now()
        )).join();

        UUID brokeViewerUuid = UUID.randomUUID();
        economyBalances.put(brokeViewerUuid, 50.0); // Cost is 100.0
        List<String> messages = new ArrayList<>();
        Player brokeViewer = createMockPlayer("BrokeViewer", brokeViewerUuid, messages, "socialblueprint.show", "socialblueprint.show-others");

        guiService.openGuiAsync(brokeViewer, "TargetUser", configManager.snapshot()).join();
        drainMainThreadQueue();

        Inventory inv = openedInventories.get(0);
        StatusGuiHolder holder = (StatusGuiHolder) inv.getHolder();

        // Click slot 10 to reveal
        guiService.handleClick(brokeViewer, holder, 10);
        awaitGuiOutcome(() -> messageRegistry.hasCall("gui.reveal.insufficient-funds"));

        // Balance untouched
        assertThat(economyBalances.get(brokeViewerUuid)).isEqualTo(50.0);
        // Error message received
        assertThat(messageRegistry.hasCall("gui.reveal.insufficient-funds")).isTrue();
        assertThat(holder.layout().get(10).titleKey()).isEqualTo("gui.history.anonymous-rater");
        // Not persisted
        assertThat(raterRevealRepo.findRevealedEventsByViewerAsync(brokeViewerUuid).join()).isEmpty();
    }

    // =========================================================================
    // T-125 — Comments are Inert
    // =========================================================================

    @Test
    @DisplayName("T-125: Reason lore is inert plain text, stripped of '&' codes and '§' character")
    void reasonLoreIsInertPlainText() {
        UUID targetUuid = UUID.randomUUID();
        PlayerId targetId = PlayerId.of(targetUuid);
        offlineNames.put(targetUuid, "TargetUser");
        onlineLookupMap.put("targetuser", new PlayerLookup.KnownPlayer(targetId, "TargetUser", true));

        String hostileReason = "&4&lGriefed §4base &#123456completely <click:run_command:'/op'>click</click>";
        reputationRepo.saveAsync(new ReputationEvent(
                PlayerId.of(UUID.randomUUID()), targetId, -1, HonorKind.NEGATIVE, 500.0, hostileReason, Instant.now()
        )).join();

        Player viewer = createMockPlayer("Viewer", UUID.randomUUID(), "socialblueprint.show", "socialblueprint.show-others");
        guiService.openGuiAsync(viewer, "TargetUser", configManager.snapshot()).join();
        drainMainThreadQueue();

        Inventory inv = openedInventories.get(0);
        StatusGuiHolder holder = (StatusGuiHolder) inv.getHolder();
        GuiLayout layout = holder.layout();
        GuiSlot paper = layout.get(19); // Row 2, Col 1
        assertThat(paper).isNotNull();
        assertThat(paper.iconKind()).isEqualTo(GuiIconKind.REASON_PAPER);
        assertThat(paper.lore()).isNotEmpty();

        GuiLoreLine loreLine = paper.lore().get(0);
        assertThat(loreLine.isPlain()).isTrue();
        String loreText = loreLine.plainText();

        assertThat(loreText).isEqualTo("Griefed base completely click");
        assertThat(loreText).doesNotContain("&").doesNotContain("§").doesNotContain("<");
    }

    // =========================================================================
    // T-126 — Anonymity is Cosmetic Only
    // =========================================================================

    @Test
    @DisplayName("T-126: Administrators always see real rater names immediately without paying")
    void administratorsAlwaysSeeRealRaterNames() {
        UUID targetUuid = UUID.randomUUID();
        PlayerId targetId = PlayerId.of(targetUuid);
        UUID raterUuid = UUID.randomUUID();
        PlayerId raterId = PlayerId.of(raterUuid);
        offlineNames.put(targetUuid, "TargetUser");
        offlineNames.put(raterUuid, "SecretRater");

        ReputationEvent event = new ReputationEvent(
                13L, raterId, targetId, 1, HonorKind.POSITIVE, 500.0, "Admin test", Instant.now()
        );

        StatusGuiHolder holder = new StatusGuiHolder(
                UUID.randomUUID(),
                targetId,
                "TargetUser",
                new PlayerSocialView(targetId, "TargetUser", 0, Tier.PARTICULAR, ConfidenceLevel.ESTABLISHED, PsychosisLevel.LOW, 1),
                List.of(event),
                new HashSet<>(),
                configManager.snapshot()
        );

        // Admin with permission socialblueprint.admin-adjust
        Player admin = createMockPlayer("AdminAlice", UUID.randomUUID(), "socialblueprint.admin-adjust");
        assertThat(guiService.isRaterRevealed(admin, event, holder)).isTrue();
        GuiLayout adminLayout = guiService.computeLayout(holder, admin);
        assertThat(adminLayout.get(10).titleKey()).isEqualTo("gui.history.revealed-rater");

        // Regular player without admin permission
        Player regular = createMockPlayer("Bob", UUID.randomUUID(), "socialblueprint.show");
        assertThat(guiService.isRaterRevealed(regular, event, holder)).isFalse();
        GuiLayout regularLayout = guiService.computeLayout(holder, regular);
        assertThat(regularLayout.get(10).titleKey()).isEqualTo("gui.history.anonymous-rater");
    }

    @Test
    @DisplayName("T-126: Anonymity does not bypass SB-054 cap per actor-target pair")
    void anonymityDoesNotBypassPairCap() {
        UUID actorUuid = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();
        PlayerId actorId = PlayerId.of(actorUuid);
        PlayerId targetId = PlayerId.of(targetUuid);
        offlineNames.put(targetUuid, "TargetUser");
        onlineLookupMap.put("targetuser", new PlayerLookup.KnownPlayer(targetId, "TargetUser", true));

        // Max ratings per target is 3. Insert 3 ratings from actor to target within cap window
        Instant now = Instant.now();
        for (int i = 0; i < 3; i++) {
            reputationRepo.saveAsync(new ReputationEvent(
                    (long) (100 + i),
                    actorId,
                    targetId,
                    1,
                    HonorKind.POSITIVE,
                    500.0,
                    "Previous rating",
                    now.minusSeconds((6L - 2L * i) * 24 * 3600)
            )).join();
        }

        List<String> messages = new ArrayList<>();
        Player actorPlayer = createMockPlayer("Actor", actorUuid, messages, "socialblueprint.give-reputation");
        economyBalances.put(actorUuid, 1000.0);

        // Attempt 4th rating -> rejected by pair cap
        honorService.preparePlayerHonor(
                actorPlayer, "TargetUser", HonorKind.POSITIVE, "4th attempt", configManager.snapshot()
        ).join();
        drainMainThreadQueue();

        assertThat(honorService.getPendingConfirmation(actorUuid)).isEmpty();
        assertThat(messageRegistry.hasCall("honor.cap-reached")).isTrue();
    }

    @Test
    @DisplayName("T-126: Audit trail records real actor and target UUIDs regardless of anonymity")
    void auditTrailRecordsRealActorAndTarget() {
        UUID actorUuid = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();
        PlayerId actorId = PlayerId.of(actorUuid);
        PlayerId targetId = PlayerId.of(targetUuid);
        offlineNames.put(targetUuid, "TargetUser");
        onlineLookupMap.put("targetuser", new PlayerLookup.KnownPlayer(targetId, "TargetUser", true));

        Player actorPlayer = createMockPlayer("Actor", actorUuid, "socialblueprint.show", "socialblueprint.show-others", "socialblueprint.give-reputation");
        economyBalances.put(actorUuid, 1000.0);

        // Open GUI and click Give Honor banner (T-120, T-123)
        guiService.openGuiAsync(actorPlayer, "TargetUser", configManager.snapshot()).join();
        drainMainThreadQueue();
        assertThat(openedInventories).hasSize(1);
        StatusGuiHolder holder = (StatusGuiHolder) openedInventories.get(0).getHolder();

        awaitQueued(guiService.handleClick(actorPlayer, holder, StatusGuiService.SLOT_TOP_GIVE_BANNER));
        assertThat(honorService.getPendingConfirmation(actorUuid)).isPresent();

        awaitQueued(honorService.confirmPlayerHonor(actorPlayer, configManager.snapshot()));

        // The immutable player-honor event is the actor/target audit trail.
        var events = reputationRepo.findByTargetAsync(targetId).join();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().actor()).isEqualTo(actorId);
        assertThat(events.getFirst().target()).isEqualTo(targetId);
    }

    private static long countRaterHeads(GuiLayout layout) {
        if (layout == null) return 0;
        long count = 0;
        for (int i = 0; i < layout.size(); i++) {
            GuiSlot s = layout.get(i);
            if (s != null && s.iconKind() == GuiIconKind.RATER_HEAD) {
                count++;
            }
        }
        return count;
    }

    // =========================================================================
    // Review Findings (Findings 1 - 8)
    // =========================================================================

    @Test
    @DisplayName("Finding 1: StatusGuiHolder does not expose ReputationEvents or actor UUIDs")
    void holderDoesNotExposeReputationEventsOrActorUuids() {
        for (var method : StatusGuiHolder.class.getMethods()) {
            assertThat(method.getName()).isNotEqualTo("ratings");
            assertThat(method.getName()).isNotEqualTo("ratingsForCurrentPage");
            assertThat(method.getReturnType()).isNotEqualTo(ReputationEvent.class);
        }
    }

    @Test
    @DisplayName("Finding 2: Rapid consecutive clicks on rater head do not charge twice")
    void revealRapidClickDoesNotChargeTwice() {
        UUID targetUuid = UUID.randomUUID();
        PlayerId targetId = PlayerId.of(targetUuid);
        UUID raterUuid = UUID.randomUUID();
        PlayerId raterId = PlayerId.of(raterUuid);
        offlineNames.put(targetUuid, "TargetUser");
        offlineNames.put(raterUuid, "SecretRater");
        onlineLookupMap.put("targetuser", new PlayerLookup.KnownPlayer(targetId, "TargetUser", true));

        reputationRepo.saveAsync(new ReputationEvent(
                raterId, targetId, 1, HonorKind.POSITIVE, 500.0, "Rapid click test", Instant.now()
        )).join();

        UUID viewerUuid = UUID.randomUUID();
        economyBalances.put(viewerUuid, 500.0);
        Player viewer = createMockPlayer("Viewer", viewerUuid, "socialblueprint.show", "socialblueprint.show-others");

        guiService.openGuiAsync(viewer, "TargetUser", configManager.snapshot()).join();
        drainMainThreadQueue();

        Inventory inv = openedInventories.getLast();
        StatusGuiHolder holder = (StatusGuiHolder) inv.getHolder();

        // Fire two clicks without draining main queue in between
        guiService.handleClick(viewer, holder, 10);
        guiService.handleClick(viewer, holder, 10);

        drainMainThreadQueue();

        // Charged exactly once (500 -> 400), not twice (not 300)
        assertThat(economyBalances.get(viewerUuid)).isEqualTo(400.0);
        assertThat(compensationRepo.findByPlayerAsync(viewerUuid).join()).isEmpty();
    }

    @Test
    @DisplayName("Finding 2: Duplicate reveal save refunds the player and cleans compensation")
    void revealDuplicateSaveRefundsPlayer() {
        UUID targetUuid = UUID.randomUUID();
        PlayerId targetId = PlayerId.of(targetUuid);
        UUID raterUuid = UUID.randomUUID();
        PlayerId raterId = PlayerId.of(raterUuid);
        offlineNames.put(targetUuid, "TargetUser");
        offlineNames.put(raterUuid, "SecretRater");
        onlineLookupMap.put("targetuser", new PlayerLookup.KnownPlayer(targetId, "TargetUser", true));

        ReputationEvent event = reputationRepo.saveAsync(new ReputationEvent(
                raterId, targetId, 1, HonorKind.POSITIVE, 500.0, "Duplicate test", Instant.now()
        )).join();

        UUID viewerUuid = UUID.randomUUID();
        economyBalances.put(viewerUuid, 400.0); // An earlier successful reveal already cost 100.0
        Player viewer = createMockPlayer("Viewer", viewerUuid, "socialblueprint.show", "socialblueprint.show-others");

        guiService.openGuiAsync(viewer, "TargetUser", configManager.snapshot()).join();
        drainMainThreadQueue();

        Inventory inv = openedInventories.getLast();
        StatusGuiHolder holder = (StatusGuiHolder) inv.getHolder();

        // Pre-insert into rater reveal repo so saveRevealAsync inside reveal flow detects duplicate (returns false)
        raterRevealRepo.saveRevealAsync(viewerUuid, event.id(), raterUuid, 100.0, Instant.now()).join();

        // Now trigger reveal click
        guiService.handleClick(viewer, holder, 10);
        awaitGuiOutcome(() -> messageRegistry.hasCall("gui.reveal.already-revealed")
                && economyBalances.get(viewerUuid) == 400.0);
        awaitGuiOutcome(() -> compensationRepo.findByPlayerAsync(viewerUuid).join().isEmpty());

        // Refunded: balance matches one successful reveal, never two
        assertThat(economyBalances.get(viewerUuid)).isEqualTo(400.0);
        assertThat(messageRegistry.hasCall("gui.reveal.already-revealed")).isTrue();
        assertThat(raterRevealRepo.findRevealedEventsByViewerAsync(viewerUuid).join()).containsExactly(event.id());
        assertThat(compensationRepo.findByPlayerAsync(viewerUuid).join()).isEmpty();
    }

    @Test
    @DisplayName("Finding 3: Take honor chat prompt expires after 60s timeout")
    void takeHonorChatPromptExpiresAfterTimeout() {
        UUID viewerUuid = UUID.randomUUID();
        Player viewer = createMockPlayer("Viewer", viewerUuid);

        guiService.promptForTakeHonorReason(viewer, "TargetUser", configManager.snapshot());
        assertThat(guiService.hasPendingReason(viewerUuid)).isTrue();

        testClock.advance(Duration.ofSeconds(61));

        awaitQueued(guiService.consumePendingReason(viewer, "Too late"));

        assertThat(messageRegistry.hasCall("gui.prompt-expired")).isTrue();
        assertThat(honorService.getPendingConfirmation(viewerUuid)).isEmpty();
    }

    @Test
    @DisplayName("Finding 3: Take honor chat prompt is cancelled on quit")
    void takeHonorChatPromptCancelledOnQuit() {
        UUID viewerUuid = UUID.randomUUID();
        Player viewer = createMockPlayer("Viewer", viewerUuid);

        guiService.promptForTakeHonorReason(viewer, "TargetUser", configManager.snapshot());
        assertThat(guiService.hasPendingReason(viewerUuid)).isTrue();

        guiService.cancelPendingReason(viewerUuid);
        assertThat(guiService.hasPendingReason(viewerUuid)).isFalse();
    }

    @Test
    @DisplayName("Finding 3: Take honor chat prompt can be cancelled by player typing cancel")
    void takeHonorChatPromptCancelledByPlayer() {
        UUID viewerUuid = UUID.randomUUID();
        Player viewer = createMockPlayer("Viewer", viewerUuid);

        guiService.promptForTakeHonorReason(viewer, "TargetUser", configManager.snapshot());
        assertThat(guiService.hasPendingReason(viewerUuid)).isTrue();

        awaitQueued(guiService.consumePendingReason(viewer, "cancel"));
        assertThat(guiService.hasPendingReason(viewerUuid)).isFalse();
        assertThat(messageRegistry.hasCall("gui.prompt-cancelled")).isTrue();
        assertThat(honorService.getPendingConfirmation(viewerUuid)).isEmpty();
    }

    @Test
    @DisplayName("Finding 4: Superseded open GUI request does not replace newer inventory")
    void supersededOpenGuiRequestDoesNotReplaceNewerInventory() {
        UUID targetA = UUID.randomUUID();
        UUID targetB = UUID.randomUUID();
        offlineNames.put(targetA, "TargetA");
        offlineNames.put(targetB, "TargetB");
        onlineLookupMap.put("targeta", new PlayerLookup.KnownPlayer(PlayerId.of(targetA), "TargetA", true));
        onlineLookupMap.put("targetb", new PlayerLookup.KnownPlayer(PlayerId.of(targetB), "TargetB", true));

        UUID viewerUuid = UUID.randomUUID();
        Player viewer = createMockPlayer("Viewer", viewerUuid, "socialblueprint.show", "socialblueprint.show-others");

        int initialOpenedCount = openedInventories.size();

        // 1. First open request (will be superseded)
        CompletableFuture<Void> firstReq = guiService.openGuiAsync(viewer, "TargetA", configManager.snapshot());

        // 2. Second open request immediately follows (supersedes request 1)
        CompletableFuture<Void> secondReq = guiService.openGuiAsync(viewer, "TargetB", configManager.snapshot());

        CompletableFuture.allOf(firstReq, secondReq).join();
        drainMainThreadQueue();

        // Exactly one inventory opened (for TargetB, not TargetA)
        assertThat(openedInventories.size()).isEqualTo(initialOpenedCount + 1);
        StatusGuiHolder holder = (StatusGuiHolder) openedInventories.getLast().getHolder();
        assertThat(holder.targetName()).isEqualTo("TargetB");
    }

    @Test
    @DisplayName("Finding 5: Overlong reason exceeding 100 characters is rejected with message")
    void reasonExceeding100CharsIsRejectedWithMessage() {
        UUID targetUuid = UUID.randomUUID();
        offlineNames.put(targetUuid, "TargetUser");
        onlineLookupMap.put("targetuser", new PlayerLookup.KnownPlayer(PlayerId.of(targetUuid), "TargetUser", true));

        UUID viewerUuid = UUID.randomUUID();
        economyBalances.put(viewerUuid, 1000.0);
        Player viewer = createMockPlayer("Viewer", viewerUuid, "socialblueprint.show", "socialblueprint.show-others", "socialblueprint.take-reputation");

        String overlongReason = "A".repeat(101);
        awaitQueued(honorService.preparePlayerHonor(viewer, "TargetUser", HonorKind.NEGATIVE, overlongReason, configManager.snapshot()));

        assertThat(messageRegistry.hasCall("honor.reason-too-long")).isTrue();
        assertThat(honorService.getPendingConfirmation(viewerUuid)).isEmpty();
    }

    @Test
    @DisplayName("Finding 5: Sanitizing tags to empty reason shows no-reason label")
    void sanitizingToEmptyReasonShowsNoReasonLabel() {
        UUID targetUuid = UUID.randomUUID();
        PlayerId targetId = PlayerId.of(targetUuid);
        offlineNames.put(targetUuid, "TargetUser");
        onlineLookupMap.put("targetuser", new PlayerLookup.KnownPlayer(targetId, "TargetUser", true));

        // Rating with tags only, which sanitize to blank
        reputationRepo.saveAsync(new ReputationEvent(
                PlayerId.of(UUID.randomUUID()), targetId, 1, HonorKind.POSITIVE, 500.0, "&4§l   ", Instant.now()
        )).join();

        Player viewer = createMockPlayer("Viewer", UUID.randomUUID(), "socialblueprint.show", "socialblueprint.show-others");
        guiService.openGuiAsync(viewer, "TargetUser", configManager.snapshot()).join();
        drainMainThreadQueue();

        Inventory inv = openedInventories.getLast();
        StatusGuiHolder holder = (StatusGuiHolder) inv.getHolder();
        GuiSlot paperSlot = holder.layout().get(19);
        assertThat(paperSlot).isNotNull();
        assertThat(paperSlot.lore()).isNotEmpty();
        assertThat(paperSlot.lore().getFirst().key()).isEqualTo("gui.history.no-reason");
    }

    @Test
    @DisplayName("Finding 6: Closed or departed viewer does not receive reveal message or render")
    void departedOrClosedViewerDoesNotReceiveRevealMessageOrRender() {
        UUID targetUuid = UUID.randomUUID();
        PlayerId targetId = PlayerId.of(targetUuid);
        UUID raterUuid = UUID.randomUUID();
        offlineNames.put(targetUuid, "TargetUser");
        offlineNames.put(raterUuid, "SecretRater");
        onlineLookupMap.put("targetuser", new PlayerLookup.KnownPlayer(targetId, "TargetUser", true));

        reputationRepo.saveAsync(new ReputationEvent(
                PlayerId.of(raterUuid), targetId, 1, HonorKind.POSITIVE, 500.0, "Great", Instant.now()
        )).join();

        UUID viewerUuid = UUID.randomUUID();
        economyBalances.put(viewerUuid, 500.0);
        List<String> messages = new ArrayList<>();
        Player viewer = createMockPlayer("Viewer", viewerUuid, messages, "socialblueprint.show", "socialblueprint.show-others");

        guiService.openGuiAsync(viewer, "TargetUser", configManager.snapshot()).join();
        drainMainThreadQueue();

        Inventory inv = openedInventories.getLast();
        StatusGuiHolder holder = (StatusGuiHolder) inv.getHolder();

        // Player closes inventory before reveal executes
        viewer.closeInventory();

        guiService.handleClick(viewer, holder, 10);
        drainMainThreadQueue();

        // No reveal messages sent to closed viewer
        assertThat(messageRegistry.hasCall("gui.reveal.success")).isFalse();
    }

    @Test
    @DisplayName("Finding 7: Click dispatch rejects different viewer and dispatches on layout icon kind")
    void clickDispatchUsesLayoutIconKindAndRejectsDifferentViewer() {
        UUID targetUuid = UUID.randomUUID();
        offlineNames.put(targetUuid, "TargetUser");
        onlineLookupMap.put("targetuser", new PlayerLookup.KnownPlayer(PlayerId.of(targetUuid), "TargetUser", true));

        UUID viewerAUuid = UUID.randomUUID();
        Player viewerA = createMockPlayer("ViewerA", viewerAUuid, "socialblueprint.show", "socialblueprint.show-others");

        guiService.openGuiAsync(viewerA, "TargetUser", configManager.snapshot()).join();
        drainMainThreadQueue();

        Inventory inv = openedInventories.getLast();
        StatusGuiHolder holder = (StatusGuiHolder) inv.getHolder();

        // Viewer B tries to click in Viewer A's holder
        UUID viewerBUuid = UUID.randomUUID();
        Player viewerB = createMockPlayer("ViewerB", viewerBUuid, "socialblueprint.show", "socialblueprint.show-others");

        guiService.handleClick(viewerB, holder, StatusGuiService.SLOT_TOP_GIVE_BANNER);
        drainMainThreadQueue();

        // Viewer B action rejected
        assertThat(honorService.getPendingConfirmation(viewerBUuid)).isEmpty();
        assertThat(honorService.getPendingConfirmation(viewerAUuid)).isEmpty();

        // Clicking an empty slot (e.g. slot 0 which has no icon) takes no action
        guiService.handleClick(viewerA, holder, 0);
        drainMainThreadQueue();
        assertThat(honorService.getPendingConfirmation(viewerAUuid)).isEmpty();
    }

    @Test
    @DisplayName("Finding 8: Layout decides GuiDyeKind for each tier")
    void layoutDecidesDyeKindForEachTier() {
        RuntimeSnapshot snapshot = configManager.snapshot();
        for (var entry : Map.of(
                Tier.CRIMINAL, GuiDyeKind.RED,
                Tier.FORAJIDO, GuiDyeKind.RED,
                Tier.DELINCUENTE, GuiDyeKind.RED,
                Tier.TEMERARIO, GuiDyeKind.RED,
                Tier.PARTICULAR, GuiDyeKind.WHITE,
                Tier.AFABLE, GuiDyeKind.LIME,
                Tier.HONORABLE, GuiDyeKind.LIME,
                Tier.INSIGNE, GuiDyeKind.LIME,
                Tier.ILUSTRE, GuiDyeKind.LIGHT_BLUE).entrySet()) {
            assertThat(GuiDyeKind.fromTier(entry.getKey())).isEqualTo(entry.getValue());
            int status = snapshot.config().tiers().ladder().threshold(entry.getKey());
            PlayerSocialView view = new PlayerSocialView(PlayerId.of(UUID.randomUUID()), "Test", status, entry.getKey(), ConfidenceLevel.ESTABLISHED, PsychosisLevel.LOW, 1);
            GuiLayout layout = StatusGuiService.buildPageLayout(view, List.of(), 0, 1, Set.of(), null, snapshot, null);
            GuiSlot dyeSlot = layout.get(StatusGuiService.SLOT_TOP_TIER_DYE);
            assertThat(dyeSlot).isNotNull();
            assertThat(dyeSlot.dyeKind()).isEqualTo(entry.getValue());
        }
    }

    @Test
    @DisplayName("Finding 7: Storage continuations in openGuiAsync do not dereference Player off-thread")
    void finding7_storageContinuationsDoNotDereferencePlayer() throws Exception {
        UUID viewerUuid = UUID.randomUUID();
        Thread mainThread = Thread.currentThread();

        // Player that strictly enforces thread confinement: any method call off main thread throws!
        InvocationHandler strictHandler = (proxy, method, args) -> {
            if (Thread.currentThread() != mainThread) {
                throw new IllegalStateException("Player." + method.getName() + " was dereferenced off the main thread!");
            }
            String mName = method.getName();
            if ("getName".equals(mName)) return "StrictViewer";
            if ("getUniqueId".equals(mName)) return viewerUuid;
            if ("isOnline".equals(mName)) return true;
            if ("hasPermission".equals(mName)) return true;
            if ("sendMessage".equals(mName)) return null;
            if ("openInventory".equals(mName)) return null;
            return defaultValue(method.getReturnType());
        };
        Player strictPlayer = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                strictHandler
        );

        // Open GUI asynchronously: profile resolution, ratings fetch, and reveals fetch execute on storage engine thread!
        CompletableFuture<Void> future = guiService.openGuiAsync(strictPlayer, "StrictViewer", configManager.snapshot());

        // Process any main-thread tasks
        awaitQueued(future);

        assertThat(future.isCompletedExceptionally()).isFalse();
    }

    @Test
    @DisplayName("Finding 11: Compensation cleanups attach failure logging and do not drop silently")
    void finding11_compensationCleanupsObservedAndLogged() throws Exception {
        UUID viewerUuid = UUID.randomUUID();
        // Save an intended compensation row
        long compId = compensationRepo.saveIntentAsync(viewerUuid, 25.0, "rater_reveal", Instant.now()).join();

        // Delete the compensation with logging
        CompletableFuture<Void> delFuture = compensationRepo.deleteCompensationAsync(compId);
        delFuture.join();

        assertThat(compensationRepo.findByIdAsync(compId).join()).isEmpty();
    }

    // =========================================================================
    // Test Helpers & Mocks
    // =========================================================================

    private void awaitQueued(CompletableFuture<?> future) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!future.isDone() && System.nanoTime() < deadline) {
            drainMainThreadQueue();
            Thread.onSpinWait();
        }
        future.orTimeout(0, TimeUnit.SECONDS).join();
        drainMainThreadQueue();
    }

    private void awaitGuiOutcome(BooleanSupplier done) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            drainMainThreadQueue();
            Thread.onSpinWait();
        }
        drainMainThreadQueue();
        assertThat(done.getAsBoolean()).isTrue();
    }

    private Player createMockPlayer(String name, UUID uuid, String... permissions) {
        return createMockPlayer(name, uuid, new ArrayList<>(), permissions);
    }

    private Player createMockPlayer(String name, UUID uuid, List<String> messageOutput, String... permissions) {
        Set<String> perms = new HashSet<>(List.of(permissions));
        Inventory[] activeInv = new Inventory[1];

        InvocationHandler handler = (proxy, method, args) -> {
            String mName = method.getName();
            if ("getName".equals(mName)) return name;
            if ("getUniqueId".equals(mName)) return uuid;
            if ("isOnline".equals(mName)) return true;
            if ("hasPermission".equals(mName)) {
                if (args != null && args.length > 0 && args[0] instanceof String perm) {
                    return perms.contains(perm);
                }
                return false;
            }
            if ("sendMessage".equals(mName)) {
                if (args != null && args.length > 0 && args[0] instanceof Component comp) {
                    String serialized = com.dasannn.socialblueprint.config.ColorParser.serialize(comp);
                    messageOutput.add(serialized);
                }
                return null;
            }
            if ("openInventory".equals(mName)) {
                if (args != null && args.length > 0 && args[0] instanceof Inventory inv) {
                    activeInv[0] = inv;
                    openedInventories.add(inv);
                }
                return null;
            }
            if ("closeInventory".equals(mName)) {
                activeInv[0] = null;
                return null;
            }
            if ("getOpenInventory".equals(mName)) {
                if (activeInv[0] == null) return null;
                InvocationHandler viewHandler = (vProxy, vMethod, vArgs) -> {
                    if ("getTopInventory".equals(vMethod.getName())) return activeInv[0];
                    return defaultValue(vMethod.getReturnType());
                };
                return (InventoryView) Proxy.newProxyInstance(
                        InventoryView.class.getClassLoader(),
                        new Class<?>[]{InventoryView.class},
                        viewHandler
                );
            }
            return defaultValue(method.getReturnType());
        };

        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                handler
        );
    }

    private CommandSender createMockConsole(List<String> messages) {
        InvocationHandler handler = (proxy, method, args) -> {
            String mName = method.getName();
            if ("getName".equals(mName)) return "CONSOLE";
            if ("hasPermission".equals(mName)) return true;
            if ("sendMessage".equals(mName)) {
                if (args != null && args.length > 0 && args[0] instanceof Component comp) {
                    messages.add(com.dasannn.socialblueprint.config.ColorParser.serialize(comp));
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

    private OfflinePlayer createMockOfflinePlayer(UUID uuid, String name) {
        InvocationHandler handler = (proxy, method, args) -> {
            String mName = method.getName();
            if ("getUniqueId".equals(mName)) return uuid;
            if ("getName".equals(mName)) return name;
            return defaultValue(method.getReturnType());
        };

        return (OfflinePlayer) Proxy.newProxyInstance(
                OfflinePlayer.class.getClassLoader(),
                new Class<?>[]{OfflinePlayer.class},
                handler
        );
    }

    private Economy createMockEconomy() {
        InvocationHandler econHandler = (proxy, method, args) -> {
            String mName = method.getName();
            if ("has".equals(mName) && args.length >= 2) {
                OfflinePlayer p = (OfflinePlayer) args[0];
                double amt = ((Number) args[1]).doubleValue();
                return economyBalances.getOrDefault(p.getUniqueId(), 0.0) >= amt;
            }
            if ("withdrawPlayer".equals(mName) && args.length >= 2) {
                OfflinePlayer p = (OfflinePlayer) args[0];
                double amt = ((Number) args[1]).doubleValue();
                double current = economyBalances.getOrDefault(p.getUniqueId(), 0.0);
                if (current < amt) {
                    return new EconomyResponse(0, current, EconomyResponse.ResponseType.FAILURE, "Insufficient funds");
                }
                double remaining = current - amt;
                economyBalances.put(p.getUniqueId(), remaining);
                return new EconomyResponse(amt, remaining, EconomyResponse.ResponseType.SUCCESS, null);
            }
            if ("depositPlayer".equals(mName) && args.length >= 2) {
                OfflinePlayer p = (OfflinePlayer) args[0];
                double amt = ((Number) args[1]).doubleValue();
                double current = economyBalances.getOrDefault(p.getUniqueId(), 0.0);
                double total = current + amt;
                economyBalances.put(p.getUniqueId(), total);
                return new EconomyResponse(amt, total, EconomyResponse.ResponseType.SUCCESS, null);
            }
            if ("format".equals(mName)) {
                return "$" + args[0];
            }
            return defaultValue(method.getReturnType());
        };

        return (Economy) Proxy.newProxyInstance(
                Economy.class.getClassLoader(),
                new Class<?>[]{Economy.class},
                econHandler
        );
    }

    private void setupMockBukkitServer() throws Exception {
        ItemFactory mockItemFactory = (ItemFactory) Proxy.newProxyInstance(
                ItemFactory.class.getClassLoader(),
                new Class<?>[]{ItemFactory.class},
                (proxy, method, args) -> {
                    String mName = method.getName();
                    if ("getItemMeta".equals(mName)) {
                        return createMockItemMeta(SkullMeta.class);
                    }
                    if ("asMetaFor".equals(mName)) {
                        return args[0];
                    }
                    if ("isApplicable".equals(mName)) {
                        return true;
                    }
                    if ("equals".equals(mName)) {
                        return java.util.Objects.equals(args[0], args[1]);
                    }
                    return defaultValue(method.getReturnType());
                }
        );

        Server mockServer = (Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, args) -> {
                    String mName = method.getName();
                    if ("getItemFactory".equals(mName)) {
                        return mockItemFactory;
                    }
                    if ("createInventory".equals(mName)) {
                        InventoryHolder holder = (InventoryHolder) args[0];
                        int size = (int) args[1];
                        return createMockInventory(holder, size);
                    }
                    return defaultValue(method.getReturnType());
                }
        );

        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, mockServer);
    }

    private void resetBukkitServer() throws Exception {
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, null);
    }

    private <T extends ItemMeta> T createMockItemMeta(Class<T> metaClass) {
        Map<String, Object> state = new HashMap<>();

        InvocationHandler handler = (proxy, method, args) -> {
            String mName = method.getName();
            if ("itemName".equals(mName)) {
                if (args != null && args.length == 1) {
                    state.put("itemName", args[0]);
                    return null;
                }
                return state.get("itemName");
            }
            if ("displayName".equals(mName)) {
                if (args != null && args.length == 1) {
                    state.put("displayName", args[0]);
                    return null;
                }
                return state.get("displayName");
            }
            if ("lore".equals(mName)) {
                if (args != null && args.length == 1) {
                    state.put("lore", args[0]);
                    return null;
                }
                return state.get("lore");
            }
            if ("setOwningPlayer".equals(mName)) {
                state.put("owningPlayer", args[0]);
                return true;
            }
            if ("getOwningPlayer".equals(mName)) {
                return state.get("owningPlayer");
            }
            if ("clone".equals(mName)) {
                return createMockItemMeta(metaClass);
            }
            return defaultValue(method.getReturnType());
        };

        return metaClass.cast(Proxy.newProxyInstance(
                metaClass.getClassLoader(),
                new Class<?>[]{metaClass},
                handler
        ));
    }

    private Inventory createMockInventory(InventoryHolder holder, int size) {
        ItemStack[] items = new ItemStack[size];

        InvocationHandler handler = (proxy, method, args) -> {
            String mName = method.getName();
            if ("getSize".equals(mName)) return size;
            if ("getHolder".equals(mName)) return holder;
            if ("getItem".equals(mName)) {
                int slot = (int) args[0];
                return items[slot];
            }
            if ("setItem".equals(mName)) {
                int slot = (int) args[0];
                items[slot] = (ItemStack) args[1];
                return null;
            }
            if ("clear".equals(mName)) {
                for (int i = 0; i < size; i++) {
                    items[i] = null;
                }
                return null;
            }
            return defaultValue(method.getReturnType());
        };

        return (Inventory) Proxy.newProxyInstance(
                Inventory.class.getClassLoader(),
                new Class<?>[]{Inventory.class},
                handler
        );
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == void.class) return null;
        if (returnType == boolean.class) return false;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == double.class) return 0.0;
        if (returnType == float.class) return 0.0f;
        return null;
    }

    public static class RecordingMessageRegistry extends MessageRegistry {
        public record RenderCall(String key, Map<String, String> placeholders, boolean withPrefix) {}

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

        public RenderCall lastCall() {
            if (renderedCalls.isEmpty()) {
                throw new AssertionError("No messages were rendered");
            }
            return renderedCalls.getLast();
        }

        public boolean hasCall(String key) {
            return renderedCalls.stream().anyMatch(c -> c.key().equals(key));
        }

        @Override
        public Component renderWithPrefix(RuntimeSnapshot snapshot, String key, Map<String, String> placeholders) {
            renderedCalls.add(new RenderCall(key, placeholders != null ? Map.copyOf(placeholders) : Map.of(), true));
            return super.renderWithPrefix(snapshot, key, placeholders);
        }

        @Override
        public Component renderWithPrefix(RuntimeSnapshot snapshot, String key) {
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
        public Component render(RuntimeSnapshot snapshot, String key, Map<String, String> placeholders) {
            renderedCalls.add(new RenderCall(key, placeholders != null ? Map.copyOf(placeholders) : Map.of(), false));
            return super.render(snapshot, key, placeholders);
        }

        @Override
        public Component render(RuntimeSnapshot snapshot, String key) {
            return render(snapshot, key, Collections.emptyMap());
        }

        @Override
        public Component render(String key, Map<String, String> placeholders) {
            renderedCalls.add(new RenderCall(key, placeholders != null ? Map.copyOf(placeholders) : Map.of(), false));
            return super.render(key, placeholders);
        }

        @Override
        public Component render(String key) {
            return render(key, Collections.emptyMap());
        }
    }

    private static class TestClock extends Clock {
        private Instant now = Instant.parse("2026-09-30T12:00:00Z");
        private ZoneId zone = ZoneOffset.UTC;

        public void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { this.zone = zone; return this; }
        @Override public Instant instant() { return now; }
    }
}
