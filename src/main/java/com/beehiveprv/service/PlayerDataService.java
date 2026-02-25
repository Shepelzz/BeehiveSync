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
 * Shared by PlayerJoinListener, SyncCommand, and PeriodicSyncTask.
 */
public class PlayerDataService {

    public record MainThreadExtras(int advancements, boolean sponsor, boolean moderator, boolean admin) {}

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

    // ── Main-thread methods (advancements & permissions) ─────────────────

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
        return Bukkit.getWorlds().get(0).getWorldFolder().toPath().resolve("advancements");
    }

    // ── PlayerData building (any thread) ─────────────────────────────────

    /**
     * Build {@link PlayerData} for a single player.
     * Can be called from any thread.
     *
     * @param player  the player (online or offline)
     * @param extras  main-thread data (null for offline players without extras)
     * @param skinHead pre-resolved skin base64 (null if unavailable)
     */
    public PlayerData buildPlayerData(OfflinePlayer player, MainThreadExtras extras, String skinHead) {
        String name = player.getName();
        if (name == null) return null;

        long playtimeTicks = safeStat(player, Statistic.PLAY_ONE_MINUTE);
        int kills = (int) safeStat(player, Statistic.PLAYER_KILLS);
        int deaths = (int) safeStat(player, Statistic.DEATHS);
        long distanceCm = 0;
        for (Statistic s : DISTANCE_STATS) {
            distanceCm += safeStat(player, s);
        }

        return new PlayerData(
                player.getUniqueId(),
                name,
                skinHead,
                playtimeTicks / 20L / 60L,
                extras != null ? extras.advancements() : 0,
                extras != null && extras.sponsor(),
                player.isBanned(),
                extras != null && extras.moderator(),
                extras != null && extras.admin(),
                kills,
                deaths,
                distanceCm / 100_000.0
        );
    }

    /**
     * Build {@link PlayerData} for ALL known players.
     * Resolves skins via {@link SkinService#getSkin(UUID)} (cache + SkinsRestorer fallback).
     * Can be called from any thread (pass pre-collected main-thread data).
     *
     * @param onlineExtras main-thread extras for online players
     * @param advFolder    path to world/advancements/ folder
     */
    public List<PlayerData> buildAllPlayerData(Map<UUID, MainThreadExtras> onlineExtras, Path advFolder) {
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
            PlayerData data = buildPlayerData(op, extras, skin);
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

    private static long safeStat(OfflinePlayer player, Statistic stat) {
        try {
            return player.getStatistic(stat);
        } catch (Exception e) {
            return 0;
        }
    }
}