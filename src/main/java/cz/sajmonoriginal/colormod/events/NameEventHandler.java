/*
 * Server-side colour application for the player's nameplate (above-head) and tab-list
 * entry, using NeoForge's two name-format events with modern {@link Component} RGB
 * styling - no scoreboard teams, no legacy {@code §x§...} escape codes.
 *
 * <p>Why the rewrite?
 * The previous scoreboard-team-with-legacy-escape approach worked for vanilla font
 * rendering but leaked the raw {@code §x§F§F§5§5§5§5} text into Discord-bridge mods
 * (sdlink, etc.) that consume the chat / join / leave components as plain strings.
 * Players showed up in Discord as {@code §xSajmonOriginal joined the server}.
 *
 * <p>Modern {@code Style.withColor(int rgb)} attaches an RGB colour to a Component
 * via its proper style field. The vanilla client renders it correctly above-head and
 * in the tab list. Discord bridges that serialise Component → string drop the style
 * (Discord doesn't render per-character RGB anyway) and emit clean text. No leak.
 *
 * <p>Two events:
 * <ul>
 *   <li>{@link PlayerEvent.NameFormat} - fires inside
 *       {@link Player#getDisplayName()} which the client uses for the
 *       above-head nameplate. We replace with a coloured component built from the
 *       profile name.</li>
 *   <li>{@link PlayerEvent.TabListNameFormat} - fires inside
 *       {@link ServerPlayer#getTabListDisplayName()} for the tab-list entry. Same
 *       treatment.</li>
 * </ul>
 *
 * <p>Both events cache their result on the Player object - invalidate via
 * {@link Player#refreshDisplayName()} / {@link ServerPlayer#refreshTabListName()}
 * whenever the colour changes. Those calls are made by {@link cz.sajmonoriginal.colormod.ColorCommand}
 * and {@link cz.sajmonoriginal.colormod.discord.DiscordBot} after the colour write.
 */
package cz.sajmonoriginal.colormod.events;

import cz.sajmonoriginal.colormod.Color;
import cz.sajmonoriginal.colormod.ColorMod;
import cz.sajmonoriginal.colormod.ColorService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Registered manually via {@code NeoForge.EVENT_BUS.register(NameEventHandler.class)}
 * in {@link ColorMod#ColorMod} - the {@code @EventBusSubscriber} annotation has been
 * known to silently fail to auto-discover handlers under some NeoForge / mod-loader
 * configurations, and the symptom (handlers never firing, names stay white) is
 * exactly what we hit on Sajmon's stack. Manual registration removes that variable.
 */
public final class NameEventHandler {
    private NameEventHandler() {}

    @SubscribeEvent
    public static void onNameFormat(PlayerEvent.NameFormat event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Color colour = ColorService.currentColor(player.getUUID());
        ColorMod.LOG.debug("[colormod/names] NameFormat fired for {} → colour={}",
                player.getGameProfile().getName(),
                colour == null ? "null" : colour.name());
        if (colour == null) return;
        String name = player.getGameProfile().getName();
        Component coloured = Component.literal(name).withStyle(s -> s.withColor(colour.hex()));
        event.setDisplayname(coloured);
    }

    @SubscribeEvent
    public static void onTabListNameFormat(PlayerEvent.TabListNameFormat event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Color colour = ColorService.currentColor(player.getUUID());
        ColorMod.LOG.debug("[colormod/names] TabListNameFormat fired for {} → colour={}",
                player.getGameProfile().getName(),
                colour == null ? "null" : colour.name());
        if (colour == null) return;
        String name = player.getGameProfile().getName();
        Component coloured = Component.literal(name).withStyle(s -> s.withColor(colour.hex()));
        event.setDisplayName(coloured);
    }

    /**
     * Invalidate the NeoForge-cached display + tab name on a player so the next
     * read re-fires the events. Call this after a colour write.
     */
    public static void refresh(ServerPlayer player) {
        try {
            player.refreshDisplayName();
            player.refreshTabListName();
        } catch (Throwable t) {
            ColorMod.LOG.debug("[colormod/names] refresh failed for {}: {}",
                    player.getGameProfile().getName(), t.toString());
        }
    }
}
