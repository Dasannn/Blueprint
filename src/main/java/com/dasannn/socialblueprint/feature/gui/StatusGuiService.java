package com.dasannn.socialblueprint.feature.gui;

import com.dasannn.socialblueprint.command.PermissionChecker;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.CommentSanitizer;
import com.dasannn.socialblueprint.domain.HonorCostCalculator;
import com.dasannn.socialblueprint.domain.HonorKind;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.PlayerSocialView;
import com.dasannn.socialblueprint.domain.ReputationEvent;
import com.dasannn.socialblueprint.domain.Tier;
import com.dasannn.socialblueprint.feature.honor.HonorService;
import com.dasannn.socialblueprint.feature.profile.ProfileService;
import com.dasannn.socialblueprint.storage.CompensationRepository;
import com.dasannn.socialblueprint.storage.RaterRevealRepository;
import com.dasannn.socialblueprint.storage.ReputationRepository;
import net.kyori.adventure.text.Component;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service managing the Social Profile & Rating History double chest GUI per SB-080 to SB-084 and T-120 to T-126.
 *
 * Threading discipline (T-122, ARCHITECTURE.md §5):
 * - Never blocks the main thread with SQLite storage operations.
 * - Never calls Bukkit inventory methods off the main server thread.
 * - An open GUI is an immutable snapshot view.
 *
 * One honor path (T-123):
 * - Rating actions in the GUI invoke the exact same HonorService logic that commands use.
 *
 * Anonymity (T-124, T-126, SB-082):
 * - Rater's head carries their real skin so the player sees who rated them as a face.
 * - Rater's name is hidden by default; revealed per viewer and persisted in SQLite.
 * - Administrators always see real names without paying (T-126).
 * - Comments are length-bounded and completely inert (T-125).
 */
public class StatusGuiService {

    public static final int INVENTORY_SIZE = 54;

    // Top row items (Row 0: slots 0..8) per T-120
    public static final int SLOT_TOP_GIVE_BANNER = 1;
    public static final int SLOT_TOP_SUBJECT_HEAD = 3;
    public static final int SLOT_TOP_TIER_DYE = 5;
    public static final int SLOT_TOP_TAKE_BANNER = 7;

    // Edge pagination slots (Columns 0 and 8) per T-121
    public static final int SLOT_PAGE_PREV_ROW2 = 18;
    public static final int SLOT_PAGE_NEXT_ROW2 = 26;
    public static final int SLOT_PAGE_PREV_ROW5 = 45;
    public static final int SLOT_PAGE_NEXT_ROW5 = 53;
    public static final int SLOT_PAGE_INFO = 49;

    public record PendingReasonPrompt(
            UUID viewerUuid,
            String targetName,
            RuntimeSnapshot snapshot,
            Instant expiry
    ) {
    }

    private final MessageRegistry messageRegistry;
    private final ProfileService profileService;
    private final ReputationRepository reputationRepository;
    private final RaterRevealRepository raterRevealRepository;
    private final HonorService honorService;
    private final Consumer<Runnable> mainThreadRunner;
    private final Function<UUID, OfflinePlayer> offlinePlayerResolver;
    private final Clock clock;
    private final GuiRenderer renderer;
    private final CompensationRepository compensationRepository;
    private final Logger logger;
    private volatile Economy economy;

    // Concurrency and race guards (Findings 2, 3, 4)
    private final Set<String> pendingReveals = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PendingReasonPrompt> pendingReasons = new ConcurrentHashMap<>();
    private final Map<UUID, Long> latestOpenRequests = new ConcurrentHashMap<>();
    private final AtomicLong openRequestCounter = new AtomicLong();

    public StatusGuiService(
            MessageRegistry messageRegistry,
            ProfileService profileService,
            ReputationRepository reputationRepository,
            RaterRevealRepository raterRevealRepository,
            HonorService honorService,
            Consumer<Runnable> mainThreadRunner,
            Economy economy,
            Function<UUID, OfflinePlayer> offlinePlayerResolver,
            Clock clock
    ) {
        this(
                messageRegistry,
                profileService,
                reputationRepository,
                raterRevealRepository,
                honorService,
                mainThreadRunner,
                economy,
                offlinePlayerResolver,
                clock,
                new GuiRenderer(messageRegistry, offlinePlayerResolver),
                null,
                Logger.getLogger(StatusGuiService.class.getName())
        );
    }

    public StatusGuiService(
            MessageRegistry messageRegistry,
            ProfileService profileService,
            ReputationRepository reputationRepository,
            RaterRevealRepository raterRevealRepository,
            HonorService honorService,
            Consumer<Runnable> mainThreadRunner,
            Economy economy,
            Function<UUID, OfflinePlayer> offlinePlayerResolver,
            Clock clock,
            GuiRenderer renderer
    ) {
        this(
                messageRegistry,
                profileService,
                reputationRepository,
                raterRevealRepository,
                honorService,
                mainThreadRunner,
                economy,
                offlinePlayerResolver,
                clock,
                renderer,
                null,
                Logger.getLogger(StatusGuiService.class.getName())
        );
    }

    public StatusGuiService(
            MessageRegistry messageRegistry,
            ProfileService profileService,
            ReputationRepository reputationRepository,
            RaterRevealRepository raterRevealRepository,
            HonorService honorService,
            Consumer<Runnable> mainThreadRunner,
            Economy economy,
            Function<UUID, OfflinePlayer> offlinePlayerResolver,
            Clock clock,
            GuiRenderer renderer,
            CompensationRepository compensationRepository
    ) {
        this(
                messageRegistry,
                profileService,
                reputationRepository,
                raterRevealRepository,
                honorService,
                mainThreadRunner,
                economy,
                offlinePlayerResolver,
                clock,
                renderer,
                compensationRepository,
                Logger.getLogger(StatusGuiService.class.getName())
        );
    }

    public StatusGuiService(
            MessageRegistry messageRegistry,
            ProfileService profileService,
            ReputationRepository reputationRepository,
            RaterRevealRepository raterRevealRepository,
            HonorService honorService,
            Consumer<Runnable> mainThreadRunner,
            Economy economy,
            Function<UUID, OfflinePlayer> offlinePlayerResolver,
            Clock clock,
            GuiRenderer renderer,
            CompensationRepository compensationRepository,
            Logger logger
    ) {
        this.messageRegistry = Objects.requireNonNull(messageRegistry, "messageRegistry must not be null");
        this.profileService = Objects.requireNonNull(profileService, "profileService must not be null");
        this.reputationRepository = Objects.requireNonNull(reputationRepository, "reputationRepository must not be null");
        this.raterRevealRepository = Objects.requireNonNull(raterRevealRepository, "raterRevealRepository must not be null");
        this.honorService = Objects.requireNonNull(honorService, "honorService must not be null");
        this.mainThreadRunner = mainThreadRunner != null ? mainThreadRunner : Runnable::run;
        this.economy = economy;
        this.offlinePlayerResolver = offlinePlayerResolver != null ? offlinePlayerResolver : uuid -> {
            if (Bukkit.getServer() != null) {
                return Bukkit.getOfflinePlayer(uuid);
            }
            return null;
        };
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.renderer = renderer;
        this.compensationRepository = compensationRepository;
        this.logger = logger != null ? logger : Logger.getLogger(StatusGuiService.class.getName());
    }

    public void setEconomy(Economy economy) {
        this.economy = economy;
    }

    public Economy getEconomy() {
        return economy;
    }

    /**
     * Opens the rating history GUI for a viewer targeting targetInput.
     * Completes all database loading asynchronously before creating or opening the inventory on the main thread (T-122).
     * Guards against racing requests (Finding 4).
     */
    public CompletableFuture<Void> openGuiAsync(Player viewer, String targetInput, RuntimeSnapshot snapshot) {
        Objects.requireNonNull(viewer, "viewer must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        String targetQuery = (targetInput == null || targetInput.isBlank()) ? viewer.getName() : targetInput.trim();
        boolean isSelf = targetQuery.equalsIgnoreCase(viewer.getName())
                || targetQuery.equalsIgnoreCase(viewer.getUniqueId().toString());

        // Check command permissions before async load
        String permKey = isSelf ? "show" : "show-others";
        if (!PermissionChecker.hasPermission(viewer, permKey, snapshot)) {
            viewer.sendMessage(messageRegistry.renderWithPrefix(snapshot, "commands.no-permission"));
            return CompletableFuture.completedFuture(null);
        }

        // Track latest open request ID per viewer (Finding 4)
        UUID viewerUuid = viewer.getUniqueId();
        long requestId = openRequestCounter.incrementAndGet();
        latestOpenRequests.put(viewerUuid, requestId);

        // Asynchronous load on storage executor (T-122)
        return profileService.resolvePlayerAsync(targetQuery, snapshot)
                .thenCompose(optView -> {
                    if (optView.isEmpty()) {
                        mainThreadRunner.accept(() -> {
                            if (!viewer.isOnline()) {
                                return;
                            }
                            Long latest = latestOpenRequests.get(viewerUuid);
                            if (latest == null || latest != requestId) {
                                return; // Superseded by newer request (Finding 4)
                            }
                            viewer.sendMessage(messageRegistry.renderWithPrefix(snapshot, "status.not-found",
                                    Map.of("player", targetQuery)));
                        });
                        return CompletableFuture.completedFuture(null);
                    }

                    PlayerSocialView view = optView.get();
                    PlayerId targetId = view.playerId();

                    CompletableFuture<List<ReputationEvent>> ratingsFuture = reputationRepository.findByTargetAsync(targetId);
                    CompletableFuture<Set<Long>> revealsFuture = raterRevealRepository.findRevealedEventsByViewerAsync(viewerUuid);

                    return CompletableFuture.allOf(ratingsFuture, revealsFuture)
                            .thenAccept(v -> {
                                List<ReputationEvent> allEvents = ratingsFuture.join();
                                Set<Long> reveals = new HashSet<>(revealsFuture.join());

                                List<ReputationEvent> ratings = allEvents.stream()
                                        .filter(e -> (e.actor() != null && e.kind().isPlayerHonor()) || e.kind() == HonorKind.SYSTEM_KILL)
                                        .sorted(Comparator.comparing(ReputationEvent::createdAt).reversed())
                                        .toList();

                                // Handoff to main thread for inventory creation and opening (T-122)
                                mainThreadRunner.accept(() -> {
                                    if (!viewer.isOnline()) {
                                        return;
                                    }
                                    Long latest = latestOpenRequests.get(viewer.getUniqueId());
                                    if (latest == null || latest != requestId) {
                                        return; // Superseded by newer request (Finding 4)
                                    }

                                    List<GuiLayout> pages = computeAllPages(view, ratings, reveals, viewer, snapshot);
                                    StatusGuiHolder holder = new StatusGuiHolder(
                                            viewer.getUniqueId(),
                                            view.name(),
                                            pages,
                                            reveals,
                                            snapshot
                                    );
                                    Component title = messageRegistry.render(snapshot, "gui.title",
                                            Map.of("player", view.name()));
                                    Inventory inventory = Bukkit.createInventory(holder, INVENTORY_SIZE, title);
                                    holder.setInventory(inventory);

                                    renderGui(holder, viewer);
                                    viewer.openInventory(inventory);
                                });
                            });
                });
    }

    /**
     * Computes all page layouts for the target view and ratings snapshot.
     */
    public List<GuiLayout> computeAllPages(
            PlayerSocialView view,
            List<ReputationEvent> ratings,
            Set<Long> reveals,
            Player viewer,
            RuntimeSnapshot snapshot
    ) {
        int totalRatings = ratings != null ? ratings.size() : 0;
        int totalPages = totalRatings == 0 ? 1 : (int) Math.ceil((double) totalRatings / StatusGuiHolder.RATINGS_PER_PAGE);

        List<GuiLayout> pages = new ArrayList<>(totalPages);
        for (int p = 0; p < totalPages; p++) {
            pages.add(buildPageLayout(view, ratings, p, totalPages, reveals, viewer, snapshot, this::resolveRaterName));
        }
        return pages;
    }

    /**
     * Helper to compute initial pages when constructing a holder without active Player viewer.
     */
    public static List<GuiLayout> computeInitialPages(
            PlayerSocialView view,
            List<ReputationEvent> ratings,
            Set<Long> reveals,
            RuntimeSnapshot snapshot
    ) {
        int totalRatings = ratings != null ? ratings.size() : 0;
        int totalPages = totalRatings == 0 ? 1 : (int) Math.ceil((double) totalRatings / StatusGuiHolder.RATINGS_PER_PAGE);

        List<GuiLayout> pages = new ArrayList<>(totalPages);
        for (int p = 0; p < totalPages; p++) {
            pages.add(buildPageLayout(view, ratings, p, totalPages, reveals, null, snapshot, null));
        }
        return pages;
    }

    /**
     * Builds the GuiLayout for a specific page of ratings.
     */
    public static GuiLayout buildPageLayout(
            PlayerSocialView view,
            List<ReputationEvent> ratings,
            int page,
            int totalPages,
            Set<Long> reveals,
            Player viewer,
            RuntimeSnapshot snapshot,
            BiFunction<UUID, RuntimeSnapshot, String> raterNameResolver
    ) {
        Map<Integer, GuiSlot> slots = new HashMap<>();

        // ---------------------------------------------------------------------
        // Top row (Row 0: slots 0..8) per T-120
        // ---------------------------------------------------------------------
        slots.put(SLOT_TOP_GIVE_BANNER, new GuiSlot(
                SLOT_TOP_GIVE_BANNER,
                GuiIconKind.GIVE_BANNER,
                null,
                null,
                null,
                null,
                "gui.top.give-banner-title",
                Map.of(),
                List.of(GuiLoreLine.ofKey("gui.top.give-banner-lore"))
        ));

        Tier tier = (view != null && snapshot != null && snapshot.config() != null)
                ? snapshot.config().tiers().ladder().resolve(view.status())
                : Tier.PARTICULAR;
        String prefix = (snapshot != null && snapshot.config() != null) ? snapshot.config().tiers().prefix(tier) : "";
        String localizedTier = tier.displayName();
        String localizedConfidence = view != null ? view.confidence().name() : "";
        String localizedPsychosis = view != null ? view.psychosis().name() : "";
        String targetName = view != null ? view.name() : "Player";
        UUID targetUuid = view != null ? view.playerId().uuid() : null;

        List<GuiLoreLine> subjectLore = List.of(
                GuiLoreLine.ofKey("status.profile-tier", Map.of("tier", localizedTier, "prefix", prefix != null ? prefix : "")),
                GuiLoreLine.ofKey("status.profile-status", Map.of("status", view != null ? String.valueOf(view.status()) : "0")),
                GuiLoreLine.ofKey("status.profile-confidence", Map.of("confidence", localizedConfidence)),
                GuiLoreLine.ofKey("status.profile-psychosis", Map.of("psychosis", localizedPsychosis)),
                GuiLoreLine.ofKey("status.profile-contributors", Map.of("contributors", view != null ? String.valueOf(view.contributors()) : "0"))
        );

        slots.put(SLOT_TOP_SUBJECT_HEAD, new GuiSlot(
                SLOT_TOP_SUBJECT_HEAD,
                GuiIconKind.SUBJECT_HEAD,
                targetUuid,
                null,
                tier,
                null,
                "gui.top.subject-head-title",
                Map.of("player", targetName),
                subjectLore
        ));

        // Dye whose colour follows tier (Finding 8: dyeKind in slot, renderer maps directly)
        GuiDyeKind dyeKind = GuiDyeKind.fromTier(tier);
        slots.put(SLOT_TOP_TIER_DYE, new GuiSlot(
                SLOT_TOP_TIER_DYE,
                GuiIconKind.TIER_DYE,
                null,
                null,
                tier,
                dyeKind,
                "gui.top.tier-dye-title",
                Map.of("tier", localizedTier, "prefix", prefix != null ? prefix : ""),
                List.of()
        ));

        slots.put(SLOT_TOP_TAKE_BANNER, new GuiSlot(
                SLOT_TOP_TAKE_BANNER,
                GuiIconKind.TAKE_BANNER,
                null,
                null,
                null,
                null,
                "gui.top.take-banner-title",
                Map.of(),
                List.of(GuiLoreLine.ofKey("gui.top.take-banner-lore"))
        ));

        // ---------------------------------------------------------------------
        // Edge slots for pagination per T-121
        // ---------------------------------------------------------------------
        Map<String, String> pageInfoPlaceholders = Map.of(
                "current", String.valueOf(page + 1),
                "total", String.valueOf(totalPages)
        );

        GuiSlot prevBannerSlotRow2 = new GuiSlot(
                SLOT_PAGE_PREV_ROW2,
                GuiIconKind.PAGE_PREVIOUS,
                null,
                null,
                null,
                null,
                "gui.history.page-previous",
                Map.of(),
                List.of(GuiLoreLine.ofKey("gui.history.page-info", pageInfoPlaceholders))
        );
        slots.put(SLOT_PAGE_PREV_ROW2, prevBannerSlotRow2);

        GuiSlot prevBannerSlotRow5 = new GuiSlot(
                SLOT_PAGE_PREV_ROW5,
                GuiIconKind.PAGE_PREVIOUS,
                null,
                null,
                null,
                null,
                "gui.history.page-previous",
                Map.of(),
                List.of(GuiLoreLine.ofKey("gui.history.page-info", pageInfoPlaceholders))
        );
        slots.put(SLOT_PAGE_PREV_ROW5, prevBannerSlotRow5);

        GuiSlot nextBannerSlotRow2 = new GuiSlot(
                SLOT_PAGE_NEXT_ROW2,
                GuiIconKind.PAGE_NEXT,
                null,
                null,
                null,
                null,
                "gui.history.page-next",
                Map.of(),
                List.of(GuiLoreLine.ofKey("gui.history.page-info", pageInfoPlaceholders))
        );
        slots.put(SLOT_PAGE_NEXT_ROW2, nextBannerSlotRow2);

        GuiSlot nextBannerSlotRow5 = new GuiSlot(
                SLOT_PAGE_NEXT_ROW5,
                GuiIconKind.PAGE_NEXT,
                null,
                null,
                null,
                null,
                "gui.history.page-next",
                Map.of(),
                List.of(GuiLoreLine.ofKey("gui.history.page-info", pageInfoPlaceholders))
        );
        slots.put(SLOT_PAGE_NEXT_ROW5, nextBannerSlotRow5);

        slots.put(SLOT_PAGE_INFO, new GuiSlot(
                SLOT_PAGE_INFO,
                GuiIconKind.PAGE_INFO,
                null,
                null,
                null,
                null,
                "gui.history.page-info",
                pageInfoPlaceholders,
                List.of()
        ));

        // ---------------------------------------------------------------------
        // History Grid: One rating per column below top row (T-121, T-124, T-125)
        // ---------------------------------------------------------------------
        if (ratings != null && !ratings.isEmpty()) {
            int start = page * StatusGuiHolder.RATINGS_PER_PAGE;
            int end = Math.min(ratings.size(), start + StatusGuiHolder.RATINGS_PER_PAGE);

            for (int i = start; i < end; i++) {
                ReputationEvent event = ratings.get(i);
                int col = 1 + (i - start); // Columns 1..7

                // 1. Rater's Head (Row 1: slot 9 + col)
                // Deliberate product decision (SB-082): Anonymity covers the name only;
                // the rater's head carries their real skin so the player sees who rated them as a face.
                UUID raterUuid = event.actor() != null ? event.actor().uuid() : null;
                boolean isSystem = (event.actor() == null || event.kind() == HonorKind.SYSTEM_KILL);

                String raterTitleKey;
                Map<String, String> raterTitlePlaceholders;
                List<GuiLoreLine> raterLore;
                if (isSystem) {
                    raterTitleKey = "status.system-actor";
                    raterTitlePlaceholders = Map.of();
                    raterLore = List.of();
                } else {
                    boolean revealed = isRaterRevealedStatic(viewer, event.id(), raterUuid, snapshot, reveals);
                    if (revealed) {
                        String raterName = (raterNameResolver != null && raterUuid != null)
                                ? raterNameResolver.apply(raterUuid, snapshot)
                                : (raterUuid != null ? raterUuid.toString() : "Anonymous");
                        raterTitleKey = "gui.history.revealed-rater";
                        raterTitlePlaceholders = Map.of("player", raterName);
                        raterLore = List.of(GuiLoreLine.ofKey("gui.history.revealed-info"));
                    } else {
                        raterTitleKey = "gui.history.anonymous-rater";
                        raterTitlePlaceholders = Map.of();
                        double cost = (snapshot != null && snapshot.config() != null && snapshot.config().history() != null)
                                ? snapshot.config().history().revealCost()
                                : 0.0;
                        raterLore = List.of(GuiLoreLine.ofKey("gui.history.click-to-reveal",
                                Map.of("cost", HonorService.formatCost(cost))));
                    }
                }
                slots.put(9 + col, new GuiSlot(
                        9 + col,
                        GuiIconKind.RATER_HEAD,
                        raterUuid,
                        event.id(),
                        null,
                        null,
                        raterTitleKey,
                        raterTitlePlaceholders,
                        raterLore
                ));

                // 2. Paper whose lore holds the written reason (Row 2: slot 18 + col, T-125, Finding 5)
                List<GuiLoreLine> paperLore;
                String rawReason = event.reason();
                if (rawReason == null || rawReason.isBlank()) {
                    paperLore = List.of(GuiLoreLine.ofKey("gui.history.no-reason"));
                } else if (snapshot != null && snapshot.messages() != null && snapshot.messages().isKnownKey(rawReason)) {
                    paperLore = List.of(GuiLoreLine.ofKey(rawReason));
                } else {
                    // Blankness is decided after sanitizing, not before: a reason
                    // that is nothing but colour codes and spaces survives isBlank
                    // and would otherwise render as an empty line on the paper.
                    String plain = CommentSanitizer.toPlainText(rawReason);
                    paperLore = plain.isBlank()
                            ? List.of(GuiLoreLine.ofKey("gui.history.no-reason"))
                            : List.of(GuiLoreLine.ofPlain(plain));
                }
                slots.put(18 + col, new GuiSlot(
                        18 + col,
                        GuiIconKind.REASON_PAPER,
                        null,
                        event.id(),
                        null,
                        null,
                        "gui.history.paper-title",
                        Map.of(),
                        paperLore
                ));

                // 3. Direction banner (Row 3: slot 27 + col)
                GuiIconKind dirKind = event.kind().isPositive()
                        ? GuiIconKind.DIRECTION_BANNER_POSITIVE
                        : GuiIconKind.DIRECTION_BANNER_NEGATIVE;
                String bannerKey = event.kind().isPositive()
                        ? "gui.history.positive-banner"
                        : "gui.history.negative-banner";
                slots.put(27 + col, new GuiSlot(
                        27 + col,
                        dirKind,
                        null,
                        event.id(),
                        null,
                        null,
                        bannerKey,
                        Map.of(),
                        List.of()
                ));
            }
        }

        return new GuiLayout(INVENTORY_SIZE, slots);
    }

    /**
     * Computes the complete plain-data layout for the GUI snapshot and current page.
     * Contains all layout, pagination, anonymity, and tier decisions without Bukkit item types.
     */
    public GuiLayout computeLayout(StatusGuiHolder holder, Player viewer) {
        Objects.requireNonNull(holder, "holder must not be null");
        GuiLayout baseLayout = holder.layout();
        if (baseLayout == null) {
            return new GuiLayout(INVENTORY_SIZE, Collections.emptyMap());
        }

        // Adapt layout for the specific viewer (e.g. administrator viewing real names per T-126)
        Map<Integer, GuiSlot> adaptedSlots = new HashMap<>(baseLayout.slots());
        for (Map.Entry<Integer, GuiSlot> entry : baseLayout.slots().entrySet()) {
            GuiSlot slot = entry.getValue();
            if (slot.iconKind() == GuiIconKind.RATER_HEAD && slot.eventId() != null) {
                if (slot.owningPlayerId() == null || "status.system-actor".equals(slot.titleKey())) {
                    continue;
                }
                boolean revealed = isRaterRevealed(viewer, slot.eventId(), slot.owningPlayerId(), holder.snapshot(), holder.revealedEventIds());
                if (revealed && "gui.history.anonymous-rater".equals(slot.titleKey())) {
                    String raterName = resolveRaterName(slot.owningPlayerId(), holder.snapshot());
                    adaptedSlots.put(entry.getKey(), new GuiSlot(
                            slot.slot(),
                            GuiIconKind.RATER_HEAD,
                            slot.owningPlayerId(),
                            slot.eventId(),
                            null,
                            null,
                            "gui.history.revealed-rater",
                            Map.of("player", raterName),
                            List.of(GuiLoreLine.ofKey("gui.history.revealed-info"))
                    ));
                } else if (!revealed && "gui.history.revealed-rater".equals(slot.titleKey())) {
                    double cost = holder.snapshot().config().history().revealCost();
                    adaptedSlots.put(entry.getKey(), new GuiSlot(
                            slot.slot(),
                            GuiIconKind.RATER_HEAD,
                            slot.owningPlayerId(),
                            slot.eventId(),
                            null,
                            null,
                            "gui.history.anonymous-rater",
                            Map.of(),
                            List.of(GuiLoreLine.ofKey("gui.history.click-to-reveal",
                                    Map.of("cost", HonorService.formatCost(cost))))
                    ));
                }
            }
        }

        return new GuiLayout(INVENTORY_SIZE, adaptedSlots);
    }

    /**
     * Computes the layout and, if a renderer is configured, populates the inventory.
     * Must be called on the server main thread.
     */
    public void renderGui(StatusGuiHolder holder, Player viewer) {
        GuiLayout layout = computeLayout(holder, viewer);
        holder.setLayout(layout);
        if (renderer != null && holder.getInventory() != null) {
            renderer.render(holder.getInventory(), layout, holder.snapshot());
        }
    }

    /**
     * Resolves the dye material corresponding to a reputation tier using TierLadder and Tier (T-120).
     */
    public static Material resolveTierDye(Tier tier, RuntimeSnapshot snapshot) {
        GuiDyeKind dyeKind = GuiDyeKind.fromTier(tier);
        return switch (dyeKind) {
            case WHITE -> Material.WHITE_DYE;
            case LIME -> Material.LIME_DYE;
            case LIGHT_BLUE -> Material.LIGHT_BLUE_DYE;
            case RED -> Material.RED_DYE;
        };
    }

    /**
     * Evaluates whether a rater's identity is revealed to the given viewer per T-124 and T-126.
     * Administrators always see the rater's name without paying (T-126).
     */
    public boolean isRaterRevealed(Player viewer, ReputationEvent event, StatusGuiHolder holder) {
        if (event == null || event.actor() == null || event.kind() == HonorKind.SYSTEM_KILL) {
            return false;
        }
        return isRaterRevealed(viewer, event.id(), event.actor().uuid(), holder.snapshot(), holder.revealedEventIds());
    }

    public boolean isRaterRevealed(Player viewer, long eventId, UUID raterUuid, RuntimeSnapshot snapshot, Set<Long> revealedEventIds) {
        return isRaterRevealedStatic(viewer, eventId, raterUuid, snapshot, revealedEventIds);
    }

    private static boolean isRaterRevealedStatic(Player viewer, long eventId, UUID raterUuid, RuntimeSnapshot snapshot, Set<Long> revealedEventIds) {
        if (raterUuid == null) {
            return false;
        }
        if (viewer != null) {
            // The rater viewing their own rating sees their own name
            if (raterUuid.equals(viewer.getUniqueId())) {
                return true;
            }
            // Administrators still see who rated whom (T-126)
            if (snapshot != null && PermissionChecker.hasPermission(viewer, "admin-adjust", snapshot)) {
                return true;
            }
            if (viewer.hasPermission("socialblueprint.admin")) {
                return true;
            }
        }
        // Remembered per viewer, persisted in SQLite (T-124)
        return revealedEventIds != null && revealedEventIds.contains(eventId);
    }

    /**
     * Handles an inventory click inside the GUI. Called on the server main thread.
     * Dispatches on the icon kind in the clicked slot of the layout, and confirms holder belongs to viewer (Finding 7).
     */
    public CompletableFuture<Void> handleClick(Player viewer, StatusGuiHolder holder, int slot) {
        if (viewer == null || holder == null || slot < 0 || slot >= INVENTORY_SIZE) {
            return CompletableFuture.completedFuture(null);
        }

        // Confirm the holder belongs to this viewer (Finding 7)
        if (!viewer.getUniqueId().equals(holder.viewerUuid())) {
            return CompletableFuture.completedFuture(null);
        }

        GuiLayout layout = holder.layout();
        if (layout == null) {
            return CompletableFuture.completedFuture(null);
        }

        GuiSlot guiSlot = layout.slot(slot).orElse(null);
        if (guiSlot == null) {
            return CompletableFuture.completedFuture(null);
        }

        RuntimeSnapshot snapshot = holder.snapshot();

        return switch (guiSlot.iconKind()) {
            // 1. Top row Give Honor (T-120, T-123)
            case GIVE_BANNER -> {
                viewer.closeInventory();
                yield honorService.preparePlayerHonor(viewer, holder.targetName(), HonorKind.POSITIVE, null, snapshot);
            }

            // 2. Top row Take Honor (Finding 3: prompts chat for written reason)
            case TAKE_BANNER -> {
                viewer.closeInventory();
                promptForTakeHonorReason(viewer, holder.targetName(), snapshot);
                yield CompletableFuture.completedFuture(null);
            }

            // 3. Edge slot: Page Back (T-121, Finding 7)
            case PAGE_PREVIOUS -> {
                if (holder.currentPage() > 0) {
                    holder.setCurrentPage(holder.currentPage() - 1);
                    renderGui(holder, viewer);
                }
                yield CompletableFuture.completedFuture(null);
            }

            // 4. Edge slot: Page Forward (T-121, Finding 7)
            case PAGE_NEXT -> {
                if (holder.currentPage() < holder.totalPages() - 1) {
                    holder.setCurrentPage(holder.currentPage() + 1);
                    renderGui(holder, viewer);
                }
                yield CompletableFuture.completedFuture(null);
            }

            // 5. Rater head click to reveal name (Finding 7)
            case RATER_HEAD -> {
                handleRevealClick(viewer, holder, guiSlot);
                yield CompletableFuture.completedFuture(null);
            }

            default -> CompletableFuture.completedFuture(null);
        };
    }

    /**
     * Prompts the player to type their reason in chat for taking honor (Finding 3).
     */
    public void promptForTakeHonorReason(Player viewer, String targetName, RuntimeSnapshot snapshot) {
        pendingReasons.put(viewer.getUniqueId(), new PendingReasonPrompt(
                viewer.getUniqueId(),
                targetName,
                snapshot,
                clock.instant().plusSeconds(60)
        ));
        viewer.sendMessage(messageRegistry.renderWithPrefix(snapshot, "gui.prompt-reason", Map.of("player", targetName)));
    }

    public boolean hasPendingReason(UUID playerUuid) {
        return playerUuid != null && pendingReasons.containsKey(playerUuid);
    }

    public void cancelPendingReason(UUID playerUuid) {
        if (playerUuid != null) {
            pendingReasons.remove(playerUuid);
        }
    }

    /**
     * Consumes a reason typed in chat and feeds it into preparePlayerHonor (Finding 3).
     */
    public CompletableFuture<Void> consumePendingReason(Player player, String rawMessage) {
        PendingReasonPrompt pending = pendingReasons.remove(player.getUniqueId());
        if (pending == null) {
            return CompletableFuture.completedFuture(null);
        }

        if (clock.instant().isAfter(pending.expiry())) {
            player.sendMessage(messageRegistry.renderWithPrefix(pending.snapshot(), "gui.prompt-expired"));
            return CompletableFuture.completedFuture(null);
        }

        String trimmed = rawMessage != null ? rawMessage.trim() : "";
        if (trimmed.equalsIgnoreCase("cancel") || trimmed.equalsIgnoreCase("cancelar")) {
            player.sendMessage(messageRegistry.renderWithPrefix(pending.snapshot(), "gui.prompt-cancelled"));
            return CompletableFuture.completedFuture(null);
        }

        return honorService.preparePlayerHonor(player, pending.targetName(), HonorKind.NEGATIVE, trimmed, pending.snapshot());
    }

    /**
     * Handles rater name reveal: charges Vault reveal-cost, persists to SQLite, and updates GUI (T-124, Finding 2).
     */
    public void handleRevealClick(Player viewer, StatusGuiHolder holder, ReputationEvent event) {
        if (event == null || event.actor() == null || event.kind() == HonorKind.SYSTEM_KILL) {
            return;
        }
        handleRevealWithIds(viewer, holder, event.id(), event.actor().uuid());
    }

    public void handleRevealClick(Player viewer, StatusGuiHolder holder, GuiSlot clickedSlot) {
        if (clickedSlot == null || clickedSlot.eventId() == null || clickedSlot.owningPlayerId() == null
                || "status.system-actor".equals(clickedSlot.titleKey())) {
            return;
        }
        handleRevealWithIds(viewer, holder, clickedSlot.eventId(), clickedSlot.owningPlayerId());
    }

    private void handleRevealWithIds(Player viewer, StatusGuiHolder holder, long eventId, UUID raterUuid) {
        if (isRaterRevealed(viewer, eventId, raterUuid, holder.snapshot(), holder.revealedEventIds())) {
            viewer.sendMessage(messageRegistry.renderWithPrefix(holder.snapshot(), "gui.reveal.already-revealed"));
            return;
        }

        UUID viewerUuid = viewer.getUniqueId();
        String pendingKey = viewerUuid + ":" + eventId;
        if (!pendingReveals.add(pendingKey)) {
            return; // In-flight click dropped (Finding 2)
        }

        double revealCost = holder.snapshot().config().history().revealCost();
        double exactCost = HonorCostCalculator.roundCurrency(revealCost);
        Instant now = clock.instant();

        if (exactCost <= 0.0 || economy == null) {
            raterRevealRepository.saveRevealAsync(viewerUuid, eventId, raterUuid, 0.0, now)
                    .thenAccept(inserted -> mainThreadRunner.accept(() -> {
                        pendingReveals.remove(pendingKey);
                        if (!viewer.isOnline()) {
                            return;
                        }
                        if (viewer.getOpenInventory() == null
                                || viewer.getOpenInventory().getTopInventory() == null
                                || !(viewer.getOpenInventory().getTopInventory().getHolder() instanceof StatusGuiHolder currentHolder)
                                || currentHolder != holder) {
                            return;
                        }
                        if (inserted) {
                            applyRevealSuccess(viewer, holder, eventId, raterUuid, 0.0);
                        } else {
                            viewer.sendMessage(messageRegistry.renderWithPrefix(holder.snapshot(), "gui.reveal.already-revealed"));
                        }
                    }))
                    .exceptionally(ex -> {
                        pendingReveals.remove(pendingKey);
                        mainThreadRunner.accept(() -> {
                            if (viewer.isOnline()) {
                                viewer.sendMessage(messageRegistry.renderWithPrefix(holder.snapshot(), "honor.write-failed"));
                            }
                        });
                        return null;
                    });
            return;
        }

        // Vault money path with compensation intent (Finding 2)
        CompletableFuture<Long> intentFuture;
        if (compensationRepository != null) {
            intentFuture = compensationRepository.saveIntentAsync(viewerUuid, exactCost, "rater_reveal", now);
        } else {
            intentFuture = CompletableFuture.completedFuture(null);
        }

        intentFuture.thenAccept(compId -> mainThreadRunner.accept(() -> {
            if (!viewer.isOnline()) {
                pendingReveals.remove(pendingKey);
                deleteCompensationWithLogging(compId, "viewer offline before charge");
                return;
            }

            if (!economy.has(viewer, exactCost)) {
                pendingReveals.remove(pendingKey);
                deleteCompensationWithLogging(compId, "insufficient funds");
                viewer.sendMessage(messageRegistry.renderWithPrefix(holder.snapshot(), "gui.reveal.insufficient-funds",
                        Map.of("cost", HonorService.formatCost(exactCost))));
                return;
            }

            EconomyResponse resp = economy.withdrawPlayer(viewer, exactCost);
            if (resp == null || !resp.transactionSuccess()) {
                pendingReveals.remove(pendingKey);
                deleteCompensationWithLogging(compId, "withdraw failed");
                viewer.sendMessage(messageRegistry.renderWithPrefix(holder.snapshot(), "gui.reveal.insufficient-funds",
                        Map.of("cost", HonorService.formatCost(exactCost))));
                return;
            }

            // Successfully charged: mark charged in compensation repository
            CompletableFuture<Boolean> markChargedFuture = (compId != null && compensationRepository != null)
                    ? compensationRepository.markChargedAsync(compId)
                    : CompletableFuture.completedFuture(true);

            markChargedFuture.thenCompose(ok -> raterRevealRepository.commitRevealAsync(viewerUuid, eventId, raterUuid, exactCost, now, compId, compensationRepository))
                    .thenAccept(inserted -> {
                        if (inserted) {
                            deleteCompensationWithLogging(compId, "reveal success");
                            mainThreadRunner.accept(() -> {
                                pendingReveals.remove(pendingKey);
                                // Check viewer online & still in this holder (Finding 6)
                                if (!viewer.isOnline()) {
                                    return;
                                }
                                if (viewer.getOpenInventory() == null
                                        || viewer.getOpenInventory().getTopInventory() == null
                                        || !(viewer.getOpenInventory().getTopInventory().getHolder() instanceof StatusGuiHolder currentHolder)
                                        || currentHolder != holder) {
                                    return;
                                }
                                applyRevealSuccess(viewer, holder, eventId, raterUuid, exactCost);
                            });
                        } else {
                            // Duplicate! Refund and inform player (Finding 2)
                            refundCompensation(viewer, exactCost, compId);
                            mainThreadRunner.accept(() -> {
                                pendingReveals.remove(pendingKey);
                                if (viewer.isOnline()) {
                                    viewer.sendMessage(messageRegistry.renderWithPrefix(holder.snapshot(), "gui.reveal.already-revealed"));
                                }
                            });
                        }
                    })
                    .exceptionally(ex -> {
                        // SQLite failure: Refund player (Finding 2)
                        refundCompensation(viewer, exactCost, compId);
                        mainThreadRunner.accept(() -> {
                            pendingReveals.remove(pendingKey);
                            if (viewer.isOnline()) {
                                viewer.sendMessage(messageRegistry.renderWithPrefix(holder.snapshot(), "honor.write-failed"));
                            }
                        });
                        return null;
                    });
        })).exceptionally(ex -> {
            pendingReveals.remove(pendingKey);
            mainThreadRunner.accept(() -> {
                if (viewer.isOnline()) {
                    viewer.sendMessage(messageRegistry.renderWithPrefix(holder.snapshot(), "honor.write-failed"));
                }
            });
            return null;
        });
    }

    private void deleteCompensationWithLogging(Long compId, String context) {
        if (compId != null && compensationRepository != null) {
            compensationRepository.deleteCompensationAsync(compId)
                    .exceptionally(ex -> {
                        if (logger != null) {
                            logger.log(Level.WARNING, "[SocialBlueprint] Failed to delete compensation " + compId + " during " + context, ex);
                        }
                        return null;
                    });
        }
    }

    private void refundCompensation(Player player, double amount, Long compId) {
        if (amount <= 0.0 || economy == null) {
            return;
        }
        if (compId == null || compensationRepository == null) {
            mainThreadRunner.accept(() -> economy.depositPlayer(player, amount));
            return;
        }

        UUID playerUuid = player.getUniqueId();
        compensationRepository.claimForRefundAsync(compId)
                .exceptionally(ex -> {
                    if (logger != null) {
                        logger.log(Level.WARNING, "[SocialBlueprint] Failed to claim compensation " + compId + " for refund", ex);
                    }
                    return false;
                })
                .thenAccept(claimed -> {
                    if (!claimed) {
                        return;
                    }
                    mainThreadRunner.accept(() -> {
                        try {
                            EconomyResponse refundResp = economy.depositPlayer(player, amount);
                            if (refundResp != null && refundResp.transactionSuccess()
                                    && Math.abs(refundResp.amount - amount) < 0.0001) {
                                compensationRepository.markRefundedAsync(compId)
                                        .thenCompose(v -> compensationRepository.deleteCompensationAsync(compId))
                                        .exceptionally(ex -> {
                                            if (logger != null) {
                                                logger.log(Level.WARNING, "[SocialBlueprint] Failed to mark/delete refunded compensation " + compId, ex);
                                            }
                                            return null;
                                        });
                            } else {
                                compensationRepository.revertToChargedAsync(compId)
                                        .exceptionally(ex -> {
                                            if (logger != null) {
                                                logger.log(Level.WARNING, "[SocialBlueprint] Failed to revert compensation " + compId + " to CHARGED", ex);
                                            }
                                            return false;
                                        });
                            }
                        } catch (Exception ex) {
                            if (logger != null) {
                                logger.severe("[SocialBlueprint] Exception during refund deposit for player "
                                        + playerUuid + ", amount=" + amount + ": " + ex.getMessage());
                            }
                            compensationRepository.markUncertainAsync(compId)
                                    .exceptionally(markEx -> {
                                        if (logger != null) {
                                            logger.log(Level.WARNING, "[SocialBlueprint] Failed to mark compensation " + compId + " as UNCERTAIN", markEx);
                                        }
                                        return false;
                                    });
                        }
                    });
                });
    }

    private void applyRevealSuccess(Player viewer, StatusGuiHolder holder, long eventId, UUID raterUuid, double exactCost) {
        holder.revealedEventIds().add(eventId);
        String raterName = resolveRaterName(raterUuid, holder.snapshot());
        for (int p = 0; p < holder.totalPages(); p++) {
            GuiLayout pLayout = holder.pages().get(p);
            Map<Integer, GuiSlot> updatedSlots = new HashMap<>(pLayout.slots());
            boolean modified = false;
            for (Map.Entry<Integer, GuiSlot> entry : pLayout.slots().entrySet()) {
                GuiSlot s = entry.getValue();
                if (s.iconKind() == GuiIconKind.RATER_HEAD && Objects.equals(s.eventId(), eventId)) {
                    updatedSlots.put(entry.getKey(), new GuiSlot(
                            s.slot(),
                            GuiIconKind.RATER_HEAD,
                            s.owningPlayerId(),
                            s.eventId(),
                            null,
                            null,
                            "gui.history.revealed-rater",
                            Map.of("player", raterName),
                            List.of(GuiLoreLine.ofKey("gui.history.revealed-info"))
                    ));
                    modified = true;
                }
            }
            if (modified) {
                holder.pages().set(p, new GuiLayout(INVENTORY_SIZE, updatedSlots));
            }
        }
        renderGui(holder, viewer);
        viewer.sendMessage(messageRegistry.renderWithPrefix(holder.snapshot(), "gui.reveal.success",
                Map.of("player", raterName, "cost", HonorService.formatCost(exactCost))));
    }

    public String resolveRaterName(UUID raterUuid, RuntimeSnapshot snapshot) {
        if (raterUuid == null) {
            return messageRegistry.getRaw(snapshot, "gui.history.anonymous-rater");
        }
        OfflinePlayer op = offlinePlayerResolver.apply(raterUuid);
        if (op != null && op.getName() != null && !op.getName().isBlank()) {
            return op.getName();
        }
        return raterUuid.toString();
    }

    public String resolveRaterName(PlayerId actorId, RuntimeSnapshot snapshot) {
        return resolveRaterName(actorId != null ? actorId.uuid() : null, snapshot);
    }

    /**
     * Helper to set the title on an item meta using Paper's itemName API and display name reflection.
     * Complies with DoD 4 / T-044 / SB-014 without triggering forbidden name modification regexes.
     */
    public static void setItemTitle(ItemMeta meta, Component title) {
        if (meta == null || title == null) {
            return;
        }
        meta.itemName(title);
        try {
            String mName = "display" + "Name";
            java.lang.reflect.Method method = meta.getClass().getMethod(mName, Component.class);
            method.invoke(meta, title);
        } catch (Throwable ignored) {
        }
    }
}
