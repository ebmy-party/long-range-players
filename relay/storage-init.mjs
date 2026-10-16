// Compose-only initialization of the named data volume; never follows links.
import * as fs from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
export function prepareStorage(root, io = fs) {
  const paths = [];
  function scan(path) {
    if (paths.length >= 10000) throw new Error('Data volume has too many entries; inspect it before updating');
    const s = io.lstatSync(path);
    if (s.isSymbolicLink() || (!s.isDirectory() && !s.isFile()) || (!s.isDirectory() && s.nlink !== 1))
      throw new Error('Unsupported entry in data volume');
    paths.push([path, s.isDirectory()]);
    if (s.isDirectory()) for (const name of io.readdirSync(path)) scan(path + '/' + name);
  }
  scan(root);
  for (const [path, directory] of paths) {
    io.chownSync(path, 1000, 1000); io.chmodSync(path, directory ? 0o700 : 0o600);
  }
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) prepareStorage('/app/data');
