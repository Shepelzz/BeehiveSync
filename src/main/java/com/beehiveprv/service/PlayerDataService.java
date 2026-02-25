package com.beehiveprv.service;

import com.beehiveprv.dto.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects player statistics and builds {@link PlayerData} objects.
 * Reads stats from world/stats/{uuid}.json on disk (works for both online and offline players).
 */
public class PlayerDataService {

    public record MainThreadExtras(int advancements, boolean sponsor, boolean moderator, boolean admin) {}

    /** Raw numeric stats read from world/stats/{uuid}.json */
    public record PlayerStats(long playtimeTicks, int kills, int deaths, long distanceCm) {}

    private static final String[] DISTANCE_STAT_KEYS = {
            "walk_one_cm", "sprint_one_cm", "swim_one_cm",
            "fly_one_cm", "climb_one_cm", "boat_one_cm",
            "minecart_one_cm", "horse_one_cm", "pig_one_cm",
            "strider_one_cm", "aviate_one_cm"
    };

    private final Logger logger;
    private final SkinService skinService;

    public PlayerDataService(Logger logger, SkinService skinService) {
        this.logger = logger;
        this.skinService = skinService;
    }

    // ── Main-thread methods (advancements, permissions, world paths) ─────

    /**
     * Collect advancements and permissions for a single online player.
     * <b>MUST be called on the main thread.</b>
     */
    public MainThreadExtras collectMainThreadExtras(Player player) {
        List<Advancement> advList = getNonRecipeAdvancements();
        int adv = 0;
        for (Advancement a : advList) {
            try {
                if (player.getAdvancementProgress(a).isDone()) adv++;
            } catch (Exception ignored) {}
        }
        return new MainThreadExtras(
                adv,
                player.hasPermission("group.sponsor"),
                player.hasPermission("group.moderator"),
                player.hasPermission("group.admin") || player.hasPermission("group.owner")
        );
    }

    /**
     * Collect advancements and permissions for all online players.
     * <b>MUST be called on the main thread.</b>
     */
    public Map<UUID, MainThreadExtras> collectAllMainThreadExtras() {
        List<Advancement> advList = getNonRecipeAdvancements();
        Map<UUID, MainThreadExtras> map = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            int adv = 0;
            for (Advancement a : advList) {
                try {
                    if (p.getAdvancementProgress(a).isDone()) adv++;
                } catch (Exception ignored) {}
            }
            map.put(p.getUniqueId(), new MainThreadExtras(
                    adv,
                    p.hasPermission("group.sponsor"),
                    p.hasPermission("group.moderator"),
                    p.hasPermission("group.admin") || p.hasPermission("group.owner")
            ));
        }
        return map;
    }

    /**
     * Path to the world/advancements/ folder.
     * <b>MUST be called on the main thread.</b>
     */
    public Path getAdvancementsFolder() {
        return getWorldFolder().resolve("advancements");
    }

    /**
     * Path to the world/stats/ folder.
     * <b>MUST be called on the main thread.</b>
     */
    public Path getStatsFolder() {
        return getWorldFolder().resolve("stats");
    }

    private Path getWorldFolder() {
        return Bukkit.getWorlds().get(0).getWorldFolder().toPath();
    }

    // ── PlayerData building (any thread) ─────────────────────────────────

    /**
     * Build {@link PlayerData} for a single player using disk-read stats.
     * Can be called from any thread.
     */
    public PlayerData buildPlayerData(OfflinePlayer player, MainThreadExtras extras,
                                      String skinHead, Path statsFolder) {
        String name = player.getName();
        if (name == null) return null;

        PlayerStats stats = readStatsFromDisk(statsFolder, player.getUniqueId());

        return new PlayerData(
                player.getUniqueId(),
                name,
                skinHead,
                stats.playtimeTicks() / 20L / 60L,
                extras != null ? extras.advancements() : 0,
                extras != null && extras.sponsor(),
                player.isBanned(),
                extras != null && extras.moderator(),
                extras != null && extras.admin(),
                stats.kills(),
                stats.deaths(),
                stats.distanceCm() / 100_000.0
        );
    }

    /**
     * Build {@link PlayerData} for ALL known players.
     * Reads stats and advancements from disk, resolves skins via cache + SkinsRestorer.
     * Can be called from any thread (pass pre-collected main-thread data).
     */
    public List<PlayerData> buildAllPlayerData(Map<UUID, MainThreadExtras> onlineExtras,
                                               Path advFolder, Path statsFolder) {
        boolean advFolderExists = Files.isDirectory(advFolder);
        OfflinePlayer[] allPlayers = Bukkit.getOfflinePlayers();
        List<PlayerData> result = new ArrayList<>(allPlayers.length);

        for (OfflinePlayer op : allPlayers) {
            if (op.getName() == null) continue;

            UUID uuid = op.getUniqueId();

            MainThreadExtras extras = onlineExtras.get(uuid);
            if (extras == null && advFolderExists) {
                int adv = countAdvancementsFromDisk(advFolder, uuid);
                extras = new MainThreadExtras(adv, false, false, false);
            }

            String skin = skinService.getSkin(uuid);
            PlayerData data = buildPlayerData(op, extras, skin, statsFolder);
            if (data != null) result.add(data);
        }

        return result;
    }

    // ── Disk-based stats reading ─────────────────────────────────────────

    /**
     * Read player stats from world/stats/{uuid}.json.
     * File format:
     * <pre>
     * { "stats": { "minecraft:custom": { "minecraft:play_one_minute": 12345, ... } } }
     * </pre>
     * Safe to call from any thread.
     */
    public PlayerStats readStatsFromDisk(Path statsFolder, UUID uuid) {
        Path statsFile = statsFolder.resolve(uuid + ".json");
        if (!Files.exists(statsFile)) return new PlayerStats(0, 0, 0, 0);

        try {
            String content = Files.readString(statsFile, StandardCharsets.UTF_8);

            long playtime = extractStat(content, "play_one_minute");
            int kills = (int) extractStat(content, "player_kills");
            int deaths = (int) extractStat(content, "deaths");

            long distanceCm = 0;
            for (String key : DISTANCE_STAT_KEYS) {
                distanceCm += extractStat(content, key);
            }

            return new PlayerStats(playtime, kills, deaths, distanceCm);
        } catch (Exception e) {
            logger.warning("Failed to read stats for " + uuid + ": " + e.getMessage());
            return new PlayerStats(0, 0, 0, 0);
        }
    }

    private static long extractStat(String content, String statName) {
        Matcher m = Pattern.compile("\"minecraft:" + statName + "\"\\s*:\\s*(\\d+)").matcher(content);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    // ── Disk-based advancement counting ──────────────────────────────────

    /**
     * Count completed non-recipe advancements by reading
     * world/advancements/{uuid}.json directly from disk.
     * Safe to call from any thread.
     */
    public int countAdvancementsFromDisk(Path advFolder, UUID uuid) {
        Path advFile = advFolder.resolve(uuid + ".json");
        if (!Files.exists(advFile)) return 0;
        try {
            String content = Files.readString(advFile, StandardCharsets.UTF_8);
            int count = 0;
            Matcher m = Pattern.compile("\"done\"\\s*:\\s*true").matcher(content);
            while (m.find()) {
                int brace = findEnclosingOpenBrace(content, m.start());
                if (brace < 0) continue;

                String before = content.substring(0, brace).stripTrailing();
                if (!before.endsWith(":")) continue;
                before = before.substring(0, before.length() - 1).stripTrailing();
                if (!before.endsWith("\"")) continue;
                int keyEnd = before.length() - 1;
                int keyStart = before.lastIndexOf('"', keyEnd - 1);
                if (keyStart < 0) continue;
                String key = before.substring(keyStart + 1, keyEnd);

                if (!key.contains("recipes/")) count++;
            }
            return count;
        } catch (Exception e) {
            logger.warning("Failed to read advancements for " + uuid + ": " + e.getMessage());
            return 0;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static int findEnclosingOpenBrace(String s, int pos) {
        int depth = 0;
        for (int i = pos - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == '}') depth++;
            else if (c == '{') {
                depth--;
                if (depth < 0) return i;
            }
        }
        return -1;
    }

    private List<Advancement> getNonRecipeAdvancements() {
        List<Advancement> list = new ArrayList<>();
        Bukkit.advancementIterator().forEachRemaining(a -> {
            if (!a.getKey().getKey().startsWith("recipes/")) list.add(a);
        });
        return list;
    }
}
