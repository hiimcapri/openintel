const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { EventEmitter } = require('node:events');
const visibility = require('./visibility');

const users = [
  { name: 'AdminOne', token: 'test-admin-one', role: 'admin' },
  { name: 'AdminTwo', token: 'test-admin-two', role: 'admin' },
  ...['member', 'operator', 'captain'].map(role => ({ name: role, token: 'test-' + role, role })),
];
const files = new Map([
  ['config.json', JSON.stringify({ minecraftServer: 'play.example.net', adminToken: 'test-api', discord: {}, webhooks: {} })],
  ['users.json', JSON.stringify({ users })],
  ['allegiances.json', JSON.stringify({ allies: [], enemies: [], focus: [] })],
]);
const routes = new Map();
const app = { use() {}, get(route, ...handlers) { routes.set('GET ' + route, handlers); },
  put(route, ...handlers) { routes.set('PUT ' + route, handlers); } };
let server;
class FakeServer extends EventEmitter {
  constructor() { super(); this.clients = new Set(); server = this; }
}
class Socket extends EventEmitter {
  constructor() { super(); this.readyState = 1; this.messages = []; }
  send(raw) { this.messages.push(JSON.parse(raw)); }
  close() { this.readyState = 3; }
  submit(message) { this.emit('message', JSON.stringify(message)); }
  take(type) { const found = this.messages.filter(m => m.type === type); this.messages = this.messages.filter(m => m.type !== type); return found; }
}
const timers = [];
const express = Object.assign(() => app, { json: () => () => {} });
vm.runInNewContext(fs.readFileSync(path.join(__dirname, 'server.js'), 'utf8'), {
  __dirname, Buffer, console: { log() {}, error() {} }, process: { argv: [], pid: 1234 },
  setInterval(fn) { timers.push(fn); },
  fetch: async () => ({ ok: true }),
  require(name) {
    if (name === './visibility') return visibility;
    if (name === 'express') return express;
    if (name === 'ws') return { WebSocketServer: FakeServer };
    if (name === 'http') return { createServer: () => ({ listen(port, callback) { callback(); } }) };
    if (name === 'fs') return {
      readFileSync(name) { const value = files.get(path.basename(name)); if (value === undefined) throw new Error('missing fixture'); return value; },
      writeFileSync(name, value) { files.set(path.basename(name), value); },
      renameSync(from, to) { files.set(path.basename(to), files.get(path.basename(from))); files.delete(path.basename(from)); },
      chmodSync() {}, appendFileSync(name, value) { files.set(path.basename(name), (files.get(path.basename(name)) || '') + value); },
    };
    return require(name);
  },
}, { filename: 'server.js' });
function login(user) {
  const socket = new Socket();
  server.clients.add(socket);
  server.emit('connection', socket, { socket: { remoteAddress: '127.0.0.1' } });
  socket.submit({ type: 'hello', token: user.token, minecraftServer: 'play.example.net' });
  assert.equal(socket.take('welcome')[0].role, user.role);
  return socket;
}
const sessions = users.map(login);
const [a, b, ...lower] = sessions;
const tick = () => timers.forEach(fn => fn());
const clear = () => sessions.forEach(socket => socket.messages.length = 0);
function report(socket, subject, x) {
  socket.submit({ type: 'positions', reports: [{ subject, x, y: 64, z: 0, dim: 'minecraft:overworld' }] });
}
function players(socket) { return socket.take('state').at(-1)?.players ?? []; }
for (const socket of lower) {
  socket.submit({ type: 'cut', action: 'on', name: 'AdminOne', role: 'admin' });
  assert.match(socket.take('notice')[0].msg, /admin-only/);
}
a.submit({ type: 'cut', action: 'on', name: 'AdminTwo' });
assert.equal(JSON.parse(files.get('users.json')).users.find(u => u.name === 'AdminOne').relayCut, true);
assert.equal(JSON.parse(files.get('users.json')).users.find(u => u.name === 'AdminTwo').relayCut, undefined);
assert(lower.every(socket => socket.take('intel_reset').length === 1));
assert.equal(b.take('intel_reset').length, 0);
clear();
report(a, 'AdminOne', 100);
report(a, 'Observed', 200);
report(lower[0], 'AdminOne', 300);
report(lower[0], 'PublicOnly', 400);
tick();
assert.equal(players(b).length, 3);
for (const socket of lower) assert.deepEqual(players(socket).map(p => p.name), ['PublicOnly']);
assert.equal(players(a).length, 3);
clear();
report(lower[0], 'Observed', 500);
tick();
for (const socket of lower) assert.equal(players(socket).find(p => p.name === 'Observed').x, 500);
clear();
const snitch = { type: 'snitch', player: 'Tripper', snitch: 'Test', world: 'minecraft:the_end', x: 1, y: 2, z: 3 };
a.submit(snitch);
assert.equal(b.take('snitch').length, 1);
assert(lower.every(socket => socket.take('snitch').length === 0));
lower[0].submit(snitch);
assert.equal(b.take('snitch').length, 0);
assert(lower.every(socket => socket.take('snitch').length === 1));
clear();
lower[0].submit({ ...snitch, player: 'AdminOne', x: 9 });
assert.equal(b.take('snitch').length, 1);
assert(lower.every(socket => socket.take('snitch').length === 0));
clear();
a.submit({ type: 'ping', id: 'private-ping', x: 1, y: 2, z: 3, dim: 'minecraft:overworld' });
assert.equal(b.take('ping').length, 1);
assert(lower.every(socket => socket.take('ping').length === 0));
b.submit({ type: 'cut', action: 'on' });
clear();
a.submit({ type: 'ping', id: 'both-cut' });
assert.equal(b.take('ping').length, 1);
b.submit({ type: 'ping', id: 'reverse-both-cut' });
assert.equal(a.take('ping').length, 2);
lower[0].submit({ type: 'ping', id: 'incoming-public' });
assert.equal(a.take('ping').length, 1);
assert.equal(b.take('ping').length, 2);
a.submit({ type: 'focus', action: 'add', subject: 'PrivateFocus' });
assert(b.take('allegiances').some(m => m.focus.includes('PrivateFocus')));
assert(lower.every(socket => socket.take('allegiances').every(m => !m.focus.includes('PrivateFocus'))));
const reconnected = login({ ...users[0], role: 'admin' });
reconnected.submit({ type: 'cut', action: 'status' });
assert.match(reconnected.take('notice')[0].msg, /ON/);
reconnected.close();
a.submit({ type: 'cut', action: 'off' });
assert.equal(a.readyState, 1);
assert(lower.every(socket => players(socket).some(p => p.name === 'AdminOne')));
clear();
a.submit({ type: 'ping', id: 'public-again' });
assert(lower.every(socket => socket.take('ping').length === 1));
assert.equal(b.take('ping').length, 1);
a.submit({ type: 'cut', action: 'on' });
clear();
const demoted = JSON.parse(files.get('users.json'));
demoted.users.find(u => u.name === 'AdminTwo').role = 'member';
routes.get('PUT /users').at(-1)({ body: demoted }, { json() {} });
assert.equal(b.take('intel_reset').length, 1);
a.submit({ type: 'ping', id: 'not-for-demoted-admin' });
assert.equal(b.take('ping').length, 0);
b.submit({ type: 'cut', action: 'off' });
assert.match(b.take('notice')[0].msg, /admin-only/);
console.log('Cut routing tests passed: permissions, both admin cut states, provenance, reset, persistence, focus, demotion and audience deduplication');
