package com.dasannn.socialblueprint.feature.gui;

import com.dasannn.socialblueprint.config.MessageRegistry;
import com.dasannn.socialblueprint.config.RuntimeSnapshot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
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
 * and no tier lookup. Just a switch from icon kind to Material and the lore strings.
 */
public class GuiRenderer {

    private final MessageRegistry messageRegistry;
    private final Function<UUID, OfflinePlayer> offlinePlayerResolver;

    public GuiRenderer(MessageRegistry messageRegistry, Function<UUID, OfflinePlayer> offlinePlayerResolver) {
        this.messageRegistry = Objects.requireNonNull(messageRegistry, "messageRegistry must not be null");
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
            OfflinePlayer op = offlinePlayerResolver.apply(slot.owningPlayerId());
            if (op != null) {
                skullMeta.setOwningPlayer(op);
            }
        }

        if (slot.titleKey() != null) {
            Component title = messageRegistry.render(snapshot, slot.titleKey(), slot.titlePlaceholders());
            StatusGuiService.setItemTitle(meta, title);
        }

        if (!slot.lore().isEmpty()) {
            List<Component> loreComponents = new ArrayList<>();
            for (GuiLoreLine line : slot.lore()) {
                if (line.isPlain()) {
                    loreComponents.add(Component.text(line.plainText())
                            .color(NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false));
                } else {
                    loreComponents.add(messageRegistry.render(snapshot, line.key(), line.placeholders()));
                }
            }
            meta.lore(loreComponents);
        }

        item.setItemMeta(meta);
        return item;
    }

    public Material resolveMaterial(GuiSlot slot, RuntimeSnapshot snapshot) {
        return switch (slot.iconKind()) {
            case SUBJECT_HEAD, RATER_HEAD -> Material.PLAYER_HEAD;
            case TIER_DYE -> StatusGuiService.resolveTierDye(slot.tier(), snapshot);
            case GIVE_BANNER, PAGE_NEXT, DIRECTION_BANNER_POSITIVE -> Material.GREEN_BANNER;
            case TAKE_BANNER, PAGE_PREVIOUS, DIRECTION_BANNER_NEGATIVE -> Material.RED_BANNER;
            case REASON_PAPER, PAGE_INFO -> Material.PAPER;
        };
    }
}
