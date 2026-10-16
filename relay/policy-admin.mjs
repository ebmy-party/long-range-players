import { readFileSync, writeFileSync, renameSync, unlinkSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { resolve } from 'node:path';
import { parsePolicy, uuidPattern } from './access-policy.mjs';

// Offline administrator tool. It never contacts Minecraft, Mojang or the Relay.
const [command, file, ...args] = process.argv.slice(2);
try {
  if (!file) throw new Error('Usage: node policy-admin.mjs validate|server|grant|revoke <policy-file> <arguments>');
  const path = resolve(file), input = JSON.parse(readFileSync(path, 'utf8'));
  if (command === 'validate') {
    const policy = parsePolicy(input);
    console.log(JSON.stringify({ valid: true, groups: input.servers.length, policyHash: policy.policyHash }));
  } else {
    if (command === 'server') {
      if (args.length !== 1 || !args[0].trim()) throw new Error('server requires one Minecraft connection address');
      input.servers.push({ id: randomUUID(), addresses: [args[0].trim().toLowerCase()], members: [] });
    } else {
      const [group, uuid, daysText] = args;
      const server = input.servers.find(server => server.id === group);
      if (!server || !uuidPattern.test(uuid)) throw new Error('Unknown group or invalid UUID');
      if (command === 'grant') {
        const days = Number(daysText);
        if (!Number.isInteger(days) || days < 1 || days > 365 || args.length !== 3) throw new Error('grant requires group UUID, player UUID, and 1..365 days');
        server.members = server.members.filter(member => member.uuid !== uuid);
        server.members.push({ uuid, expiresAt: Date.now() + days * 86400000 });
      } else if (command === 'revoke' && args.length === 2) server.members = server.members.filter(member => member.uuid !== uuid);
      else throw new Error('Unknown command or invalid arguments');
    }
    parsePolicy(input);
    const temp = path + '.' + randomUUID() + '.tmp';
    try { writeFileSync(temp, JSON.stringify(input, null, 2) + '\n', { mode: 0o600, flag: 'wx' }); renameSync(temp, path); }
    finally { try { unlinkSync(temp); } catch (error) { if (error.code !== 'ENOENT') throw error; } }
    console.log(JSON.stringify({ updated: true, groups: input.servers.map(server => ({ id: server.id, memberCount: server.members.length })) }));
  }
} catch (error) {
  // No raw JSON or credentials in diagnostics.
  console.error('Policy operation failed: check file permissions, metadata, UUIDs and arguments.'); process.exitCode = 1;
}
