package com.beehiveprv;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class BeehiveSync extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();

        String url = getConfig().getString("sync.url", "https://beehiveprv.club/api/sync.php");
        String token = getConfig().getString("sync.token", "");
        long intervalMinutes = getConfig().getLong("sync.interval-minutes", 5);

        if (token.isBlank()) {
            getLogger().warning("sync.token is not set — periodic sync and REST commands disabled. File dump still works.");
        }

        SkinManager skinManager = new SkinManager(this);
        getServer().getPluginManager().registerEvents(skinManager, this);

        if (!token.isBlank()) {
            long intervalTicks = intervalMinutes * 60L * 20L;
            getServer().getScheduler().runTaskTimerAsynchronously(
                    this,
                    new SyncTask(this, skinManager, url, token),
                    intervalTicks,
                    intervalTicks
            );
            getLogger().info("Periodic sync every " + intervalMinutes + " min → " + url);
        }

        Objects.requireNonNull(getCommand("sync"))
                .setExecutor(new AllDataSyncCommand(this, url, token));

        getLogger().info("Enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabled.");
    }
}
