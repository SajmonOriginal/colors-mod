package cz.sajmonoriginal.colormod.db;

import java.util.Optional;
import java.util.UUID;

/**
 * Color storage backend contract. The mod resolves an mc-uuid to a palette
 * entry name (e.g. "red") through this interface and writes back the player's
 * choice through the same.
 *
 * <p>Reads are cache-only and must not block (called from server-tick paths
 * like the NameFormat event). Writes are allowed to block; callers run them
 * off-thread via CompletableFuture.
 */
public interface ColorStorage extends AutoCloseable {

    /** Cache lookup. Returns empty if the uuid was never preloaded or has no color. */
    Optional<String> getColorNameCached(UUID uuid);

    /**
     * Schedule a fetch of the player's color from the backend, populate the
     * cache, and fire the change callback if the value differs from what the
     * cache previously held. Safe to call from any thread.
     */
    void preload(UUID uuid);

    /** Persist a color for the player. Synchronous, blocks on the network. */
    void setColor(UUID uuid, String colorName);

    /** Clear the player's color. Synchronous, blocks on the network. */
    void clearColor(UUID uuid);

    /** Stop any background pollers and release resources. */
    @Override
    void close();
}
