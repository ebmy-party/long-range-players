import { appendFileSync, mkdirSync, readdirSync, statSync, unlinkSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { randomUUID } from 'node:crypto';

export function createAuditLog(directory, { maxBytes = 10 * 1024 * 1024, maxFiles = 30, retentionDays = 7, now = Date.now } = {}) {
  const root = resolve(directory);
  mkdirSync(root, { recursive: true, mode: 0o700 });
  let path, bytes = 0, serial = 0;
  const pattern = /^access-([\dTZ-]+)-\d+-\d+-[a-f0-9-]{36}\.jsonl$/;
  function created(name) {
    const stamp = name.match(pattern)[1];
    return Date.parse(stamp.replace(/T(\d+)-(\d+)-(\d+)-(\d+)Z$/, 'T$1:$2:$3.$4Z'));
  }
  function prune() {
    const files = readdirSync(root).filter(name => pattern.test(name)).sort();
    const cutoff = now() - retentionDays * 86400000;
    const remove = new Set(files.filter(name => created(name) <= cutoff));
    const keep = files.filter(name => !remove.has(name));
    // A restarted writer can sort before older files created in the same millisecond.
    // Never prune the current writable file to enforce the file-count limit.
    const candidates = keep.filter(name => join(root, name) !== path);
    for (const name of candidates.slice(0, Math.max(0, keep.length - maxFiles))) remove.add(name);
    for (const name of remove) {
      const target = join(root, name); unlinkSync(target);
      if (target === path) { path = undefined; bytes = 0; }
    }
  }
  function rotate() {
    const stamp = new Date(now()).toISOString().replace(/[:.]/g, '-');
    path = join(root, `access-${stamp}-${process.pid}-${String(serial++).padStart(4, '0')}-${randomUUID()}.jsonl`);
    appendFileSync(path, '', { mode: 0o600 }); bytes = statSync(path).size; prune();
  }
  const append = record => {
    prune(); const line = JSON.stringify(record) + '\n';
    if (Buffer.byteLength(line) > maxBytes) throw new Error('Audit record exceeds limit');
    if (!path || bytes + Buffer.byteLength(line) > maxBytes) rotate();
    appendFileSync(path, line, { mode: 0o600 }); bytes += Buffer.byteLength(line);
  };
  append.prune = prune;
  append.setRetentionDays = days => { retentionDays = days; };
  prune(); return append;
}
