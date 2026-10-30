package com.beehiveprv.dto;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Full role snapshot of all known players. The site replaces its flags with these lists,
 * so anyone missing from a list loses that role.
 */
public record RoleLists(
        List<UUID> banned,
        List<UUID> admins,
        List<UUID> moderators,
        List<UUID> sponsors) {

    public String toJson() {
        return "{\"banned\":" + array(banned)
                + ",\"admins\":" + array(admins)
                + ",\"moderators\":" + array(moderators)
                + ",\"sponsors\":" + array(sponsors) + "}";
    }

    private static String array(List<UUID> uuids) {
        return uuids.stream().map(u -> "\"" + u + "\"").collect(Collectors.joining(",", "[", "]"));
    }
}
