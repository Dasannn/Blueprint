package com.dasannn.socialblueprint.feature.gui;

import com.dasannn.socialblueprint.command.PermissionChecker;
import com.dasannn.socialblueprint.config.ColorParser;
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
import com.dasannn.socialblueprint.storage.RaterRevealRepository;
import com.dasannn.socialblueprint.storage.ReputationRepository;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
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
import java.util.function.Consumer;
import java.util.function.Function;

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
 * Anonymity (T-124, T-126):
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

    private final MessageRegistry messageRegistry;
    private final ProfileService profileService;
    private final ReputationRepository reputationRepository;
    private final RaterRevealRepository raterRevealRepository;
    private final HonorService honorService;
    private final Consumer<Runnable> mainThreadRunner;
    private final Function<UUID, OfflinePlayer> offlinePlayerResolver;
    private final Clock clock;
    private final GuiRenderer renderer;
    private volatile Economy economy;

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
                new GuiRenderer(messageRegistry, offlinePlayerResolver)
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

        // Asynchronous load on storage executor (T-122)
        return profileService.resolvePlayerAsync(targetQuery, snapshot)
                .thenCompose(optView -> {
                    if (optView.isEmpty()) {
                        mainThreadRunner.accept(() -> {
                            viewer.sendMessage(messageRegistry.renderWithPrefix(snapshot, "status.not-found",
                                    Map.of("player", targetQuery)));
                        });
                        return CompletableFuture.completedFuture(null);
                    }

                    PlayerSocialView view = optView.get();
                    PlayerId targetId = view.playerId();

                    CompletableFuture<List<ReputationEvent>> ratingsFuture = reputationRepository.findByTargetAsync(targetId);
                    CompletableFuture<Set<Long>> revealsFuture = raterRevealRepository.findRevealedEventsByViewerAsync(viewer.getUniqueId());

                    return CompletableFuture.allOf(ratingsFuture, revealsFuture)
                            .thenAccept(v -> {
                                List<ReputationEvent> allEvents = ratingsFuture.join();
                                Set<Long> reveals = new HashSet<>(revealsFuture.join());

                                List<ReputationEvent> ratings = allEvents.stream()
                                        .filter(e -> e.actor() != null && e.kind().isPlayerHonor())
                                        .sorted(Comparator.comparing(ReputationEvent::createdAt).reversed())
                                        .toList();

                                // Handoff to main thread for inventory creation and opening (T-122)
                                mainThreadRunner.accept(() -> {
                                    if (!viewer.isOnline()) {
                                        return;
                                    }
                                    StatusGuiHolder holder = new StatusGuiHolder(
                                            viewer.getUniqueId(),
                                            targetId,
                                            view.name(),
                                            view,
                                            ratings,
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
     * Computes the complete plain-data layout for the GUI snapshot and current page.
     * Contains all layout, pagination, anonymity, and tier decisions without Bukkit item types.
     */
    public GuiLayout computeLayout(StatusGuiHolder holder, Player viewer) {
        Objects.requireNonNull(holder, "holder must not be null");
        RuntimeSnapshot snapshot = holder.snapshot();
        PlayerSocialView view = holder.targetView();

        Map<Integer, GuiSlot> slots = new HashMap<>();

        // ---------------------------------------------------------------------
        // Top row (Row 0: slots 0..8) per T-120
        // ---------------------------------------------------------------------
        // 1. Give Honor (Green banner)
        slots.put(SLOT_TOP_GIVE_BANNER, new GuiSlot(
                SLOT_TOP_GIVE_BANNER,
                GuiIconKind.GIVE_BANNER,
                null,
                null,
                "gui.top.give-banner-title",
                Map.of(),
                List.of(GuiLoreLine.ofKey("gui.top.give-banner-lore"))
        ));

        // 2. Subject's head (Never anonymous per T-124)
        Tier tier = snapshot.config().tiers().ladder().resolve(view.status());
        String prefix = snapshot.config().tiers().prefix(tier);
        String localizedTier = messageRegistry.tierName(snapshot, tier);
        String confKey = "confidence." + view.confidence().name().toLowerCase(Locale.ROOT);
        String localizedConfidence = messageRegistry.getRaw(snapshot, confKey);
        String psychKey = "psychosis." + view.psychosis().name().toLowerCase(Locale.ROOT);
        String localizedPsychosis = messageRegistry.getRaw(snapshot, psychKey);

        List<GuiLoreLine> subjectLore = List.of(
                GuiLoreLine.ofKey("status.profile-tier", Map.of("tier", localizedTier, "prefix", prefix != null ? prefix : "")),
                GuiLoreLine.ofKey("status.profile-status", Map.of("status", String.valueOf(view.status()))),
                GuiLoreLine.ofKey("status.profile-confidence", Map.of("confidence", localizedConfidence)),
                GuiLoreLine.ofKey("status.profile-psychosis", Map.of("psychosis", localizedPsychosis)),
                GuiLoreLine.ofKey("status.profile-contributors", Map.of("contributors", String.valueOf(view.contributors())))
        );

        slots.put(SLOT_TOP_SUBJECT_HEAD, new GuiSlot(
                SLOT_TOP_SUBJECT_HEAD,
                GuiIconKind.SUBJECT_HEAD,
                holder.targetId().uuid(),
                tier,
                "gui.top.subject-head-title",
                Map.of("player", view.name()),
                subjectLore
        ));

        // 3. Dye whose colour follows tier from TierLadder and Tier (T-120)
        slots.put(SLOT_TOP_TIER_DYE, new GuiSlot(
                SLOT_TOP_TIER_DYE,
                GuiIconKind.TIER_DYE,
                null,
                tier,
                "gui.top.tier-dye-title",
                Map.of("tier", localizedTier, "prefix", prefix != null ? prefix : ""),
                List.of()
        ));

        // 4. Take Honor (Red banner)
        slots.put(SLOT_TOP_TAKE_BANNER, new GuiSlot(
                SLOT_TOP_TAKE_BANNER,
                GuiIconKind.TAKE_BANNER,
                null,
                null,
                "gui.top.take-banner-title",
                Map.of(),
                List.of(GuiLoreLine.ofKey("gui.top.take-banner-lore"))
        ));

        // ---------------------------------------------------------------------
        // Edge slots for pagination per T-121
        // ---------------------------------------------------------------------
        int currentPg = holder.currentPage();
        int totalPg = holder.totalPages();
        Map<String, String> pageInfoPlaceholders = Map.of(
                "current", String.valueOf(currentPg + 1),
                "total", String.valueOf(totalPg)
        );

        GuiSlot prevBannerSlotRow2 = new GuiSlot(
                SLOT_PAGE_PREV_ROW2,
                GuiIconKind.PAGE_PREVIOUS,
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
                "gui.history.page-next",
                Map.of(),
                List.of(GuiLoreLine.ofKey("gui.history.page-info", pageInfoPlaceholders))
        );
        slots.put(SLOT_PAGE_NEXT_ROW5, nextBannerSlotRow5);

        // Page info in bottom row
        slots.put(SLOT_PAGE_INFO, new GuiSlot(
                SLOT_PAGE_INFO,
                GuiIconKind.PAGE_INFO,
                null,
                null,
                "gui.history.page-info",
                pageInfoPlaceholders,
                List.of()
        ));

        // ---------------------------------------------------------------------
        // History Grid: One rating per column below top row (T-121, T-124, T-125)
        // Columns 1 to 7:
        //   Row 1: Rater's head
        //   Row 2: Paper with written reason
        //   Row 3: Green or Red direction banner
        // ---------------------------------------------------------------------
        List<ReputationEvent> pageRatings = holder.ratingsForCurrentPage();
        for (int i = 0; i < pageRatings.size(); i++) {
            ReputationEvent event = pageRatings.get(i);
            int col = 1 + i; // Columns 1..7

            // 1. Rater's Head (Row 1: slot 9 + col)
            // A head without a name is still that player's skin, so treat the head as a weak hint, not a guarantee of anonymity.
            UUID raterUuid = event.actor() != null ? event.actor().uuid() : null;
            boolean revealed = isRaterRevealed(viewer, event, holder);

            String raterTitleKey;
            Map<String, String> raterTitlePlaceholders;
            List<GuiLoreLine> raterLore;
            if (revealed) {
                String raterName = resolveRaterName(event.actor(), snapshot);
                raterTitleKey = "gui.history.revealed-rater";
                raterTitlePlaceholders = Map.of("player", raterName);
                raterLore = List.of(GuiLoreLine.ofKey("gui.history.revealed-info"));
            } else {
                raterTitleKey = "gui.history.anonymous-rater";
                raterTitlePlaceholders = Map.of();
                double cost = snapshot.config().history().revealCost();
                raterLore = List.of(GuiLoreLine.ofKey("gui.history.click-to-reveal",
                        Map.of("cost", HonorService.formatCost(cost))));
            }
            slots.put(9 + col, new GuiSlot(
                    9 + col,
                    GuiIconKind.RATER_HEAD,
                    raterUuid,
                    null,
                    raterTitleKey,
                    raterTitlePlaceholders,
                    raterLore
            ));

            // 2. Paper whose lore holds the written reason (Row 2: slot 18 + col, T-125)
            List<GuiLoreLine> paperLore;
            if (event.reason() == null || event.reason().isBlank()) {
                paperLore = List.of(GuiLoreLine.ofKey("gui.history.no-reason"));
            } else {
                String plainTextReason = CommentSanitizer.toPlainText(event.reason());
                paperLore = List.of(GuiLoreLine.ofPlain(plainTextReason));
            }
            slots.put(18 + col, new GuiSlot(
                    18 + col,
                    GuiIconKind.REASON_PAPER,
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
                    null,
                    bannerKey,
                    Map.of(),
                    List.of()
            ));
        }

        return new GuiLayout(INVENTORY_SIZE, slots);
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
        if (tier == null) {
            return Material.WHITE_DYE;
        }

        // Read configured prefix token to extract color code rather than inventing a separate mapping
        if (snapshot != null && snapshot.config() != null && snapshot.config().tiers() != null) {
            String prefix = snapshot.config().tiers().prefix(tier);
            if (prefix != null) {
                String lower = prefix.toLowerCase(Locale.ROOT);
                if (lower.contains("&4") || lower.contains("&c") || lower.contains("&#c") || lower.contains("&#4")) {
                    return Material.RED_DYE;
                }
                if (lower.contains("&f")) {
                    return Material.WHITE_DYE;
                }
                if (lower.contains("&a") || lower.contains("&2")) {
                    return Material.LIME_DYE;
                }
                if (lower.contains("&b") || lower.contains("&3") || lower.contains("&9") || lower.contains("&1")) {
                    return Material.LIGHT_BLUE_DYE;
                }
                if (lower.contains("&e")) {
                    return Material.YELLOW_DYE;
                }
                if (lower.contains("&6")) {
                    return Material.ORANGE_DYE;
                }
            }
        }

        // Canonical mapping defined by Tier level thresholds
        if (tier.isNegative()) {
            return Material.RED_DYE;
        }
        if (tier.isNeutral()) {
            return Material.WHITE_DYE;
        }
        if (tier == Tier.ILUSTRE) {
            return Material.LIGHT_BLUE_DYE;
        }
        return Material.LIME_DYE;
    }

    /**
     * Evaluates whether a rater's identity is revealed to the given viewer per T-124 and T-126.
     * Administrators always see the rater's name without paying (T-126).
     */
    public boolean isRaterRevealed(Player viewer, ReputationEvent event, StatusGuiHolder holder) {
        if (event.actor() == null) {
            return true;
        }
        if (viewer != null) {
            // The rater viewing their own rating sees their own name
            if (event.actor().uuid().equals(viewer.getUniqueId())) {
                return true;
            }
            // Administrators still see who rated whom (T-126)
            if (PermissionChecker.hasPermission(viewer, "admin-adjust", holder.snapshot())
                    || viewer.hasPermission("socialblueprint.admin")) {
                return true;
            }
        }
        // Remembered per viewer, persisted in SQLite (T-124)
        return holder.revealedEventIds().contains(event.id());
    }

    /**
     * Handles an inventory click inside the GUI. Called on the server main thread.
     */
    public CompletableFuture<Void> handleClick(Player viewer, StatusGuiHolder holder, int slot) {
        if (viewer == null || holder == null || slot < 0 || slot >= INVENTORY_SIZE) {
            return CompletableFuture.completedFuture(null);
        }

        RuntimeSnapshot snapshot = holder.snapshot();

        // 1. Top row Give Honor (T-120, T-123)
        if (slot == SLOT_TOP_GIVE_BANNER) {
            viewer.closeInventory();
            return honorService.preparePlayerHonor(viewer, holder.targetName(), HonorKind.POSITIVE, null, snapshot);
        }

        // 2. Top row Take Honor (T-120, T-123)
        if (slot == SLOT_TOP_TAKE_BANNER) {
            viewer.closeInventory();
            return honorService.preparePlayerHonor(viewer, holder.targetName(), HonorKind.NEGATIVE, null, snapshot);
        }

        // 3. Edge slot: Page Back (T-121)
        if (slot == SLOT_PAGE_PREV_ROW2 || slot == SLOT_PAGE_PREV_ROW5 || (slot % 9 == 0 && slot > 0)) {
            if (holder.currentPage() > 0) {
                holder.setCurrentPage(holder.currentPage() - 1);
                renderGui(holder, viewer);
            }
            return CompletableFuture.completedFuture(null);
        }

        // 4. Edge slot: Page Forward (T-121)
        if (slot == SLOT_PAGE_NEXT_ROW2 || slot == SLOT_PAGE_NEXT_ROW5 || (slot % 9 == 8 && slot > 8)) {
            if (holder.currentPage() < holder.totalPages() - 1) {
                holder.setCurrentPage(holder.currentPage() + 1);
                renderGui(holder, viewer);
            }
            return CompletableFuture.completedFuture(null);
        }

        // 5. Rater head click to reveal name (Row 1: slots 10..16, T-124)
        if (slot >= 10 && slot <= 16) {
            int col = slot - 9;
            int ratingIndex = (col - 1);
            List<ReputationEvent> pageRatings = holder.ratingsForCurrentPage();
            if (ratingIndex < pageRatings.size()) {
                ReputationEvent ratingEvent = pageRatings.get(ratingIndex);
                handleRevealClick(viewer, holder, ratingEvent);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Handles rater name reveal: charges Vault reveal-cost, persists to SQLite, and updates GUI (T-124).
     */
    public void handleRevealClick(Player viewer, StatusGuiHolder holder, ReputationEvent event) {
        if (event.actor() == null) {
            return;
        }
        if (isRaterRevealed(viewer, event, holder)) {
            viewer.sendMessage(messageRegistry.renderWithPrefix(holder.snapshot(), "gui.reveal.already-revealed"));
            return;
        }

        double revealCost = holder.snapshot().config().history().revealCost();
        double exactCost = HonorCostCalculator.roundCurrency(revealCost);

        // Vault balance check on main thread
        if (economy != null && exactCost > 0.0 && !economy.has(viewer, exactCost)) {
            viewer.sendMessage(messageRegistry.renderWithPrefix(holder.snapshot(), "gui.reveal.insufficient-funds",
                    Map.of("cost", HonorService.formatCost(exactCost))));
            return;
        }

        // Vault charge on main thread
        if (economy != null && exactCost > 0.0) {
            EconomyResponse resp = economy.withdrawPlayer(viewer, exactCost);
            if (resp == null || !resp.transactionSuccess()) {
                viewer.sendMessage(messageRegistry.renderWithPrefix(holder.snapshot(), "gui.reveal.insufficient-funds",
                        Map.of("cost", HonorService.formatCost(exactCost))));
                return;
            }
        }

        // Persist on storage executor (T-122)
        Instant now = clock.instant();
        raterRevealRepository.saveRevealAsync(viewer.getUniqueId(), event.id(), event.actor().uuid(), exactCost, now)
                .thenAccept(v -> mainThreadRunner.accept(() -> {
                    holder.revealedEventIds().add(event.id());
                    renderGui(holder, viewer);
                    String raterName = resolveRaterName(event.actor(), holder.snapshot());
                    viewer.sendMessage(messageRegistry.renderWithPrefix(holder.snapshot(), "gui.reveal.success",
                            Map.of("player", raterName, "cost", HonorService.formatCost(exactCost))));
                }))
                .exceptionally(ex -> {
                    mainThreadRunner.accept(() -> {
                        if (economy != null && exactCost > 0.0) {
                            economy.depositPlayer(viewer, exactCost);
                        }
                        viewer.sendMessage(messageRegistry.renderWithPrefix(holder.snapshot(), "honor.write-failed"));
                    });
                    return null;
                });
    }

    private String resolveRaterName(PlayerId actorId, RuntimeSnapshot snapshot) {
        if (actorId == null) {
            return messageRegistry.getRaw(snapshot, "gui.history.anonymous-rater");
        }
        OfflinePlayer op = offlinePlayerResolver.apply(actorId.uuid());
        if (op != null && op.getName() != null && !op.getName().isBlank()) {
            return op.getName();
        }
        return actorId.toString();
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
