# OpenIntel

OpenIntel is a Fabric intelligence client for Minecraft 1.21.11. Approved
players continuously share themselves and everyone in their render distance
through a small relay server. The client turns that shared state into a modern,
configurable HUD without adding a minimap, waypoint database, or navigation
system.

## Features

### Shared player intelligence

- **Live player markers** use dynamic-FOV-safe projection and hand off to the
  real entity position when a player enters render distance.
- **Four-way edge stacks** place off-screen targets on the top, bottom, left,
  or right edge, sorted by distance. Each edge has an independent draggable
  anchor.
- **Allegiance colors** are consistent across markers, nameplates, radar, and
  the relay roster: green friends, soft-purple allies, red enemies, grey
  neutral players, and a bright-purple diamond for focus targets.
- **Tinted nameplates**, configurable relay opacity, optional visible-player
  markers, unlimited or capped marker range, and stale-intel fading.
- **Focus targets** can be managed in-game by Captains or through the Discord
  terminal and update live for every connected client.

### Snitch intelligence

- In-game and Discord JukeAlert messages enter the same marker pipeline.
- Normal events (`entered snitch`, login/logout) and interactions (container,
  block, sanctuary, and other coordinate-bearing actions) are parsed
  separately and refresh the correct player's location.
- One active marker is kept per player, so movement through several snitches
  updates the marker instead of leaving a ghost trail.
- Markers are dimension-aware, show snitch name, player, age, and distance,
  and have configurable color, lifetime, range, and fade.
- Semantically identical alerts are deduplicated across in-game and Discord
  sources. Approved relay users do not receive redundant snitch markers when
  their live position is already available.

### Radar and shared pings

- Anti-aliased circular radar with player heads, allegiance colors, distance
  labels, compass points, configurable range rings, rotating or north-up
  orientation, and optional logarithmic distance compression.
- Optional dropped-item, boat, and minecart icons.
- Relay players outside render distance and shared pings pin to the radar rim,
  preserving their bearing at any distance.
- Hold the configurable ping key to open a radial wheel and broadcast a
  temporary, dimension-aware location marker.

### Movable HUD

- `/oi hud` opens a visual editor: click and drag the radar, relay roster,
  event feed, armor HUD, potion HUD, and all four marker anchors.
- Elements snap to screen edges and center guides; positions persist in
  `config/openintel.json` and can be reset from the editor.
- **Relay roster** shows connected/tracked players, allegiance, dimension,
  distance, and freshness.
- **Event feed** reports relay presence, teammate deaths, enemy sightings,
  pings, and snitch activity without blocking chat.
- **Armor HUD** shows equipped pieces with remaining durability percentages.
- **Potion HUD** shows active effect names, amplifier levels, and timers.

### Macros and configuration

- Attack, hold-attack, hold-use, and ice-road macros with safe disengagement
  when screens open, the mouse unlocks, or the selected hotbar slot changes.
- Ice-road automation supports 45-degree yaw/pitch snapping, sprint/jump
  movement, auto-eating, and optional low-hunger parking.
- `/oi settings` provides one unified screen for relay credentials, rendering,
  HUD toggles, marker/snitch behavior, presence, pings, colors, keybinds, radar,
  and macro configuration.

## Screenshots

![OpenIntel gameplay overview with radar, markers, roster and status HUDs](docs/openintel-overview.png)

<table>
  <tr>
    <td align="center"><img src="docs/radar-modern.png" alt="Circular player radar"/><br/><strong>Radar</strong></td>
    <td align="center"><img src="docs/presence-panel.png" alt="Relay presence panel"/><br/><strong>Relay roster</strong></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/relay-marker.png" alt="Live relay player marker"/><br/><strong>Live player marker</strong></td>
    <td align="center"><img src="docs/snitch-marker.png" alt="Snitch hit marker with age and distance"/><br/><strong>Snitch intel</strong></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/armor-hud.png" alt="Armor durability HUD"/><br/><strong>Armor HUD</strong></td>
    <td align="center"><img src="docs/potion-hud.png" alt="Potion effect HUD"/><br/><strong>Potion HUD</strong></td>
  </tr>
</table>

Off-screen contacts remain readable on independently positioned edge stacks:

![Modern edge marker](docs/edge-marker-modern.png)

Focus and enemy-alert behavior is shared across the relay:

![Focus target marker in purple next to a friend marker](docs/focus-target.png)

![Enemy spotted chat alert](docs/enemy-alert-chat.png)

## Relay roles and focus targets

Users in `users.json` can have `"role": "member"` (default), `"captain"`, or
`"admin"`. Captains and admins can mark priority targets in-game:

```
/oi focus <player>    mark a focus target (bright purple ◆ for everyone)
/oi unfocus <player>  unmark
/oi focus clear       clear all focus targets
```

Focus state lives in `allegiances.json`, is pushed live to every client, and
each change is announced in both the alerts and admin Discord channels.

## How it works

```
┌────────────┐   WebSocket    ┌───────────────┐   Webhook POST   ┌──────────────────┐
│ Client mod │◄──────────────►│  Relay server │─────────────────►│ Discord #alerts  │
│ (Fabric)   │  positions in/ │  (Node.js)    │  enemy pings     │ (@role ping)     │
└────────────┘  state out     │               │─────────────────►│ Discord #admin   │
      ▲                       │  users.json   │  auth/log events │ (audit log)      │
      └─ every approved user  │  allegiances  │                  └──────────────────┘
         runs one of these    └───────────────┘
```


- **Deduplication** — six people spotting the same enemy = one ping, not six.
- **Cooldowns** — an enemy standing on your snitch line doesn't spam the channel.
- **Central auth** — approved-user list lives in one place (`users.json`),
  not baked into the mod, so admins control access without rebuilding.


## Repo layout

- `mod/` — Fabric client mod (Java 21, Minecraft 1.21.11, Fabric API)
- `relay/` — Node.js relay server + webhook integration

## Quick start

### Relay server
```bash
cd relay
npm install
cp config.example.json config.json   # set port, admin token, webhook URLs
cp users.example.json users.json     # approved users + their tokens
cp allegiances.example.json allegiances.json
node server.js
```

### Client mod
```bash
cd mod
./gradlew build       # jar lands in build/libs/
```
Drop the jar in `.minecraft/mods` alongside Fabric API. On first launch the
mod writes `config/openintel.json` — set `relayUrl` (e.g.
`ws://your.server:8765`) and your personal `token`, then `/oi reconnect`
or relaunch.

### Discord setup
1. Create two webhooks (Server Settings → Integrations → Webhooks):
   one in your alerts channel, one in a private admin channel.
2. Put both URLs in `relay/config.json`.
3. For role pings, copy the role ID into `alertRoleId` and make sure the
   role is mentionable by webhooks.

## Discord terminal (optional bot)

A Discord channel can act as the relay's admin terminal. The bot runs inside
the relay process — no extra service — and is disabled until
`config.discord.botToken` is set.

### Setup

1. **Create the bot:** [discord.com/developers/applications](https://discord.com/developers/applications)
   → *New Application* (name it e.g. `OpenIntel`) → **Bot** tab → *Reset Token*
   → copy the token into `config.json` → `discord.botToken`.
2. **Enable reading messages:** still on the Bot tab, turn ON
   **Message Content Intent** under *Privileged Gateway Intents*. Without this
   the bot logs in fine but every message looks empty to it.
3. **Invite it:** **OAuth2 → URL Generator** → scope `bot` → bot permissions
   *View Channels*, *Send Messages*, *Read Message History* → open the
   generated URL and add it to your server.
4. **Pick the terminal channel:** in Discord, *User Settings → Advanced →
   Developer Mode* ON, then right-click your private intel channel →
   *Copy Channel ID* → paste into `discord.terminalChannelId`. (Leave empty to
   accept commands from any channel the bot can read — not recommended.)
5. **Map the captain role:** right-click your Captain role → *Copy Role ID* →
   `discord.captainRoleId`. Members with this role (or Discord Administrator)
   can run the mutating commands; everyone in the channel can run read-only ones.
6. Restart the relay. You should see `Discord terminal ready as OpenIntel#1234`.

```jsonc
// config.json
"discord": {
  "botToken": "MTIz...",          // Bot tab → Reset Token
  "terminalChannelId": "1513...", // right-click channel → Copy Channel ID
  "captainRoleId": "987..."       // right-click role → Copy Role ID
}
```

The bot token is a secret like everything else in `config.json` — gitignored,
never ships in the client jar. If it ever leaks, *Reset Token* in the dev
portal invalidates the old one.

### Commands

Type these in the terminal channel:

| Command | Does | Needs captain |
|---|---|---|
| `!help` | list commands | |
| `!online` | who is connected to the relay | |
| `!list` | users / allies / enemies / focus lists | |
| `!where <player>` | last known position of a tracked player | |
| `!ally add\|remove <player>` | edit ally list (pushed live to clients) | ✔ |
| `!enemy add\|remove <player>` | edit enemy list (pushed live to clients) | ✔ |
| `!focus <player>` | mark focus target (bright purple ◆ for everyone) | ✔ |
| `!unfocus <player>` / `!focus clear` | unmark / clear all | ✔ |

Positions still travel over the relay's own WebSocket — Discord rate limits
(~5 msgs/5 s per channel) make it unusable as the position transport, so the
bot is command/control only.

## Admin panel

The relay exposes a small authenticated REST API (header `x-admin-token`):

| Endpoint | Method | Purpose |
|---|---|---|
| `/allegiances` | GET / PUT | Read or replace ally/enemy lists (pushed live to all clients) |
| `/users` | GET / PUT | Read or replace approved-user list |
| `/online` | GET | Who is currently connected |

Every admin change and every connect/auth-failure is logged to the admin
webhook, so the admin Discord channel doubles as an audit trail.

## Client commands and controls

| Command | Purpose |
|---|---|
| `/oi settings` | Unified relay, rendering, HUD, ping, event, color, and keybind settings |
| `/oi hud` | Visual drag-and-drop HUD editor |
| `/oi radar` | Radar size, range, orientation, entity, icon, text, and color settings |
| `/oi macros` | Attack and ice-road macro settings |
| `/oi ping` | Open the shared ping wheel without holding its keybind |
| `/oi status` | Show relay connection status |
| `/oi reconnect` | Reconnect after changing relay credentials |
| `/oi url <url>` | Set the relay WebSocket URL |
| `/oi token <token>` | Set the personal relay token |
| `/oi focus <player>` | Captain-only priority target |
| `/oi unfocus <player>` | Remove a priority target |

All controls are rebindable under the OpenIntel keybind category.

| Default key | Action |
|---|---|
| `R` | Toggle radar |
| `G` | Hold for the shared ping wheel |
| `0` | Toggle timed attack presses |
| `-` | Toggle hold attack |
| `=` | Toggle hold use |
| `Backspace` | Toggle ice-road movement |

## Fair-play notes

This project exists because server admins never requested an open, equal-access
version of closed intel tools. Before running it on any server, confirm
position-sharing/radar mods are legal under that server's rules — legality
varies between Civ servers.

## License

MIT — see `LICENSE`.
