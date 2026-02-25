package com.beehiveprv.dto;

import java.util.Locale;
import java.util.UUID;

public record PlayerData(
        UUID uuid,
        String name,
        String skinHead,
        long playtimeMinutes,
        int achievements,
        boolean sponsor,
        boolean banned,
        boolean moderator,
        boolean admin,
        int kills,
        int deaths,
        double distanceKm
) {

    public String toJson() {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        sb.append("\"uuid\":\"").append(uuid).append("\",");
        sb.append("\"name\":\"").append(escapeJson(name)).append("\",");
        if (skinHead != null) {
            sb.append("\"skin_head\":\"").append(skinHead).append("\",");
        }
        sb.append("\"playtime_minutes\":").append(playtimeMinutes).append(',');
        sb.append("\"achievements\":").append(achievements).append(',');
        sb.append("\"is_sponsor\":").append(sponsor).append(',');
        sb.append("\"is_banned\":").append(banned).append(',');
        sb.append("\"is_moderator\":").append(moderator).append(',');
        sb.append("\"is_admin\":").append(admin).append(',');
        sb.append("\"kills\":").append(kills).append(',');
        sb.append("\"deaths\":").append(deaths).append(',');
        sb.append("\"distance_km\":").append(String.format(Locale.US, "%.2f", distanceKm));
        sb.append('}');
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}