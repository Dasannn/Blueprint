package com.dasannn.socialblueprint;

import org.bukkit.plugin.java.JavaPlugin;

public final class SocialBlueprintPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("SocialBlueprint enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("SocialBlueprint disabled.");
    }
}
