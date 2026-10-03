package com.dasannn.socialblueprint.feature.effects;

import com.dasannn.socialblueprint.config.SerenityEffectsConfig;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Loaded air above soil only, outside client block selection reach. */
final class PrivateFlowers {
    private PrivateFlowers() {}

    static List<Location> choose(Player player, SerenityEffectsConfig.Flowers config, Random random) {
        Location origin = player.getLocation();
        List<Location> sites = new ArrayList<>();
        int radius = (int) Math.ceil(config.range());
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++)
            for (int y = -2; y <= 2; y++) {
                Location at = new Location(origin.getWorld(), origin.getBlockX() + x, origin.getBlockY() + y, origin.getBlockZ() + z);
                if (at.clone().add(.5, .5, .5).distanceSquared(origin) <= config.range() * config.range() && safe(player, at)) sites.add(at);
            }
        Collections.shuffle(sites, random);
        return new ArrayList<>(sites.subList(0, Math.min(config.count(), sites.size())));
    }

    static boolean safe(Player player, Location at) {
        return safe(player, at, player.getLocation());
    }

    static boolean safe(Player player, Location at, Location viewerAt) {
        var world = at.getWorld();
        if (!player.isOnline() || !viewerAt.getWorld().equals(world) || at.getBlockY() <= world.getMinHeight()
                || at.getBlockY() >= world.getMaxHeight() || !world.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return false;
        var reach = player.getAttribute(org.bukkit.attribute.Attribute.BLOCK_INTERACTION_RANGE);
        if (reach == null) return false;
        Location current = player.getLocation();
        Location eye = player.getEyeLocation().add(viewerAt.getX() - current.getX(),
                viewerAt.getY() - current.getY(), viewerAt.getZ() - current.getZ());
        double dx = Math.max(at.getX() - eye.getX(), Math.max(0, eye.getX() - at.getX() - 1));
        double dy = Math.max(at.getY() - eye.getY(), Math.max(0, eye.getY() - at.getY() - 1));
        double dz = Math.max(at.getZ() - eye.getZ(), Math.max(0, eye.getZ() - at.getZ() - 1));
        return CalmEffectDecision.flowerSite(at.getBlock().getType().isAir(),
                at.clone().add(0, -1, 0).getBlock().getType().name().toLowerCase(Locale.ROOT),
                0, 1, dx * dx + dy * dy + dz * dz, reach.getValue());
    }
}
