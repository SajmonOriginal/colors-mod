/*
 * Optional chat-message body recolouring.
 *
 * <p>Hooks {@link net.neoforged.neoforge.event.ServerChatEvent} and replaces
 * the outgoing chat message with a re-styled version: the body is tinted grey
 * (or whatever {@code chat.defaultBodyHex} is set to) for every player who is
 * NOT a member of the configured "elite" LuckPerms group (typically {@code owner}).
 *
 * <p>Default OFF - if the server already runs styled-chat or another mod that
 * formats chat, this duplicate would either fight that mod or produce double-
 * styled output. Turn on only when colormod owns the chat layer.
 *
 * <p>Decision matrix:
 * <ul>
 *   <li>{@code chat.recolourBody = false} → no listener, vanilla / styled-chat behaviour.</li>
 *   <li>{@code chat.recolourBody = true}, player IS in eliteLpGroup → message untouched.</li>
 *   <li>{@code chat.recolourBody = true}, player NOT in eliteLpGroup → body wrapped in
 *       {@code Component.literal(...).withColor(defaultBodyHex)}.</li>
 * </ul>
 */
package cz.sajmonoriginal.colormod.chat;

import cz.sajmonoriginal.colormod.ColorConfig;
import cz.sajmonoriginal.colormod.ColorMod;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedDataManager;
import net.luckperms.api.model.user.User;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.ServerChatEvent;

public final class ChatFormatter {
    private ChatFormatter() {}

    @SubscribeEvent
    public static void onChat(ServerChatEvent event) {
        if (!ColorConfig.COMMON.chatRecolourBody.get()) return;
        ServerPlayer player = event.getPlayer();
        if (player == null) return;

        if (isElite(player)) return; // owner / staff - leave their message alone

        // Replace the outgoing component body with a grey-tinted copy. We re-build
        // a literal component from the original message string so the tint applies
        // uniformly; styling embedded by the player (e.g. legacy &codes through
        // styled-chat) is intentionally dropped here - when this formatter is on,
        // styled-chat is presumably off.
        String raw = event.getRawText();
        Component coloured = Component.literal(raw)
                .withStyle(s -> s.withColor(ColorConfig.COMMON.chatDefaultBodyHex.get()));
        event.setMessage(coloured);
    }

    /**
     * Returns true iff the player is a member of the configured elite LuckPerms group
     * (transitively - inherited groups count). Defensive: any failure → false ("not elite"),
     * which means message gets greyed (safe default).
     */
    private static boolean isElite(ServerPlayer player) {
        String group = ColorConfig.COMMON.chatEliteLpGroup.get();
        if (group == null || group.isBlank()) return false;
        LuckPerms lp = ColorMod.luckPerms();
        if (lp == null) return false;
        try {
            User user = lp.getUserManager().getUser(player.getUUID());
            if (user == null) return false;
            CachedDataManager data = user.getCachedData();
            return data.getPermissionData().checkPermission("group." + group).asBoolean();
        } catch (Throwable t) {
            ColorMod.LOG.debug("[colormod/chat] elite check failed for {}: {}",
                    player.getGameProfile().getName(), t.toString());
            return false;
        }
    }
}
