package com.gmail.crizardevelop.playerstatus;

import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;


public class EventManager implements Listener {

    private final main main;

    public EventManager(main main) {
        this.main = main;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        main.setDefaultConfig(event.getPlayer());
        main.updatePlayerPrefix(event.getPlayer());
    }

    @EventHandler
    public void playerKillPlayer(PlayerDeathEvent ev) {
        if (ev.getEntity().getKiller() != null) {
            main.removeReputationPoint(ev.getEntity().getKiller());
            main.updatePlayerPrefix(ev.getEntity().getKiller());
        }
    }

    @EventHandler
    public void verifyPlayerDeath(PlayerDeathEvent ev){
        //main.checkDeathAndReputationPlayer(ev.getEntity().getPlayer());
    }

}
