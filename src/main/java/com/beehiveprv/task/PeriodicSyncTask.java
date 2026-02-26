//package com.beehiveprv.task;
//
//import com.beehiveprv.dto.PlayerData;
//import com.beehiveprv.service.PlayerDataService;
//import com.beehiveprv.service.PlayerDataService.MainThreadExtras;
//import com.beehiveprv.service.PlayerDataService.PlayerStats;
//import com.beehiveprv.service.SyncApiClient;
//import org.bukkit.plugin.java.JavaPlugin;
//
//import java.nio.file.Path;
//import java.util.List;
//import java.util.Map;
//import java.util.UUID;
//import java.util.concurrent.CompletableFuture;
//import java.util.concurrent.TimeUnit;
//import java.util.logging.Logger;
//
///**
// * Periodic async task that syncs ALL player data to the API.
// * Uses the same data collection as /sync alldata command.
// */
//public class PeriodicSyncTask implements Runnable {
//
//    private final JavaPlugin plugin;
//    private final PlayerDataService dataService;
//    private final SyncApiClient apiClient;
//    private final Logger logger;
//
//    public PeriodicSyncTask(JavaPlugin plugin, PlayerDataService dataService, SyncApiClient apiClient) {
//        this.plugin = plugin;
//        this.dataService = dataService;
//        this.apiClient = apiClient;
//        this.logger = plugin.getLogger();
//    }
//
//    @Override
//    public void run() {
//        if (!plugin.isEnabled()) return;
//
//        try {
//            // Hop to main thread for advancements, permissions & stats
//            CompletableFuture<MainThreadResult> future = new CompletableFuture<>();
//            plugin.getServer().getScheduler().runTask(plugin, () -> {
//                try {
//                    Map<UUID, MainThreadExtras> extras = dataService.collectAllMainThreadExtras();
//                    Map<UUID, PlayerStats> stats = dataService.collectAllPlayerStats();
//                    Path advFolder = dataService.getAdvancementsFolder();
//                    future.complete(new MainThreadResult(extras, stats, advFolder));
//                } catch (Exception e) {
//                    future.completeExceptionally(e);
//                }
//            });
//
//            MainThreadResult mtr = future.get(30, TimeUnit.SECONDS);
//
//            // Build all player data (async: skins, disk advancements)
//            List<PlayerData> players = dataService.buildAllPlayerData(mtr.extras(), mtr.stats(), mtr.advFolder());
//
//            if (!players.isEmpty()) {
//                int sent = apiClient.sendPlayers(players);
//                logger.info("Periodic sync: " + sent + "/" + players.size() + " players synced.");
//            }
//        } catch (Exception e) {
//            logger.warning("Periodic sync error: " + e.getMessage());
//        }
//    }
//
//    private record MainThreadResult(Map<UUID, MainThreadExtras> extras, Map<UUID, PlayerStats> stats, Path advFolder) {}
//}