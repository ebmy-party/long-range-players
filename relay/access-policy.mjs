import { isIP } from 'node:net';
import { createHash } from 'node:crypto';
import { readFileSync, statSync } from 'node:fs';

export const CONSENT_REVISION = 'lrp-privacy-2026-10-v3';
export const sha256 = text => createHash('sha256').update(text).digest('hex');
export const serverId = address => sha256(address.trim().toLowerCase());
export const uuidPattern = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
export function sessionHash(hello, nonce, publicUrl) {
  const text = ['long-range-players/session/v5', hello.clientNonce, nonce, hello.uuid, hello.serverId, hello.world, publicUrl, hello.groupHash ?? ''].join('\n');
  const hex = createHash('sha1').update(text).digest('hex');
  let n = BigInt('0x' + hex);
  if (n >= (1n << 159n)) n -= 1n << 160n;
  return n < 0 ? '-' + (-n).toString(16) : n.toString(16);
}
export function parsePolicy(input) {
  const url = new URL(input.publicUrl);
  if (url.protocol !== 'wss:' || url.username || url.password || url.search || url.hash || url.pathname !== '/ws') throw new Error('Invalid public WSS URL');
  if (input.revision !== CONSENT_REVISION || typeof input.operator !== 'string' || !input.operator.trim()
      || typeof input.contact !== 'string' || !input.contact.trim()) throw new Error('Operator/contact/consent policy must be configured');
  if (input.operator.length > 100 || input.contact.length > 512 || /[\r\n]/.test(input.operator + input.contact)) throw new Error('Invalid operator metadata');
  if (!Number.isInteger(input.retentionDays) || input.retentionDays < 1 || input.retentionDays > 30) throw new Error('Invalid retention days');
  if (!Array.isArray(input.servers) || input.servers.length > 100 || !Array.isArray(input.trustedProxyAddresses)
      || input.trustedProxyAddresses.length > 16) throw new Error('Invalid access policy');
  const servers = new Map(), groups = new Set();
  if (input.trustedProxyAddresses.some(ip => typeof ip !== 'string' || !isIP(ip))) throw new Error('Trusted proxy must be a literal IP address');
  for (const entry of input.servers) {
    if (typeof entry.id !== 'string' || !uuidPattern.test(entry.id) || groups.has(entry.id)
        || !Array.isArray(entry.addresses) || !entry.addresses.length || entry.addresses.length > 16
        || !Array.isArray(entry.members) || entry.members.length > 5000) throw new Error('Invalid server grant');
    groups.add(entry.id);
    const members = new Map();
    for (const member of entry.members) {
      if (!uuidPattern.test(member.uuid) || members.has(member.uuid) || !Number.isSafeInteger(member.expiresAt)) throw new Error('Invalid member grant');
      members.set(member.uuid, member.expiresAt);
    }
    for (const address of entry.addresses) {
      if (typeof address !== 'string' || !address.trim() || address.length > 255) throw new Error('Invalid server address');
      const id = serverId(address);
      if (servers.has(id)) throw new Error('Duplicate server address');
      servers.set(id, { id: entry.id, members });
    }
  }
  const policyHash = sha256([input.revision, url.href, input.operator.trim(), input.contact, input.retentionDays].join('\n'));
  return { publicUrl: url.href, operator: input.operator.trim(), contact: input.contact, policyHash,
    revision: input.revision, retentionDays: input.retentionDays, servers,
    trustedProxyAddresses: new Set(input.trustedProxyAddresses) };
}
export function readPolicy(path) {
  if (statSync(path).size > 1_048_576) throw new Error('Access policy too large');
  return parsePolicy(JSON.parse(readFileSync(path, 'utf8')));
}
export function permitted(policy, hello, now = Date.now()) {
  if (policy.blocked) return null;
  if (policy.mode === 'group') {
    if (policy.deniedUUIDs.has(hello.uuid) || typeof hello.groupHash !== 'string' || !/^[a-f0-9]{64}$/.test(hello.groupHash)) return null;
    return { id: sha256(hello.serverId + ':' + hello.groupHash), members: new Map([[hello.uuid, Infinity]]) };
  }
  const server = policy.servers.get(hello.serverId);
  return server && (server.members.get(hello.uuid) ?? 0) > now ? server : null;
}

/** Fixed official endpoint, no caller-controlled URL or redirect; no access token reaches Relay. */
export async function verifyMinecraftIdentity(name, hash, signal) {
  const url = new URL('https://sessionserver.mojang.com/session/minecraft/hasJoined');
  url.searchParams.set('username', name); url.searchParams.set('serverId', hash);
  const response = await fetch(url, { signal, redirect: 'error' });
  if (response.status !== 200 || !response.body) return null;
  const reader = response.body.getReader(); let bytes = 0; const chunks = [];
  try {
    while (true) {
      const { done, value } = await reader.read(); if (done) break;
      bytes += value.length; if (bytes > 65_536) throw new Error('Identity response too large'); chunks.push(value);
    }
  } finally { await reader.cancel(); }
  const profile = JSON.parse(Buffer.concat(chunks).toString('utf8'));
  if (!/^[a-f0-9]{32}$/i.test(profile.id) || !/^[A-Za-z0-9_]{1,16}$/.test(profile.name)) return null;
  const id = profile.id.toLowerCase();
  return { uuid: `${id.slice(0,8)}-${id.slice(8,12)}-${id.slice(12,16)}-${id.slice(16,20)}-${id.slice(20)}`, name: profile.name };
}
