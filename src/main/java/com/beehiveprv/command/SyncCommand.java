package com.beehiveprv.command;

import com.beehiveprv.dto.PlayerData;
import com.beehiveprv.service.PlayerDataService;
import com.beehiveprv.service.PlayerDataService.MainThreadExtras;
import com.beehiveprv.service.SkinRetryQueue;
import com.beehiveprv.service.SyncOutputService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * /sync alldata — collects all players' data and routes to the configured output
 * (REST API or local JSON file), as set by {@code sync.mode} in config.yml.
 */
public class SyncCommand implements CommandExecutor {

    private final JavaPlugin plugin;
    private final PlayerDataService dataService;
    private final SyncOutputService syncOutput;
    private final SkinRetryQueue retryQueue;
    private final Logger logger;

    public SyncCommand(JavaPlugin plugin, PlayerDataService dataService,
                       SyncOutputService syncOutput, SkinRetryQueue retryQueue) {
        this.plugin = plugin;
        this.dataService = dataService;
        this.syncOutput = syncOutput;
        this.retryQueue = retryQueue;
        this.logger = plugin.getLogger();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("alldata")) {
            sender.sendMessage("\u00a7eUsage: /sync alldata");
            return true;
        }
        if (sender instanceof Player p && !p.isOp()) {
            sender.sendMessage("\u00a7cYou must be OP to use this.");
            return true;
        }
        if (!syncOutput.isActive()) {
            sender.sendMessage("\u00a7csync.token is not set in config.yml \u2014 set a token or switch mode to \u00a7efile\u00a7c.");
            return true;
        }

        sender.sendMessage(syncOutput.isFileMode()
                ? "\u00a7aBuilding full data dump to file..."
                : "\u00a7aStarting full sync to API...");

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<PlayerData> players = collectAllPlayers(sender);

                sender.sendMessage("\u00a77Sending " + players.size() + " players...");
                int done = syncOutput.syncAll(players);

                sender.sendMessage(syncOutput.isFileMode()
                        ? "\u00a7aDumped " + done + "/" + players.size() + " players \u2192 players.json"
                        : "\u00a7aSync complete! Sent " + done + "/" + players.size() + " players.");
            } catch (Exception e) {
                logger.severe("AllData sync error: " + e.getMessage());
                sender.sendMessage("\u00a7cSync error: " + e.getMessage());
            }
        });
        return true;
    }

    private List<PlayerData> collectAllPlayers(CommandSender feedback) throws Exception {
        CompletableFuture<MainThreadResult> future = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                Map<UUID, MainThreadExtras> extras = dataService.collectAllMainThreadExtras();
                // Only online players — offline players' stats are read from disk below
                Map<UUID, PlayerDataService.PlayerStats> onlineStats = dataService.collectAllPlayerStats();
                Path advFolder = dataService.getAdvancementsFolder();
                Path statsFolder = dataService.getStatsFolder();
                future.complete(new MainThreadResult(extras, onlineStats, advFolder, statsFolder));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        MainThreadResult mtr = future.get(30, TimeUnit.SECONDS);

        feedback.sendMessage("\u00a77Collecting player data...");
        List<PlayerData> result = dataService.buildAllPlayerData(
                mtr.extras(), mtr.onlineStats(), mtr.advFolder(), mtr.statsFolder(), retryQueue);
        feedback.sendMessage("\u00a77Collected " + result.size() + " players.");
        return result;
    }

    private record MainThreadResult(
            Map<UUID, MainThreadExtras> extras,
            Map<UUID, PlayerDataService.PlayerStats> onlineStats,
            Path advFolder,
            Path statsFolder
    ) {}
}
