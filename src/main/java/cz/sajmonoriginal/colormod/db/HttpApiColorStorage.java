package cz.sajmonoriginal.colormod.db;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cz.sajmonoriginal.colormod.ColorMod;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * REST backend that talks to the server's color HTTP API.
 *
 * <p>Endpoints the mod hits:
 * <ul>
 *   <li>POST /internal/colors/set    body { actor, colorName }</li>
 *   <li>POST /internal/colors/clear  body { actor }</li>
 *   <li>GET  /internal/colors/by-mc-uuid/:uuid   returns { colorName, entry } | null</li>
 *   <li>GET  /internal/colors/changes-since?cursor=N&amp;limit=500  polled every poll_interval_seconds</li>
 * </ul>
 *
 * <p>Actor identifier shape used for writes: {@code { type: "mc-uuid", value: <uuid> }}.
 *
 * <p>Read model: in-memory cache keyed by Minecraft UUID. Entries are
 * populated by {@link #preload(UUID)} (called on player login) and refreshed
 * when the changes-since poll reports non-empty changes. {@link #getColorNameCached(UUID)}
 * is a non-blocking cache read, safe to call from the server tick.
 *
 * <p>The change-log rows are keyed on the server's internal user id, not the
 * mc-uuid the mod cares about, so we don't try to drive per-player updates
 * from them. On any non-empty poll batch we walk every cached uuid and
 * refetch by-mc-uuid; that's the cheap way to resolve who changed.
 */
public final class HttpApiColorStorage implements ColorStorage {

    /** Cache sentinel for "we know this uuid has no color". Distinguishes from "uncached". */
    private static final String NO_COLOR = "";

    private final HttpClient http;
    private final Gson gson = new Gson();
    private final String baseUrl;
    private final String internalKey;
    private final ScheduledExecutorService poller;
    private final ScheduledFuture<?> pollHandle;
    private final BiConsumer<UUID, String> onRemoteChange;

    private final Map<UUID, String> cache = new ConcurrentHashMap<>();
    private volatile long lastCursor = 0L;

    private HttpApiColorStorage(String baseUrl,
                                String internalKey,
                                long pollIntervalSeconds,
                                BiConsumer<UUID, String> onRemoteChange) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.internalKey = internalKey;
        this.onRemoteChange = onRemoteChange;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "colormod-http-poller");
            t.setDaemon(true);
            return t;
        });
        long period = Math.max(1L, pollIntervalSeconds);
        this.pollHandle = poller.scheduleAtFixedRate(this::pollChanges, period, period, TimeUnit.SECONDS);
    }

    public static HttpApiColorStorage open(String baseUrl,
                                           String internalKey,
                                           long pollIntervalSeconds,
                                           BiConsumer<UUID, String> onRemoteChange) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("storage.base_url must be set");
        }
        if (internalKey == null || internalKey.isBlank()) {
            throw new IllegalArgumentException("storage.internal_key must be set");
        }
        return new HttpApiColorStorage(baseUrl, internalKey, pollIntervalSeconds, onRemoteChange);
    }

    @Override
    public Optional<String> getColorNameCached(UUID uuid) {
        String v = cache.get(uuid);
        if (v == null || v.equals(NO_COLOR)) return Optional.empty();
        return Optional.of(v);
    }

    @Override
    public void preload(UUID uuid) {
        poller.submit(() -> {
            try {
                String fetched = fetchByMcUuid(uuid);
                String prev = cache.put(uuid, fetched == null ? NO_COLOR : fetched);
                String prevName = (prev == null || prev.equals(NO_COLOR)) ? null : prev;
                if (!Objects.equals(prevName, fetched) && onRemoteChange != null) {
                    onRemoteChange.accept(uuid, fetched);
                }
            } catch (Exception e) {
                ColorMod.LOG.warn("[colormod/http] preload({}) failed: {}", uuid, e.toString());
            }
        });
    }

    @Override
    public void setColor(UUID uuid, String colorName) {
        JsonObject payload = new JsonObject();
        payload.add("actor", mcUuidActor(uuid));
        payload.addProperty("colorName", colorName);
        try {
            HttpRequest req = request("/internal/colors/set")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
                    .build();
            send(req);
            cache.put(uuid, colorName);
        } catch (Exception e) {
            ColorMod.LOG.error("[colormod/http] setColor({}, {}) failed", uuid, colorName, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void clearColor(UUID uuid) {
        JsonObject payload = new JsonObject();
        payload.add("actor", mcUuidActor(uuid));
        try {
            HttpRequest req = request("/internal/colors/clear")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
                    .build();
            send(req);
            cache.put(uuid, NO_COLOR);
        } catch (Exception e) {
            ColorMod.LOG.error("[colormod/http] clearColor({}) failed", uuid, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        pollHandle.cancel(false);
        poller.shutdownNow();
    }

    // ─────────────────────────────────────────────── internals

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + internalKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15));
    }

    private JsonObject send(HttpRequest req) throws Exception {
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new RuntimeException("HTTP " + res.statusCode() + ": " + res.body());
        }
        if (res.body() == null || res.body().isEmpty()) return new JsonObject();
        return JsonParser.parseString(res.body()).getAsJsonObject();
    }

    private JsonObject mcUuidActor(UUID uuid) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "mc-uuid");
        o.addProperty("value", uuid.toString());
        return o;
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /**
     * Fetch one player's color from the server. Returns null when the server
     * has no color set for them (response body null) or they aren't whitelisted.
     */
    private String fetchByMcUuid(UUID uuid) throws Exception {
        HttpRequest req = request("/internal/colors/by-mc-uuid/" + urlEncode(uuid.toString())).GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 404) return null;
        if (res.statusCode() / 100 != 2) {
            throw new RuntimeException("HTTP " + res.statusCode() + " by-mc-uuid: " + res.body());
        }
        String body = res.body();
        if (body == null || body.isEmpty()) return null;
        JsonElement el = JsonParser.parseString(body);
        if (el.isJsonNull()) return null;
        JsonObject obj = el.getAsJsonObject();
        JsonElement nameEl = obj.get("colorName");
        if (nameEl == null || nameEl.isJsonNull()) return null;
        return nameEl.getAsString();
    }

    /**
     * Poll the change log. On any non-empty batch, refresh every cached uuid
     * from by-mc-uuid and emit the change callback for diffs. The change rows
     * carry no mc-uuid so we can't target updates from them directly.
     */
    private void pollChanges() {
        try {
            HttpRequest req = request("/internal/colors/changes-since?cursor=" + lastCursor + "&limit=500").GET().build();
            JsonObject body = send(req);
            JsonArray changes = body.has("changes") && body.get("changes").isJsonArray()
                    ? body.getAsJsonArray("changes")
                    : null;
            if (changes == null || changes.isEmpty()) return;
            for (JsonElement el : changes) {
                JsonObject ch = el.getAsJsonObject();
                if (ch.has("id") && !ch.get("id").isJsonNull()) {
                    long id = ch.get("id").getAsLong();
                    if (id > lastCursor) lastCursor = id;
                }
            }
            Set<UUID> uuids = new HashSet<>(cache.keySet());
            for (UUID uuid : uuids) {
                try {
                    String fresh = fetchByMcUuid(uuid);
                    String prev = cache.put(uuid, fresh == null ? NO_COLOR : fresh);
                    String prevName = (prev == null || prev.equals(NO_COLOR)) ? null : prev;
                    if (!Objects.equals(prevName, fresh) && onRemoteChange != null) {
                        onRemoteChange.accept(uuid, fresh);
                    }
                } catch (Exception e) {
                    ColorMod.LOG.warn("[colormod/http] poll refresh for {} failed: {}", uuid, e.toString());
                }
            }
        } catch (Exception e) {
            ColorMod.LOG.warn("[colormod/http] poll failed: {}", e.toString());
        }
    }
}
