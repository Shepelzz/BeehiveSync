package com.beehiveprv.service;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.entity.Player;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles all skin operations: downloading, cropping, caching,
 * and SkinsRestorer file reading.
 */
public class SkinService {

    private final Logger logger;
    private final Path skinsRestorerFolder;
    private final ConcurrentHashMap<UUID, String> cache = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newHttpClient();

    public SkinService(Logger logger, Path pluginDataFolder) {
        this.logger = logger;
        this.skinsRestorerFolder = pluginDataFolder.resolveSibling("SkinsRestorer");
    }

    /**
     * Build skin head from an online player's profile textures and cache it.
     * Call from async thread (downloads the skin PNG over HTTP).
     */
    public String buildAndCache(Player player) {
        String head = buildFromProfile(player);
        if (head != null) {
            cache.put(player.getUniqueId(), head);
        }
        return head;
    }

    /**
     * Get skin for any player: in-memory cache first, then SkinsRestorer disk files.
     * Call from async thread.
     */
    public String getSkin(UUID uuid) {
        String cached = cache.get(uuid);
        if (cached != null) return cached;
        String fromSR = buildFromSkinsRestorer(uuid);
        if (fromSR != null) {
            cache.put(uuid, fromSR);
        }
        return fromSR;
    }

    // ── From online player profile ───────────────────────────────────────

    private String buildFromProfile(Player player) {
        try {
            PlayerProfile profile = player.getPlayerProfile();
            String texturesValue = null;
            for (ProfileProperty prop : profile.getProperties()) {
                if ("textures".equals(prop.getName())) {
                    texturesValue = prop.getValue();
                    break;
                }
            }
            if (texturesValue == null) return null;
            String skinUrl = extractSkinUrl(texturesValue);
            if (skinUrl == null) return null;
            return downloadAndProcess(skinUrl);
        } catch (Exception e) {
            logger.warning("Failed to fetch skin for " + player.getName() + ": " + e.getMessage());
            return null;
        }
    }

    // ── From SkinsRestorer disk files ────────────────────────────────────

    private String buildFromSkinsRestorer(UUID uuid) {
        Path playersFolder = skinsRestorerFolder.resolve("players");
        Path skinsFolder = skinsRestorerFolder.resolve("skins");
        Path playerFile = playersFolder.resolve(uuid + ".player");

        if (!Files.exists(playerFile)) return null;
        try {
            String content = Files.readString(playerFile, StandardCharsets.UTF_8);
            SkinRef ref = parseSkinRef(content);
            if (ref == null) return null;
            return resolveSkinFromRef(skinsFolder, ref);
        } catch (Exception e) {
            logger.warning("Failed to read SR skin for " + uuid + ": " + e.getMessage());
            return null;
        }
    }

    private record SkinRef(String identifier, String type) {}

    private SkinRef parseSkinRef(String content) {
        int blockStart = content.indexOf("\"skinIdentifier\"");
        if (blockStart == -1) return null;
        int openBrace = content.indexOf('{', blockStart);
        if (openBrace == -1) return null;
        int closeBrace = content.indexOf('}', openBrace + 1);
        if (closeBrace == -1) return null;

        String block = content.substring(openBrace, closeBrace + 1);
        Matcher idM = Pattern.compile("\"identifier\"\\s*:\\s*\"([^\"]+)\"").matcher(block);
        Matcher typeM = Pattern.compile("\"type\"\\s*:\\s*\"([^\"]+)\"").matcher(block);
        if (!idM.find() || !typeM.find()) return null;
        return new SkinRef(idM.group(1), typeM.group(1));
    }

    private String resolveSkinFromRef(Path skinsFolder, SkinRef ref) {
        Path skinFile = switch (ref.type()) {
            case "PLAYER" -> skinsFolder.resolve(ref.identifier() + ".playerskin");
            case "URL" -> {
                String hash = sha256Hex(ref.identifier());
                Path classic = skinsFolder.resolve(hash + "_CLASSIC.urlskin");
                yield Files.exists(classic) ? classic : skinsFolder.resolve(hash + "_SLIM.urlskin");
            }
            case "CUSTOM" -> skinsFolder.resolve(ref.identifier() + ".customskin");
            default -> null;
        };

        if (skinFile == null || !Files.exists(skinFile)) return null;

        try {
            String content = Files.readString(skinFile, StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\"value\"\\s*:\\s*\"([A-Za-z0-9+/=]+)\"").matcher(content);
            if (!m.find()) return null;

            String skinUrl = extractSkinUrl(m.group(1));
            if (skinUrl == null) return null;
            return downloadAndProcess(skinUrl);
        } catch (Exception e) {
            logger.warning("Failed to resolve SR skin (" + ref.type() + ":" + ref.identifier() + "): " + e.getMessage());
            return null;
        }
    }

    // ── Shared skin processing ───────────────────────────────────────────

    private String extractSkinUrl(String base64Textures) {
        String json = new String(Base64.getDecoder().decode(base64Textures), StandardCharsets.UTF_8);
        int skinIdx = json.indexOf("\"SKIN\"");
        if (skinIdx == -1) return null;
        int urlIdx = json.indexOf("\"url\":\"", skinIdx);
        if (urlIdx == -1) return null;
        int urlStart = urlIdx + 7;
        int urlEnd = json.indexOf("\"", urlStart);
        if (urlEnd == -1) return null;
        return json.substring(urlStart, urlEnd);
    }

    private String downloadAndProcess(String skinUrl) throws Exception {
        HttpResponse<byte[]> resp = http.send(
                HttpRequest.newBuilder().uri(URI.create(skinUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
        );
        if (resp.statusCode() != 200) return null;

        BufferedImage skin = ImageIO.read(new ByteArrayInputStream(resp.body()));
        if (skin == null) return null;

        // Crop face: base layer (8,8,8,8) + overlay (40,8,8,8)
        BufferedImage face = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = face.createGraphics();
        g.drawImage(skin.getSubimage(8, 8, 8, 8), 0, 0, null);
        g.drawImage(skin.getSubimage(40, 8, 8, 8), 0, 0, null);
        g.dispose();

        // Scale 8x8 -> 64x64 nearest neighbor
        BufferedImage scaled = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = scaled.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2.drawImage(face, 0, 0, 64, 64, null);
        g2.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(scaled, "png", baos);
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }
}