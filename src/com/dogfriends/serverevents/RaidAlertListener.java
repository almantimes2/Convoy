package com.dogfriends.serverevents;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.block.BlockExplodeEvent;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RaidAlertListener implements Listener {

    private final ServerEventsPlugin plugin;
    private final File dbFile;
    private final String webhookUrl;
    private final long cooldownMillis;
    private final boolean enabled;
    private final ConcurrentHashMap<String, Long> lastAlertByProtection = new ConcurrentHashMap<>();
    // Rate-limit por chunk ANTES de encolar la tarea async: durante un raid sostenido con
    // muchas explosiones en la misma zona, sin esto se abriria una conexion SQLite nueva
    // por cada explosion aunque el resultado vaya a ser el mismo.
    private static final long CHUNK_CHECK_TTL_MILLIS = 30_000L;
    private final ConcurrentHashMap<String, Long> recentChunkChecks = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private boolean warnedMissingDb = false;

    public RaidAlertListener(ServerEventsPlugin plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("raid-alerts.enabled", true);
        this.webhookUrl = plugin.getConfig().getString("raid-alerts.discord-webhook-url", "");
        this.cooldownMillis = plugin.getConfig().getLong("raid-alerts.cooldown-minutes", 5) * 60_000L;
        this.dbFile = new File(plugin.getDataFolder().getParentFile(), "BaseRaiders/Database/protections.db");
    }

    public boolean isConfigured() {
        return enabled && webhookUrl != null && !webhookUrl.isBlank();
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        handleExplosion(event.blockList(), event.getLocation());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        handleExplosion(event.blockList(), event.getBlock().getLocation());
    }

    private void handleExplosion(List<Block> blocks, Location fallback) {
        if (!isConfigured()) {
            return;
        }
        Set<String> seenChunks = new HashSet<>();
        String world = fallback.getWorld() != null ? fallback.getWorld().getName() : "world";

        if (blocks.isEmpty()) {
            queueChunkCheck(world, fallback.getBlockX() >> 4, fallback.getBlockZ() >> 4);
            return;
        }

        for (Block block : blocks) {
            int cx = block.getX() >> 4;
            int cz = block.getZ() >> 4;
            String key = world + ":" + cx + ":" + cz;
            if (seenChunks.add(key)) {
                queueChunkCheck(world, cx, cz);
            }
        }
    }

    private void queueChunkCheck(String world, int chunkX, int chunkZ) {
        long now = System.currentTimeMillis();
        String key = world + ":" + chunkX + ":" + chunkZ;
        Long lastCheck = recentChunkChecks.get(key);
        if (lastCheck != null && now - lastCheck < CHUNK_CHECK_TTL_MILLIS) {
            return;
        }
        recentChunkChecks.put(key, now);
        if (recentChunkChecks.size() > 500) {
            recentChunkChecks.entrySet().removeIf(e -> now - e.getValue() >= CHUNK_CHECK_TTL_MILLIS);
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> checkAndAlert(world, chunkX, chunkZ));
    }

    private void checkAndAlert(String world, int chunkX, int chunkZ) {
        if (!dbFile.exists()) {
            if (!warnedMissingDb) {
                plugin.getLogger().warning("No se encontro la base de datos de BaseRaiders en " + dbFile.getPath()
                        + " - las alertas de raid estan desactivadas.");
                warnedMissingDb = true;
            }
            return;
        }

        String protectionId = null;
        String ownerName = null;
        String protectionName = null;
        int locX = 0, locY = 0, locZ = 0;

        String url = "jdbc:sqlite:file:" + dbFile.getAbsolutePath().replace("\\", "/") + "?mode=ro";
        try (Connection conn = DriverManager.getConnection(url)) {
            String sql = "SELECT id, owner_name, protection_name, location_x, location_y, location_z "
                    + "FROM protections WHERE world = ? AND chunk_x = ? AND chunk_z = ? LIMIT 1";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, world);
                ps.setInt(2, chunkX);
                ps.setInt(3, chunkZ);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        protectionId = rs.getString("id");
                        ownerName = rs.getString("owner_name");
                        protectionName = rs.getString("protection_name");
                        locX = rs.getInt("location_x");
                        locY = rs.getInt("location_y");
                        locZ = rs.getInt("location_z");
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error consultando la base de datos de BaseRaiders: " + e.getMessage());
            return;
        }

        if (protectionId == null) {
            return;
        }

        long now = System.currentTimeMillis();
        Long last = lastAlertByProtection.get(protectionId);
        if (last != null && (now - last) < cooldownMillis) {
            return;
        }
        lastAlertByProtection.put(protectionId, now);

        sendWebhook(ownerName, protectionName, world, locX, locY, locZ);
    }

    private void sendWebhook(String ownerName, String protectionName, String world, int x, int y, int z) {
        String safeOwner = ownerName == null ? "?" : ownerName.replace("\"", "'");
        String safeProt = protectionName == null || protectionName.isBlank() ? "(sin nombre)" : protectionName.replace("\"", "'");
        String content = String.format(
                ":rotating_light: **Raid en progreso!** La base de **%s** (\"%s\") esta siendo atacada. Ubicacion: %s (%d, %d, %d)",
                safeOwner, safeProt, world, x, y, z);
        String jsonBody = "{\"content\":\"" + content.replace("\"", "\\\"") + "\"}";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(ex -> {
                        plugin.getLogger().warning("Fallo al enviar la alerta de raid a Discord: " + ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            plugin.getLogger().warning("Fallo al construir la alerta de raid: " + e.getMessage());
        }
    }
}
