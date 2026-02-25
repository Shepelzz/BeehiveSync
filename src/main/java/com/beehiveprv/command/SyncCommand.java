package com.beehiveprv.command;

import com.beehiveprv.dto.PlayerData;
import com.beehiveprv.service.PlayerDataService;
import com.beehiveprv.service.PlayerDataService.MainThreadExtras;
import com.beehiveprv.service.SkinService;
import com.beehiveprv.service.SyncApiClient;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * /sync alldata [file] — collects all players' data and sends to API or dumps to file.
 */
public class SyncCommand implements CommandExecutor {

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final JavaPlugin plugin;
    private final PlayerDataService dataService;
    private final SyncApiClient apiClient;
    private final Logger logger;

    public SyncCommand(JavaPlugin plugin, PlayerDataService dataService, SyncApiClient apiClient) {
        this.plugin = plugin;
        this.dataService = dataService;
        this.apiClient = apiClient;
        this.logger = plugin.getLogger();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("alldata")) {
            sender.sendMessage("\u00a7eUsage: /sync alldata [file]");
            return true;
        }
        if (sender instanceof Player p && !p.isOp()) {
            sender.sendMessage("\u00a7cYou must be OP to use this.");
            return true;
        }

        boolean toFile = args.length >= 2 && args[1].equalsIgnoreCase("file");

        if (!toFile && !apiClient.isConfigured()) {
            sender.sendMessage("\u00a7cToken not set in config.yml \u2014 can't send to REST. Use \u00a7e/sync alldata file\u00a7c to dump to disk.");
            return true;
        }

        sender.sendMessage(toFile
                ? "\u00a7aBuilding full data dump to file..."
                : "\u00a7aStarting full sync...");

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<PlayerData> players = collectAllPlayers(sender);

                if (toFile) {
                    dumpToFile(sender, players);
                } else {
                    sender.sendMessage("\u00a7aSending " + players.size() + " players...");
                    int sent = apiClient.sendPlayers(players);
                    sender.sendMessage("\u00a7aSync complete! Synced " + sent + "/" + players.size() + " players.");
                }
            } catch (Exception e) {
                logger.severe("AllData sync error: " + e.getMessage());
                sender.sendMessage("\u00a7cSync error: " + e.getMessage());
            }
        });
        return true;
    }

    private List<PlayerData> collectAllPlayers(CommandSender feedback) throws Exception {
        // Main thread: collect online extras + advancement folder path
        CompletableFuture<MainThreadResult> future = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                Map<UUID, MainThreadExtras> extras = dataService.collectAllMainThreadExtras();
                Path advFolder = dataService.getAdvancementsFolder();
                future.complete(new MainThreadResult(extras, advFolder));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        MainThreadResult mtr = future.get(30, TimeUnit.SECONDS);

        // Async: build all player data (stats + skins + disk advancements)
        feedback.sendMessage("\u00a77Collecting player data...");
        List<PlayerData> result = dataService.buildAllPlayerData(mtr.extras(), mtr.advFolder());
        feedback.sendMessage("\u00a77Collected " + result.size() + " players.");
        return result;
    }

    private void dumpToFile(CommandSender sender, List<PlayerData> players) throws Exception {
        List<String> jsons = new ArrayList<>(players.size());
        for (PlayerData p : players) {
            jsons.add(p.toJson());
        }
        String payload = "{\"players\":[" + String.join(",", jsons) + "]}";

        plugin.getDataFolder().mkdirs();
        String filename = LocalDateTime.now().format(FILE_TS) + ".json";
        Path out = plugin.getDataFolder().toPath().resolve(filename);
        Files.writeString(out, payload, StandardCharsets.UTF_8);
        sender.sendMessage("\u00a7aDumped " + players.size() + " players \u2192 " + out);
    }

    private record MainThreadResult(Map<UUID, MainThreadExtras> extras, Path advFolder) {}
}