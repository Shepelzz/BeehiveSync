package com.beehiveprv.service;

import com.beehiveprv.dto.PlayerData;
import com.beehiveprv.dto.RoleLists;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

/**
 * Routes player data to the configured output: REST API or local JSON files.
 *
 * <p>Mode is set once at startup via {@code sync.mode} in config.yml:
 * <ul>
 *   <li>{@code api}  — sends data to the Beehive REST API (requires token)</li>
 *   <li>{@code file} — writes pretty-printed JSON to the plugin data folder</li>
 * </ul>
 *
 * <p>File layout (mode=file):
 * <ul>
 *   <li>{@code plugins/BeehiveSync/players.json} — full dump from {@code /sync alldata}</li>
 *   <li>{@code plugins/BeehiveSync/players/{uuid}.json} — per-player file updated on join</li>
 *   <li>{@code plugins/BeehiveSync/roles.json} — role snapshot written on startup</li>
 * </ul>
 */
public class SyncOutputService {

    private final Logger logger;
    private final boolean fileMode;
    private final SyncApiClient apiClient;
    private final Path dataFolder;

    public SyncOutputService(Logger logger, boolean fileMode,
                             SyncApiClient apiClient, Path dataFolder) {
        this.logger = logger;
        this.fileMode = fileMode;
        this.apiClient = apiClient;
        this.dataFolder = dataFolder;
    }

    /**
     * Whether syncing is active at all.
     * In file mode — always active; in api mode — requires a configured token.
     */
    public boolean isActive() {
        return fileMode || apiClient.isConfigured();
    }

    public boolean isFileMode() {
        return fileMode;
    }

    /**
     * Sync all players — called from {@code /sync alldata}.
     *
     * @return number of players successfully written / sent
     */
    public int syncAll(List<PlayerData> players) {
        if (fileMode) {
            try {
                writeAllToFile(players);
                return players.size();
            } catch (Exception e) {
                logger.severe("Failed to write players.json: " + e.getMessage());
                return 0;
            }
        }
        return apiClient.sendPlayers(players);
    }

    /**
     * Sync a single player — called on player join.
     */
    public void syncOne(PlayerData player) {
        if (fileMode) {
            try {
                writeOneToFile(player);
            } catch (Exception e) {
                logger.warning("Failed to write player file for "
                        + player.name() + ": " + e.getMessage());
            }
        } else {
            apiClient.sendPlayers(List.of(player));
        }
    }

    /**
     * Sync the full role snapshot — called on plugin startup.
     */
    public void syncRoles(RoleLists roles) {
        if (fileMode) {
            try {
                dataFolder.toFile().mkdirs();
                Path out = dataFolder.resolve("roles.json");
                Files.writeString(out, roles.toJson(), StandardCharsets.UTF_8);
                logger.info("Wrote roles -> " + out);
            } catch (Exception e) {
                logger.warning("Failed to write roles.json: " + e.getMessage());
            }
        } else {
            apiClient.sendRoles(roles);
        }
    }

    // ── File writers ──────────────────────────────────────────────────────

    private void writeAllToFile(List<PlayerData> players) throws Exception {
        dataFolder.toFile().mkdirs();
        Path out = dataFolder.resolve("players.json");

        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"players\": [\n");
        for (int i = 0; i < players.size(); i++) {
            sb.append(players.get(i).toPrettyJson("    "));
            if (i < players.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n}");

        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        logger.info("Wrote " + players.size() + " players -> " + out);
    }

    private void writeOneToFile(PlayerData player) throws Exception {
        Path playersDir = dataFolder.resolve("players");
        playersDir.toFile().mkdirs();
        Path out = playersDir.resolve(player.uuid() + ".json");
        Files.writeString(out, player.toPrettyJson(), StandardCharsets.UTF_8);
        logger.fine("Wrote player -> " + out);
    }
}
