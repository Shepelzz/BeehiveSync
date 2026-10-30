package com.beehiveprv.service;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Resolves site roles from LuckPerms group membership.
 * Falls back gracefully (all false) if LuckPerms is not installed.
 *
 * <p>Roles are taken from groups only — never from {@code hasPermission}, because OPs
 * pass every undefined permission check and would show up on the site as staff.
 * <ul>
 *   <li>sponsor   — {@code sponsor}</li>
 *   <li>moderator — {@code moderator}, {@code moderator_light}</li>
 *   <li>admin     — {@code admin}, {@code admin_light}, {@code owner}</li>
 * </ul>
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
     * Roles of an online player. LuckPerms keeps online users loaded, so this is safe
     * on the main thread. Without LuckPerms only explicitly set {@code group.*} permissions count.
     */
    public PermsResult permsOf(Player player) {
        if (api == null) {
            return new PermsResult(
                    explicit(player, "group.sponsor"),
                    explicit(player, "group.moderator") || explicit(player, "group.moderator_light"),
                    explicit(player, "group.admin") || explicit(player, "group.admin_light")
                            || explicit(player, "group.owner")
            );
        }
        User user = api.getUserManager().getUser(player.getUniqueId());
        return user != null ? fromUser(user) : queryPerms(player.getUniqueId());
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

            PermsResult result = fromUser(user);

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

    private static PermsResult fromUser(User user) {
        Set<String> groups = user.getInheritedGroups(QueryOptions.nonContextual())
                .stream()
                .map(g -> g.getName().toLowerCase())
                .collect(Collectors.toSet());

        return new PermsResult(
                groups.contains("sponsor"),
                groups.contains("moderator") || groups.contains("moderator_light"),
                groups.contains("admin") || groups.contains("admin_light") || groups.contains("owner")
        );
    }

    private static boolean explicit(Player player, String permission) {
        return player.isPermissionSet(permission) && player.hasPermission(permission);
    }
}
