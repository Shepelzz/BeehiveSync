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
import java.util.Set;
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
    private final boolean fetchMojangSkin;
    private final ConcurrentHashMap<UUID, String> cache = new ConcurrentHashMap<>();
    /** UUIDs where the Mojang API returned an error (not "no skin" — actual failure). */
    private final Set<UUID> mojangFailed = ConcurrentHashMap.newKeySet();
    private final HttpClient http = HttpClient.newHttpClient();

    public SkinService(Logger logger, Path pluginDataFolder, boolean fetchMojangSkin) {
        this.logger = logger;
        this.skinsRestorerFolder = pluginDataFolder.resolveSibling("SkinsRestorer");
        this.fetchMojangSkin = fetchMojangSkin;
    }

    /**
     * Build skin head from an online player's profile textures and cache it.
     * Call from async thread (downloads the skin PNG over HTTP).
     */
    public String buildAndCache(Player player) {
        // 1. Vanilla profile textures (fastest for online player)
        String head = buildFromProfile(player);
        if (head != null) {
            cache.put(player.getUniqueId(), head);
            return head;
        }
        // 2. SkinsRestorer files → Mojang API fallback (via getSkin)
        return getSkin(player.getUniqueId());
    }

    /**
     * Get skin for any player:
     * 1. In-memory cache
     * 2. SkinsRestorer disk files
     * 3. Mojang Session Server API (fallback, returns default skin if player has none)
     *
     * Call from async thread.
     */
    public String getSkin(UUID uuid) {
        String cached = cache.get(uuid);
        if (cached != null) return cached;

        String fromSR = buildFromSkinsRestorer(uuid);
        if (fromSR != null) {
            cache.put(uuid, fromSR);
            return fromSR;
        }

        logger.warning("[Skin] No SkinsRestorer skin for " + uuid + ".");

        if (!fetchMojangSkin) {
            logger.warning("[Skin] fetch-mojang-skin is disabled — skin_head will be omitted for " + uuid + ".");
            return null;
        }

        logger.info("[Skin] Trying Mojang API for " + uuid + "...");

        String fromMojang = fetchFromMojang(uuid);
        if (fromMojang != null) {
            cache.put(uuid, fromMojang);
        }
        return fromMojang;
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
        Path skinsFolder   = skinsRestorerFolder.resolve("skins");
        Path playerFile    = playersFolder.resolve(uuid + ".player");

        if (!Files.exists(playerFile)) {
            logger.warning("[Skin/SR] Player file not found: " + playerFile);
            return null;
        }
        try {
            String content = Files.readString(playerFile, StandardCharsets.UTF_8);
            SkinRef ref = parseSkinRef(content);
            if (ref == null) {
                logger.warning("[Skin/SR] Could not parse skinIdentifier from " + playerFile);
                return null;
            }
            logger.info("[Skin/SR] Resolved ref for " + uuid + " → type=" + ref.type() + " id=" + ref.identifier());
            return resolveSkinFromRef(skinsFolder, ref, uuid);
        } catch (Exception e) {
            logger.warning("[Skin/SR] Error reading player file for " + uuid + ": " + e.getMessage());
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

    private String resolveSkinFromRef(Path skinsFolder, SkinRef ref, UUID uuid) {
        Path skinFile = switch (ref.type()) {
            case "PLAYER" -> skinsFolder.resolve(ref.identifier() + ".playerskin");
            case "URL" -> {
                String hash = sha256Hex(ref.identifier());
                Path classic = skinsFolder.resolve(hash + "_CLASSIC.urlskin");
                yield Files.exists(classic) ? classic : skinsFolder.resolve(hash + "_SLIM.urlskin");
            }
            case "CUSTOM" -> skinsFolder.resolve(ref.identifier() + ".customskin");
            default -> {
                logger.warning("[Skin/SR] Unknown skin type '" + ref.type() + "' for " + uuid);
                yield null;
            }
        };

        if (skinFile == null) return null;

        if (!Files.exists(skinFile)) {
            logger.warning("[Skin/SR] Skin file not found: " + skinFile);
            return null;
        }

        logger.info("[Skin/SR] Reading skin file: " + skinFile);
        try {
            String content = Files.readString(skinFile, StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\"value\"\\s*:\\s*\"([A-Za-z0-9+/=]+)\"").matcher(content);
            if (!m.find()) {
                logger.warning("[Skin/SR] No 'value' field in " + skinFile);
                return null;
            }

            String skinUrl = extractSkinUrl(m.group(1));
            if (skinUrl == null) {
                logger.warning("[Skin/SR] Could not extract SKIN url from value in " + skinFile);
                return null;
            }
            return downloadAndProcess(skinUrl);
        } catch (Exception e) {
            logger.warning("[Skin/SR] Error reading skin file " + skinFile + ": " + e.getMessage());
            return null;
        }
    }

    // ── Mojang API fallback ──────────────────────────────────────────────

    /** Whether the last Mojang API call for this UUID resulted in an error (not "no skin"). */
    public boolean isMojangFailed(UUID uuid) {
        return mojangFailed.contains(uuid);
    }

    /**
     * Fetch skin via Mojang Session Server.
     * Returns the processed head Base64, or {@code null} if:
     * <ul>
     *   <li>API error / rate-limit → sets {@link #isMojangFailed} flag, logs WARNING</li>
     *   <li>Player has no custom skin (legitimate) → flag NOT set</li>
     * </ul>
     * Endpoint: GET https://sessionserver.mojang.com/session/minecraft/profile/{uuid-no-dashes}
     */
    public String fetchFromMojang(UUID uuid) {
        try {
            String uuidNoDashes = uuid.toString().replace("-", "");
            String url = "https://sessionserver.mojang.com/session/minecraft/profile/" + uuidNoDashes;

            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            int status = resp.statusCode();
            if (status == 204 || status == 404) {
                // Player not found in Mojang DB — expected for offline/cracked accounts
                logger.info("[Skin] Mojang has no profile for " + uuid
                        + " (HTTP " + status + ") — offline account, no skin.");
                return null;
            }
            if (status != 200) {
                // Actual error (429 rate-limit, 5xx server error, etc.) — worth retrying
                mojangFailed.add(uuid);
                logger.warning("[Skin] Mojang API returned HTTP " + status
                        + " for " + uuid + " — queued for retry.");
                return null;
            }

            // API responded OK — clear any previous failure flag
            mojangFailed.remove(uuid);

            // Extract base64 textures value from the "textures" property
            // Response: {"properties":[{"name":"textures","value":"<base64>"}]}
            Matcher m = Pattern.compile(
                    "\"name\"\\s*:\\s*\"textures\"[^}]*?\"value\"\\s*:\\s*\"([A-Za-z0-9+/=]+)\""
            ).matcher(resp.body());
            if (!m.find()) return null; // no custom skin — legitimate, no retry needed

            String skinUrl = extractSkinUrl(m.group(1));
            if (skinUrl == null) return null;
            return downloadAndProcess(skinUrl);
        } catch (Exception e) {
            mojangFailed.add(uuid);
            logger.warning("[Skin] Mojang request failed for " + uuid
                    + ": " + e.getMessage() + " — queued for retry.");
            return null;
        }
    }

    // ── Shared skin processing ───────────────────────────────────────────

    private String extractSkinUrl(String base64Textures) {
        String json = new String(Base64.getDecoder().decode(base64Textures), StandardCharsets.UTF_8);
        int skinIdx = json.indexOf("\"SKIN\"");
        if (skinIdx == -1) return null;
        Matcher m = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        m.region(skinIdx, json.length());
        return m.find() ? m.group(1) : null;
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