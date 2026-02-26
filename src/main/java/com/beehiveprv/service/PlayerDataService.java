package com.beehiveprv.service;

import com.beehiveprv.dto.PlayerData;
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
 * Stats are collected via the Bukkit API ({@link OfflinePlayer#getStatistic}).
 * Skins — from cache / SkinsRestorer files.
 * Advancements for offline players — from disk.
 */
public class PlayerDataService {

    public record MainThreadExtras(int advancements, boolean sponsor, boolean moderator, boolean admin) {}

    /** Player stats collected via Bukkit API. */
    public record PlayerStats(long playtimeTicks, int kills, int deaths, long distanceCm) {}

    private static final Statistic[] DISTANCE_STATS = {
            Statistic.WALK_ONE_CM, Statistic.SPRINT_ONE_CM, Statistic.SWIM_ONE_CM,
            Statistic.FLY_ONE_CM, Statistic.CLIMB_ONE_CM, Statistic.BOAT_ONE_CM,
            Statistic.MINECART_ONE_CM, Statistic.HORSE_ONE_CM, Statistic.PIG_ONE_CM,
            Statistic.STRIDER_ONE_CM, Statistic.AVIATE_ONE_CM
    };

    private final Logger logger;
    private final SkinService skinService;

    public PlayerDataService(Logger logger, SkinService skinService) {
        this.logger = logger;
        this.skinService = skinService;
    }

    // ── Main-thread methods (advancements, permissions, stats) ───────────

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
     * Collect stats for a single player via Bukkit API.
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
     * Collect stats for all known players via Bukkit API.
     * <b>MUST be called on the main thread.</b>
     */
    public Map<UUID, PlayerStats> collectAllPlayerStats() {
        OfflinePlayer[] allPlayers = Bukkit.getOfflinePlayers();
        Map<UUID, PlayerStats> map = new HashMap<>(allPlayers.length);
        for (OfflinePlayer op : allPlayers) {
            if (op.getName() == null) continue;
            map.put(op.getUniqueId(), collectPlayerStats(op));
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

    private Path getWorldFolder() {
        return Bukkit.getWorlds().get(0).getWorldFolder().toPath();
    }

    // ── PlayerData building (any thread) ─────────────────────────────────

    /**
     * Build {@link PlayerData} for a single player using pre-collected stats.
     * Can be called from any thread.
     */
    public PlayerData buildPlayerData(OfflinePlayer player, MainThreadExtras extras,
                                      PlayerStats stats, String skinHead) {
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
                stats.distanceCm() / 100_000.0
        );
    }

    /**
     * Build {@link PlayerData} for ALL known players.
     * Stats come from pre-collected map, skins from cache + SkinsRestorer,
     * advancements for offline players from disk.
     * Can be called from any thread (pass pre-collected main-thread data).
     */
    public List<PlayerData> buildAllPlayerData(Map<UUID, MainThreadExtras> onlineExtras,
                                               Map<UUID, PlayerStats> allStats,
                                               Path advFolder) {
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

            PlayerStats stats = allStats.getOrDefault(uuid, new PlayerStats(0, 0, 0, 0));

            String skin = skinService.getSkin(uuid);
            PlayerData data = buildPlayerData(op, extras, stats, skin);
            if (data != null) result.add(data);
        }

        return result;
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