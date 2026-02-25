package com.beehiveprv;

import com.beehiveprv.command.SyncCommand;
import com.beehiveprv.listener.PlayerJoinListener;
import com.beehiveprv.service.PlayerDataService;
import com.beehiveprv.service.SkinService;
import com.beehiveprv.service.SyncApiClient;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class BeehiveSync extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();

        String url = getConfig().getString("sync.url", "https://beehiveprv.club/api/sync.php");
        String token = getConfig().getString("sync.token", "");
        long intervalMinutes = getConfig().getLong("sync.interval-minutes", 5);

        // Services
        SkinService skinService = new SkinService(getLogger(), getDataFolder().toPath());
        SyncApiClient apiClient = new SyncApiClient(getLogger(), url, token);
        PlayerDataService dataService = new PlayerDataService(getLogger(), skinService);

        // PlayerJoin listener — syncs individual player on join (no-op if no token)
        getServer().getPluginManager().registerEvents(
                new PlayerJoinListener(this, dataService, skinService, apiClient), this);

        // Periodic sync — only if token is set
//        if (apiClient.isConfigured()) {
//            long intervalTicks = intervalMinutes * 60L * 20L;
//            getServer().getScheduler().runTaskTimerAsynchronously(
//                    this,
//                    new PeriodicSyncTask(this, dataService, apiClient),
//                    intervalTicks, intervalTicks
//            );
//            getLogger().info("Periodic sync every " + intervalMinutes + " min -> " + url);
//        } else {
//            getLogger().warning("sync.token is not set — periodic sync and player join sync disabled. File dump still works.");
//        }

        // /sync command — alldata works even without token (file dump mode)
        Objects.requireNonNull(getCommand("sync"))
                .setExecutor(new SyncCommand(this, dataService, apiClient));

        getLogger().info("Enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabled.");
    }
}