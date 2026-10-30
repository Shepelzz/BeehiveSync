package com.beehiveprv.service;

import com.beehiveprv.dto.PlayerData;
import com.beehiveprv.dto.RoleLists;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
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
 *
 * <p>Stats strategy:
 * <ul>
 *   <li>Online players  — Bukkit API ({@link Player#getStatistic}), data is in memory and current.</li>
 *   <li>Offline players — read directly from {@code world/stats/{uuid}.json} on disk,
 *       because {@link OfflinePlayer#getStatistic} returns 0 for players not loaded in memory.</li>
 * </ul>
 */
public class PlayerDataService {

    public record MainThreadExtras(int advancements, boolean sponsor, boolean moderator, boolean admin) {}

    public record PlayerStats(long playtimeTicks, int kills, int deaths, long distanceCm) {}

    private static final Statistic[] DISTANCE_STATS = {
            Statistic.WALK_ONE_CM, Statistic.SPRINT_ONE_CM, Statistic.SWIM_ONE_CM,
            Statistic.FLY_ONE_CM, Statistic.CLIMB_ONE_CM, Statistic.BOAT_ONE_CM,
            Statistic.MINECART_ONE_CM, Statistic.HORSE_ONE_CM, Statistic.PIG_ONE_CM,
            Statistic.STRIDER_ONE_CM, Statistic.AVIATE_ONE_CM
    };

    // Corresponding minecraft:custom keys in world/stats/{uuid}.json
    private static final String[] DISTANCE_STAT_KEYS = {
            "minecraft:walk_one_cm", "minecraft:sprint_one_cm", "minecraft:swim_one_cm",
            "minecraft:fly_one_cm", "minecraft:climb_one_cm", "minecraft:boat_one_cm",
            "minecraft:minecart_one_cm", "minecraft:horse_one_cm", "minecraft:pig_one_cm",
            "minecraft:strider_one_cm", "minecraft:aviate_one_cm"
    };

    private final Logger logger;
    private final SkinService skinService;
    private final LuckPermsService luckPerms;

    public PlayerDataService(Logger logger, SkinService skinService, LuckPermsService luckPerms) {
        this.logger = logger;
        this.skinService = skinService;
        this.luckPerms = luckPerms;
    }

    // ── Main-thread methods ───────────────────────────────────────────────

    public MainThreadExtras collectMainThreadExtras(Player player) {
        List<Advancement> advList = getNonRecipeAdvancements();
        int adv = 0;
        for (Advancement a : advList) {
            try {
                if (player.getAdvancementProgress(a).isDone()) adv++;
            } catch (Exception ignored) {}
        }
        LuckPermsService.PermsResult perms = luckPerms.permsOf(player);
        return new MainThreadExtras(adv, perms.sponsor(), perms.moderator(), perms.admin());
    }

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
            LuckPermsService.PermsResult perms = luckPerms.permsOf(p);
            map.put(p.getUniqueId(), new MainThreadExtras(adv, perms.sponsor(), perms.moderator(), perms.admin()));
        }
        return map;
    }

    /**
     * Collect stats via Bukkit API for a single player.
     * <b>MUST be called on the main thread.</b>
     */
    public PlayerStats collectPlayerStats(OfflinePlayer player) {
        try {
            long playtimeTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE);
            int kills = player.getStatistic(Statistic.PLAYER_KILLS);
            int deaths = player.getStatistic(Statistic.DEATHS);
            long distanceCm = 0;
            for (Statistic stat : DISTANCE_STATS) {
                distanceCm += player.getStatistic(stat);
            }
            return new PlayerStats(playtimeTicks, kills, deaths, distanceCm);
        } catch (Exception e) {
            logger.warning("Failed to collect stats for " + player.getName() + ": " + e.getMessage());
            return new PlayerStats(0, 0, 0, 0);
        }
    }

    /**
     * Collect stats via Bukkit API for currently ONLINE players only.
     * Offline players will be handled via {@link #readStatsFromDisk}.
     * <b>MUST be called on the main thread.</b>
     */
    public Map<UUID, PlayerStats> collectAllPlayerStats() {
        Map<UUID, PlayerStats> map = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            map.put(p.getUniqueId(), collectPlayerStats(p));
        }
        return map;
    }

    public Path getAdvancementsFolder() {
        return getWorldFolder().resolve("advancements");
    }

    public Path getStatsFolder() {
        return getWorldFolder().resolve("stats");
    }

    private Path getWorldFolder() {
        return Bukkit.getWorlds().get(0).getWorldFolder().toPath();
    }

    // ── PlayerData building (any thread) ─────────────────────────────────

    public PlayerData buildPlayerData(OfflinePlayer player, MainThreadExtras extras,
                                      PlayerStats stats, String skinHead, boolean online) {
        String name = player.getName();
        if (name == null) return null;

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
                stats.distanceCm() / 100_000.0,
                online
        );
    }

    /**
     * Build {@link PlayerData} for ALL known players.
     *
     * <ul>
     *   <li>Online players  — use pre-collected {@code onlineStats} (Bukkit API, current).</li>
     *   <li>Offline players — read stats from {@code world/stats/{uuid}.json} on disk.</li>
     *   <li>Advancements for offline players — from {@code world/advancements/{uuid}.json}.</li>
     * </ul>
     *
     * Can be called from any thread (pass pre-collected main-thread data).
     */
    public List<PlayerData> buildAllPlayerData(Map<UUID, MainThreadExtras> onlineExtras,
                                               Map<UUID, PlayerStats> onlineStats,
                                               Path advFolder, Path statsFolder,
                                               SkinRetryQueue retryQueue) {
        boolean advFolderExists = Files.isDirectory(advFolder);
        OfflinePlayer[] allPlayers = Bukkit.getOfflinePlayers();
        List<PlayerData> result = new ArrayList<>(allPlayers.length);

        for (OfflinePlayer op : allPlayers) {
            if (op.getName() == null) continue;

            UUID uuid = op.getUniqueId();

            // Advancements: online → Bukkit API extras; offline → disk + LuckPerms
            MainThreadExtras extras = onlineExtras.get(uuid);
            if (extras == null) {
                int adv = advFolderExists ? countAdvancementsFromDisk(advFolder, uuid) : 0;
                LuckPermsService.PermsResult perms = luckPerms.queryPerms(uuid);
                extras = new MainThreadExtras(adv, perms.sponsor(), perms.moderator(), perms.admin());
            }

            // Stats: online → Bukkit API (in memory, accurate); offline → disk file
            boolean online = onlineStats.containsKey(uuid);
            PlayerStats stats;
            if (online) {
                stats = onlineStats.get(uuid);
            } else {
                stats = readStatsFromDisk(statsFolder, uuid);
            }

            if (stats.playtimeTicks() == 0) continue;

            String skin = skinService.getSkin(uuid);
            PlayerData data = buildPlayerData(op, extras, stats, skin, online);
            if (data == null) continue;

            // If Mojang API errored (not "player has no skin"), queue for retry
            if (skin == null && retryQueue != null && skinService.isMojangFailed(uuid)) {
                retryQueue.addAndSchedule(uuid, data);
            }

            result.add(data);
        }

        return result;
    }

    /**
     * Collect banned / admin / moderator / sponsor lists for ALL known players.
     * Roles come from LuckPerms storage, so this loads every user — call from an async thread.
     */
    public RoleLists collectRoleLists() {
        List<UUID> banned = new ArrayList<>();
        List<UUID> admins = new ArrayList<>();
        List<UUID> moderators = new ArrayList<>();
        List<UUID> sponsors = new ArrayList<>();

        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            UUID uuid = op.getUniqueId();
            if (op.isBanned()) banned.add(uuid);

            LuckPermsService.PermsResult perms = luckPerms.queryPerms(uuid);
            if (perms.admin()) admins.add(uuid);
            if (perms.moderator()) moderators.add(uuid);
            if (perms.sponsor()) sponsors.add(uuid);
        }
        return new RoleLists(banned, admins, moderators, sponsors);
    }

    // ── Disk-based stats reading ──────────────────────────────────────────

    /**
     * Read player statistics from {@code world/stats/{uuid}.json}.
     * Safe to call from any thread.
     *
     * <p>Stats JSON structure:
     * <pre>
     * {
     *   "stats": {
     *     "minecraft:custom": {
     *       "minecraft:play_time": 12345,
     *       "minecraft:deaths": 3,
     *       "minecraft:walk_one_cm": 100000,
     *       ...
     *     },
     *     "minecraft:killed": {
     *       "minecraft:player": 2
     *     }
     *   }
     * }
     * </pre>
     */
    public PlayerStats readStatsFromDisk(Path statsFolder, UUID uuid) {
        Path statsFile = statsFolder.resolve(uuid + ".json");
        if (!Files.exists(statsFile)) return new PlayerStats(0, 0, 0, 0);
        try {
            String content = Files.readString(statsFile, StandardCharsets.UTF_8);

            long playtimeTicks = parseStat(content, "minecraft:custom", "minecraft:play_time");
            int kills          = (int) parseStat(content, "minecraft:custom", "minecraft:player_kills");
            int deaths         = (int) parseStat(content, "minecraft:custom", "minecraft:deaths");

            long distanceCm = 0;
            for (String key : DISTANCE_STAT_KEYS) {
                distanceCm += parseStat(content, "minecraft:custom", key);
            }

            return new PlayerStats(playtimeTicks, kills, deaths, distanceCm);
        } catch (Exception e) {
            logger.warning("Failed to read stats from disk for " + uuid + ": " + e.getMessage());
            return new PlayerStats(0, 0, 0, 0);
        }
    }

    /**
     * Extract a numeric stat value from a Minecraft stats JSON string.
     *
     * @param json    full content of world/stats/{uuid}.json
     * @param section e.g. "minecraft:custom" or "minecraft:killed"
     * @param key     e.g. "minecraft:play_time" or "minecraft:player"
     */
    private static long parseStat(String json, String section, String key) {
        int sectionIdx = json.indexOf("\"" + section + "\"");
        if (sectionIdx < 0) return 0;

        int braceStart = json.indexOf('{', sectionIdx);
        if (braceStart < 0) return 0;

        int braceEnd = findMatchingBrace(json, braceStart);
        if (braceEnd < 0) return 0;

        String sectionContent = json.substring(braceStart, braceEnd + 1);
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)")
                .matcher(sectionContent);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    private static int findMatchingBrace(String s, int start) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }

    // ── Disk-based advancement counting ──────────────────────────────────

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

    private static int findEnclosingOpenBrace(String s, int pos) {
        int depth = 0;
        for (int i = pos - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == '}') depth++;
            else if (c == '{') {
                if (--depth < 0) return i;
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
