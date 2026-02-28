package com.beehiveprv.service;

import com.beehiveprv.dto.PlayerData;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Holds {@link PlayerData} records whose Mojang skin fetch failed,
 * and schedules a one-shot retry task 5 minutes later.
 *
 * <p>Only ONE retry task is scheduled at a time — additional failures
 * while a task is already pending are batched into the same run.
 */
public class SkinRetryQueue {

    private static final long RETRY_DELAY_TICKS = 5L * 60L * 20L; // 5 minutes

    private final JavaPlugin plugin;
    private final SkinService skinService;
    private final SyncOutputService syncOutput;
    private final Logger logger;

    private final ConcurrentHashMap<UUID, PlayerData> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean scheduled = new AtomicBoolean(false);

    public SkinRetryQueue(JavaPlugin plugin, SkinService skinService, SyncOutputService syncOutput) {
        this.plugin = plugin;
        this.skinService = skinService;
        this.syncOutput = syncOutput;
        this.logger = plugin.getLogger();
    }

    /**
     * Add a player whose skin could not be fetched from Mojang due to an API error.
     * Schedules the retry task if it isn't already scheduled.
     */
    public void addAndSchedule(UUID uuid, PlayerData dataWithoutSkin) {
        pending.put(uuid, dataWithoutSkin);
        logger.warning("[SkinRetry] Queued " + dataWithoutSkin.name()
                + " — Mojang API failed, will retry in 5 min.");

        if (scheduled.compareAndSet(false, true)) {
            logger.warning("[SkinRetry] Retry task scheduled (" + pending.size() + " player(s) pending).");
            plugin.getServer().getScheduler()
                    .runTaskLaterAsynchronously(plugin, this::runRetry, RETRY_DELAY_TICKS);
        }
    }

    // ── Retry task ────────────────────────────────────────────────────────

    private void runRetry() {
        scheduled.set(false);

        Map<UUID, PlayerData> toRetry = drain();
        if (toRetry.isEmpty()) return;

        logger.warning("[SkinRetry] Retrying skins for " + toRetry.size() + " player(s)...");
        int success = 0;

        for (Map.Entry<UUID, PlayerData> entry : toRetry.entrySet()) {
            UUID uuid = entry.getKey();
            PlayerData old = entry.getValue();

            String skin = skinService.fetchFromMcHeads(uuid);
            if (skin != null) {
                syncOutput.syncOne(old.withSkin(skin));
                logger.warning("[SkinRetry] Got skin for " + old.name() + " — re-synced.");
                success++;
            } else {
                logger.warning("[SkinRetry] Still no skin for " + old.name()
                        + " after retry — giving up (data already written without skin).");
            }
        }

        logger.warning("[SkinRetry] Done: " + success + "/" + toRetry.size() + " skin(s) recovered.");
    }

    private Map<UUID, PlayerData> drain() {
        Map<UUID, PlayerData> snapshot = new HashMap<>(pending);
        snapshot.keySet().forEach(pending::remove);
        return snapshot;
    }
}
