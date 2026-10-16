import { isIP } from 'node:net';
export const normalizeIP = value => value?.startsWith('::ffff:') ? value.slice(7) : value;
export function secureProxyRequest(req, policy) {
  return policy.trustedProxyAddresses.has(normalizeIP(req.socket.remoteAddress))
    && req.headers['x-forwarded-proto'] === 'https'
    && req.headers.host === new URL(policy.publicUrl).host;
}
export function clientAddress(req) {
  const address = req.headers['x-real-ip'];
  return typeof address === 'string' && isIP(address) ? normalizeIP(address) : normalizeIP(req.socket.remoteAddress);
}
