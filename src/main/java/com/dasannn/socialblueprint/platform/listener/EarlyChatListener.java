package com.dasannn.socialblueprint.platform.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/** Reserves LOWEST ordering during enable, passing through until initialization completes. */
public final class EarlyChatListener implements Listener {

    private volatile AsyncChatListener delegate;

    /** Published on the main thread; null restores pass-through during disable. */
    public void setDelegate(AsyncChatListener delegate) {
        this.delegate = delegate;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPrepareChat(AsyncChatEvent event) {
        AsyncChatListener current = delegate;
        if (current != null) current.onPrepareChat(event);
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPrepareLegacyChat(AsyncPlayerChatEvent event) {
        AsyncChatListener current = delegate;
        if (current != null) current.onPrepareLegacyChat(event);
    }
}
