package com.beehiveprv;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.advancement.Advancement;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AllDataSyncCommand implements CommandExecutor {

    private record SkinRef(String identifier, String type) {}

    private record OnlineExtras(int advancements, boolean sponsor, boolean moderator, boolean admin) {}

    /** Data collected on the main thread: online player extras + path to advancements folder. */
    private record MainThreadData(Map<UUID, OnlineExtras> extras, Path advFolder) {}

    private static final Statistic[] DISTANCE_STATS = {
            Statistic.WALK_ONE_CM, Statistic.SPRINT_ONE_CM, Statistic.SWIM_ONE_CM,
            Statistic.FLY_ONE_CM, Statistic.CLIMB_ONE_CM, Statistic.BOAT_ONE_CM,
            Statistic.MINECART_ONE_CM, Statistic.HORSE_ONE_CM, Statistic.PIG_ONE_CM,
            Statistic.STRIDER_ONE_CM, Statistic.AVIATE_ONE_CM
    };

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final BeehiveSync plugin;
    private final String url;
    private final String token;
    private final Logger logger;
    private final HttpClient http = HttpClient.newHttpClient();

    AllDataSyncCommand(BeehiveSync plugin, String url, String token) {
        this.plugin = plugin;
        this.url = url;
        this.token = token;
        this.logger = plugin.getLogger();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("alldata")) {
            sender.sendMessage("§eUsage: /sync alldata [file]");
            return true;
        }
        if (sender instanceof Player p && !p.isOp()) {
            sender.sendMessage("§cYou must be OP to use this.");
            return true;
        }

        boolean toFile = args.length >= 2 && args[1].equalsIgnoreCase("file");

        if (!toFile && token.isBlank()) {
            sender.sendMessage("§cToken not set in config.yml — can't send to REST. Use §e/sync alldata file§c to dump to disk.");
            return true;
        }

        sender.sendMessage(toFile
                ? "§aBuilding full data dump to file..."
                : "§aStarting full sync from SkinsRestorer data...");

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                runFullSync(sender, toFile);
            } catch (Exception e) {
                logger.severe("AllData sync error: " + e.getMessage());
                sender.sendMessage("§cSync error: " + e.getMessage());
            }
        });
        return true;
    }

    private void runFullSync(CommandSender sender, boolean toFile) throws Exception {
        Path srFolder = plugin.getDataFolder().toPath().resolveSibling("SkinsRestorer");
        Path playersFolder = srFolder.resolve("players");
        Path skinsFolder = srFolder.resolve("skins");

        if (!Files.isDirectory(playersFolder)) {
            sender.sendMessage("§cSkinsRestorer players folder not found: " + playersFolder);
            return;
        }

        // Step 1: Read .player files → skin heads
        List<Path> playerFiles;
        try (var stream = Files.list(playersFolder)) {
            playerFiles = stream
                    .filter(p -> p.getFileName().toString().endsWith(".player"))
                    .toList();
        }

        sender.sendMessage("§7Found " + playerFiles.size() + " SR player files. Downloading skins...");

        Map<UUID, String> skinHeads = new HashMap<>();
        int loaded = 0;
        for (Path pf : playerFiles) {
            String fname = pf.getFileName().toString();
            String uuidStr = fname.substring(0, fname.length() - ".player".length());
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidStr);
            } catch (IllegalArgumentException e) {
                continue;
            }

            String content = Files.readString(pf, StandardCharsets.UTF_8);
            SkinRef ref = parseSkinRef(content);
            if (ref == null) continue;

            String head = resolveSkinHead(skinsFolder, ref);
            if (head != null) {
                skinHeads.put(uuid, head);
                loaded++;
            }
        }

        sender.sendMessage("§7Loaded " + loaded + " skin heads. Collecting player stats...");

        // Step 2: Collect online-player extras + advancement folder path on main thread
        CompletableFuture<MainThreadData> future = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                future.complete(collectMainThreadData());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        MainThreadData mtd = future.get(30, TimeUnit.SECONDS);

        // Step 3: For offline players — read advancement counts from world/advancements/{uuid}.json
        Map<UUID, OnlineExtras> extras = mtd.extras();
        Path advFolder = mtd.advFolder();

        OfflinePlayer[] allPlayers = Bukkit.getOfflinePlayers();

        if (Files.isDirectory(advFolder)) {
            for (OfflinePlayer op : allPlayers) {
                UUID uuid = op.getUniqueId();
                if (!extras.containsKey(uuid)) {
                    // Offline: read playtime stats via getStatistic (works fine),
                    // but advancements must come from the save file on disk.
                    int adv = countAdvancementsFromDisk(advFolder, uuid);
                    extras.put(uuid, new OnlineExtras(adv, false, false, false));
                }
            }
        }

        // Step 4: Build JSON
        List<String> jsons = new ArrayList<>(allPlayers.length);
        for (OfflinePlayer op : allPlayers) {
            if (op.getName() == null) continue;
            String json = buildPlayerJson(op, skinHeads.get(op.getUniqueId()), extras.get(op.getUniqueId()));
            if (json != null) jsons.add(json);
        }

        skinHeads.clear();

        String fullPayload = "{\"players\":[" + String.join(",", jsons) + "]}";

        if (toFile) {
            plugin.getDataFolder().mkdirs();
            String filename = LocalDateTime.now().format(FILE_TS) + ".json";
            Path out = plugin.getDataFolder().toPath().resolve(filename);
            Files.writeString(out, fullPayload, StandardCharsets.UTF_8);
            sender.sendMessage("§aDumped " + jsons.size() + " players → " + out);
        } else {
            sender.sendMessage("§aSending " + jsons.size() + " players in batches of 10...");
            int sent = 0;
            for (int i = 0; i < jsons.size(); i += 10) {
                List<String> batch = jsons.subList(i, Math.min(i + 10, jsons.size()));
                if (sendBatch(batch)) sent += batch.size();
                if (i + 10 < jsons.size()) Thread.sleep(500);
            }
            sender.sendMessage("§aSync complete! Synced " + sent + "/" + jsons.size() + " players.");
        }
    }

    // ── Main thread data collection ──────────────────────────────────────────

    private MainThreadData collectMainThreadData() {
        // Advancement folder: <main-world>/advancements/
        Path advFolder = Bukkit.getWorlds().get(0).getWorldFolder().toPath().resolve("advancements");

        // Build advancement list once (skip recipes) for online players
        List<Advancement> advList = new ArrayList<>();
        Bukkit.advancementIterator().forEachRemaining(a -> {
            if (!a.getKey().getKey().startsWith("recipes/")) advList.add(a);
        });

        Map<UUID, OnlineExtras> map = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            int adv = 0;
            for (Advancement a : advList) {
                try {
                    if (p.getAdvancementProgress(a).isDone()) adv++;
                } catch (Exception ignored) {}
            }
            map.put(p.getUniqueId(), new OnlineExtras(
                    adv,
                    p.hasPermission("group.sponsor"),
                    p.hasPermission("group.moderator"),
                    p.hasPermission("group.admin") || p.hasPermission("group.owner")
            ));
        }
        return new MainThreadData(map, advFolder);
    }

    // ── Disk-based advancement counting for offline players ──────────────────

    /**
     * Counts completed non-recipe advancements by reading
     * world/advancements/{uuid}.json directly from disk.
     *
     * File format:
     * {
     *   "minecraft:story/root": {"criteria":{"...":"timestamp"},"done":true},
     *   "minecraft:recipes/tools/axe": {"criteria":{...},"done":true},
     *   "DataVersion": 3953
     * }
     */
    private int countAdvancementsFromDisk(Path advFolder, UUID uuid) {
        Path advFile = advFolder.resolve(uuid + ".json");
        if (!Files.exists(advFile)) return 0;
        try {
            String content = Files.readString(advFile, StandardCharsets.UTF_8);
            int count = 0;
            Matcher m = Pattern.compile("\"done\"\\s*:\\s*true").matcher(content);
            while (m.find()) {
                // Find the opening brace of the object that directly contains this "done":true
                int brace = findEnclosingOpenBrace(content, m.start());
                if (brace < 0) continue;

                // Extract the key that precedes this opening brace: "namespace:path": {
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

    /**
     * Returns the position of the { that directly encloses the character at pos.
     * Walks backwards tracking brace depth; returns when depth drops below 0.
     */
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

    // ── Skin resolution ───────────────────────────────────────────────────────

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

    private String resolveSkinHead(Path skinsFolder, SkinRef ref) {
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
            String value = m.group(1);

            String json = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
            int skinIdx = json.indexOf("\"SKIN\"");
            if (skinIdx == -1) return null;
            int urlIdx = json.indexOf("\"url\":\"", skinIdx);
            if (urlIdx == -1) return null;
            int urlStart = urlIdx + 7;
            int urlEnd = json.indexOf("\"", urlStart);
            if (urlEnd == -1) return null;
            String skinUrl = json.substring(urlStart, urlEnd);

            HttpResponse<byte[]> resp = http.send(
                    HttpRequest.newBuilder().uri(URI.create(skinUrl)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray()
            );
            if (resp.statusCode() != 200) return null;

            BufferedImage skin = ImageIO.read(new ByteArrayInputStream(resp.body()));
            if (skin == null) return null;

            BufferedImage face = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = face.createGraphics();
            g.drawImage(skin.getSubimage(8, 8, 8, 8), 0, 0, null);
            g.drawImage(skin.getSubimage(40, 8, 8, 8), 0, 0, null);
            g.dispose();

            BufferedImage scaled = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = scaled.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2.drawImage(face, 0, 0, 64, 64, null);
            g2.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(scaled, "png", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());

        } catch (Exception e) {
            logger.warning("Failed to resolve skin (" + ref.type() + ":" + ref.identifier() + "): " + e.getMessage());
            return null;
        }
    }

    // ── JSON building ─────────────────────────────────────────────────────────

    private String buildPlayerJson(OfflinePlayer op, String skin, OnlineExtras ex) {
        try {
            long ticks = 0;
            int kills = 0, deaths = 0;
            long dist = 0;

            try { ticks = op.getStatistic(Statistic.PLAY_ONE_MINUTE); } catch (Exception ignored) {}
            try { kills = op.getStatistic(Statistic.PLAYER_KILLS); } catch (Exception ignored) {}
            try { deaths = op.getStatistic(Statistic.DEATHS); } catch (Exception ignored) {}
            for (Statistic s : DISTANCE_STATS) {
                try { dist += op.getStatistic(s); } catch (Exception ignored) {}
            }

            StringBuilder sb = new StringBuilder("{");
            sb.append("\"uuid\":\"").append(op.getUniqueId()).append("\",");
            sb.append("\"name\":\"").append(escapeJson(op.getName())).append("\",");
            if (skin != null) sb.append("\"skin_head\":\"").append(skin).append("\",");
            sb.append("\"playtime_minutes\":").append(ticks / 20L / 60L).append(",");
            sb.append("\"achievements\":").append(ex != null ? ex.advancements() : 0).append(",");
            sb.append("\"is_sponsor\":").append(ex != null && ex.sponsor()).append(",");
            sb.append("\"is_banned\":").append(op.isBanned()).append(",");
            sb.append("\"is_moderator\":").append(ex != null && ex.moderator()).append(",");
            sb.append("\"is_admin\":").append(ex != null && ex.admin()).append(",");
            sb.append("\"kills\":").append(kills).append(",");
            sb.append("\"deaths\":").append(deaths).append(",");
            sb.append("\"distance_km\":").append(String.format(Locale.US, "%.2f", dist / 100_000.0));
            sb.append("}");
            return sb.toString();
        } catch (Exception e) {
            logger.warning("Failed to build JSON for " + op.getName() + ": " + e.getMessage());
            return null;
        }
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private boolean sendBatch(List<String> playerJsons) {
        try {
            String body = "{\"players\":[" + String.join(",", playerJsons) + "]}";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                logger.info("Batch ok (" + playerJsons.size() + " players): " + resp.body());
                return true;
            } else {
                logger.warning("Batch failed [" + resp.statusCode() + "]: " + resp.body());
                return false;
            }
        } catch (Exception e) {
            logger.warning("HTTP error: " + e.getMessage());
            return false;
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
