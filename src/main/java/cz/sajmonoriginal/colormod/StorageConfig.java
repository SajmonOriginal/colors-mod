package cz.sajmonoriginal.colormod;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-side storage configuration. Held in a single TOML file:
 *
 * <pre>
 * [storage]
 * base_url = "http://localhost:3000"
 * internal_key = ""
 * poll_interval_seconds = 3
 * </pre>
 *
 * Missing required values ({@code base_url}, {@code internal_key}) abort the
 * storage open with an error in the log; the mod then runs in a degraded
 * read-null write-fail state until the values are filled in and the server
 * restarted.
 */
public final class StorageConfig {

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.ConfigValue<String> BASE_URL;
    public static final ModConfigSpec.ConfigValue<String> INTERNAL_KEY;
    public static final ModConfigSpec.IntValue POLL_INTERVAL_SECONDS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("storage");
        BASE_URL = b.comment("Base URL of the color server API (no trailing slash).")
                .define("base_url", "http://localhost:3000");
        INTERNAL_KEY = b.comment("Bearer token for the /internal/colors/* endpoints.")
                .define("internal_key", "");
        POLL_INTERVAL_SECONDS = b.comment("Polling interval for the HTTP backend cache, in seconds.")
                .defineInRange("poll_interval_seconds", 3, 1, 3600);
        b.pop();
        SPEC = b.build();
    }

    private StorageConfig() {}
}
