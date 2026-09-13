const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

const USER_ROLES = ["member", "operator", "captain", "admin"];
const MINECRAFT_NAME = /^[A-Za-z0-9_]{3,16}$/;
const lower = (s) => String(s).toLowerCase();
const normalizeMinecraftServer = (value) => {
  let normalized = lower(value ?? "").trim().replace(/^[a-z]+:\/\//, "").split("/")[0];
  while (normalized.endsWith(".")) normalized = normalized.slice(0, -1);
  if (normalized.endsWith(":25565")) normalized = normalized.slice(0, -6);
  return normalized;
};
const validName = (name) => MINECRAFT_NAME.test(String(name ?? ""));
const validRole = (role) => USER_ROLES.includes(lower(role));
const roleRank = (role) => Math.max(0, USER_ROLES.indexOf(lower(role ?? "member")));
const hasTier = (role, required) => roleRank(role) >= roleRank(required);
const tokenFingerprint = (token) => token
  ? crypto.createHash("sha256").update(String(token)).digest("hex").slice(0, 12)
  : null;
function safeAuditValue(value, key = "") {
  if (value == null || typeof value !== "object") {
    return /token|secret/i.test(key) && value != null ? `sha256:${tokenFingerprint(value)}` : value;
  }
  if (Array.isArray(value)) return value.map((item) => safeAuditValue(item));
  return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, safeAuditValue(v, k)]));
}
function pageText(label, entries, requestedPage = 1, pageSize = 20) {
  const pages = Math.max(1, Math.ceil(entries.length / pageSize));
  const page = Math.min(pages, Math.max(1, Number.parseInt(requestedPage, 10) || 1));
  const rows = entries.slice((page - 1) * pageSize, page * pageSize);
  return `${label} — page ${page}/${pages} (${entries.length})\n${rows.length ? rows.join("\n") : "(none)"}`;
}
function runSelfTest() {
  const assert = require("assert");
  assert.equal(normalizeMinecraftServer("PLAY.CIV.PLUS:25565"), "play.civ.plus");
  assert.equal(normalizeMinecraftServer("minecraft://play.civ.plus/"), "play.civ.plus");
  assert(validName("Player_123"));
  assert(!validName("ab"));
  assert(!validName("bad-name"));
  assert(validRole("operator"));
  assert(!validRole("owner"));
  assert(hasTier("admin", "captain"));
  assert(!hasTier("operator", "captain"));
  assert.equal(pageText("users", ["a", "b", "c"], 2, 2), "users — page 2/2 (3)\nc");
  const safe = safeAuditValue({ token: "test-token", nested: { botToken: "bot" } });
  assert.equal(safe.token, `sha256:${tokenFingerprint("test-token")}`);
  assert(!JSON.stringify(safe).includes("test-token"));
  console.log("relay self-test passed");
}
if (process.argv.includes("--self-test")) {
  runSelfTest();
  process.exit(0);
}

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
const http = require("http");
const express = require("express");
const { WebSocketServer } = require("ws");

const filePath = (name) => path.join(__dirname, name);
const CONFIG = JSON.parse(fs.readFileSync(filePath("config.json"), "utf8"));
const MINECRAFT_SERVER = normalizeMinecraftServer(CONFIG.minecraftServer);
if (!MINECRAFT_SERVER) throw new Error("config.minecraftServer is required");
let USERS = loadJson("users.json", { users: [] });
let ALLEGIANCES = loadJson("allegiances.json", { allies: [], enemies: [] });

function loadJson(name, fallback) {
  try { return JSON.parse(fs.readFileSync(filePath(name), "utf8")); } catch { return fallback; }
}
function saveJson(name, data) {
  const destination = filePath(name);
  const temporary = `${destination}.${process.pid}.tmp`;
  const mode = name === "users.json" ? 0o600 : 0o640;
  fs.writeFileSync(temporary, JSON.stringify(data, null, 2), { mode });
  fs.chmodSync(temporary, mode);
  fs.renameSync(temporary, destination);
}

const userByToken = (token) => USERS.users.find((u) => !u.disabled && u.token === token);
const userByName = (name) => USERS.users.find((u) => lower(u.name) === lower(name));
const userNames = () => USERS.users.filter((u) => !u.disabled).map((u) => u.name);
const isEnemy = (name) =>
  ALLEGIANCES.enemies.map(lower).includes(lower(name)) ||
  (ALLEGIANCES.focus ?? []).map(lower).includes(lower(name));
const isOperator = (role) => hasTier(role, "operator");

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
  postWebhook(CONFIG.webhooks.admin, {
    content: `🛠️ ${msg}`, username: "OpenIntel Admin", allowed_mentions: { parse: [] },
  });
const recentAudit = [];
function audit({ actor, tier, action, target = null, source = {}, before = null, after = null, success = true, reason = null }) {
  const entry = safeAuditValue({
    timestamp: new Date().toISOString(), actor, tier, action, target,
    source: { guild: source.guild ?? null, channel: source.channel ?? null },
    before, after, success: Boolean(success), reason,
  });
  try { fs.appendFileSync(filePath("audit.jsonl"), `${JSON.stringify(entry)}\n`, { mode: 0o600 }); }
  catch (e) { console.error("audit write failed:", e.message); }
  recentAudit.unshift(entry);
  if (recentAudit.length > 20) recentAudit.length = 20;
  const outcome = entry.success ? "succeeded" : `failed${entry.reason ? `: ${entry.reason}` : ""}`;
  adminLog(`**${entry.actor}** (${entry.tier}) — \`${entry.action}\`${entry.target ? ` on **${entry.target}**` : ""} ${outcome}`);
  return entry;
}

const alertCooldowns = new Map(); // enemyName -> last ping ms
const recentSnitch = new Map();   // dedupe key -> first seen ms

// Semantic dedupe: the same tripper at the same coords is ONE hit, even when
// the in-game chat line and the Discord relay post word it differently.
function snitchDedupeKey(m) {
  if (m.x != null && m.y != null && m.z != null)
    return `${String(m.player ?? "?").toLowerCase()}@${m.x},${m.y},${m.z}`;
  return String(m.message ?? "");
}
function dedupeSnitch(m) {
  const key = snitchDedupeKey(m);
  if (!key) return true;
  const now = Date.now();
  const last = recentSnitch.get(key) ?? 0;
  if (now - last < 10_000) return false;
  recentSnitch.set(key, now);
  if (recentSnitch.size > 200) {
    for (const [k, v] of recentSnitch) if (now - v > 60_000) recentSnitch.delete(k);
  }
  return true;
}
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
  const delivered = new Set();
  for (const client of wss.clients) {
    const identity = client.authedAs ? lower(client.authedAs) : null;
    if (client.readyState !== 1 || !identity || delivered.has(identity)) continue;
    client.send(data);
    delivered.add(identity);
  }
}
function socketsFor(name) {
  return [...wss.clients].filter((client) => client.authedAs && lower(client.authedAs) === lower(name));
}
function revokeSessions(name, reason = "session revoked") {
  const sockets = socketsFor(name);
  for (const socket of sockets) socket.close(4001, reason);
  return sockets.length;
}
function removeReportedPositions(name) {
  for (const [key, value] of positions) if (lower(value.reporter) === lower(name)) positions.delete(key);
}
function currentSocketUser(ws) {
  return ws.authedAs ? userByName(ws.authedAs) : null;
}

// Shared by the in-game /oi focus command and the Discord terminal.
// Returns a human-readable description of what changed, or null if the
// action was invalid / a no-op.
function applyFocus(action, subject, actorLabel, context = {}) {
  const before = [...(ALLEGIANCES.focus ?? [])];
  ALLEGIANCES.focus = ALLEGIANCES.focus ?? [];
  if (action === "add" && subject && validName(subject)) {
    if (!ALLEGIANCES.focus.map(lower).includes(lower(subject))) ALLEGIANCES.focus.push(subject);
  } else if (action === "remove" && subject && validName(subject)) {
    ALLEGIANCES.focus = ALLEGIANCES.focus.filter((n) => lower(n) !== lower(subject));
  } else if (action === "clear") {
    ALLEGIANCES.focus = [];
  } else {
    audit({ ...context, actor: actorLabel, action: `focus.${action}`, target: subject, success: false, reason: "invalid action or player name" });
    return null;
  }
  saveJson("allegiances.json", ALLEGIANCES);
  broadcast(allegiancePayload());
  const what = action === "clear" ? "cleared all focus targets"
                                  : `${action === "add" ? "focused" : "unfocused"} **${subject}**`;
  audit({ ...context, actor: actorLabel, action: `focus.${action}`, target: subject, before, after: ALLEGIANCES.focus });
  postWebhook(CONFIG.webhooks.alerts, {
    username: "OpenIntel",
    content: `🎯 **${actorLabel}** ${what}`,
  });
  return what;
}

// kind: "allies" | "enemies"; action: "add" | "remove". Returns a result line.
function applyAllegiance(kind, action, name, actorLabel, context = {}) {
  const list = ALLEGIANCES[kind] ?? (ALLEGIANCES[kind] = []);
  const before = [...list];
  const present = list.map(lower).includes(lower(name));
  if (!validName(name)) {
    audit({ ...context, actor: actorLabel, action: `${kind}.${action}`, target: name, before, after: before, success: false, reason: "invalid player name" });
    return "invalid Minecraft player name";
  }
  if (action === "add") {
    if (present) {
      audit({ ...context, actor: actorLabel, action: `${kind}.${action}`, target: name, before, after: before, success: false, reason: "already present" });
      return `${name} is already on the ${kind} list`;
    }
    list.push(name);
  } else {
    if (!present) {
      audit({ ...context, actor: actorLabel, action: `${kind}.${action}`, target: name, before, after: before, success: false, reason: "not present" });
      return `${name} is not on the ${kind} list`;
    }
    ALLEGIANCES[kind] = list.filter((n) => lower(n) !== lower(name));
  }
  saveJson("allegiances.json", ALLEGIANCES);
  broadcast(allegiancePayload());
  audit({ ...context, actor: actorLabel, action: `${kind}.${action}`, target: name, before, after: ALLEGIANCES[kind] });
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
        ws.send(JSON.stringify({ type: "deny", reason: "bad_token" }));
        adminLog(`❌ Auth failure from \`${ip}\``);
        ws.close();
        return;
      }
      const minecraftServer = normalizeMinecraftServer(msg.minecraftServer);
      if (minecraftServer !== MINECRAFT_SERVER) {
        ws.send(JSON.stringify({ type: "deny", reason: "wrong_server", expectedServer: MINECRAFT_SERVER }));
        adminLog(`❌ **${user.name}** rejected from Minecraft server \`${minecraftServer || "missing"}\``);
        ws.close(4003, "wrong Minecraft server");
        return;
      }
      ws.minecraftServer = minecraftServer;
      ws.authedAs = user.name;
      ws.role = validRole(user.role) ? lower(user.role) : "member";
      ws.send(JSON.stringify({ ...allegiancePayload(), type: "welcome", minecraftServer: MINECRAFT_SERVER }));
      adminLog(`✅ **${user.name}** connected (${ws.role})`);
      return;
    }

    if (!ws.authedAs) return;
    const user = currentSocketUser(ws);
    if (!user || user.disabled || user.token == null) {
      ws.close(4001, "session revoked");
      return;
    }
    ws.role = validRole(user.role) ? lower(user.role) : "member";

    if (msg.type === "focus") {
      if (user.quarantined) {
        ws.send(JSON.stringify({ type: "notice", msg: "quarantined sessions cannot change focus targets" }));
        return;
      }
      if (!isOperator(ws.role)) {
        ws.send(JSON.stringify({ type: "notice", msg: "/oi focus requires the Operator role" }));
        return;
      }
      applyFocus(msg.action, msg.subject ? String(msg.subject) : null, ws.authedAs, {
        tier: ws.role, source: { guild: null, channel: "websocket" },
      });
      return;
    }

    // Shared pings + forwarded snitch alerts: stamp the sender and fan out.
    if (msg.type === "ping" || msg.type === "snitch") {
      if (user.quarantined) return;
      // Several clients can see the same snitch line — don't multiply it.
      if (msg.type === "snitch" && !dedupeSnitch(msg)) return;
      msg.from = ws.authedAs;
      msg.t = Date.now();
      broadcast(msg);
      return;
    }

    if (msg.type === "positions" && Array.isArray(msg.reports)) {
      if (user.quarantined) return;
      const now = Date.now();
      for (const r of msg.reports.slice(0, 100)) {
        if (typeof r.subject !== "string" || !validName(r.subject)) continue;
        if (![r.x, r.y, r.z].every(Number.isFinite) || typeof r.dim !== "string") continue;
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

// Fan shared state out ~4x/sec.
setInterval(() => {
  const now = Date.now();
  for (const [k, v] of positions) if (now - v.t > STALE_MS) positions.delete(k);
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
  const before = ALLEGIANCES;
  ALLEGIANCES = {
    allies: req.body.allies ?? [],
    enemies: req.body.enemies ?? [],
    focus: req.body.focus ?? ALLEGIANCES.focus ?? [],
  };
  saveJson("allegiances.json", ALLEGIANCES);
  broadcast(allegiancePayload());
  audit({ actor: "REST admin", tier: "admin", action: "allegiances.replace", source: { channel: "REST" }, before, after: ALLEGIANCES });
  res.json({ ok: true });
});

app.get("/users", requireAdmin, (req, res) =>
  res.json({ users: USERS.users.map((u) => u.name) })); // never expose tokens
app.put("/users", requireAdmin, (req, res) => {
  const before = USERS;
  USERS = { users: req.body.users ?? [] };
  saveJson("users.json", USERS);
  broadcast(allegiancePayload());
  for (const client of wss.clients) {
    const user = currentSocketUser(client);
    if (client.authedAs && (!user || user.disabled)) client.close(4001, "session revoked");
  }
  audit({ actor: "REST admin", tier: "admin", action: "users.replace", source: { channel: "REST" }, before, after: USERS });
  res.json({ ok: true });
});

app.get("/online", requireAdmin, (req, res) =>
  res.json({ online: [...wss.clients].filter((c) => c.authedAs).map((c) => c.authedAs) }));

// ---------------------------------------------------------------- discord terminal
// Reads plain-text "!" commands from one Discord channel, so the channel works
// like an admin terminal. Disabled unless config.discord.botToken is set.
// Mutating commands require the configured captain role (or Discord Administrator).
// Snitch relay channel: bot-authored JukeAlert posts get parsed and turned
// into the same "snitch" broadcast clients forward — marker, feed line, all
// of it. dedupeSnitch keys on player@coords so a teammate who also saw the
// in-game alert doesn't produce a second hit.
const DISCORD_SNITCH_EVENT =
  /^\s*(?<snitch>.+?):\s*(?<player>\w{3,16})\s+(?<action>entered snitch|logged (?:in|out))\s+at\s*\((?<x>-?\d+)[,\s]+(?<y>-?\d+)[,\s]+(?<z>-?\d+)\s*\)/i;
const DISCORD_SNITCH_INTERACTION =
  /^\s*(?<snitch>.+?):\s*(?<player>\w{3,16})\s+(?<action>(?!entered snitch\b|logged (?:in|out)\b).{2,96}?)\s+at\s*\((?<x>-?\d+)[,\s]+(?<y>-?\d+)[,\s]+(?<z>-?\d+)\s*\)/i;
const DISCORD_SNITCH_INGAME =
  /(?<player>\w{3,16})\s+(?<action>entered snitch)\s+at\s+(?<snitch>\S+)[^\[(]*\[\s*(?<world>\S+)\s+(?<x>-?\d+)\s+(?<y>-?\d+)\s+(?<z>-?\d+)\s*\]/i;

function forwardDiscordSnitch(text) {
  let eventKind = "event";
  let m = text.match(DISCORD_SNITCH_EVENT) ?? text.match(DISCORD_SNITCH_INGAME);
  if (!m) {
    m = text.match(DISCORD_SNITCH_INTERACTION);
    eventKind = "interaction";
  }
  if (!m) return;
  const player = m.groups.player;
  let snitch = m.groups.snitch.replace(/^[+\s*]+|[+\s*]+$/g, "");
  const world = m.groups.world ?? null;
  const x = +m.groups.x, y = +m.groups.y, z = +m.groups.z;
  if (!player || !snitch) return;
  const msg = {
    type: "snitch", message: text, reporter: "discord", player, snitch,
    action: m.groups.action.trim(), eventKind, x, y, z,
  };
  if (world) msg.world = world;
  if (!dedupeSnitch(msg)) return;
  msg.from = "discord";
  msg.t = Date.now();
  broadcast(msg);
  adminLog(`📡 Snitch hit via Discord relay: ${snitch ?? "?"} by ${player ?? "?"}`);
}

const DISCORD = CONFIG.discord ?? {};
const idSet = (many, one) => new Set([
  ...(Array.isArray(many) ? many : []),
  ...(one ? [one] : []),
].map(String).filter(Boolean));
const TERMINAL_CHANNELS = idSet(DISCORD.terminalChannelIds, DISCORD.terminalChannelId);
const SNITCH_CHANNELS = idSet(DISCORD.snitchChannelIds, DISCORD.snitchChannelId);
const OPERATOR_ROLES = idSet(DISCORD.operatorRoleIds, DISCORD.operatorRoleId);
const CAPTAIN_ROLES = idSet(DISCORD.captainRoleIds, DISCORD.captainRoleId);
const ADMIN_ROLES = idSet(DISCORD.adminRoleIds, DISCORD.adminRoleId);

if (DISCORD.botToken) {
  const {
    ActionRowBuilder, ButtonBuilder, ButtonStyle, Client, EmbedBuilder,
    GatewayIntentBits, PermissionsBitField,
  } = require("discord.js");
  const bot = new Client({
    intents: [
      GatewayIntentBits.Guilds,
      GatewayIntentBits.GuildMessages,
      GatewayIntentBits.MessageContent, // must also be enabled in the dev portal
    ],
  });

  const fence = (s) => "```\n" + String(s).slice(0, 1900) + "\n```";
  const discordTier = (member) => {
    if (!member) return "member";
    if (member.permissions.has(PermissionsBitField.Flags.Administrator)) return "admin";
    if ([...ADMIN_ROLES].some((id) => member.roles.cache.has(id))) return "admin";
    if ([...CAPTAIN_ROLES].some((id) => member.roles.cache.has(id))) return "captain";
    if ([...OPERATOR_ROLES].some((id) => member.roles.cache.has(id))) return "operator";
    return "member";
  };
  const sourceOf = (value) => ({ guild: value.guildId ?? null, channel: value.channelId ?? null });
  const actorOf = (value) => `${value.member?.displayName ?? value.user?.username ?? value.author?.username ?? "unknown"} (${value.user?.id ?? value.author?.id ?? "unknown"})`;
  const onlineRows = () => [...wss.clients]
    .filter((c) => c.authedAs)
    .map((c) => `${c.authedAs} (${c.role}${userByName(c.authedAs)?.quarantined ? ", quarantined" : ""})`);
  const listRows = (kind) => {
    if (kind === "users") return USERS.users.map((u) => `${u.name} (${u.role ?? "member"}${u.disabled ? ", disabled" : ""}${u.quarantined ? ", quarantined" : ""})`);
    if (kind === "allies") return (ALLEGIANCES.allies ?? []).map(String);
    if (kind === "enemies") return (ALLEGIANCES.enemies ?? []).map(String);
    if (kind === "focus") return (ALLEGIANCES.focus ?? []).map(String);
    if (kind === "online") return onlineRows();
    return [
      ...USERS.users.map((u) => `user: ${u.name} (${u.role ?? "member"}${u.disabled ? ", disabled" : ""}${u.quarantined ? ", quarantined" : ""})`),
      ...(ALLEGIANCES.allies ?? []).map((n) => `ally: ${n}`),
      ...(ALLEGIANCES.enemies ?? []).map((n) => `enemy: ${n}`),
      ...(ALLEGIANCES.focus ?? []).map((n) => `focus: ${n}`),
    ];
  };
  const renderList = (kind = "all", page = 1) => pageText(kind, listRows(kind), page);
  const commandAudit = (msg, tier, action, target, details = {}) => audit({
    actor: actorOf(msg), tier, action, target, source: sourceOf(msg), ...details,
  });
  const requireTier = async (msg, tier, required, action, target = null) => {
    if (hasTier(tier, required)) return true;
    commandAudit(msg, tier, action, target, { success: false, reason: `requires ${required}` });
    await msg.reply(`⛔ requires the ${required} role`);
    return false;
  };
  const HELP = fence(
    [
      "!online [page]                       who is connected to the relay",
      "!list [users|allies|enemies|focus|online|all] [page]",
      "!where <player>                      last known position of a tracked player",
      "!broadcast <message>                 relay notice (operator)",
      "!focus <player>|clear / !unfocus     focus management (operator)",
      "!panel                               interactive status panel (operator)",
      "!ally|enemy add|remove <player>      allegiance management (captain)",
      "!kick <user>                         close active sessions (captain)",
      "!quarantine|unquarantine <user>      control submissions (captain)",
      "!user add <name> [role]              create user + DM token (admin)",
      "!user remove|disable|enable <name>   user lifecycle (admin)",
      "!user role <name> <role>             set user role (admin)",
      "!user rotate-token|info <name>       token/user details (admin)",
      "!help                                this message",
    ].join("\n")
  );
  const panelRows = () => new ActionRowBuilder().addComponents(
    new ButtonBuilder().setCustomId("oi:status").setLabel("Refresh Status").setStyle(ButtonStyle.Primary),
    new ButtonBuilder().setCustomId("oi:online").setLabel("Online").setStyle(ButtonStyle.Secondary),
    new ButtonBuilder().setCustomId("oi:lists").setLabel("Lists").setStyle(ButtonStyle.Secondary),
    new ButtonBuilder().setCustomId("oi:audit").setLabel("Recent Activity").setStyle(ButtonStyle.Secondary),
  );
  const panelEmbed = () => new EmbedBuilder()
    .setTitle("OpenIntel Relay")
    .setDescription("Relay administration and live status")
    .addFields(
      { name: "Online", value: String(onlineRows().length), inline: true },
      { name: "Users", value: String(USERS.users.length), inline: true },
      { name: "Focus", value: String((ALLEGIANCES.focus ?? []).length), inline: true },
      { name: "Minecraft server", value: MINECRAFT_SERVER, inline: false },
    )
    .setTimestamp();

  bot.on("messageCreate", async (msg) => {
    try {
      // Snitch relay channels share one lane and one global dedupe map.
      if (SNITCH_CHANNELS.has(msg.channelId)) {
        forwardDiscordSnitch(msg.content);
        return;
      }
      if (msg.author.bot || !msg.guild) return;
      if (TERMINAL_CHANNELS.size > 0 && !TERMINAL_CHANNELS.has(msg.channelId)) return;
      if (!msg.content.startsWith("!")) return;

      const parts = msg.content.slice(1).trim().split(/\s+/);
      const cmd = lower(parts[0] ?? "");
      const actor = actorOf(msg);
      const tier = discordTier(msg.member);
      const context = { tier, source: sourceOf(msg) };

      if (cmd === "help") return void msg.reply(HELP);

      if (cmd === "online") return void msg.reply(fence(renderList("online", parts[1])));

      if (cmd === "list") {
        const kinds = ["users", "allies", "enemies", "focus", "online", "all"];
        const requested = lower(parts[1] ?? "all");
        const kind = kinds.includes(requested) ? requested : "all";
        const page = kinds.includes(requested) ? parts[2] : parts[1];
        return void msg.reply(fence(renderList(kind, page)));
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
      if (cmd === "broadcast") {
        const text = parts.slice(1).join(" ").trim();
        if (!(await requireTier(msg, tier, "operator", "broadcast", null))) return;
        if (!text || text.length > 1500) {
          commandAudit(msg, tier, "broadcast", null, { success: false, reason: "message must be 1-1500 characters" });
          return void msg.reply("usage: `!broadcast <message>` (maximum 1500 characters)");
        }
        broadcast({ type: "notice", msg: `[Broadcast] ${text}`, from: actor, t: Date.now() });
        commandAudit(msg, tier, "broadcast", null, { after: { message: text } });
        return void msg.reply("broadcast sent");
      }

      if (cmd === "panel") {
        if (!(await requireTier(msg, tier, "operator", "panel.open", null))) return;
        commandAudit(msg, tier, "panel.open", null);
        return void msg.reply({ embeds: [panelEmbed()], components: [panelRows()] });
      }

      if (cmd === "focus" || cmd === "unfocus") {
        if (!(await requireTier(msg, tier, "operator", `focus.${cmd === "unfocus" ? "remove" : "add"}`, parts[1]))) return;
        const arg = parts[1];
        if (!arg) return void msg.reply(cmd === "focus" ? "usage: `!focus <player>` or `!focus clear`" : "usage: `!unfocus <player>`");
        const action = cmd === "unfocus" ? "remove" : lower(arg) === "clear" ? "clear" : "add";
        const what = applyFocus(action, action === "clear" ? null : arg, actor, context);
        return void msg.reply(what ? fence(what.replace(/\*\*/g, "")) : "invalid Minecraft player name");
      }

      if (cmd === "ally" || cmd === "enemy") {
        if (!(await requireTier(msg, tier, "captain", `${cmd}.${parts[1]}`, parts[2]))) return;
        const action = lower(parts[1] ?? "");
        const name = parts[2];
        if (!["add", "remove"].includes(action) || !name) {
          return void msg.reply(`usage: \`!${cmd} add|remove <player>\``);
        }
        const result = applyAllegiance(cmd === "ally" ? "allies" : "enemies", action, name, actor, context);
        return void msg.reply(fence(result));
      }

      if (cmd === "kick") {
        const name = parts[1];
        if (!(await requireTier(msg, tier, "captain", "session.kick", name))) return;
        if (!name || !validName(name)) {
          commandAudit(msg, tier, "session.kick", name, { success: false, reason: "invalid player name" });
          return void msg.reply("usage: `!kick <user>`");
        }
        const user = userByName(name);
        if (!user) {
          commandAudit(msg, tier, "session.kick", name, { success: false, reason: "user not found" });
          return void msg.reply("user not found");
        }
        const count = revokeSessions(user.name, "kicked by relay captain");
        commandAudit(msg, tier, "session.kick", user.name, { before: { sessions: count }, after: { sessions: 0 } });
        return void msg.reply(`closed ${count} active session(s) for ${user.name}`);
      }

      if (cmd === "quarantine" || cmd === "unquarantine") {
        const name = parts[1];
        if (!(await requireTier(msg, tier, "captain", `user.${cmd}`, name))) return;
        const user = name ? userByName(name) : null;
        if (!user) {
          commandAudit(msg, tier, `user.${cmd}`, name, { success: false, reason: "user not found" });
          return void msg.reply("user not found");
        }
        const before = { quarantined: Boolean(user.quarantined) };
        user.quarantined = cmd === "quarantine";
        if (!user.quarantined) delete user.quarantined;
        if (cmd === "quarantine") removeReportedPositions(user.name);
        saveJson("users.json", USERS);
        commandAudit(msg, tier, `user.${cmd}`, user.name, { before, after: { quarantined: Boolean(user.quarantined) } });
        return void msg.reply(`${user.name} ${cmd === "quarantine" ? "quarantined; submissions are blocked" : "unquarantined"}`);
      }

      if (cmd === "user") {
        const action = lower(parts[1] ?? "");
        const name = parts[2];
        if (!(await requireTier(msg, tier, "admin", `user.${action || "unknown"}`, name))) return;
        if (!["add", "remove", "disable", "enable", "role", "rotate-token", "info"].includes(action)) {
          return void msg.reply("usage: `!user add|remove|disable|enable|role|rotate-token|info ...`");
        }
        if (!name || !validName(name)) {
          commandAudit(msg, tier, `user.${action}`, name, { success: false, reason: "invalid Minecraft name" });
          return void msg.reply("invalid Minecraft name; expected 3-16 letters, numbers, or underscores");
        }
        if (action === "add") {
          const role = lower(parts[3] ?? "member");
          if (!validRole(role)) {
            commandAudit(msg, tier, "user.add", name, { success: false, reason: "invalid role" });
            return void msg.reply("role must be member, operator, captain, or admin");
          }
          if (userByName(name)) {
            commandAudit(msg, tier, "user.add", name, { success: false, reason: "user already exists" });
            return void msg.reply("user already exists");
          }
          const token = crypto.randomBytes(32).toString("base64url");
          const user = { name, token, role };
          try {
            await msg.author.send(`OpenIntel token for **${name}** (${role}):\n\`${token}\`\nStore it securely; it will not be shown in the command channel.`);
          } catch {
            commandAudit(msg, tier, "user.add", name, { before: null, after: null, success: false, reason: "DM delivery failed; user not created" });
            return void msg.reply(`user was not created because DM delivery failed. Enable DMs and retry.`);
          }
          USERS.users.push(user);
          saveJson("users.json", USERS);
          broadcast(allegiancePayload());
          commandAudit(msg, tier, "user.add", name, { before: null, after: user });
          return void msg.reply(`user ${name} added; the token was sent to you by DM`);
        }
        const user = userByName(name);
        if (!user) {
          commandAudit(msg, tier, `user.${action}`, name, { success: false, reason: "user not found" });
          return void msg.reply("user not found");
        }
        const before = { ...user };
        if (action === "info") {
          commandAudit(msg, tier, "user.info", user.name, { before: null, after: null });
          return void msg.reply(fence([
            `name: ${user.name}`,
            `role: ${user.role ?? "member"}`,
            `disabled: ${Boolean(user.disabled)}`,
            `quarantined: ${Boolean(user.quarantined)}`,
            `token fingerprint: sha256:${tokenFingerprint(user.token)}`,
            `active sessions: ${socketsFor(user.name).length}`,
          ].join("\n")));
        }
        if (action === "remove") USERS.users = USERS.users.filter((u) => lower(u.name) !== lower(user.name));
        if (action === "disable") user.disabled = true;
        if (action === "enable") delete user.disabled;
        if (action === "role") {
          const role = lower(parts[3] ?? "");
          if (!validRole(role)) {
            commandAudit(msg, tier, "user.role", user.name, { before, after: before, success: false, reason: "invalid role" });
            return void msg.reply("role must be member, operator, captain, or admin");
          }
          user.role = role;
        }
        let newToken = null;
        if (action === "rotate-token") {
          newToken = crypto.randomBytes(32).toString("base64url");
          try {
            await msg.author.send(`New OpenIntel token for **${user.name}**:\n\`${newToken}\`\nStore it securely; the prior token will be revoked now.`);
          } catch {
            commandAudit(msg, tier, `user.${action}`, user.name, { before, after: before, success: false, reason: "DM delivery failed; token unchanged" });
            return void msg.reply(`token was not rotated because DM delivery failed. Enable DMs and retry.`);
          }
          user.token = newToken;
        }
        saveJson("users.json", USERS);
        broadcast(allegiancePayload());
        const revoked = ["remove", "disable", "rotate-token"].includes(action)
          ? revokeSessions(user.name, `${action} by relay admin`) : 0;
        const auditDetails = {
          before, after: action === "remove" ? null : user,
          reason: revoked ? `${revoked} session(s) revoked` : null,
        };
        if (newToken) {
          commandAudit(msg, tier, `user.${action}`, user.name, auditDetails);
          return void msg.reply(`token rotated for ${user.name}; the new token was sent to you by DM`);
        }
        commandAudit(msg, tier, `user.${action}`, user.name, auditDetails);
        return void msg.reply(`${user.name}: ${action} complete${revoked ? `; ${revoked} session(s) revoked` : ""}`);
      }
    } catch (e) {
      console.error("discord command failed:", e.message);
    }
  });

  bot.on("interactionCreate", async (interaction) => {
    if (!interaction.isButton() || !interaction.customId.startsWith("oi:") || !interaction.guild) return;
    try {
      if (TERMINAL_CHANNELS.size > 0 && !TERMINAL_CHANNELS.has(interaction.channelId)) {
        return void interaction.reply({ content: "This panel is not active in this channel.", ephemeral: true });
      }
      const tier = discordTier(interaction.member);
      const action = `panel.${interaction.customId.slice(3)}`;
      if (!hasTier(tier, "operator")) {
        audit({ actor: actorOf(interaction), tier, action, source: sourceOf(interaction), success: false, reason: "requires operator" });
        return void interaction.reply({ content: "Requires the operator role.", ephemeral: true });
      }
      audit({ actor: actorOf(interaction), tier, action, source: sourceOf(interaction) });
      if (interaction.customId === "oi:status") {
        return void interaction.update({ embeds: [panelEmbed()], components: [panelRows()] });
      }
      if (interaction.customId === "oi:online") {
        return void interaction.reply({ content: fence(renderList("online", 1)), ephemeral: true });
      }
      if (interaction.customId === "oi:lists") {
        return void interaction.reply({ content: fence(renderList("all", 1)), ephemeral: true });
      }
      const lines = recentAudit.slice(0, 8).map((entry) =>
        `${entry.timestamp} ${entry.actor}: ${entry.action}${entry.target ? ` ${entry.target}` : ""} [${entry.success ? "ok" : "failed"}]`);
      return void interaction.reply({ content: fence(pageText("recent activity", lines, 1, 8)), ephemeral: true });
    } catch (e) {
      console.error("discord interaction failed:", e.message);
    }
  });

  bot.once("clientReady", () => console.log(
    `Discord bridge ready as ${bot.user.tag} across ${bot.guilds.cache.size} guild(s), ` +
    `${TERMINAL_CHANNELS.size} terminal channel(s), ${SNITCH_CHANNELS.size} snitch channel(s)`
  ));
  bot.login(DISCORD.botToken).catch((e) => console.error("Discord login failed:", e.message));
}

server.listen(CONFIG.port ?? 8765, () => {
  console.log(`OpenIntel relay listening on :${CONFIG.port ?? 8765} for ${MINECRAFT_SERVER}`);
  adminLog("🟢 Relay server started");
});
