import { createReadStream, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { createInterface } from 'node:readline';

const directory = process.argv.slice(2).find(arg => !arg.startsWith('--')) ?? process.env.LOG_DIR ?? './data';
const players = new Map();
let joins = 0, leaves = 0, seconds = 0, latestSample = null, invalidLines = 0;
for (const name of readdirSync(directory).filter(n => /^access-.*\.jsonl$/.test(n)).sort()) {
  const lines = createInterface({ input: createReadStream(join(directory, name)), crlfDelay: Infinity });
  for await (const line of lines) {
    let entry;
    try { entry = JSON.parse(line); } catch { invalidLines++; continue; }
    if (entry.event === 'usage_sample') latestSample = entry;
    if (entry.event === 'peer_joined') {
      joins++;
      const player = players.get(entry.uuid) ?? { uuid: entry.uuid, name: entry.name, sessions: 0, firstSeen: entry.time, lastSeen: entry.time };
      player.name = entry.name; player.sessions++; player.lastSeen = entry.time; players.set(entry.uuid, player);
    }
    if (entry.event === 'peer_left') { leaves++; seconds += entry.durationSeconds ?? 0; }
  }
}
console.log(JSON.stringify({ scope: 'retained log files only', uniquePlayers: players.size, joins, leaves,
  completedSessionSeconds: seconds, latestSample, invalidLines,
  ...(process.argv.includes('--users') ? { players: [...players.values()] } : {}) }, null, 2));
