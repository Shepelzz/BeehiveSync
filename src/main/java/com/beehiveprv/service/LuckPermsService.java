package com.beehiveprv.service;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;

import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Queries LuckPerms for offline player group membership.
 * Falls back gracefully (all false) if LuckPerms is not installed.
 *
 * <p>Groups checked: {@code sponsor}, {@code moderator}, {@code admin}, {@code owner}.
 */
public class LuckPermsService {

    public record PermsResult(boolean sponsor, boolean moderator, boolean admin) {
        static final PermsResult EMPTY = new PermsResult(false, false, false);
    }

    private final LuckPerms api;
    private final Logger logger;

    public LuckPermsService(Logger logger) {
        this.logger = logger;
        LuckPerms lp = null;
        try {
            lp = LuckPermsProvider.get();
            logger.info("LuckPerms API connected.");
        } catch (IllegalStateException e) {
            logger.warning("LuckPerms not found — sponsor/moderator/admin will always be false for offline players.");
        }
        this.api = lp;
    }

    public boolean isAvailable() {
        return api != null;
    }

    /**
     * Load a player's LuckPerms data and return their group membership.
     * Safe to call from any async thread.
     *
     * <p>Loads the user from LP storage if not already cached,
     * then unloads them after reading (to avoid memory leaks on bulk operations).
     */
    public PermsResult queryPerms(UUID uuid) {
        if (api == null) return PermsResult.EMPTY;

        try {
            boolean alreadyLoaded = api.getUserManager().isLoaded(uuid);
            User user = api.getUserManager().loadUser(uuid).join();
            if (user == null) return PermsResult.EMPTY;

            Set<String> groups = user.getInheritedGroups(QueryOptions.nonContextual())
                    .stream()
                    .map(g -> g.getName().toLowerCase())
                    .collect(Collectors.toSet());

            PermsResult result = new PermsResult(
                    groups.contains("sponsor"),
                    groups.contains("moderator"),
                    groups.contains("admin") || groups.contains("owner")
            );

            // Remove from cache if we loaded them (they're offline and weren't cached before)
            if (!alreadyLoaded) {
                api.getUserManager().cleanupUser(user);
            }

            return result;
        } catch (Exception e) {
            logger.warning("[LuckPerms] Failed to query perms for " + uuid + ": " + e.getMessage());
            return PermsResult.EMPTY;
        }
    }
}
