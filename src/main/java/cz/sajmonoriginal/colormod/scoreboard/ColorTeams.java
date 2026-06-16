/*
 * Vanilla scoreboard-team manager - colours the player's above-head nameplate by
 * mapping each palette hex to its nearest legacy {@link ChatFormatting} colour
 * and applying that via {@link PlayerTeam#setColor(ChatFormatting)}. Empty
 * prefix + empty suffix + no {@code §x} legacy escapes anywhere → nothing
 * leaks into chat / Discord-bridge text consumers.
 *
 * <p>This is the same mechanism the TAB plugin uses for "RGB nametags" on
 * vanilla clients - see TAB's {@code TabTextColor.loadClosestColor()}. Vanilla
 * 1.21.1 has no third path: {@code Team#setColor} only accepts the 16-colour
 * {@link ChatFormatting} enum, the prefix/suffix can carry modern RGB style on
 * their own text but that style does not propagate to the bare player-name
 * sibling component, and the only client-side mechanism that DOES carry RGB
 * onto the trailing name is the {@code §x§R§R§G§G§B§B} legacy escape - which
 * leaks as plain text into anything that serialises the team-formatted name
 * (sdlink Discord bridge, server logs, …).
 *
 * <p>Trade-off accepted: above-head nameplate uses the closest 16-colour match
 * to the configured hex. Tab list ({@code TabListNameFormat} event) and chat
 * ({@code NameFormat} event + styled-chat) keep TRUE RGB.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@link #init(MinecraftServer)} - called from {@link cz.sajmonoriginal.colormod.ColorMod}
 *       on server start. Creates / refreshes a team per palette colour plus a
 *       {@code default} team. Idempotent.</li>
 *   <li>{@link #applyForLogin(ServerPlayer)} - login hook reads stored colour, slots
 *       the player into the matching team.</li>
 *   <li>{@link #apply(ServerPlayer, Color)} - explicit setter from /color and Discord.</li>
 * </ul>
 */
package cz.sajmonoriginal.colormod.scoreboard;

import cz.sajmonoriginal.colormod.Color;
import cz.sajmonoriginal.colormod.ColorConfig;
import cz.sajmonoriginal.colormod.ColorMod;
import cz.sajmonoriginal.colormod.ColorService;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;

public final class ColorTeams {
    private ColorTeams() {}

    /** Suffix used for the "no colour" default team. */
    public static final String DEFAULT_SUFFIX = "default";

    /**
     * Create / refresh every team. Idempotent: existing teams are looked up and reused;
     * the colour mapping is re-applied so a config reload (palette edit) propagates.
     */
    public static void init(MinecraftServer server) {
        if (!ColorConfig.COMMON.teamsEnabled.get()) return;
        Scoreboard sb = server.getScoreboard();

        // Default team - closest match to the configured grey for unflagged players.
        ensureTeam(sb, defaultTeamName(),
                nearestChatFormatting(ColorConfig.COMMON.defaultGreyHex.get()));

        for (Color c : Color.palette()) {
            ensureTeam(sb, c.teamName(), nearestChatFormatting(c.hex()));
        }
    }

    /**
     * Move {@code player} to the team that matches {@code colour}, or to the default
     * team if {@code colour == null}. Vanilla auto-removes from any prior team.
     */
    public static void apply(ServerPlayer player, Color colour) {
        if (!ColorConfig.COMMON.teamsEnabled.get()) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        Scoreboard sb = server.getScoreboard();

        String teamName = (colour == null) ? defaultTeamName() : colour.teamName();
        PlayerTeam team = sb.getPlayerTeam(teamName);
        if (team == null) {
            // Defensive - init() should have created it. Recreate on the fly.
            init(server);
            team = sb.getPlayerTeam(teamName);
            if (team == null) return;
        }
        sb.addPlayerToTeam(player.getScoreboardName(), team);
    }

    /** Convenience for login: read current colour + apply. */
    public static void applyForLogin(ServerPlayer player) {
        if (!ColorConfig.COMMON.teamsEnabled.get()) return;
        Color current = ColorService.currentColor(player.getUUID());
        apply(player, current);
    }

    public static String defaultTeamName() {
        // 99_ prefix sorts the default team to the bottom of the alphabetic tab list,
        // below tier teams (00_…, 01_…) and the un-prefixed colour teams.
        return ColorConfig.COMMON.teamPrefix.get() + "99_" + DEFAULT_SUFFIX;
    }

    // ─────────────────────────────────────────────────── internals

    private static void ensureTeam(Scoreboard sb, String name, ChatFormatting colour) {
        PlayerTeam team = sb.getPlayerTeam(name);
        if (team == null) {
            team = sb.addPlayerTeam(name);
            ColorMod.LOG.info("[colormod/teams] created team '{}' (colour={})", name, colour);
        }
        // Empty prefix + suffix - anything in here would either leak as plain text into
        // chat consumers (if it carried §x) or render visibly as extra characters.
        team.setPlayerPrefix(Component.empty());
        team.setPlayerSuffix(Component.empty());
        // setColor takes ChatFormatting (16 colours). The closest match is what the
        // player's name renders in above-head. True RGB on the nameplate body is not
        // available on a vanilla client - see class javadoc for why.
        team.setColor(colour);

        // Defaults - make sure another mod's manual /team modify doesn't leave us
        // with a hidden nametag or weird collision behaviour.
        team.setNameTagVisibility(Team.Visibility.ALWAYS);
        team.setCollisionRule(Team.CollisionRule.ALWAYS);
        team.setAllowFriendlyFire(true);
        team.setSeeFriendlyInvisibles(false);
    }

    /**
     * RGB → nearest legacy {@link ChatFormatting} via Chebyshev (max-channel) distance.
     * Direct port of TAB's {@code TabTextColor.loadClosestColor}.
     */
    private static ChatFormatting nearestChatFormatting(int hex) {
        int r = (hex >> 16) & 0xFF, g = (hex >> 8) & 0xFF, b = hex & 0xFF;
        ChatFormatting best = ChatFormatting.WHITE;
        int bestDist = Integer.MAX_VALUE;
        for (ChatFormatting f : ChatFormatting.values()) {
            Integer fc = f.getColor();
            if (fc == null) continue; // bold / italic / reset etc., not a colour
            int fr = (fc >> 16) & 0xFF, fg = (fc >> 8) & 0xFF, fb = fc & 0xFF;
            int dist = Math.max(Math.max(Math.abs(r - fr), Math.abs(g - fg)), Math.abs(b - fb));
            if (dist < bestDist) { bestDist = dist; best = f; }
        }
        return best;
    }
}
