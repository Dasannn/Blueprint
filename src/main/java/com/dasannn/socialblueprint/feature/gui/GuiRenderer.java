package com.dasannn.socialblueprint.feature.gui;

import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Thin renderer turning a plain-data {@link GuiLayout} into real Bukkit {@link ItemStack}s
 * and filling an inventory.
 *
 * This renderer is intentionally untested by unit tests and is covered on the live server
 * in P11 instead. Constructing Bukkit {@link ItemStack}s requires a running server with an
 * initialized registry.
 *
 * Contains no decisions: no pagination arithmetic, no permission check, no anonymity logic,
 * and no tier lookup. Just a switch from icon kind to Material and the already parsed text components.
 */
public class GuiRenderer {

    private final Function<UUID, OfflinePlayer> offlinePlayerResolver;

    public GuiRenderer(MessageRegistry messageRegistry, Function<UUID, OfflinePlayer> offlinePlayerResolver) {
        Objects.requireNonNull(messageRegistry, "messageRegistry must not be null");
        this.offlinePlayerResolver = offlinePlayerResolver != null ? offlinePlayerResolver : uuid -> {
            if (Bukkit.getServer() != null) {
                return Bukkit.getOfflinePlayer(uuid);
            }
            return null;
        };
    }

    public void render(Inventory inventory, GuiLayout layout, RuntimeSnapshot snapshot) {
        if (inventory == null || layout == null) {
            return;
        }
        inventory.clear();
        for (GuiSlot slot : layout.slots().values()) {
            ItemStack item = renderSlot(slot, snapshot);
            if (item != null) {
                inventory.setItem(slot.slot(), item);
            }
        }
    }

    public ItemStack renderSlot(GuiSlot slot, RuntimeSnapshot snapshot) {
        Material material = resolveMaterial(slot, snapshot);
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        if (meta instanceof SkullMeta skullMeta && slot.owningPlayerId() != null) {
            // Deliberate product decision (SB-082): Anonymity covers the name only;
            // the rater's head carries their real skin so the player sees who rated them as a face.
            OfflinePlayer op = offlinePlayerResolver.apply(slot.owningPlayerId());
            if (op != null) {
                skullMeta.setOwningPlayer(op);
            }
        }

        StatusGuiService.setItemTitle(meta, slot.title());
        if (!slot.renderedLore().isEmpty()) {
            meta.lore(slot.renderedLore());
        }

        item.setItemMeta(meta);
        return item;
    }

    public Material resolveMaterial(GuiSlot slot, RuntimeSnapshot snapshot) {
        return switch (slot.iconKind()) {
            case SUBJECT_HEAD, RATER_HEAD -> Material.PLAYER_HEAD;
            case TIER_DYE -> slot.dyeKind() != null ? mapDyeMaterial(slot.dyeKind()) : Material.WHITE_DYE;
            case GIVE_BANNER, PAGE_NEXT, DIRECTION_BANNER_POSITIVE -> Material.GREEN_BANNER;
            case TAKE_BANNER, PAGE_PREVIOUS, DIRECTION_BANNER_NEGATIVE -> Material.RED_BANNER;
            case REASON_PAPER, PAGE_INFO -> Material.PAPER;
        };
    }

    private static Material mapDyeMaterial(GuiDyeKind dyeKind) {
        return switch (dyeKind) {
            case WHITE -> Material.WHITE_DYE;
            case LIME -> Material.LIME_DYE;
            case LIGHT_BLUE -> Material.LIGHT_BLUE_DYE;
            case RED -> Material.RED_DYE;
        };
    }
}
