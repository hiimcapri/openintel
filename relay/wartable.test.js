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
  { name: 'trial', token: 'test-trial', role: 'trial' },
];
const files = new Map([
  ['config.json', JSON.stringify({ minecraftServer: 'play.example.net', adminToken: 'test-api', discord: {}, webhooks: {} })],
  ['users.json', JSON.stringify({ users })],
  ['allegiances.json', JSON.stringify({ allies: [], enemies: [], focus: [] })],
  ['wartable.json', JSON.stringify({ strokes: [] })],
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
const [a, b, ...rest] = sessions;
const trialSocket = rest.at(-1);
const members = rest.slice(0, -1);
const clear = () => sessions.forEach(socket => socket.messages.length = 0);

// Every non-trial session received the (empty) stroke sync on login.
for (const socket of [a, b, ...members]) {
  const sync = socket.take('wartable_sync');
  assert.equal(sync.length, 1);
  assert.deepEqual(sync[0].strokes, []);
}
assert.equal(trialSocket.take('wartable_sync').length, 0);
clear();

// Members can't draw — admin-only gate.
members[0].submit({ type: 'wartable', action: 'add', stroke: {
  id: 'member-stroke-1', tool: 'pen', color: -65536, width: 4, points: [0, 0, 10, 10] } });
assert.match(members[0].take('notice')[0].msg, /admin-only/);
assert.equal(b.take('wartable').length, 0);
assert.equal(JSON.parse(files.get('wartable.json')).strokes.length, 0);

// Admin adds a stroke — it fans out to everyone but trial, and persists.
a.submit({ type: 'wartable', action: 'add', stroke: {
  id: 'admin-front-1', tool: 'pen', color: -65536, width: 4, points: [0, 0, 100, 50, 200, 100], label: undefined } });
for (const socket of [a, b, ...members]) {
  const m = socket.take('wartable');
  assert.equal(m.length, 1);
  assert.equal(m[0].action, 'add');
  assert.equal(m[0].stroke.id, 'admin-front-1');
  assert.equal(m[0].stroke.from, 'AdminOne');
  assert(m[0].stroke.t > 0);
}
assert.equal(trialSocket.take('wartable').length, 0);
assert.equal(JSON.parse(files.get('wartable.json')).strokes.length, 1);
clear();

// Invalid strokes are rejected before they can fan out or persist.
a.submit({ type: 'wartable', action: 'add', stroke: { id: 'bad', tool: 'pen', color: -65536, width: 4, points: [0] } });
assert.match(a.take('notice')[0].msg, /invalid/);
a.submit({ type: 'wartable', action: 'add', stroke: { id: 'bad-tool-1', tool: 'nuke', color: -65536, width: 4, points: [0, 0, 1, 1] } });
assert.match(a.take('notice')[0].msg, /invalid/);
assert.equal(JSON.parse(files.get('wartable.json')).strokes.length, 1);
clear();

// Second admin draws; late joiner gets the full sync.
b.submit({ type: 'wartable', action: 'add', stroke: {
  id: 'admin-zone-1', tool: 'area', color: -16711681, width: 4, points: [0, 0, 100, 0, 100, 100, 0, 100] } });
assert.equal(JSON.parse(files.get('wartable.json')).strokes.length, 2);
const late = login(users.find(u => u.name === 'captain'));
assert.equal(late.take('wartable_sync')[0].strokes.length, 2);
late.close();
clear();

// Admin deletes by id — fans out, persists.
members[1].submit({ type: 'wartable', action: 'delete', id: 'admin-front-1' });
assert.match(members[1].take('notice')[0].msg, /admin-only/);
a.submit({ type: 'wartable', action: 'delete', id: 'admin-front-1' });
for (const socket of [a, b, ...members]) {
  const m = socket.take('wartable');
  assert.equal(m[0].action, 'delete');
  assert.equal(m[0].id, 'admin-front-1');
}
assert.equal(JSON.parse(files.get('wartable.json')).strokes.length, 1);
clear();

// clear scope=mine only removes the author's strokes.
b.submit({ type: 'wartable', action: 'clear', scope: 'mine' });
for (const socket of [a, b, ...members]) assert.equal(socket.take('wartable')[0].action, 'clear');
assert.equal(JSON.parse(files.get('wartable.json')).strokes.length, 0);
clear();

// clear scope=all empties the table.
a.submit({ type: 'wartable', action: 'add', stroke: { id: 'final-1', tool: 'marker', color: -1, width: 4, points: [5, 5] } });
a.submit({ type: 'wartable', action: 'clear', scope: 'all' });
assert.equal(JSON.parse(files.get('wartable.json')).strokes.length, 0);
clear();

a.submit({ type: 'wartable', action: 'add', stroke: {
  id: 'private-sync-1', tool: 'pen', color: -65536, width: 4, points: [0, 0, 10, 10] } });
b.submit({ type: 'wartable', action: 'add', stroke: {
  id: 'public-sync-1', tool: 'line', color: -1, width: 4, points: [10, 10, 20, 20] } });
clear();
a.submit({ type: 'cut', action: 'on' });
for (const socket of members) {
  assert.deepEqual(socket.take('wartable_sync').at(-1).strokes.map(s => s.id), ['public-sync-1']);
}
assert.equal(trialSocket.take('wartable_sync').length, 0);
const publicLogin = login(users.find(u => u.name === 'member'));
assert.deepEqual(publicLogin.take('wartable_sync')[0].strokes.map(s => s.id), ['public-sync-1']);
publicLogin.close();
const adminLogin = login(users[0]);
assert.equal(adminLogin.take('wartable_sync')[0].strokes.length, 2);
adminLogin.close();
clear();
const demoted = JSON.parse(files.get('users.json'));
demoted.users.find(u => u.name === 'AdminTwo').role = 'trial';
routes.get('PUT /users').at(-1)({ body: demoted }, { json() {} });
assert.deepEqual(b.take('wartable_sync').at(-1).strokes, []);
clear();
a.submit({ type: 'cut', action: 'off' });
for (const socket of members) assert.equal(socket.take('wartable_sync').at(-1).strokes.length, 2);

console.log('War table relay tests passed: admin gate, sync on login, add/delete/clear broadcast, validation, persistence, trial exclusion, cut visibility and demotion');
