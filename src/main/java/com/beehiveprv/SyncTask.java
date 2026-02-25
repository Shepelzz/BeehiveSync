package com.beehiveprv;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class SyncTask implements Runnable {

    private record OnlineData(int advancements, boolean sponsor, boolean moderator, boolean admin) {}

    private static final Statistic[] DISTANCE_STATS = {
            Statistic.WALK_ONE_CM, Statistic.SPRINT_ONE_CM, Statistic.SWIM_ONE_CM,
            Statistic.FLY_ONE_CM, Statistic.CLIMB_ONE_CM, Statistic.BOAT_ONE_CM,
            Statistic.MINECART_ONE_CM, Statistic.HORSE_ONE_CM, Statistic.PIG_ONE_CM,
            Statistic.STRIDER_ONE_CM, Statistic.AVIATE_ONE_CM
    };

    private final BeehiveSync plugin;
    private final SkinManager skinManager;
    private final String url;
    private final String token;
    private final Logger logger;
    private final HttpClient http = HttpClient.newHttpClient();

    SyncTask(BeehiveSync plugin, SkinManager skinManager, String url, String token) {
        this.plugin = plugin;
        this.skinManager = skinManager;
        this.url = url;
        this.token = token;
        this.logger = plugin.getLogger();
    }

    @Override
    public void run() {
        if (!plugin.isEnabled()) return;

        // Collect permissions & advancements on the main thread (thread-safe)
        CompletableFuture<Map<UUID, OnlineData>> future = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                future.complete(collectOnlineData());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        Map<UUID, OnlineData> onlineData;
        try {
            onlineData = future.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warning("BeehiveSync: could not collect online data: " + e.getMessage());
            return;
        }

        // getOfflinePlayers() is disk I/O — safe to call from async thread
        OfflinePlayer[] all = Bukkit.getOfflinePlayers();

        List<String> batch = new ArrayList<>(500);
        for (OfflinePlayer op : all) {
            if (op.getUniqueId() == null || op.getName() == null) continue;
            String json = buildPlayerJson(op, onlineData.get(op.getUniqueId()));
            if (json == null) continue;
            batch.add(json);
            if (batch.size() == 500) {
                sendBatch(batch);
                batch = new ArrayList<>(500);
            }
        }
        if (!batch.isEmpty()) sendBatch(batch);
    }

    // Must be called on the main thread
    private Map<UUID, OnlineData> collectOnlineData() {
        // Build advancement list once, skipping recipe advancements
        List<Advancement> advList = new ArrayList<>();
        Bukkit.advancementIterator().forEachRemaining(a -> {
            if (!a.getKey().getKey().startsWith("recipes/")) advList.add(a);
        });

        Map<UUID, OnlineData> map = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            int adv = 0;
            for (Advancement a : advList) {
                try {
                    if (p.getAdvancementProgress(a).isDone()) adv++;
                } catch (Exception ignored) {}
            }
            boolean sponsor = p.hasPermission("group.sponsor");
            boolean mod = p.hasPermission("group.moderator");
            boolean admin = p.hasPermission("group.admin") || p.hasPermission("group.owner");
            map.put(p.getUniqueId(), new OnlineData(adv, sponsor, mod, admin));
        }
        return map;
    }

    private String buildPlayerJson(OfflinePlayer op, OnlineData online) {
        try {
            UUID uuid = op.getUniqueId();
            String name = op.getName();

            long playtimeTicks = 0;
            int kills = 0, deaths = 0;
            long distanceCm = 0;

            try { playtimeTicks = op.getStatistic(Statistic.PLAY_ONE_MINUTE); } catch (Exception ignored) {}
            try { kills = op.getStatistic(Statistic.PLAYER_KILLS); } catch (Exception ignored) {}
            try { deaths = op.getStatistic(Statistic.DEATHS); } catch (Exception ignored) {}
            for (Statistic s : DISTANCE_STATS) {
                try { distanceCm += op.getStatistic(s); } catch (Exception ignored) {}
            }

            String skin = skinManager.getCachedSkin(uuid);
            int adv = online != null ? online.advancements() : 0;
            boolean sponsor = online != null && online.sponsor();
            boolean mod = online != null && online.moderator();
            boolean admin = online != null && online.admin();
            boolean banned = op.isBanned();

            StringBuilder sb = new StringBuilder("{");
            sb.append("\"uuid\":\"").append(uuid).append("\",");
            sb.append("\"name\":\"").append(escapeJson(name)).append("\",");
            if (skin != null) sb.append("\"skin_head\":\"").append(skin).append("\",");
            sb.append("\"playtime_minutes\":").append(playtimeTicks / 20L / 60L).append(",");
            sb.append("\"achievements\":").append(adv).append(",");
            sb.append("\"is_sponsor\":").append(sponsor).append(",");
            sb.append("\"is_banned\":").append(banned).append(",");
            sb.append("\"is_moderator\":").append(mod).append(",");
            sb.append("\"is_admin\":").append(admin).append(",");
            sb.append("\"kills\":").append(kills).append(",");
            sb.append("\"deaths\":").append(deaths).append(",");
            sb.append("\"distance_km\":").append(String.format(Locale.US, "%.2f", distanceCm / 100_000.0));
            sb.append("}");
            return sb.toString();

        } catch (Exception e) {
            logger.warning("BeehiveSync: failed to build JSON for " + op.getName() + ": " + e.getMessage());
            return null;
        }
    }

    private void sendBatch(List<String> playerJsons) {
        try {
            String body = "{\"players\":[" + String.join(",", playerJsons) + "]}";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                logger.info("Sync ok (" + playerJsons.size() + " players): " + resp.body());
            } else {
                logger.warning("Sync failed [" + resp.statusCode() + "]: " + resp.body());
            }
        } catch (Exception e) {
            logger.warning("BeehiveSync: HTTP error: " + e.getMessage());
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
