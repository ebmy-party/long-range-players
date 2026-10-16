import { randomBytes, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { WebSocketServer, WebSocket } from 'ws';
import { permitted, sessionHash, uuidPattern, CONSENT_REVISION, sha256 } from './access-policy.mjs';
import { normalizePose } from './pose.mjs';

export const PROTOCOL = 4;
const hex = /^[a-f0-9]{64}$/;
const dimension = /^[a-z0-9_.-]+:[a-z0-9_][a-z0-9_./-]*$/;
/** Injectable verifier is used by in-process tests; production always supplies the official verifier. */
export function createRelay({ policy: initialPolicy, verifyIdentity, log, secureRequest, remoteAddress = req => req.socket.remoteAddress ?? 'unknown', now = Date.now }) {
  let policy = initialPolicy, auditFailed = false, verifying = 0;
  const rooms = new Map(), sessions = new Set(), attempts = new Map();
  const audit = (event, fields = {}) => {
    try { log({ time: new Date(now()).toISOString(), event, ...fields }); auditFailed = false; }
    catch { auditFailed = true; }
  };
  const http = createServer((req, res) => {
    res.writeHead(req.url === '/healthz' ? (auditFailed ? 503 : 200) : 404, { 'cache-control': 'no-store' });
    res.end(req.url === '/healthz' ? (auditFailed ? 'audit unavailable' : 'ok') : '');
  });
  http.headersTimeout = 10000; http.requestTimeout = 10000; http.maxConnections = 1024;
  const wss = new WebSocketServer({ noServer: true, maxPayload: 4096, perMessageDeflate: false });
  function allowAttempt(ip) {
    const time = now();
    for (const [key, entry] of attempts) if (time - entry.at > 60000) attempts.delete(key);
    if (!attempts.has(ip) && attempts.size >= 1024) return false;
    const entry = attempts.get(ip) ?? { tokens: 64, at: time };
    entry.tokens = Math.min(64, entry.tokens + Math.max(0, time - entry.at) / 1000); entry.at = time;
    attempts.set(ip, entry);
    if (entry.tokens < 1) return false; entry.tokens--; return true;
  }
  http.on('upgrade', (req, socket, head) => {
    const ip = remoteAddress(req);
    if (req.url !== '/ws' || !secureRequest(req, policy) || wss.clients.size >= 512 || !allowAttempt(ip)) {
      socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\nContent-Length: 0\r\n\r\n'); return;
    }
    wss.handleUpgrade(req, socket, head, ws => wss.emit('connection', ws, req));
  });
  function send(ws, value) {
    if (ws.readyState !== WebSocket.OPEN) return;
    if (ws.bufferedAmount > 262144) { ws.terminate(); return; }
    ws.send(JSON.stringify(value), error => { if (error) ws.terminate(); });
  }
  function broadcast(room, except, value) { if (!room) return; for (const peer of room.values()) if (peer !== except) send(peer.ws, value); }
  const publicPeer = peer => ({ uuid: peer.uuid, name: peer.name, dimension: peer.dimension, ...peer.pose });
  function clearPose(peer) {
    if (!peer.pose) return;
    peer.pose = undefined;
    const room = rooms.get(peer.room);
    if (room) broadcast(room, null, { type: 'pose_stale', uuid: peer.uuid });
  }
  function leave(peer) {
    const room = rooms.get(peer.room);
    if (room?.get(peer.uuid) !== peer) return;
    room.delete(peer.uuid); sessions.delete(peer);
    broadcast(room, null, { type: 'leave', uuid: peer.uuid });
    if (!room.size) rooms.delete(peer.room);
    audit('peer_left', { connectionId: peer.connectionId, uuid: peer.uuid, name: peer.name,
      room: peer.room.slice(0, 36), durationSeconds: Math.round((now() - peer.joinedAt) / 1000), activePeers: sessions.size });
  }
  wss.on('connection', (ws, req) => {
    const connectionId = randomUUID(), address = remoteAddress(req);
    let peer, hello, nonce, state = 'hello', strikes = 0, alive = true;
    let frameTokens = 80, lastFrame = now();
    const controller = new AbortController();
    const timeout = setTimeout(() => reject(1008, 'authentication timeout'), 30000);
    function reject(code, reason) {
      if (state === 'closed') return;
      state = 'closed'; controller.abort(); clearTimeout(timeout);
      if (peer) leave(peer);
      // No arbitrary packet text, credentials, coordinates, or challenge material is logged.
      audit('connection_rejected', { connectionId, reason });
      ws.close(code, reason); setTimeout(() => ws.terminate(), 1000).unref();
    }
    function bad() { if (++strikes >= 8) reject(1008, 'invalid message limit'); }
    function frame() {
      const time = now(); frameTokens = Math.min(80, frameTokens + Math.max(0, time - lastFrame) * 0.04); lastFrame = time;
      if (frameTokens < 1) { reject(1008, 'rate limit'); return false; }
      frameTokens--; return true;
    }
    ws.on('ping', frame);
    ws.on('pong', () => { if (frame()) alive = true; });
    ws.on('message', async (raw, binary) => {
      if (state === 'closed' || !frame()) return;
      if (binary) { bad(); return; }
      let msg;
      try { msg = JSON.parse(raw.toString()); } catch { bad(); return; }
      if (!msg || typeof msg !== 'object' || Array.isArray(msg)) { bad(); return; }
      if (state === 'hello') {
        if (msg.protocol !== PROTOCOL) { reject(4002, 'protocol update required'); return; }
        if (msg.type !== 'hello' || typeof msg.uuid !== 'string' || !uuidPattern.test(msg.uuid)
            || typeof msg.name !== 'string' || !/^[A-Za-z0-9_]{1,16}$/.test(msg.name)
            || !['serverId','world','clientNonce'].every(key => typeof msg[key] === 'string' && hex.test(msg[key]))
            || typeof msg.dimension !== 'string' || msg.dimension.length > 128 || !dimension.test(msg.dimension)
            || msg.consent !== CONSENT_REVISION || typeof msg.policyHash !== 'string' || !hex.test(msg.policyHash)
            || (policy.mode !== 'group' && msg.policyHash !== policy.policyHash)) { reject(1008, 'invalid consent or hello'); return; }
        let groupHash, relayUrl = policy.publicUrl;
        if (policy.mode === 'group') {
          if (policy.deniedUUIDs.has(msg.uuid)) { reject(4007, 'account denied'); return; }
          if (typeof msg.groupCode !== 'string' || !/^(?=.*[A-Za-z])(?=.*[0-9])[A-Za-z0-9]{8,64}$/.test(msg.groupCode)) { reject(4004, 'invalid group code'); return; }
          if (msg.retentionDays !== policy.retentionDays) { reject(4008, 'retention consent mismatch'); return; }
          try {
            if (typeof msg.relayUrl !== 'string' || msg.relayUrl.length > 512) throw new Error();
            const url = new URL(msg.relayUrl);
            if (url.protocol !== 'wss:' || url.username || url.password || url.search || url.hash || url.pathname !== '/ws') throw new Error();
            relayUrl = url.href;
          } catch { reject(1008, 'invalid destination'); return; }
          groupHash = sha256(msg.groupCode);
        }
        if (!permitted(policy, { ...msg, groupHash }, now())) { reject(4004, 'access not granted'); return; }
        // Copy only validated fields. Token/room/unknown input never controls authorization.
        hello = { uuid: msg.uuid, name: msg.name, serverId: msg.serverId, world: msg.world,
          dimension: msg.dimension, clientNonce: msg.clientNonce, policyHash: policy.policyHash, groupHash, relayUrl };
        nonce = randomBytes(32).toString('hex'); state = 'challenge';
        send(ws, { type: 'challenge', protocol: PROTOCOL, nonce, retentionDays: policy.retentionDays }); return;
      }
      if (state === 'challenge') {
        if (msg.type !== 'prove' || msg.nonce !== nonce || verifying >= 16) { reject(1008, 'invalid proof state'); return; }
        state = 'verifying'; verifying++;
        const verifyTimeout = setTimeout(() => controller.abort(), 7000);
        let identity;
        try { identity = await verifyIdentity(hello.name, sessionHash(hello, nonce, hello.relayUrl), controller.signal); }
        catch { identity = null; }
        finally { verifying--; clearTimeout(verifyTimeout); }
        if (state !== 'verifying' || ws.readyState !== WebSocket.OPEN) return;
        const grant = permitted(policy, hello, now());
        if (controller.signal.aborted || !identity || identity.uuid !== hello.uuid || identity.name.toLowerCase() !== hello.name.toLowerCase()
            || !grant || hello.policyHash !== policy.policyHash) {
          reject(4005, 'identity verification failed'); return;
        }
        const roomId = grant.id + ':' + hello.world;
        let room = rooms.get(roomId);
        if (!room) { if (rooms.size >= 100) { reject(1013, 'room limit'); return; } room = new Map(); rooms.set(roomId, room); }
        // A new connection never evicts an existing authenticated UUID.
        if (room.has(identity.uuid)) { reject(4006, 'already connected'); return; }
        if (room.size >= 64) { reject(1013, 'peer limit'); return; }
        peer = { ws, uuid: identity.uuid, name: identity.name, dimension: hello.dimension, room: roomId,
          serverId: hello.serverId, policyHash: hello.policyHash, groupHash: hello.groupHash, connectionId, joinedAt: now(), lastPose: 0,
          expiresAt: Math.min(grant.members.get(identity.uuid), now() + 30 * 60 * 1000) };
        room.set(peer.uuid, peer); sessions.add(peer); state = 'online'; clearTimeout(timeout);
        audit('peer_joined', { connectionId, uuid: peer.uuid, name: peer.name, remoteAddress: address,
          room: sha256(roomId).slice(0, 16), identityVerified: true, accessAuthorized: true, activePeers: sessions.size });
        send(ws, { type: 'welcome', protocol: PROTOCOL, identityVerified: true, peers: [...room.values()].filter(p => p !== peer).map(publicPeer) });
        broadcast(room, peer, { type: 'join', peer: publicPeer(peer) }); return;
      }
      if (state !== 'online') { bad(); return; }
      if (!sessions.has(peer)) { reject(4004, 'access revoked'); return; }
      if (msg.type === 'withdraw' || (msg.type === 'pose' && msg.invisible === true)) { clearPose(peer); return; }
      const pose = normalizePose(msg);
      if (!pose) { bad(); return; }
      const time = now(); if (time - peer.lastPose < 80) return;
      peer.lastPose = time; peer.dimension = pose.dimension; peer.pose = pose;
      broadcast(rooms.get(peer.room), peer, { type: 'pose', uuid: peer.uuid, name: peer.name, ...pose });
    });
    ws.on('close', () => { state = 'closed'; controller.abort(); clearTimeout(timeout); if (peer) leave(peer); });
    ws.on('error', () => ws.terminate());
    ws.check = () => { if (ws.readyState !== WebSocket.OPEN) return; if (!alive) ws.terminate(); else { alive = false; ws.ping(); } };
  });
  const interval = setInterval(() => {
    for (const peer of sessions) {
      const grant = permitted(policy, peer, now());
      if (!grant || now() >= peer.expiresAt || peer.policyHash !== policy.policyHash || !peer.room.startsWith(grant.id + ':')) {
        leave(peer); peer.ws.terminate(); continue;
      }
      if (peer.pose && now() - peer.lastPose > 5000) clearPose(peer);
    }
  }, 1000);
  const heartbeat = setInterval(() => { for (const ws of wss.clients) ws.check?.(); }, 20000);
  return {
    http, updatePolicy(next) {
      const changed = policy.policyHash !== next.policyHash; policy = next;
      if (changed) for (const ws of wss.clients) ws.terminate();
      for (const peer of sessions) {
        const grant = permitted(policy, peer, now());
        if (!grant || !peer.room.startsWith(grant.id + ':')) { leave(peer); peer.ws.terminate(); }
      }
    },
    async close() { clearInterval(interval); clearInterval(heartbeat); for (const ws of wss.clients) ws.terminate(); await new Promise(resolve => http.close(resolve)); },
    stats: () => ({ peers: sessions.size, rooms: rooms.size, verifying })
  };
}
