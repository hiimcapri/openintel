/**
 * OpenIntel relay server
 * ----------------------
 * - Authenticates approved users (users.json) over WebSocket
 * - Merges position reports from every client, broadcasts shared state ~4x/sec
 * - Pushes allegiance lists live to clients when admins change them
 * - Fires the ALERT webhook (with role ping) when an enemy enters anyone's
 *   render — deduplicated + cooldown, so one enemy = one ping
 * - Fires the ADMIN webhook for connects, auth failures, and admin changes
 *   (your admin Discord channel becomes the audit log / "admin panel" feed)
 *
 * Run:  npm install && node server.js
 */
const fs = require("fs");
const http = require("http");
const express = require("express");
const { WebSocketServer } = require("ws");

const CONFIG = JSON.parse(fs.readFileSync("config.json", "utf8"));
let USERS = loadJson("users.json", { users: [] });
let ALLEGIANCES = loadJson("allegiances.json", { allies: [], enemies: [] });

function loadJson(path, fallback) {
  try { return JSON.parse(fs.readFileSync(path, "utf8")); } catch { return fallback; }
}
function saveJson(path, data) {
  fs.writeFileSync(path, JSON.stringify(data, null, 2));
}

const lower = (s) => String(s).toLowerCase();
const userByToken = (token) => USERS.users.find((u) => u.token === token);
const userNames = () => USERS.users.map((u) => u.name);
const isEnemy = (name) =>
  ALLEGIANCES.enemies.map(lower).includes(lower(name)) ||
  (ALLEGIANCES.focus ?? []).map(lower).includes(lower(name));
const isCaptain = (role) => role === "captain" || role === "admin";

// ---------------------------------------------------------------- webhooks
async function postWebhook(url, payload) {
  if (!url) return;
  try {
    await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
  } catch (e) {
    console.error("webhook failed:", e.message);
  }
}
const adminLog = (msg) =>
  postWebhook(CONFIG.webhooks.admin, { content: `🛠️ ${msg}`, username: "OpenIntel Admin" });

const alertCooldowns = new Map(); // enemyName -> last ping ms
function enemyAlert(enemy, x, z, dim, reporter) {
  const now = Date.now();
  const last = alertCooldowns.get(lower(enemy)) ?? 0;
  if (now - last < (CONFIG.alertCooldownMs ?? 300000)) return;
  alertCooldowns.set(lower(enemy), now);

  const ping = CONFIG.webhooks.alertRoleId ? `<@&${CONFIG.webhooks.alertRoleId}> ` : "";
  postWebhook(CONFIG.webhooks.alerts, {
    username: "OpenIntel",
    content:
      `${ping}🔴 **${enemy}** spotted at **${Math.round(x)}, ${Math.round(z)}** ` +
      `(${dim.replace("minecraft:", "")}) — reported by ${reporter}`,
    allowed_mentions: { parse: ["roles"] },
  });
}

// ---------------------------------------------------------------- state
// subject(lower) -> { name, x, y, z, dim, t, reporter }
const positions = new Map();
const STALE_MS = CONFIG.staleMs ?? 10000;

// ---------------------------------------------------------------- websocket
const app = express();
app.use(express.json());
const server = http.createServer(app);
const wss = new WebSocketServer({ server });

function allegiancePayload() {
  return {
    type: "allegiances",
    users: userNames(),
    allies: ALLEGIANCES.allies,
    enemies: ALLEGIANCES.enemies,
    focus: ALLEGIANCES.focus ?? [],
  };
}

function broadcast(obj) {
  const data = JSON.stringify(obj);
  for (const client of wss.clients) {
    if (client.readyState === 1 && client.authedAs) client.send(data);
  }
}

// Shared by the in-game /oi focus command and the Discord terminal.
// Returns a human-readable description of what changed, or null if the
// action was invalid / a no-op.
function applyFocus(action, subject, actorLabel) {
  ALLEGIANCES.focus = ALLEGIANCES.focus ?? [];
  if (action === "add" && subject) {
    if (!ALLEGIANCES.focus.map(lower).includes(lower(subject))) ALLEGIANCES.focus.push(subject);
  } else if (action === "remove" && subject) {
    ALLEGIANCES.focus = ALLEGIANCES.focus.filter((n) => lower(n) !== lower(subject));
  } else if (action === "clear") {
    ALLEGIANCES.focus = [];
  } else {
    return null;
  }
  saveJson("allegiances.json", ALLEGIANCES);
  broadcast(allegiancePayload());
  const what = action === "clear" ? "cleared all focus targets"
                                  : `${action === "add" ? "focused" : "unfocused"} **${subject}**`;
  adminLog(`🎯 Captain **${actorLabel}** ${what}`);
  postWebhook(CONFIG.webhooks.alerts, {
    username: "OpenIntel",
    content: `🎯 Captain **${actorLabel}** ${what}`,
  });
  return what;
}

// kind: "allies" | "enemies"; action: "add" | "remove". Returns a result line.
function applyAllegiance(kind, action, name, actorLabel) {
  const list = ALLEGIANCES[kind] ?? (ALLEGIANCES[kind] = []);
  const present = list.map(lower).includes(lower(name));
  if (action === "add") {
    if (present) return `${name} is already on the ${kind} list`;
    list.push(name);
  } else {
    if (!present) return `${name} is not on the ${kind} list`;
    ALLEGIANCES[kind] = list.filter((n) => lower(n) !== lower(name));
  }
  saveJson("allegiances.json", ALLEGIANCES);
  broadcast(allegiancePayload());
  adminLog(`📋 **${actorLabel}** ${action === "add" ? "added" : "removed"} **${name}** ${action === "add" ? "to" : "from"} ${kind}`);
  return `${action === "add" ? "added" : "removed"} ${name} ${action === "add" ? "to" : "from"} ${kind}`;
}

wss.on("connection", (ws, req) => {
  ws.authedAs = null;
  const ip = req.socket.remoteAddress;

  ws.on("message", (raw) => {
    let msg;
    try { msg = JSON.parse(raw); } catch { return; }

    if (msg.type === "hello") {
      const user = userByToken(msg.token);
      if (!user) {
        ws.send(JSON.stringify({ type: "deny" }));
        adminLog(`❌ Auth failure from \`${ip}\``);
        ws.close();
        return;
      }
      ws.authedAs = user.name;
      ws.role = user.role ?? "member";
      ws.send(JSON.stringify({ ...allegiancePayload(), type: "welcome" }));
      adminLog(`✅ **${user.name}** connected (${ws.role})`);
      return;
    }

    if (!ws.authedAs) return;

    if (msg.type === "focus") {
      if (!isCaptain(ws.role)) {
        ws.send(JSON.stringify({ type: "notice", msg: "/oi focus requires the Captain role" }));
        return;
      }
      applyFocus(msg.action, msg.subject ? String(msg.subject) : null, ws.authedAs);
      return;
    }

    // Ping-wheel-style location ping: fan out to every authed client.
    if (msg.type === "ping") {
      const now = Date.now();
      if (now - (ws.lastPing ?? 0) < 1000) return; // per-user rate limit
      const x = +msg.x, y = +msg.y, z = +msg.z;
      if (!Number.isFinite(x) || !Number.isFinite(y) || !Number.isFinite(z)) return;
      ws.lastPing = now;
      broadcast({
        type: "ping",
        x, y, z,
        dim: String(msg.dim ?? "minecraft:overworld"),
        by: ws.authedAs,
      });
      return;
    }

    if (msg.type === "positions" && Array.isArray(msg.reports)) {
      const now = Date.now();
      for (const r of msg.reports.slice(0, 100)) {
        if (typeof r.subject !== "string") continue;
        positions.set(lower(r.subject), {
          name: r.subject, x: +r.x, y: +r.y, z: +r.z,
          dim: String(r.dim), t: now, reporter: ws.authedAs,
        });
        if (isEnemy(r.subject)) enemyAlert(r.subject, r.x, r.z, r.dim, ws.authedAs);
      }
    }
  });

  ws.on("close", () => {
    if (ws.authedAs) adminLog(`👋 **${ws.authedAs}** disconnected`);
  });
});

// Fan shared state out ~4x/sec. Entries may carry their own ttl (snitch
// pings live longer than live position reports, which refresh constantly).
setInterval(() => {
  const now = Date.now();
  for (const [k, v] of positions) if (now - v.t > (v.ttl ?? STALE_MS)) positions.delete(k);
  if (positions.size === 0) return;
  broadcast({ type: "state", players: [...positions.values()] });
}, CONFIG.broadcastIntervalMs ?? 250);

// ---------------------------------------------------------------- admin REST
function requireAdmin(req, res, next) {
  if (req.headers["x-admin-token"] !== CONFIG.adminToken) {
    return res.status(403).json({ error: "bad admin token" });
  }
  next();
}

app.get("/allegiances", requireAdmin, (req, res) => res.json(ALLEGIANCES));
app.put("/allegiances", requireAdmin, (req, res) => {
  ALLEGIANCES = {
    allies: req.body.allies ?? [],
    enemies: req.body.enemies ?? [],
    focus: req.body.focus ?? ALLEGIANCES.focus ?? [],
  };
  saveJson("allegiances.json", ALLEGIANCES);
  broadcast(allegiancePayload());
  adminLog(`📋 Allegiances updated — allies: ${ALLEGIANCES.allies.length}, enemies: ${ALLEGIANCES.enemies.length}`);
  res.json({ ok: true });
});

app.get("/users", requireAdmin, (req, res) =>
  res.json({ users: USERS.users.map((u) => u.name) })); // never expose tokens
app.put("/users", requireAdmin, (req, res) => {
  USERS = { users: req.body.users ?? [] };
  saveJson("users.json", USERS);
  broadcast(allegiancePayload());
  adminLog(`👥 Approved-user list updated — ${USERS.users.length} users`);
  res.json({ ok: true });
});

app.get("/online", requireAdmin, (req, res) =>
  res.json({ online: [...wss.clients].filter((c) => c.authedAs).map((c) => c.authedAs) }));

// ---------------------------------------------------------------- discord terminal
// Reads plain-text "!" commands from one Discord channel, so the channel works
// like an admin terminal. Disabled unless config.discord.botToken is set.
// Mutating commands require the configured captain role (or Discord Administrator).
const DISCORD = CONFIG.discord ?? {};
if (DISCORD.botToken) {
  const { Client, GatewayIntentBits, PermissionsBitField } = require("discord.js");
  const bot = new Client({
    intents: [
      GatewayIntentBits.Guilds,
      GatewayIntentBits.GuildMessages,
      GatewayIntentBits.MessageContent, // must also be enabled in the dev portal
    ],
  });

  const fence = (s) => "```\n" + s + "\n```";
  const none = (arr) => (arr && arr.length ? arr.join(", ") : "(none)");
  const canMutate = (member) =>
    member != null &&
    (member.permissions.has(PermissionsBitField.Flags.Administrator) ||
      (DISCORD.captainRoleId && member.roles.cache.has(DISCORD.captainRoleId)));

  const HELP = fence(
    [
      "!online                      who is connected to the relay",
      "!list                        users / allies / enemies / focus",
      "!where <player>              last known position of a tracked player",
      "!ally add|remove <player>    edit allies        (captain)",
      "!enemy add|remove <player>   edit enemies       (captain)",
      "!focus <player>              mark focus target  (captain)",
      "!focus clear                 clear all focus    (captain)",
      "!unfocus <player>            unmark target      (captain)",
      "!help                        this message",
    ].join("\n")
  );

  // "<snitchname>: <playername> entered snitch at (x, y, z)"
  // Tolerant of markdown decoration and missing parens; coordinates may be
  // negative. Dimension is not part of the ping; overworld is assumed.
  const SNITCH_RE = /^(.+?):\s+(\S+)\s+entered\s+snitch\s+at\s+\(?\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*\)?/i;

  function handleSnitchPing(msg) {
    // Strip bold/code markdown only — underscores are legal in MC names.
    const text = msg.content.replace(/[*`]/g, "").trim();
    const m = text.match(SNITCH_RE);
    if (!m) return false;

    const [, snitch, player, x, y, z] = m;
    positions.set(lower(player), {
      name: player,
      x: +x, y: +y, z: +z,
      dim: "minecraft:overworld",
      t: Date.now(),
      ttl: DISCORD.snitchMarkerTtlMs ?? 30000,
      reporter: `snitch:${snitch.trim()}`,
    });
    if (isEnemy(player)) enemyAlert(player, +x, +z, "minecraft:overworld", `snitch ${snitch.trim()}`);
    return true;
  }

  bot.on("messageCreate", async (msg) => {
    try {
      // Snitch pings come from other bots/webhooks, so check them before the
      // bot filter below — skipping only ourselves to avoid loops.
      if (DISCORD.snitchChannelId && msg.channelId === DISCORD.snitchChannelId
          && msg.author.id !== bot.user.id) {
        if (handleSnitchPing(msg)) return;
      }

      if (msg.author.bot || !msg.guild) return;
      if (DISCORD.terminalChannelId && msg.channelId !== DISCORD.terminalChannelId) return;
      if (!msg.content.startsWith("!")) return;

      const parts = msg.content.slice(1).trim().split(/\s+/);
      const cmd = lower(parts[0] ?? "");
      const actor = msg.member?.displayName ?? msg.author.username;

      if (cmd === "help") return void msg.reply(HELP);

      if (cmd === "online") {
        const online = [...wss.clients].filter((c) => c.authedAs).map((c) => `${c.authedAs} (${c.role})`);
        return void msg.reply(fence(`online (${online.length}): ${none(online)}`));
      }

      if (cmd === "list") {
        return void msg.reply(fence(
          [
            `users:   ${none(userNames())}`,
            `allies:  ${none(ALLEGIANCES.allies)}`,
            `enemies: ${none(ALLEGIANCES.enemies)}`,
            `focus:   ${none(ALLEGIANCES.focus)}`,
          ].join("\n")
        ));
      }

      if (cmd === "where") {
        const name = parts[1];
        if (!name) return void msg.reply("usage: `!where <player>`");
        const p = positions.get(lower(name));
        if (!p) return void msg.reply(fence(`${name}: no recent report`));
        const age = Math.round((Date.now() - p.t) / 1000);
        return void msg.reply(fence(
          `${p.name}: ${Math.round(p.x)}, ${Math.round(p.y)}, ${Math.round(p.z)} ` +
          `(${p.dim.replace("minecraft:", "")}) — ${age}s ago, reported by ${p.reporter}`
        ));
      }

      // Everything below mutates state.
      if (!["ally", "enemy", "focus", "unfocus"].includes(cmd)) return;
      if (!canMutate(msg.member)) {
        return void msg.reply("⛔ requires the captain role");
      }

      if (cmd === "ally" || cmd === "enemy") {
        const action = lower(parts[1] ?? "");
        const name = parts[2];
        if (!["add", "remove"].includes(action) || !name) {
          return void msg.reply(`usage: \`!${cmd} add|remove <player>\``);
        }
        const result = applyAllegiance(cmd === "ally" ? "allies" : "enemies", action, name, actor);
        return void msg.reply(fence(result));
      }

      if (cmd === "focus") {
        const arg = parts[1];
        if (!arg) return void msg.reply("usage: `!focus <player>` or `!focus clear`");
        const what = lower(arg) === "clear"
          ? applyFocus("clear", null, actor)
          : applyFocus("add", arg, actor);
        return void msg.reply(fence(what.replace(/\*\*/g, "")));
      }

      if (cmd === "unfocus") {
        const name = parts[1];
        if (!name) return void msg.reply("usage: `!unfocus <player>`");
        const what = applyFocus("remove", name, actor);
        return void msg.reply(fence(what.replace(/\*\*/g, "")));
      }
    } catch (e) {
      console.error("discord command failed:", e);
    }
  });

  bot.once("ready", () => console.log(`Discord terminal ready as ${bot.user.tag}`));
  bot.login(DISCORD.botToken).catch((e) => console.error("Discord login failed:", e.message));
}

server.listen(CONFIG.port ?? 8765, () => {
  console.log(`OpenIntel relay listening on :${CONFIG.port ?? 8765}`);
  adminLog("🟢 Relay server started");
});
