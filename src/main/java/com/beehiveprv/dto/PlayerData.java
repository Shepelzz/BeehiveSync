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

    private static final String DEFAULT_SKIN_HEAD =
            "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAYAAACqaXHeAAAACXBIWXMAAA7EAAAOxAGVKw4bAAAB10lEQVR4nO2bsU7CUBiFb9sLpRpEEyMMJPoGvoBhNCaOujuQ6OAjGJ2NxsXFxSfQGAcd1IHBzVfQBEGJykBISJEUWurKdG6Iwxnu\\/61fezkc\\/uS2TXFWV\\/KpAsRjZJXSjgt9lMTQx3ECvevi9TPagz6F304pvLoFSAHsAGykAHYANlIAOwAbbdrnTcQpXmA2l4W+kHWgTxTe5zthhM9PDdcZ0FqAFMAOwEYKYAdgIwWwA7DRpgNO93eg9zN4nw9yc9AP+j0cwMO\\/UffnC\\/rj2wforZ8AKYAdgI0UwA7ARgpgB2DjXB3uwifnM74PF\\/iN8P245+Hzu70u9MvFJeibjVfoTVg\\/AVIAOwAbKYAdgI0UwA7ARpv2+c2Dc+i3KifQVyt16E37\\/MV9Efqb5zPor4\\/2oLd+AqQAdgA2UgA7ABspgB2AjXNZXYfPA4qlMlxA+\\/PQ39Vq06eaYHtjDfrWxxv09XYHeusnQApgB2AjBbADsJEC2AHY6HCE36MbtvD9fPOzD33ezUwdapLHpxfoS+UAekfhz7d+AqQAdgA2UgA7ABspgB2AjX7\\/bsMDxmP8f4DFhcK\\/AkSjIfSBn4M+HODrGNP61k+AFMAOwEYKYAdgIwWwA7D5A\\/UbWiy89EeuAAAAAElFTkSuQmCC";

    private String effectiveSkin() {
        return skinHead != null ? skinHead : DEFAULT_SKIN_HEAD;
    }

    /** Compact JSON — used when sending to the API. */
    public String toJson() {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        sb.append("\"uuid\":\"").append(uuid).append("\",");
        sb.append("\"name\":\"").append(escapeJson(name)).append("\",");
        sb.append("\"skin_head\":\"").append(effectiveSkin()).append("\",");
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

    /**
     * Pretty-printed JSON — used when writing to files.
     *
     * @param base leading indentation for each line (e.g. "    " for 4 spaces)
     */
    public String toPrettyJson(String base) {
        String indent = base + "  ";
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append(indent).append("\"uuid\": \"").append(uuid).append("\",\n");
        sb.append(indent).append("\"name\": \"").append(escapeJson(name)).append("\",\n");
        sb.append(indent).append("\"skin_head\": \"").append(effectiveSkin()).append("\",\n");
        sb.append(indent).append("\"playtime_minutes\": ").append(playtimeMinutes).append(",\n");
        sb.append(indent).append("\"achievements\": ").append(achievements).append(",\n");
        sb.append(indent).append("\"is_sponsor\": ").append(sponsor).append(",\n");
        sb.append(indent).append("\"is_banned\": ").append(banned).append(",\n");
        sb.append(indent).append("\"is_moderator\": ").append(moderator).append(",\n");
        sb.append(indent).append("\"is_admin\": ").append(admin).append(",\n");
        sb.append(indent).append("\"kills\": ").append(kills).append(",\n");
        sb.append(indent).append("\"deaths\": ").append(deaths).append(",\n");
        sb.append(indent).append("\"distance_km\": ").append(String.format(Locale.US, "%.2f", distanceKm)).append("\n");
        sb.append(base).append("}");
        return sb.toString();
    }

    /** Pretty-printed JSON with no extra base indentation. */
    public String toPrettyJson() {
        return toPrettyJson("");
    }

    /** Return a copy of this record with a different skinHead. */
    public PlayerData withSkin(String skinHead) {
        return new PlayerData(uuid, name, skinHead, playtimeMinutes, achievements,
                sponsor, banned, moderator, admin, kills, deaths, distanceKm);
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
