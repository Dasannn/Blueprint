package com.dasannn.socialblueprint.feature.effects;

import com.dasannn.socialblueprint.config.PresentationConfig;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.util.Random;

/** Main-thread renderer. Restores live world data; never changes a Block or Sign. */
final class PrivateBlocks {
    private PrivateBlocks() {}

    static Block choose(Player player, PresentationConfig.Block config, BlockData fake, boolean sign, Random random) {
        if (player.isHandRaised() || player.getOpenInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING)
            return null;
        Location origin = player.getLocation();
        org.bukkit.attribute.AttributeInstance reach = player.getAttribute(org.bukkit.attribute.Attribute.BLOCK_INTERACTION_RANGE);
        if (reach == null || !Double.isFinite(reach.getValue())) return null;
        Location eye = player.getEyeLocation();
        Block target = player.getTargetBlockExact((int) Math.ceil(Math.min(config.range(), 32)));
        org.bukkit.util.BoundingBox feet = player.getBoundingBox();
        String main = player.getInventory().getItemInMainHand().getType().name().toLowerCase(java.util.Locale.ROOT);
        String off = player.getInventory().getItemInOffHand().getType().name().toLowerCase(java.util.Locale.ROOT);
        // ponytail: bounded sampling can skip a sparse eligible surface; extend sampling only if live use needs it.
        int radius = (int) Math.ceil(Math.min(config.range(), 8));
        for (int i = 0; i < 128; i++) {
            int x = origin.getBlockX() + random.nextInt(radius * 2 + 1) - radius;
            int y = origin.getBlockY() + random.nextInt(5) - 1;
            int z = origin.getBlockZ() + random.nextInt(radius * 2 + 1) - radius;
            if (y < player.getWorld().getMinHeight() || y >= player.getWorld().getMaxHeight()
                    || !player.getWorld().isChunkLoaded(x >> 4, z >> 4)) continue;
            Location at = new Location(player.getWorld(), x + .5, y + .5, z + .5);
            if (at.distanceSquared(origin) > config.range() * config.range()) continue;
            // Keep even an already-open sign editor/container's block out of the candidate set.
            // Distance to the enclosing cube is conservative for the allowed sign shapes.
            double dx = Math.max(x - eye.getX(), Math.max(0, eye.getX() - (x + 1)));
            double dy = Math.max(y - eye.getY(), Math.max(0, eye.getY() - (y + 1)));
            double dz = Math.max(z - eye.getZ(), Math.max(0, eye.getZ() - (z + 1)));
            if (dx * dx + dy * dy + dz * dz <= reach.getValue() * reach.getValue()) continue;
            Block block = player.getWorld().getBlockAt(x, y, z);
            boolean standing = x + 1 > feet.getMinX() && x < feet.getMaxX()
                    && z + 1 > feet.getMinZ() && z < feet.getMaxZ()
                    && y + 1 >= feet.getMinY() - .01 && y <= feet.getMaxY();
            String data = block.getBlockData().getAsString();
            String material = BlockEquivalence.material(data);
            BlockEquivalence.Candidate candidate = new BlockEquivalence.Candidate(new BlockEquivalence.Position(x, y, z), data,
                    standing, block.equals(target), material.equals(main) || material.equals(off), false);
            if (BlockEquivalence.accepts(candidate, fake.getAsString(), sign)) return block;
        }
        return null;
    }

    static void restore(Player player, Location at) {
        if (!player.getWorld().getUID().equals(at.getWorld().getUID())
                || !at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return;
        Block current = at.getBlock();
        player.sendBlockChange(at, current.getBlockData());
        if (current.getState() instanceof Sign sign) player.sendBlockUpdate(at, sign); // both sides, colour and glow
    }
}
