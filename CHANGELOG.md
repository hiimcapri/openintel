# Changelog

## 1.2.1 — modernized client (unreleased)

A full modernization pass on the relay client: own code, own UI, shared-intel
features on top of the original position/allegiance/focus core.

### Radar
- Player radar: allegiance-colored blips, range rings, log-scale distance
  compression, crisp anti-aliased dial (vector geometry, no texels).
- `/oi radar` settings screen + orientation options.
- Configurable radar line and background colors via a real HSV color picker
  (SV square + hue bar + alpha + hex).

### Macros
- `/oi macros` — attack, hold-key, and ice-road input macros with a config
  screen and keybinds.

### Relay markers & nameplates
- Live handoff: tracked players within render distance anchor to the entity's
  lerped position; relay coords are only a fallback.
- Nearby projected name labels use collision-aware vertical stacking while
  their marker chevrons remain anchored to the players' exact positions.
- Relay player snapshots are published atomically, preventing render crashes
  when a newly tracked player arrives during a HUD frame.
- Dynamic-FOV-safe projection via `GameRenderer.project()` — sprint/zoom no
  longer shifts markers.
- Allegiance-tinted vanilla nameplates; relay nameplate + chevron overlay can
  be disabled for visible players ("Markers + nameplates in render distance").
- Master relay-render toggle + opacity slider.
- Edge chevrons: left/right vertical stacks, top/bottom single-chevron
  columns. Anchors fully adjustable (`/oi settings` → edge position/offset
  sliders) so the stacks can park around your HUD.
- Unlimited marker range by default (old 4096 cap migrates automatically);
  optional cap slider.

### Presence panel
- Side panel listing relay members: name, allegiance, dimension, distance.
- Stale-intel decay on blips and markers.

### HUD editor
- `/oi hud` visual editor with click-and-drag placement, center/edge snapping,
  persistent positions, and a reset layout action.
- Independently movable top, bottom, left, and right marker-stack anchors.
- Movable relay roster, radar, event feed, armor HUD, and potion-effect HUD.
- Armor durability percentages and compact potion names, levels, and timers.

### Ping wheel
- Ping-wheel-style shared pings (`/oi ping`), enable toggle + keybind.

### Event feed
- Snitch parsing distinguishes normal presence events from interactions and
  refreshes player markers for container, block, sanctuary, and similar events.
- Incomplete snitch messages remain feed-only instead of creating `?` markers.
- Teammate death/logout/enemy-in-range toasts; feed duration slider.

### Snitch intel
- In-game JukeAlert lines (game + chat channels) forward to the relay;
  clients get a ⚠ marker with `snitch | player | age | distance`, live age
  counter, and a fade over a configurable duration (default 2 min).
- Per-tripper markers: a new hit moves the player's marker — no ghost trails.
- Relay-side semantic dedupe (`player@x,y,z`) — the same hit from game chat
  and Discord produces one marker.
- Approved relay users don't get snitch markers (live position already
  streams).
- Dimension filtering: named worlds pin to that dimension; snitch-name
  inference ("EndSpawn" → the End) and tripper/reporter fallbacks otherwise.
- Discord snitch channel → in-game markers: the relay bot watches a
  configured channel, parses `Name: Player entered snitch at (x, y, z)` and
  `player entered snitch at name [world x y z]` formats, bot-authored posts
  allowed in that channel only.
- Snitch marker color picker (icon + text), with "Allegiance color" reset.
- `/oi snitchtest` places a fake hit for local verification.

### Settings
- Unified `/oi settings` screen: relay URL + token fields, reconnect button,
  all render toggles, keybind rebinding, links into radar/macro screens,
  and the color pickers.

### Infra
- Token-specific builds bake `openintel_token.txt` inside the jar — no
  recompile per user.
- Relay server: snitch dedupe, Discord snitch-channel watch, existing
  position/allegiance/focus/ping/Discord-terminal unchanged.
