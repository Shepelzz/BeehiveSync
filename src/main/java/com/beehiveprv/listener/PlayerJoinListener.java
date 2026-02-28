package com.beehiveprv.listener;

import com.beehiveprv.dto.PlayerData;
import com.beehiveprv.service.PlayerDataService;
import com.beehiveprv.service.PlayerDataService.MainThreadExtras;
import com.beehiveprv.service.PlayerDataService.PlayerStats;
import com.beehiveprv.service.SkinService;
import com.beehiveprv.service.SyncOutputService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;

/**
 * Sends / writes player data when a player joins, using the configured output mode.
 * In api mode — silently skips if no token is set.
 * In file mode — always writes to {@code players/{uuid}.json}.
 */
public class PlayerJoinListener implements Listener {

    private final JavaPlugin plugin;
    private final PlayerDataService dataService;
    private final SkinService skinService;
    private final SyncOutputService syncOutput;
    private final Logger logger;

    public PlayerJoinListener(JavaPlugin plugin, PlayerDataService dataService,
                              SkinService skinService, SyncOutputService syncOutput) {
        this.plugin = plugin;
        this.dataService = dataService;
        this.skinService = skinService;
        this.syncOutput = syncOutput;
        this.logger = plugin.getLogger();
    }

    @EventHandler()
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!syncOutput.isActive()) {
            logger.fine("Sync not configured, skipping player join sync.");
            return;
        }

        Player player = event.getPlayer();

        // Collect advancements, permissions, and stats on the main thread
        MainThreadExtras extras = dataService.collectMainThreadExtras(player);
        PlayerStats stats = dataService.collectPlayerStats(player);

        // Skin download + output — all async
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String skinHead = skinService.buildAndCache(player);
                PlayerData data = dataService.buildPlayerData(player, extras, stats, skinHead);
                if (data != null) {
                    syncOutput.syncOne(data);
                }
            } catch (Exception e) {
                logger.warning("Failed to sync player " + player.getName() + " on join: " + e.getMessage());
            }
        });
    }

    @EventHandler()
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (!syncOutput.isActive()) {
            logger.fine("Sync not configured, skipping player quit sync.");
            return;
        }

        Player player = event.getPlayer();

        // Collect advancements, permissions, and stats on the main thread
        MainThreadExtras extras = dataService.collectMainThreadExtras(player);
        PlayerStats stats = dataService.collectPlayerStats(player);

        // Skin download + output — all async
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String skinHead = skinService.buildAndCache(player);
                PlayerData data = dataService.buildPlayerData(player, extras, stats, skinHead);
                if (data != null) {
                    syncOutput.syncOne(data);
                }
            } catch (Exception e) {
                logger.warning("Failed to sync player " + player.getName() + " on quit: " + e.getMessage());
            }
        });
    }
}
