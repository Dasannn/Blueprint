package com.dasannn.socialblueprint.feature.gui;

import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import com.dasannn.socialblueprint.domain.PlayerId;
import com.dasannn.socialblueprint.domain.PlayerSocialView;
import com.dasannn.socialblueprint.domain.ReputationEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Custom {@link InventoryHolder} representing an active Social Profile & Rating History GUI snapshot.
 * Holds the captured runtime snapshot, target metrics, rating events, and per-viewer reveals (T-120 to T-124).
 */
public final class StatusGuiHolder implements InventoryHolder {

    public static final int RATINGS_PER_PAGE = 7;

    private final UUID viewerUuid;
    private final PlayerId targetId;
    private final String targetName;
    private final PlayerSocialView targetView;
    private final List<ReputationEvent> ratings;
    private final Set<Long> revealedEventIds;
    private final RuntimeSnapshot snapshot;

    private int currentPage;
    private Inventory inventory;
    private GuiLayout layout;

    public StatusGuiHolder(
            UUID viewerUuid,
            PlayerId targetId,
            String targetName,
            PlayerSocialView targetView,
            List<ReputationEvent> ratings,
            Set<Long> revealedEventIds,
            RuntimeSnapshot snapshot
    ) {
        this.viewerUuid = Objects.requireNonNull(viewerUuid, "viewerUuid must not be null");
        this.targetId = Objects.requireNonNull(targetId, "targetId must not be null");
        this.targetName = Objects.requireNonNull(targetName, "targetName must not be null");
        this.targetView = Objects.requireNonNull(targetView, "targetView must not be null");
        this.ratings = ratings != null ? Collections.unmodifiableList(ratings) : Collections.emptyList();
        this.revealedEventIds = Objects.requireNonNull(revealedEventIds, "revealedEventIds must not be null");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
        this.currentPage = 0;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    public GuiLayout layout() {
        return layout;
    }

    public void setLayout(GuiLayout layout) {
        this.layout = layout;
    }

    public UUID viewerUuid() {
        return viewerUuid;
    }

    public PlayerId targetId() {
        return targetId;
    }

    public String targetName() {
        return targetName;
    }

    public PlayerSocialView targetView() {
        return targetView;
    }

    public List<ReputationEvent> ratings() {
        return ratings;
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
        if (ratings.isEmpty()) {
            return 1;
        }
        return Math.max(1, (int) Math.ceil((double) ratings.size() / RATINGS_PER_PAGE));
    }

    public List<ReputationEvent> ratingsForCurrentPage() {
        if (ratings.isEmpty()) {
            return Collections.emptyList();
        }
        int start = currentPage * RATINGS_PER_PAGE;
        if (start >= ratings.size()) {
            return Collections.emptyList();
        }
        int end = Math.min(ratings.size(), start + RATINGS_PER_PAGE);
        return ratings.subList(start, end);
    }
}
