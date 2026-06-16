/*
 * NeoForge ModConfigSpec for the common-side mod config. Auto-reloaded on
 * file change. Sections:
 *
 * <ul>
 *   <li>{@code [colors]}     - palette: list of {name|hex|discord_role_id|lp_group_override?}</li>
 *   <li>{@code [luckperms]}  - mirror color_&lt;name&gt; groups to LuckPerms for downstream chat formatters</li>
 *   <li>{@code [scoreboard]} - vanilla teams for the above-head nameplate</li>
 *   <li>{@code [chat]}       - grey-out body for non-elite players</li>
 * </ul>
 *
 * <p>HTTP storage (the source of truth) lives in a separate server-side TOML;
 * see {@link StorageConfig}.
 */
package cz.sajmonoriginal.colormod;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public final class ColorConfig {
    private ColorConfig() {}

    public static final ModConfigSpec SPEC;
    public static final Common COMMON;

    static {
        var pair = new ModConfigSpec.Builder().configure(Common::new);
        SPEC = pair.getRight();
        COMMON = pair.getLeft();
    }

    public static final class Common {
        // ── colors ─────────────────────────────────────────────
        /**
         * Palette as a list of pipe-separated strings. Pipe was chosen over
         * JSON because ModConfigSpec's TOML doesn't expose nested structures
         * cleanly.
         *
         * <p>Two entry shapes:
         * <ul>
         *   <li>3 fields: {@code name|hex|discord_role_id}</li>
         *   <li>4 fields: {@code name|hex|discord_role_id|lp_group_override}</li>
         * </ul>
         * Example: {@code "red|ef4444|1234567890123456789"}.
         */
        public final ModConfigSpec.ConfigValue<List<? extends String>> colors;

        // ── luckperms ──────────────────────────────────────────
        public final ModConfigSpec.BooleanValue         luckPermsEnabled;
        public final ModConfigSpec.ConfigValue<String>  luckPermsGroupPrefix;

        // ── scoreboard teams (above-head nameplate) ────────────
        public final ModConfigSpec.BooleanValue         teamsEnabled;
        public final ModConfigSpec.ConfigValue<String>  teamPrefix;
        public final ModConfigSpec.IntValue             defaultGreyHex;

        // ── chat body recolouring ──────────────────────────────
        public final ModConfigSpec.BooleanValue         chatRecolourBody;
        public final ModConfigSpec.IntValue             chatDefaultBodyHex;
        public final ModConfigSpec.ConfigValue<String>  chatEliteLpGroup;

        Common(ModConfigSpec.Builder b) {
            // ─── colors
            b.push("colors");
            b.comment(
                    "Palette of available colours / tiers. Two entry shapes:",
                    "  3 fields: `name|hex|discord_role_id`",
                    "  4 fields: `name|hex|discord_role_id|lp_group_override`",
                    "",
                    "The 4-field form lets a tier (e.g. owner) point at an arbitrary LP group",
                    "instead of the conventional `color_<name>`, AND signals that this entry should",
                    "sort-prefix its scoreboard team name (palette index) so it lands at the top of",
                    "the tab list. Plain colour entries use the 3-field form.",
                    "",
                    "Resolution walks the palette in order. Put high-priority tiers first.",
                    "",
                    "Example with an owner tier on top:",
                    "    \"owner|FF0000|<role>|owner\",",
                    "    \"red|FF5555|<role>\",",
                    "    \"blue|3b82f6|<role>\"");
            colors = b.defineList("palette",
                    () -> List.of(
                            "red|ef4444|",
                            "orange|f97316|",
                            "yellow|eab308|",
                            "lime|84cc16|",
                            "green|22c55e|",
                            "cyan|06b6d4|",
                            "blue|3b82f6|",
                            "purple|a855f7|",
                            "pink|ec4899|"),
                    () -> "name|ef4444|",
                    s -> {
                        if (!(s instanceof String str)) return false;
                        int parts = str.split("\\|", -1).length;
                        return parts == 3 || parts == 4;
                    });
            b.pop();

            // ─── luckperms
            b.push("luckperms");
            b.comment(
                    "Mirror color_<name> groups to LuckPerms so existing styled-chat /",
                    "TabTPS configurations that key off LP groups continue to work. Disable",
                    "to skip the mirror; the HTTP backend remains the source of truth.");
            luckPermsEnabled = b.define("enabled", true);
            luckPermsGroupPrefix = b.define("groupPrefix", "color_");
            b.pop();

            // ─── scoreboard teams (above-head nameplate)
            b.push("scoreboard");
            b.comment(
                    "Vanilla-scoreboard-team carrier for the above-head player nameplate.",
                    "Each palette colour gets a team; the team's color (one of the 16 legacy",
                    "ChatFormatting values) is set to the closest match of the configured",
                    "hex (Chebyshev distance in RGB space), the same approach the TAB plugin",
                    "uses for 'RGB nametags'.",
                    "",
                    "Vanilla 1.21.1 has no third path: Team#setColor only takes ChatFormatting,",
                    "and the only mechanism that does carry true RGB onto the nameplate body is",
                    "the legacy escape, which leaks as plain text into chat / Discord-bridge",
                    "consumers. We avoid that escape entirely (empty prefix + suffix, just the",
                    "closest 16-colour team.color).",
                    "",
                    "Tab list and chat keep TRUE RGB. Those go through the NameFormat /",
                    "TabListNameFormat events, which feed packet-level Component RGB straight",
                    "to the client.");
            teamsEnabled = b.define("enabled", true);
            teamPrefix = b.comment("Team name prefix; teams will be e.g. cmod_color_red, cmod_color_default.")
                    .define("teamPrefix", "cmod_color_");
            defaultGreyHex = b.comment(
                    "RGB colour (decimal) for the 'no colour assigned' default team. The team's",
                    "vanilla colour is the closest 16-colour match: 0xAAAAAA -> ChatFormatting.GRAY.")
                    .defineInRange("defaultGreyHex", 0xAAAAAA, 0, 0xFFFFFF);
            b.pop();

            // ─── chat body recolour
            b.push("chat");
            b.comment(
                    "Recolour the BODY of player chat messages (not just the name). Vanilla and",
                    "most servers render the message body white; this option grey-tints it for",
                    "everyone EXCEPT members of the configured 'elite' LuckPerms group (typically",
                    "owners / staff), matching the Discord default-role grey vs. coloured-role",
                    "white pattern.",
                    "",
                    "Default OFF. If you already use styled-chat or another chat formatter, leave",
                    "this off and let that mod handle the body. Turn it on for self-contained setups.");
            chatRecolourBody = b.define("recolourBody", false);
            chatDefaultBodyHex = b.defineInRange("defaultBodyHex", 0xAAAAAA, 0, 0xFFFFFF);
            chatEliteLpGroup = b.comment(
                    "LuckPerms group whose members keep WHITE chat body. Typically 'owner' or 'staff'.",
                    "Anyone NOT in this group gets the grey defaultBodyHex tint. Empty = nobody is",
                    "elite (everyone gets grey).")
                    .define("eliteLpGroup", "owner");
            b.pop();
        }
    }
}
