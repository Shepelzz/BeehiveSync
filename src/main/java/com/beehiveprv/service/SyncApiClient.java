package com.beehiveprv.service;

import com.beehiveprv.dto.PlayerData;
import com.beehiveprv.dto.RoleLists;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * HTTP client for sending player data to the Beehive REST API.
 */
public class SyncApiClient {

    private static final int BATCH_SIZE = 10;

    private final Logger logger;
    private final String url;
    private final String token;
    private final HttpClient http = HttpClient.newHttpClient();

    public SyncApiClient(Logger logger, String url, String token) {
        this.logger = logger;
        this.url = url;
        this.token = token;
    }

    /**
     * Whether a valid token is configured. If false, no HTTP requests should be made.
     */
    public boolean isConfigured() {
        return token != null && !token.isBlank();
    }

    /**
     * Send a list of players to the API, splitting into batches of 500.
     *
     * @return number of players successfully sent
     */
    public int sendPlayers(List<PlayerData> players) {
        int sent = 0;
        for (int i = 0; i < players.size(); i += BATCH_SIZE) {
            List<PlayerData> batch = players.subList(i, Math.min(i + BATCH_SIZE, players.size()));
            if (sendBatch(batch)) {
                sent += batch.size();
            }
        }
        return sent;
    }

    /**
     * Tells the site to reset all players' online status to offline.
     * Called on plugin enable to clear stale online states.
     */
    public void resetOnline() {
        if (!isConfigured()) return;
        try {
            String resetUrl = url.replace("sync.php", "reset_online.php");
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(resetUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                logger.info("Reset online statuses: " + resp.body());
            } else {
                logger.warning("Reset online failed [" + resp.statusCode() + "]: " + resp.body());
            }
        } catch (Exception e) {
            logger.warning("Reset online HTTP error: " + e.getMessage());
        }
    }

    /**
     * Sends the full role snapshot. The site resets every flag not present in the lists,
     * so stale bans / roles don't hang on the site after they were removed in game.
     */
    public boolean sendRoles(RoleLists roles) {
        if (!isConfigured()) return false;
        try {
            String rolesUrl = url.replace("sync.php", "roles.php");
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(rolesUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(roles.toJson()))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                logger.info("Roles synced: " + resp.body());
                return true;
            }
            logger.warning("Roles sync failed [" + resp.statusCode() + "]: " + resp.body());
            return false;
        } catch (Exception e) {
            logger.warning("Roles sync HTTP error: " + e.getMessage());
            return false;
        }
    }

    private boolean sendBatch(List<PlayerData> batch) {
        try {
            List<String> jsons = new ArrayList<>(batch.size());
            for (PlayerData p : batch) {
                jsons.add(p.toJson());
            }

            String body = "{\"players\":[" + String.join(",", jsons) + "]}";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                logger.info("Sync OK (" + batch.size() + " players): " + resp.body());
                return true;
            } else {
                logger.warning("Sync failed [" + resp.statusCode() + "]: " + resp.body());
                return false;
            }
        } catch (Exception e) {
            logger.warning("Sync HTTP error: " + e.getMessage());
            return false;
        }
    }
}