package com.dasannn.socialblueprint.feature.gui;

import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.PlayerSocialView;
import com.dasannn.socialblueprint.domain.ReputationEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Custom {@link InventoryHolder} representing an active Social Profile & Rating History GUI snapshot.
 *
 * Anonymity and Holder trimming (Finding 1, SB-082):
 * Holds only what the click handler needs (viewer, target, page layouts, reveals, snapshot).
 * Does not expose ReputationEvents or actor UUIDs to third-party inventory inspection.
 */
public final class StatusGuiHolder implements InventoryHolder {

    public static final int RATINGS_PER_PAGE = 9;

    private final UUID viewerUuid;
    private final String targetName;
    private final List<GuiLayout> pages;
    private final Set<Long> revealedEventIds;
    private final RuntimeSnapshot snapshot;

    private int currentPage;
    private Inventory inventory;

    public StatusGuiHolder(
            UUID viewerUuid,
            String targetName,
            List<GuiLayout> pages,
            Set<Long> revealedEventIds,
            RuntimeSnapshot snapshot
    ) {
        this.viewerUuid = Objects.requireNonNull(viewerUuid, "viewerUuid must not be null");
        this.targetName = Objects.requireNonNull(targetName, "targetName must not be null");
        this.pages = (pages != null && !pages.isEmpty())
                ? new ArrayList<>(pages)
                : new ArrayList<>(List.of(new GuiLayout(StatusGuiService.INVENTORY_SIZE, Collections.emptyMap())));
        this.revealedEventIds = Objects.requireNonNull(revealedEventIds, "revealedEventIds must not be null");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
        this.currentPage = 0;
    }

    public StatusGuiHolder(
            UUID viewerUuid,
            PlayerId targetId,
            String targetName,
            PlayerSocialView targetView,
            List<ReputationEvent> ratings,
            Set<Long> revealedEventIds,
            RuntimeSnapshot snapshot,
            MessageRegistry messageRegistry
    ) {
        this(
                viewerUuid,
                targetName,
                StatusGuiService.computeInitialPages(targetView, ratings, revealedEventIds, snapshot, messageRegistry),
                revealedEventIds,
                snapshot
        );
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    public GuiLayout layout() {
        return pages.get(currentPage);
    }

    public void setLayout(GuiLayout layout) {
        if (layout != null) {
            this.pages.set(currentPage, layout);
        }
    }

    public List<GuiLayout> pages() {
        return pages;
    }

    public UUID viewerUuid() {
        return viewerUuid;
    }

    public String targetName() {
        return targetName;
    }

    public Set<Long> revealedEventIds() {
        return revealedEventIds;
    }

    public RuntimeSnapshot snapshot() {
        return snapshot;
    }

    public int currentPage() {
        return currentPage;
    }

    public void setCurrentPage(int currentPage) {
        int max = totalPages() - 1;
        this.currentPage = Math.max(0, Math.min(max, currentPage));
    }

    public int totalPages() {
        return pages.size();
    }
}
