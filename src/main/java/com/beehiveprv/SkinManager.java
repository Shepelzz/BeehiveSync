package com.beehiveprv;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class SkinManager implements Listener {

    private final BeehiveSync plugin;
    private final Logger logger;
    private final ConcurrentHashMap<UUID, String> skinCache = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newHttpClient();

    SkinManager(BeehiveSync plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        org.bukkit.entity.Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String head = buildSkinHead(player);
            if (head != null) {
                skinCache.put(player.getUniqueId(), head);
            }
        });
    }

    private String buildSkinHead(org.bukkit.entity.Player player) {
        try {
            PlayerProfile profile = player.getPlayerProfile();
            Set<ProfileProperty> properties = profile.getProperties();

            String texturesValue = null;
            for (ProfileProperty prop : properties) {
                if ("textures".equals(prop.getName())) {
                    texturesValue = prop.getValue();
                    break;
                }
            }
            if (texturesValue == null) return null;

            // Decode base64 JSON: {"textures":{"SKIN":{"url":"..."}}}
            String json = new String(Base64.getDecoder().decode(texturesValue), StandardCharsets.UTF_8);

            int skinIdx = json.indexOf("\"SKIN\"");
            if (skinIdx == -1) return null;
            int urlIdx = json.indexOf("\"url\":\"", skinIdx);
            if (urlIdx == -1) return null;
            int urlStart = urlIdx + 7;
            int urlEnd = json.indexOf("\"", urlStart);
            if (urlEnd == -1) return null;
            String skinUrl = json.substring(urlStart, urlEnd);

            // Download skin PNG
            HttpResponse<byte[]> resp = http.send(
                    HttpRequest.newBuilder().uri(URI.create(skinUrl)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray()
            );
            if (resp.statusCode() != 200) return null;

            BufferedImage skin = ImageIO.read(new ByteArrayInputStream(resp.body()));
            if (skin == null) return null;

            // Crop face: base layer (8,8) + overlay (40,8), both 8×8
            BufferedImage face = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = face.createGraphics();
            g.drawImage(skin.getSubimage(8, 8, 8, 8), 0, 0, null);
            g.drawImage(skin.getSubimage(40, 8, 8, 8), 0, 0, null);
            g.dispose();

            // Scale 8×8 → 64×64 (nearest neighbor, no blurring)
            BufferedImage scaled = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = scaled.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2.drawImage(face, 0, 0, 64, 64, null);
            g2.dispose();

            // Encode to base64 (no prefix)
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(scaled, "png", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());

        } catch (Exception e) {
            logger.warning("Failed to fetch skin for " + player.getName() + ": " + e.getMessage());
            return null;
        }
    }

    public String getCachedSkin(UUID uuid) {
        return skinCache.get(uuid);
    }
}
