package com.dasannn.socialblueprint.feature.effects;

import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

/**
 * Represents a tracked active ambient entity (fake or real) in the registry per T-073.
 */
public record ActiveEntityEntry(
        UUID targetPlayerId,
        int entityId,
        Entity realEntity,
        UUID worldUid,
        BukkitTask despawnTask,
        Runnable cleanupAction
) {
    public void cleanup() {
        if (despawnTask != null && !despawnTask.isCancelled()) {
            try {
                despawnTask.cancel();
            } catch (Exception ignored) {}
        }
        if (cleanupAction != null) {
            try {
                cleanupAction.run();
            } catch (Exception ignored) {}
        }
        if (realEntity != null && realEntity.isValid()) {
            try {
                realEntity.remove();
            } catch (Exception ignored) {}
        }
    }
}
