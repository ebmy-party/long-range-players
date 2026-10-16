import { createHash } from 'node:crypto';
import { uuidPattern } from './access-policy.mjs';
export function environmentPolicy(env) {
  const retentionDays = Number(env.RELAY_RETENTION_DAYS ?? 7);
  if (!Number.isInteger(retentionDays) || retentionDays < 1 || retentionDays > 30) throw new Error('Invalid RELAY_RETENTION_DAYS');
  const deniedUUIDs = new Set();
  for (const text of (env.RELAY_DENIED_UUIDS ?? '').split(',')) {
    if (!text.trim()) continue;
    const raw = text.trim().toLowerCase().replaceAll('-', '');
    if (!/^[a-f0-9]{32}$/.test(raw)) throw new Error('Invalid RELAY_DENIED_UUIDS');
    const uuid = `${raw.slice(0,8)}-${raw.slice(8,12)}-${raw.slice(12,16)}-${raw.slice(16,20)}-${raw.slice(20)}`;
    if (!uuidPattern.test(uuid)) throw new Error('Invalid RELAY_DENIED_UUIDS');
    deniedUUIDs.add(uuid);
    if (deniedUUIDs.size > 5000) throw new Error('Too many denied UUIDs');
  }
  const policyHash = createHash('sha256').update('group-policy-v1\n' + retentionDays).digest('hex');
  return { parsed: { mode: 'group', deniedUUIDs, retentionDays, policyHash } };
}
