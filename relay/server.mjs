import { verifyMinecraftIdentity } from './access-policy.mjs';
import { createRelay } from './secure-relay.mjs';
import { createAuditLog } from './audit-log.mjs';
import { environmentPolicy } from './environment-policy.mjs';
const port = Number(process.env.PORT ?? 8080);
if (!Number.isInteger(port) || port < 0 || port > 65535) throw new Error('Invalid PORT');
const loadPolicy = () => environmentPolicy(process.env).parsed;
let policy = loadPolicy();
const audit = createAuditLog(process.env.LOG_DIR ?? './data', { retentionDays: policy.retentionDays });
// TLS is terminated outside this HTTP service. Never trust client-supplied forwarding headers.
const relay = createRelay({ policy, verifyIdentity: verifyMinecraftIdentity, log: audit,
  secureRequest: () => true, remoteAddress: req => req.socket.remoteAddress ?? 'unknown' });
let reloadFailed = false;
const maintenance = setInterval(() => {
  try {
    const next = loadPolicy();
    relay.updatePolicy(next); policy = next; audit.setRetentionDays(next.retentionDays); audit.prune();
    if (reloadFailed) console.log(JSON.stringify({ event: 'policy_restored' }));
    reloadFailed = false;
  } catch {
    relay.updatePolicy({ ...policy, blocked: true });
    if (!reloadFailed) console.error(JSON.stringify({ event: 'policy_or_audit_failed', action: 'all_access_denied' }));
    reloadFailed = true;
  }
}, 1000);
relay.http.listen(port, () => console.log(JSON.stringify({ event: 'relay_listening', port, protocol: 5, version: '0.10.2' })));
async function shutdown() { clearInterval(maintenance); await relay.close(); }
process.once('SIGTERM', shutdown); process.once('SIGINT', shutdown);
