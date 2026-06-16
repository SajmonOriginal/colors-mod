/*
 * Color read / write through the HTTP backend (source of truth) with a
 * LuckPerms mirror for downstream chat formatters.
 *
 * <p>Reads ({@link #currentColor(UUID)}) come from the HTTP cache populated
 * by {@link cz.sajmonoriginal.colormod.db.HttpApiColorStorage}. They never
 * block; if a uuid hasn't been preloaded yet the call returns null and the
 * nameplate uses the default until preload completes.
 *
 * <p>Writes ({@link #setColor(UUID, Color)}) go to the HTTP backend first,
 * then mirror the result into LuckPerms as a {@code color_<name>} parent
 * group on the user. The LP mirror is for downstream consumers (styled-chat,
 * TabTPS); the mod itself reads from the HTTP cache only.
 *
 * <p>{@link #applyLocally(UUID, Color)} is the inverse: called from the HTTP
 * poll callback when a remote change is detected. It updates LP and refreshes
 * the in-game nameplate / tab / chat name so the player sees the change without
 * relogging.
 */
package cz.sajmonoriginal.colormod;

import cz.sajmonoriginal.colormod.db.ColorStorage;
import cz.sajmonoriginal.colormod.events.NameEventHandler;
import cz.sajmonoriginal.colormod.scoreboard.ColorTeams;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.InheritanceNode;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class ColorService {

    private ColorService() {}

    /** Cache-only read from the HTTP backend. Returns null if not yet preloaded. */
    public static Color currentColor(UUID uuid) {
        if (uuid == null) return null;
        ColorStorage s = ColorMod.storage();
        if (s == null) return null;
        Optional<String> name = s.getColorNameCached(uuid);
        return name.map(Color::byName).orElse(null);
    }

    /**
     * Write the player's color to the HTTP backend and mirror to LuckPerms.
     * Runs the HTTP call off-thread; LP mirror runs after the HTTP call
     * succeeds. The future completes after both writes finish (or fails fast
     * if the HTTP write fails).
     */
    public static CompletableFuture<Void> setColor(UUID uuid, Color target) {
        if (uuid == null) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {
            ColorStorage s = ColorMod.storage();
            if (s == null) {
                throw new IllegalStateException("HTTP storage not initialised; check colormod-server.toml");
            }
            if (target == null) s.clearColor(uuid);
            else                s.setColor(uuid, target.name());
            // LP mirror is best-effort; logs and swallows failures.
            applyToLuckPerms(uuid, target);
        });
    }

    /**
     * Apply a color the backend already accepted as truth. Used by the HTTP
     * poll loop when it spots a remote change (website, Discord picker, admin
     * tool). Writes to LP locally and refreshes the player's in-game
     * presentation on the server thread if they're online.
     */
    public static void applyLocally(UUID uuid, Color target) {
        applyToLuckPerms(uuid, target);
        MinecraftServer mcServer = ColorMod.server();
        if (mcServer == null) return;
        mcServer.execute(() -> {
            ServerPlayer player = mcServer.getPlayerList().getPlayer(uuid);
            if (player == null) return;
            ColorTeams.apply(player, target);
            NameEventHandler.refresh(player);
        });
    }

    // ─────────────────────────────────── LuckPerms mirror (downstream chat compat)

    private static void applyToLuckPerms(UUID uuid, Color target) {
        if (!ColorConfig.COMMON.luckPermsEnabled.get()) return;
        LuckPerms lp = ColorMod.luckPerms();
        if (lp == null) return;
        try {
            var user = lp.getUserManager().loadUser(uuid).join();
            String prefix = ColorConfig.COMMON.luckPermsGroupPrefix.get();
            new java.util.ArrayList<>(user.data().toCollection()).forEach(node -> {
                if (node instanceof InheritanceNode in && in.getGroupName().startsWith(prefix)) {
                    user.data().remove(node);
                }
            });
            if (target != null) {
                Node node = InheritanceNode.builder(target.luckPermsGroup()).build();
                user.data().add(node);
            }
            lp.getUserManager().saveUser(user).join();
        } catch (Throwable t) {
            ColorMod.LOG.warn("[colormod] LuckPerms mirror write failed for {}: {}", uuid, t.toString());
        }
    }
}
