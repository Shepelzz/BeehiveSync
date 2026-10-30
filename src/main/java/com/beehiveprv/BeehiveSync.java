package com.beehiveprv;

import com.beehiveprv.command.SyncCommand;
import com.beehiveprv.listener.PlayerJoinListener;
import com.beehiveprv.service.PlayerDataService;
import com.beehiveprv.service.SkinService;
import com.beehiveprv.service.LuckPermsService;
import com.beehiveprv.service.SkinRetryQueue;
import com.beehiveprv.service.SyncApiClient;
import com.beehiveprv.service.SyncOutputService;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class BeehiveSync extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();

        String mode = getConfig().getString("sync.mode", "api");
        String url = getConfig().getString("sync.url", "https://beehiveprv.club/api/sync.php");
        String token = getConfig().getString("sync.token", "");

        boolean fileMode = "file".equalsIgnoreCase(mode);
        boolean fetchMojangSkin = getConfig().getBoolean("sync.fetch-mojang-skin", true);

        // Services
        SkinService skinService = new SkinService(getLogger(), getDataFolder().toPath(), fetchMojangSkin);
        SyncApiClient apiClient = new SyncApiClient(getLogger(), url, token);

        LuckPermsService luckPerms = new LuckPermsService(getLogger());
        PlayerDataService dataService = new PlayerDataService(getLogger(), skinService, luckPerms);
        SyncOutputService syncOutput = new SyncOutputService(
                getLogger(), fileMode, apiClient, getDataFolder().toPath());

        // On startup: clear stale online states and push full role lists,
        // so bans / roles removed while the plugin was off don't stay on the site
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            apiClient.resetOnline();
            if (!syncOutput.isActive()) return;
            try {
                syncOutput.syncRoles(dataService.collectRoleLists());
            } catch (Exception e) {
                getLogger().warning("Startup roles sync error: " + e.getMessage());
            }
        });

        getLogger().info("Output mode: " + (fileMode ? "file" : "api"));

        SkinRetryQueue retryQueue = new SkinRetryQueue(this, skinService, syncOutput);

        // PlayerJoin listener
        getServer().getPluginManager().registerEvents(
                new PlayerJoinListener(this, dataService, skinService, syncOutput), this);

        // /sync command
        Objects.requireNonNull(getCommand("sync"))
                .setExecutor(new SyncCommand(this, dataService, syncOutput, retryQueue));

        getLogger().info("Enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabled.");
    }
}
