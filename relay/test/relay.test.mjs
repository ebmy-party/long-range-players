import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn, execFileSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { once } from 'node:events';
import { mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { WebSocket } from 'ws';
import { createAuditLog } from '../audit-log.mjs';
import { parsePolicy, serverId, sessionHash, CONSENT_REVISION } from '../access-policy.mjs';
import { environmentPolicy } from '../environment-policy.mjs';
import { createRelay } from '../secure-relay.mjs';
import { secureProxyRequest } from '../proxy.mjs';

const relayRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const temporary = () => mkdtempSync(join(tmpdir(), 'long-range-players-test-'));
function cleanup(path) {
  const expected = resolve(tmpdir()) + sep + 'long-range-players-test-';
  assert.ok(resolve(path).startsWith(expected)); rmSync(path, { recursive: true, force: true });
}
function logs(path) {
  return readdirSync(path).filter(n => n.endsWith('.jsonl')).flatMap(n => readFileSync(join(path, n), 'utf8').trim().split('\n').filter(Boolean).map(JSON.parse));
}
async function waitFor(predicate, timeout = 5000) {
  const until = Date.now() + timeout;
  while (!predicate()) { if (Date.now() > until) throw new Error('Local test timed out'); await delay(10); }
}

test('persistent audit rotates only its own files and bounds retention', () => {
  const dir = temporary();
  try {
    writeFileSync(join(dir, 'keep.txt'), 'unrelated');
    const log = createAuditLog(dir, { maxBytes: 180, maxFiles: 3 });
    for (let i = 0; i < 20; i++) log({ time: new Date().toISOString(), event: 'test', i, data: 'x'.repeat(70) });
    assert.equal(readdirSync(dir).filter(n => n.endsWith('.jsonl')).length, 3);
    assert.equal(readFileSync(join(dir, 'keep.txt'), 'utf8'), 'unrelated');
    assert.equal(logs(dir).at(-1).i, 19);
    const restart = createAuditLog(dir, { maxBytes: 180, maxFiles: 3 });
    restart({ time: new Date().toISOString(), event: 'restart' });
    assert.equal(logs(dir).at(-1).event, 'restart');
    assert.equal(readdirSync(dir).filter(n => n.endsWith('.jsonl')).length, 3);
  } finally { cleanup(dir); }
});


const uuid = id => `00000000-0000-0000-0000-${String(id).padStart(12, '0')}`;
const group = id => `10000000-0000-0000-0000-${String(id).padStart(12, '0')}`;
function policyInput() {
  return { revision: CONSENT_REVISION, publicUrl: 'wss://relay.example/ws', operator: 'Test operator',
    contact: 'https://example.invalid/contact', retentionDays: 7, trustedProxyAddresses: ['127.0.0.1'],
    servers: [1, 2].map(id => ({ id: group(id), addresses: [`server${id}.example`],
      members: [1, 2, 3, 4].map(id => ({ uuid: uuid(id), expiresAt: Date.now() + 86400000 })) })) };
}
const hello = (id, policy, overrides = {}) => ({ type: 'hello', protocol: 5, uuid: uuid(id), name: `Audit${id}`,
  serverId: serverId('server1.example'), world: 'a'.repeat(64), dimension: 'minecraft:overworld',
  clientNonce: 'c'.repeat(64), consent: CONSENT_REVISION, policyHash: policy.policyHash, ...(policy.mode === 'group' ? {groupCode: 'Shared1234567890', relayUrl: 'wss://relay.example/ws', retentionDays:7} : {}), ...overrides });
const pose = { type: 'pose', dimension: 'minecraft:overworld', x: 12.345, y: 64, z: -5,
  yaw: -10, pitch: 100, bodyYaw: 25, headYaw: 30, stance: 'CROUCHING', sneaking: true,
  usingItem: true, useHand: 'OFF_HAND', mainArm: 'LEFT', swing: 7, swingHand: 'MAIN_HAND',
  equipment: { MAINHAND: 'minecraft:diamond_sword' }, modelParts: 127 };
async function fixture(t, verifier, customPolicy) {
  const policy = customPolicy ?? parsePolicy(policyInput()), history = [], sockets = [], verifications = [];
  const relay = createRelay({ policy, secureRequest: () => true, log: record => history.push(record),
    verifyIdentity: async (name, hash, signal) => {
      verifications.push({name, hash});
      return verifier ? verifier(name, hash, signal) : { uuid: uuid(Number(name.replace('Audit', ''))), name };
    } });
  await new Promise(resolve => relay.http.listen(0, '127.0.0.1', resolve));
  t.after(async () => { for (const socket of sockets) socket.terminate(); await relay.close(); });
  async function connect(data, prove = true) {
    const socket = new WebSocket(`ws://127.0.0.1:${relay.http.address().port}/ws`); sockets.push(socket);
    socket.messages = []; socket.closedCode = null;
    socket.on('message', raw => {
      const message = JSON.parse(raw); socket.messages.push(message);
      if (prove && message.type === 'challenge') socket.send(JSON.stringify({ type: 'prove', nonce: message.nonce }));
    });
    socket.on('close', code => { socket.closedCode = code; }); socket.on('error', () => {});
    await once(socket, 'open'); socket.send(JSON.stringify(data)); return socket;
  }
  async function online(id, overrides = {}) {
    const socket = await connect(hello(id, policy, overrides));
    await waitFor(() => socket.messages.some(m => m.type === 'welcome'));
    return socket;
  }
  return { policy, relay, connect, online, history, verifications };
}

test('LRP-01 anonymous, old, wrong-policy and unauthorized server access rejected before identity work', async t => {
  const f = await fixture(t);
  for (const [overrides, code] of [ [{protocol: 2},4002], [{policyHash:'b'.repeat(64)},1008],
      [{uuid:uuid(99)},4004], [{serverId:serverId('ungranted.example')},4004] ]) {
    const socket = await f.connect(hello(1, f.policy, overrides));
    await waitFor(() => socket.closedCode !== null); assert.equal(socket.closedCode, code);
    assert.ok(!socket.messages.some(m => m.type === 'welcome'));
  }
  assert.equal(f.verifications.length, 0);
});

test('LRP-01 identity mismatch and replayed challenge are rejected', async t => {
  const f = await fixture(t, async () => ({ uuid:uuid(2), name:'Audit2' }));
  const impostor = await f.connect(hello(1, f.policy));
  await waitFor(() => impostor.closedCode !== null); assert.equal(impostor.closedCode,4005);
  const badNonce = await f.connect(hello(2, f.policy), false);
  await waitFor(() => badNonce.messages.length > 0);
  badNonce.send(JSON.stringify({type:'prove',nonce:'b'.repeat(64)}));
  await waitFor(() => badNonce.closedCode !== null); assert.equal(badNonce.closedCode,1008);
  assert.equal(f.relay.stats().peers,0);
});

test('LRP-01/04 verified server/world isolation, no UUID eviction, hidden position withdrawal, revocation', async t => {
  const f = await fixture(t);
  const a = await f.online(1), b = await f.online(2);
  const otherServer = await f.online(3, {serverId:serverId('server2.example')});
  const otherWorld = await f.online(4, {world:'b'.repeat(64)});
  assert.equal(b.messages.find(m => m.type === 'welcome').identityVerified,true);
  a.send(JSON.stringify(pose)); await waitFor(() => b.messages.some(m => m.type === 'pose'));
  const mirrored = b.messages.find(m => m.type === 'pose');
  assert.equal(mirrored.x,12.35); assert.equal(mirrored.yaw,350); assert.equal(mirrored.pitch,90);
  assert.equal(mirrored.name,'Audit1'); assert.equal(mirrored.equipment.MAINHAND,pose.equipment.MAINHAND);
  assert.ok(!otherServer.messages.some(m => m.type === 'pose')); assert.ok(!otherWorld.messages.some(m => m.type === 'pose'));
  const duplicate = await f.connect(hello(1,f.policy));
  await waitFor(() => duplicate.closedCode !== null); assert.equal(duplicate.closedCode,4006); assert.equal(a.closedCode,null);
  a.send(JSON.stringify({type:'withdraw'})); await waitFor(() => b.messages.some(m => m.type === 'pose_stale'));
  const withdrawn = b.messages.find(m => m.type === 'pose_stale');
  assert.deepEqual(Object.keys(withdrawn).sort(),['type','uuid']);
  const late = await f.online(3);
  assert.ok(!late.messages.find(m => m.type === 'welcome').peers.find(p => p.uuid === uuid(1)).x);
  await delay(100); a.send(JSON.stringify(pose));
  await waitFor(() => b.messages.filter(m => m.type === 'pose').length === 2);
  a.send(JSON.stringify({...pose,invisible:true,x:123456}));
  await waitFor(() => b.messages.filter(m => m.type === 'pose_stale').length === 2);
  assert.ok(!JSON.stringify(b.messages).includes('123456'));
  const input = policyInput(); input.servers[0].members = input.servers[0].members.filter(m => m.uuid !== uuid(1));
  f.relay.updatePolicy(parsePolicy(input)); await waitFor(() => a.closedCode !== null);
  assert.ok(f.history.some(e => e.event === 'peer_left' && e.uuid === uuid(1)));
  assert.ok(f.history.filter(e => e.event === 'peer_joined').every(e => e.identityVerified && e.accessAuthorized));
  assert.ok(!f.history.some(e => 'x' in e || 'token' in e || 'nonce' in e));
});

test('LRP-03 malformed poses drop without disconnect and cannot issue roster control messages', async t => {
  const f = await fixture(t), a = await f.online(1), b = await f.online(2);
  a.send(JSON.stringify({type:'pose',x:0,dimension:'minecraft:overworld'}));
  a.send(JSON.stringify(pose)); await waitFor(() => b.messages.some(m => m.type === 'pose'));
  assert.equal(a.closedCode,null);
  a.send(JSON.stringify({type:'leave',uuid:uuid(2)})); await delay(30);
  assert.ok(!b.messages.some(m => m.type === 'leave'));
  for(let i=0;i<6;i++) a.send(JSON.stringify({type:'pose',x:0}));
  await waitFor(() => a.closedCode !== null); assert.equal(a.closedCode,1008);
});

test('LRP-02 frame flooding disconnects at bounded input rate', async t => {
  const f = await fixture(t), a = await f.online(1);
  for(let i=0;i<100;i++) a.send(JSON.stringify({type:'withdraw'}));
  await waitFor(() => a.closedCode !== null); assert.equal(a.closedCode,1008);
});

test('WSS trust headers cannot be supplied by arbitrary direct clients; no unsafe policy fallback', () => {
  const policy = parsePolicy(policyInput());
  const req = {socket:{remoteAddress:'127.0.0.1'},headers:{host:'relay.example','x-forwarded-proto':'https'}};
  assert.ok(secureProxyRequest(req,policy));
  assert.ok(!secureProxyRequest({...req,socket:{remoteAddress:'127.0.0.2'}},policy));
  assert.ok(!secureProxyRequest({...req,headers:{...req.headers,'x-forwarded-proto':'http'}},policy));
  assert.throws(() => parsePolicy({...policyInput(),operator:''}));
  assert.throws(() => parsePolicy({...policyInput(),publicUrl:'ws://relay.example/ws'}));
  assert.throws(() => parsePolicy({...policyInput(),trustedProxyAddresses:['*']}));
});

test('audit history expires while idle and on restart; other files remain untouched', () => {
  const dir = temporary(); let time = Date.now();
  try {
    const audit = createAuditLog(dir,{retentionDays:7,now:()=>time});
    audit({event:'peer_joined',uuid:uuid(1)}); assert.equal(logs(dir).length,1);
    time += 7*86400000+1; audit.prune(); assert.equal(logs(dir).length,0);
    audit({event:'peer_joined',uuid:uuid(2)}); time += 7*86400000+1;
    createAuditLog(dir,{retentionDays:7,now:()=>time}); assert.equal(logs(dir).length,0);
  } finally { cleanup(dir); }
});

test('session proof binds both nonces, account, server, world and Relay destination', () => {
  const policy = parsePolicy(policyInput()), h = hello(1,policy), n = 'd'.repeat(64);
  const hash = sessionHash(h,n,policy.publicUrl);
  for(const key of ['uuid','serverId','world','clientNonce']) assert.notEqual(sessionHash({...h,[key]:h[key]+'1'},n,policy.publicUrl),hash);
  assert.notEqual(sessionHash(h,'e'.repeat(64),policy.publicUrl),hash);
  assert.notEqual(sessionHash(h,n,'wss://other.example/ws'),hash);
  assert.match(hash,/^-?[a-f0-9]+$/);
});


test('offline policy CLI creates isolated group, grants expiring UUID permission and revokes it', () => {
  const dir = temporary(), file = join(dir, 'policy.json');
  try {
    writeFileSync(file, JSON.stringify({...policyInput(), servers:[]}));
    const run = (...args) => JSON.parse(execFileSync(process.execPath, ['policy-admin.mjs', ...args], {cwd:relayRoot,encoding:'utf8'}));
    assert.equal(run('validate',file).valid,true);
    const groupId = run('server',file,'server1.example').groups[0].id;
    assert.match(groupId,/^[a-f0-9-]{36}$/);
    run('grant',file,groupId,uuid(1),'1');
    let policy = parsePolicy(JSON.parse(readFileSync(file,'utf8')));
    assert.ok(policy.servers.get(serverId('server1.example')).members.get(uuid(1)) > Date.now());
    run('revoke',file,groupId,uuid(1));
    policy = parsePolicy(JSON.parse(readFileSync(file,'utf8')));
    assert.equal(policy.servers.get(serverId('server1.example')).members.size,0);
  } finally { cleanup(dir); }
});

test('group codes require 8-64 ASCII alphanumeric characters with letters and digits', async t => {
  const f = await fixture(t, null, environmentPolicy({}).parsed);
  for(const groupCode of [undefined, '', 'Abc1234', 'abcdefgh', '12345678', 'Abc1234!', 'Ａbc12345', 'a1'.repeat(33)]) {
    const socket = await f.connect(hello(1,f.policy,{groupCode}));
    await waitFor(() => socket.closedCode !== null); assert.equal(socket.closedCode,4004);
  }
  assert.equal(f.verifications.length,0);
  await f.online(1,{groupCode:'Abcd1234'});
  await f.online(2,{groupCode:'a1'.repeat(32)});
});

test('group, server and world all isolate roster and poses; denial revokes authenticated UUID', async t => {
  const f = await fixture(t, null, environmentPolicy({}).parsed);
  const a = await f.online(1), b = await f.online(2);
  const different = [await f.online(3,{groupCode:'shared1234567890'}),
    await f.online(4,{serverId:serverId('other.example')}), await f.online(5,{world:'b'.repeat(64)})];
  for(const socket of different) assert.deepEqual(socket.messages.find(m=>m.type==='welcome').peers,[]);
  a.send(JSON.stringify(pose)); await waitFor(()=>b.messages.some(m=>m.type==='pose'));
  await delay(40);
  for(const socket of different) assert.ok(!socket.messages.some(m=>['pose','join'].includes(m.type)));
  const joined = JSON.stringify(b.messages), history = JSON.stringify(f.history);
  assert.ok(!joined.includes('Shared1234567890') && !history.includes('Shared1234567890'));
  assert.ok(!joined.includes('groupHash') && !history.includes('groupHash'));
  f.relay.updatePolicy(environmentPolicy({RELAY_DENIED_UUIDS:uuid(1)}).parsed);
  await waitFor(()=>a.closedCode!==null); assert.equal(b.closedCode,null);
  const denied = await f.connect(hello(1,f.policy)); await waitFor(()=>denied.closedCode!==null);
  assert.equal(denied.closedCode,4007);
  f.relay.updatePolicy({...environmentPolicy({}).parsed,blocked:true});
  await waitFor(()=>b.closedCode!==null);
});

test('group mode rejects claimed UUID mismatch and binds group to proof', async t => {
  const f = await fixture(t, async () => ({uuid:uuid(2),name:'Audit2'}), environmentPolicy({}).parsed);
  const wrongIdentity = await f.connect(hello(1,f.policy));
  await waitFor(()=>wrongIdentity.closedCode!==null); assert.equal(wrongIdentity.closedCode,4005);
  const h = {...hello(1,f.policy), groupHash:'a'.repeat(64)};
  assert.notEqual(sessionHash(h,'d'.repeat(64),h.relayUrl),sessionHash({...h,groupHash:'b'.repeat(64)},'d'.repeat(64),h.relayUrl));
});
test('retention is server-managed and clients need no retention setting', async t => {
  const policy = environmentPolicy({RELAY_RETENTION_DAYS:'12'}).parsed;
  const f = await fixture(t, null, policy);
  const a = await f.online(1,{retentionDays:undefined});
  assert.equal(a.messages.find(m=>m.type==='challenge').retentionDays,12);
  await f.online(2,{retentionDays:1});
});
