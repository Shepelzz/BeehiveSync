package com.beehiveprv.listener;

import com.beehiveprv.dto.PlayerData;
import com.beehiveprv.service.PlayerDataService;
import com.beehiveprv.service.PlayerDataService.MainThreadExtras;
import com.beehiveprv.service.PlayerDataService.PlayerStats;
import com.beehiveprv.service.SkinService;
import com.beehiveprv.service.SyncApiClient;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.logging.Logger;

/**
 * Sends full player data to the API when a player joins.
 * Silently skips if no token is configured.
 */
public class PlayerJoinListener implements Listener {

    private final JavaPlugin plugin;
    private final PlayerDataService dataService;
    private final SkinService skinService;
    private final SyncApiClient apiClient;
    private final Logger logger;

    public PlayerJoinListener(JavaPlugin plugin, PlayerDataService dataService,
                              SkinService skinService, SyncApiClient apiClient) {
        this.plugin = plugin;
        this.dataService = dataService;
        this.skinService = skinService;
        this.apiClient = apiClient;
        this.logger = plugin.getLogger();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!apiClient.isConfigured()) {
            logger.fine("Sync token not configured, skipping player join sync.");
            return;
        }

        Player player = event.getPlayer();

        // Collect advancements, permissions, and stats on the main thread
        MainThreadExtras extras = dataService.collectMainThreadExtras(player);
        PlayerStats stats = dataService.collectPlayerStats(player);

        // Skin download + HTTP send — all async
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String skinHead = skinService.buildAndCache(player);
                PlayerData data = dataService.buildPlayerData(player, extras, stats, skinHead);
                if (data != null) {
                    apiClient.sendPlayers(List.of(data));
                }
            } catch (Exception e) {
                logger.warning("Failed to sync player " + player.getName() + " on join: " + e.getMessage());
            }
        });
    }
}
