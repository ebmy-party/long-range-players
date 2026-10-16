import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, sep } from 'node:path';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { environmentPolicy } from '../environment-policy.mjs';
import { permitted, serverId } from '../access-policy.mjs';
import { prepareStorage } from '../storage-init.mjs';

test('volume initialization validates all entries before changing ownership', () => {
  const changed = [];
  const io = {
    lstatSync: p => ({ isSymbolicLink: () => p.endsWith('link'), isDirectory: () => p === '/data', isFile: () => p.endsWith('log'), nlink: 1 }),
    readdirSync: () => ['log', 'link'], chownSync: (...args) => changed.push(args), chmodSync: (...args) => changed.push(args)
  };
  assert.throws(() => prepareStorage('/data', io));
  assert.deepEqual(changed, []);
  io.readdirSync = () => ['log'];
  prepareStorage('/data', io);
  assert.deepEqual(changed, [['/data',1000,1000], ['/data',0o700], ['/data/log',1000,1000], ['/data/log',0o600]]);
});

const env = () => ({ RELAY_RETENTION_DAYS:'7', RELAY_DENIED_UUIDS:'' });
test('environment needs no secret, UUID whitelist or public destination', () => {
  const p = environmentPolicy(env()).parsed;
  assert.equal(p.mode, 'group'); assert.equal(p.deniedUUIDs.size, 0);
  const h = {uuid:'00000000-0000-0000-0000-000000000001',serverId:'a'.repeat(64),groupHash:'b'.repeat(64)};
  assert.ok(permitted(p,h));
  const banned = environmentPolicy({...env(),RELAY_DENIED_UUIDS:'00000000000000000000000000000001'}).parsed;
  assert.equal(permitted(banned,h),null);
  assert.equal(permitted({...p,blocked:true},h),null);
  for(const change of [{RELAY_DENIED_UUIDS:'invalid'}, {RELAY_RETENTION_DAYS:'0'}, {RELAY_RETENTION_DAYS:'31'}])
    assert.throws(() => environmentPolicy({...env(),...change}));
});
test('production server starts from environment alone without a policy file or invite code', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'lrp-env-test-'));
  const child = spawn(process.execPath, [new URL('../server.mjs', import.meta.url).pathname.replace(/^\/(.:\/)/, '$1')], {
    env: { ...process.env, ...env(), POLICY_MODE: 'environment', LOG_DIR: dir, PORT: '0' }, stdio: ['ignore','pipe','pipe'] });
  let output = '', errors = '';
  child.stdout.on('data', c => output += c);
  child.stderr.on('data', c => errors += c);
  try {
    const end = Date.now() + 8000;
    while (!output.includes('relay_listening') && child.exitCode === null && Date.now() < end)
      await new Promise(r => setTimeout(r, 20));
    assert.ok(output.includes('relay_listening'), errors);
    assert.ok(output.includes('"protocol":4'));
  } finally {
    child.kill();
    if (child.exitCode === null) await once(child, 'exit');
    assert.ok(resolve(dir).startsWith(resolve(tmpdir()) + sep + 'lrp-env-test-'));
    rmSync(dir, { recursive: true, force: true });
  }
});
