# colors-mod

Per-player chat and nameplate colors for Minecraft (NeoForge 1.21.1). Players
run `/color <name>` in-game; the choice is stored on the server's HTTP API
and rendered locally through vanilla scoreboard teams (above-head nameplate),
the NameFormat events (true RGB on the tab list and chat name), and an
optional LuckPerms `color_<name>` mirror for downstream chat formatters.

The earlier embedded Discord bot is gone. Discord integration is now handled
by the external server service that owns the color API. The mod is a thin
client.

## Install

1. Grab `colormod-<version>.jar` from
   [Releases](https://github.com/SajmonOriginal/colors-mod/releases).
2. Drop it into the server's `mods/` directory.
3. Start the server once. It writes two default config files:
   * `config/colormod-common.toml` (palette, scoreboard teams, chat body)
   * `config/colormod-server.toml` (HTTP backend URL and bearer key)
4. Fill in `colormod-server.toml` and restart.

The mod is server-only. No client-side jar is needed; the rendering pieces all
use vanilla mechanisms.

## Backend

The mod always reads and writes through an HTTP API:

* `POST /internal/colors/set` body `{ actor, colorName }`
* `POST /internal/colors/clear` body `{ actor }`
* `GET  /internal/colors/by-mc-uuid/:uuid` returns `{ colorName, entry } | null`
* `GET  /internal/colors/changes-since?cursor=N&limit=500` returns `{ changes, nextCursor }`

`actor` is the discriminated union `{ type: "mc-uuid", value: <uuid> }`.

Reads are served from an in-memory cache keyed by Minecraft UUID. The cache is
populated when a player logs in (one `by-mc-uuid` fetch per login) and
refreshed by a polling loop that watches `changes-since`. On any non-empty
batch of remote changes, every cached uuid is refetched and diffs trigger an
in-game refresh of the affected players' nameplates.

The bearer key is required for `/internal/*` calls. Missing or empty
`internal_key` aborts the storage open with an error in the log.

## Config

`colormod-server.toml`:

```toml
[storage]
base_url = "http://localhost:3000"
internal_key = ""
poll_interval_seconds = 3
```

`colormod-common.toml` (palette, scoreboard, optional chat body):

```toml
[colors]
palette = [
    "red|ef4444|",
    "orange|f97316|",
    "yellow|eab308|",
    "lime|84cc16|",
    "green|22c55e|",
    "cyan|06b6d4|",
    "blue|3b82f6|",
    "purple|a855f7|",
    "pink|ec4899|",
]

[luckperms]
enabled = true
groupPrefix = "color_"

[scoreboard]
enabled = true
teamPrefix = "cmod_color_"
defaultGreyHex = 11184810  # 0xAAAAAA

[chat]
recolourBody = false
defaultBodyHex = 11184810
eliteLpGroup = "owner"
```

Palette entries are `name|hex|discord_role_id` (the `discord_role_id` field is
informational here; the server uses it). A fourth pipe-separated field marks
a tier entry that maps to an arbitrary LuckPerms group instead of
`color_<name>` and is gated to ops in `/color`.

## Build

Needs JDK 21.

```bash
./gradlew build
./gradlew runServer
./gradlew runClient
```

The output jar is at `build/libs/colormod-<version>.jar`. gson is shaded in
via NeoForge's `jarJar`. LuckPerms is compile-only and resolved at runtime
from `LuckPerms-NeoForge` when installed; the mirror is a no-op when LP is
absent.

## License

All rights reserved. Free modpacks may include unmodified copies, and forks
are allowed as long as they credit this repository as the source. Commercial
use requires written permission from the copyright holder. See
[LICENSE](LICENSE).
