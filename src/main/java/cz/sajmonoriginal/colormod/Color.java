/*
 * Runtime palette - built from the {@code colors.palette} config list at startup and
 * refreshed when the config reloads. No more hardcoded enum: we ship a sensible default
 * (Tailwind 500 series) but admins can edit it freely.
 *
 * <p>One {@link Color} = (name, hex, optional discord_role_id).
 * Lookups by name + by Discord role ID are O(1).
 */
package cz.sajmonoriginal.colormod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class Color {

    private final String name;
    private final int hex;
    private final String discordRoleId;
    /** LP group this entry maps to. Defaults to {@code color_<name>}; overridable from config. */
    private final String lpGroupOverride;
    /** Position in the palette list - drives tab-list sort ordering (lower = higher in tab). */
    private final int sortIndex;

    public Color(String name, int hex, String discordRoleId, String lpGroupOverride, int sortIndex) {
        this.name = name;
        this.hex = hex;
        this.discordRoleId = (discordRoleId == null || discordRoleId.isBlank()) ? null : discordRoleId;
        this.lpGroupOverride = (lpGroupOverride == null || lpGroupOverride.isBlank()) ? null : lpGroupOverride;
        this.sortIndex = sortIndex;
    }

    public String name()           { return name; }
    public int    hex()            { return hex; }
    public String hexHash()        { return String.format("#%06x", hex); }
    public String discordRoleId()  { return discordRoleId; }
    public int    sortIndex()      { return sortIndex; }

    /**
     * A "tier" entry - palette supplied an explicit {@code lp_group_override} (4th
     * pipe field). Tiers are PRIVILEGE-GATED: only ops can assign them via
     * {@code /color}, they're skipped in the Discord embed buttons, and Discord
     * role changes that touch tier roles never propagate to LuckPerms. The mod
     * READS the LP group to render the colour but never WRITES it.
     */
    public boolean isTier()        { return lpGroupOverride != null; }

    /**
     * Encode {@code hex} as the legacy RGB escape sequence understood by every modern
     * vanilla client ({@code §x§R§R§G§G§B§B}). When this string sits in a scoreboard-team
     * prefix the client treats it as "switch to this RGB colour" and the trailing player
     * name renders in the exact hex - no 16-colour {@link net.minecraft.ChatFormatting}
     * approximation, no client-side mod required. Standard Spigot/Bukkit trick, supported
     * by Mojang's font renderer since 1.16.
     */
    public String legacyHex() {
        StringBuilder sb = new StringBuilder("§x");
        for (int shift = 20; shift >= 0; shift -= 4) {
            char c = "0123456789abcdef".charAt((hex >> shift) & 0xF);
            sb.append('§').append(c);
        }
        return sb.toString();
    }

    /**
     * LP group this colour maps to. Either the explicit override from the 4th
     * pipe-separated field of the palette entry, or the conventional
     * {@code color_<name>} computed from the prefix + name.
     */
    public String luckPermsGroup() {
        return lpGroupOverride != null
                ? lpGroupOverride
                : ColorConfig.COMMON.luckPermsGroupPrefix.get() + name;
    }

    /**
     * Vanilla scoreboard team name.
     *
     * <p>Tier entries (those that supplied a 4th pipe-field {@code lp_group_override})
     * get sort-prefixed with the 2-digit palette index - vanilla sorts the tab list
     * alphabetically by team name, so {@code cmod_color_00_owner} lands above the
     * un-prefixed colour teams ({@code cmod_color_red} etc.) and the default team
     * (sort-prefixed {@code 99_}) at the bottom.
     *
     * <p>Plain colour entries (3-field, no override) skip the prefix; vanilla orders
     * them alphabetically among themselves, which is the right behaviour - they all
     * have equal tier priority and the user just wanted "owner on top".
     */
    public String teamName() {
        if (lpGroupOverride != null) {
            return String.format("%s%02d_%s", ColorConfig.COMMON.teamPrefix.get(), sortIndex, name);
        }
        return ColorConfig.COMMON.teamPrefix.get() + name;
    }

    // ─────────────────────────────────────────────────────── runtime palette

    private static final AtomicReference<List<Color>> PALETTE = new AtomicReference<>(List.of());
    private static final ConcurrentHashMap<String, Color> BY_NAME = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Color> BY_ROLE_ID = new ConcurrentHashMap<>();

    /** Re-read the palette from config. Called on mod startup + config reload. */
    public static void reload() {
        List<? extends String> raw = ColorConfig.COMMON.colors.get();
        List<Color> next = new ArrayList<>(raw.size());
        BY_NAME.clear();
        BY_ROLE_ID.clear();
        int sortIndex = 0;
        for (Object o : raw) {
            if (!(o instanceof String entry)) continue;
            // Accept 3-field (legacy) and 4-field (with lp_group_override) formats.
            //   name | hex | discord_role_id
            //   name | hex | discord_role_id | lp_group_override
            String[] parts = entry.split("\\|", -1);
            if (parts.length != 3 && parts.length != 4) {
                ColorMod.LOG.warn("[colormod] palette entry '{}' has bad shape; skipping", entry);
                continue;
            }
            String name = parts[0].trim().toLowerCase();
            int hex;
            try { hex = Integer.parseInt(parts[1].trim(), 16); }
            catch (NumberFormatException ex) {
                ColorMod.LOG.warn("[colormod] palette entry '{}' has bad hex; skipping", entry);
                continue;
            }
            String roleId = parts[2].trim();
            String lpOverride = parts.length == 4 ? parts[3].trim() : null;
            Color c = new Color(name, hex, roleId, lpOverride, sortIndex++);
            next.add(c);
            BY_NAME.put(name, c);
            if (c.discordRoleId != null) BY_ROLE_ID.put(c.discordRoleId, c);
        }
        PALETTE.set(Collections.unmodifiableList(next));
        ColorMod.LOG.info("[colormod] palette loaded: {} entries", next.size());
    }

    public static List<Color> palette()                  { return PALETTE.get(); }
    public static Color       byName(String name)        { return name == null ? null : BY_NAME.get(name.toLowerCase()); }
    public static Color       byDiscordRole(String id)   { return id == null ? null : BY_ROLE_ID.get(id); }
}
