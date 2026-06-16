/*
 * Mod entry. Wires the lifecycle for every colormod module.
 *
 * <p>Modules:
 * <ul>
 *   <li>{@link Color}             - palette, refreshed from config</li>
 *   <li>{@link cz.sajmonoriginal.colormod.db.ColorStorage} - HTTP backend for the color source of truth</li>
 *   <li>{@link ColorService}      - read from HTTP cache, write through HTTP + LuckPerms mirror</li>
 *   <li>{@link cz.sajmonoriginal.colormod.scoreboard.ColorTeams}   - scoreboard team sync for the above-head nameplate</li>
 *   <li>{@link cz.sajmonoriginal.colormod.chat.ChatFormatter}      - grey body for non-elite players</li>
 *   <li>{@link cz.sajmonoriginal.colormod.events.NameEventHandler} - tab + chat name RGB via NameFormat events</li>
 * </ul>
 *
 * <p>LuckPerms is optional. Without it the mod still reads and writes colors
 * through the HTTP backend and renders them through scoreboard teams + the
 * name format events; the LP mirror is only there for downstream chat
 * formatters that key off {@code color_<name>} groups.
 */
package cz.sajmonoriginal.colormod;

import cz.sajmonoriginal.colormod.chat.ChatFormatter;
import cz.sajmonoriginal.colormod.db.ColorStorage;
import cz.sajmonoriginal.colormod.db.HttpApiColorStorage;
import cz.sajmonoriginal.colormod.events.NameEventHandler;
import cz.sajmonoriginal.colormod.scoreboard.ColorTeams;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(ColorMod.MOD_ID)
public final class ColorMod {

    public static final String MOD_ID = "colormod";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static MinecraftServer server;
    private static LuckPerms luckPerms;
    private static ColorStorage storage;

    public ColorMod(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, ColorConfig.SPEC, MOD_ID + "-common.toml");
        container.registerConfig(ModConfig.Type.SERVER, StorageConfig.SPEC, MOD_ID + "-server.toml");
        NeoForge.EVENT_BUS.register(this);
        // Static-method @SubscribeEvent listeners need to register their declaring class.
        NeoForge.EVENT_BUS.register(ChatFormatter.class);
        NeoForge.EVENT_BUS.register(NameEventHandler.class);
        // Live config reload: re-build palette + scoreboard teams when the user edits
        // colormod-common.toml without needing a server restart.
        modBus.addListener(ColorMod::onConfigReload);
    }

    private static void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getModId().equals(MOD_ID)) {
            Color.reload();
            if (server != null) {
                ColorTeams.init(server);
                for (var player : server.getPlayerList().getPlayers()) {
                    ColorTeams.applyForLogin(player);
                    NameEventHandler.refresh(player);
                }
            }
            LOG.info("[colormod] config reload: palette + teams refreshed, names re-coloured");
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ColorCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();

        // Palette must come up before anything that reads colors.
        Color.reload();

        // LuckPerms is optional. Log clearly so admins know whether the LP mirror
        // is active for downstream chat formatters.
        if (ColorConfig.COMMON.luckPermsEnabled.get()) {
            try {
                luckPerms = LuckPermsProvider.get();
                LOG.info("[colormod] LuckPerms API acquired");
            } catch (IllegalStateException e) {
                LOG.warn("[colormod] luckperms.enabled = true but the LuckPerms API is not available. "
                        + "Color groups will not be mirrored. Install LuckPerms-NeoForge or set luckperms.enabled = false.");
                luckPerms = null;
            }
        } else {
            LOG.info("[colormod] LuckPerms integration disabled by config");
            luckPerms = null;
        }

        // HTTP storage backend. Failures are logged here; getColor returns null
        // and setColor throws until the server is restarted with valid config.
        try {
            storage = HttpApiColorStorage.open(
                    StorageConfig.BASE_URL.get(),
                    StorageConfig.INTERNAL_KEY.get(),
                    StorageConfig.POLL_INTERVAL_SECONDS.get(),
                    (uuid, name) -> ColorService.applyLocally(uuid, name == null ? null : Color.byName(name)));
            LOG.info("[colormod] HTTP storage opened: {}", StorageConfig.BASE_URL.get());
        } catch (Throwable t) {
            LOG.error("[colormod] failed to open HTTP storage; fix the storage section of colormod-server.toml and restart", t);
            storage = null;
        }

        ColorTeams.init(server);
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            if (storage != null) {
                // Async fetch. Once the cache is populated the onRemoteChange
                // callback fires applyLocally, which refreshes the nameplate.
                storage.preload(sp.getUUID());
            }
            // Cache is likely empty on first login; this initial pass uses the
            // default team. The preload callback above will trigger a second
            // pass with the real color once the fetch completes.
            ColorTeams.applyForLogin(sp);
            NameEventHandler.refresh(sp);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (storage != null) {
            try { storage.close(); } catch (Exception ignore) {}
        }
        server = null;
        luckPerms = null;
        storage = null;
    }

    public static MinecraftServer server()       { return server; }
    public static LuckPerms       luckPerms()    { return luckPerms; }
    public static ColorStorage    storage()      { return storage; }
}
