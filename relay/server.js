// KHOJ relay: carries already-encrypted mesh packets between phones that have mobile data.
// It never sees plaintext (packets are sealed with the crew key on the phone) and keeps nothing on disk.
// Usage: node server.js [port]          Test: node --test
'use strict';
const http = require('node:http');

const MAX_PACKET = 256;          // mesh packets are at most 200 bytes
const KEEP_PER_CREW = 500;
const KEEP_MS = 10 * 60 * 1000;  // packets older than 10 minutes are useless for finding people
const MAX_WAIT_S = 25;
const RATE = { windowMs: 10_000, max: 60 }; // uploads per IP per window

function createRelay() {
  const crews = new Map();   // crewHex -> { next, packets: [{ seq, at, data }], waiters: Set }
  const rate = new Map();    // ip -> { start, count }

  function crew(id) {
    let c = crews.get(id);
    if (!c) { c = { next: 1, packets: [], waiters: new Set() }; crews.set(id, c); }
    return c;
  }

  function prune(c, now) {
    while (c.packets.length && (c.packets.length > KEEP_PER_CREW || now - c.packets[0].at > KEEP_MS)) c.packets.shift();
  }

  function allowed(ip, now) {
    const r = rate.get(ip);
    if (!r || now - r.start > RATE.windowMs) { rate.set(ip, { start: now, count: 1 }); return true; }
    return ++r.count <= RATE.max;
  }

  function since(c, after) {
    return { next: c.next, packets: c.packets.filter(p => p.seq > after).map(p => p.data.toString('base64')) };
  }

  function send(res, status, body) {
    res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' });
    res.end(body === undefined ? '' : JSON.stringify(body));
  }

  return http.createServer((req, res) => {
    const url = new URL(req.url, 'http://relay');
    if (url.pathname === '/healthz') return send(res, 200, { ok: true });
    const m = url.pathname.match(/^\/v1\/crews\/([0-9a-f]{8})\/packets$/);
    if (!m) return send(res, 404, { error: 'not found' });
    const id = m[1];
    const now = Date.now();

    if (req.method === 'POST') {
      if (!allowed(req.socket.remoteAddress || '?', now)) return send(res, 429, { error: 'slow down' });
      const chunks = []; let size = 0; let tooBig = false;
      req.on('data', d => { size += d.length; if (size > MAX_PACKET) { tooBig = true; req.destroy(); } else chunks.push(d); });
      req.on('end', () => {
        if (tooBig) return;
        const data = Buffer.concat(chunks);
        // Minimal header check: KHOJ magic 'K', version 1, and the crew tag in the header must match the URL.
        if (data.length < 29 || data[0] !== 0x4b || data[1] !== 1 || data.readUInt32BE(12).toString(16).padStart(8, '0') !== id) {
          return send(res, 400, { error: 'not a KHOJ packet for this crew' });
        }
        const c = crew(id);
        c.packets.push({ seq: c.next++, at: now, data });
        prune(c, now);
        for (const w of c.waiters) w();
        send(res, 204);
      });
      return;
    }

    if (req.method === 'GET') {
      const c = crew(id);
      prune(c, now);
      const after = Number(url.searchParams.get('after') || 0);
      const wait = Math.min(Number(url.searchParams.get('wait') || 0), MAX_WAIT_S);
      const ready = since(c, after);
      if (ready.packets.length || wait <= 0) return send(res, 200, ready);
      // Long poll: answer as soon as a packet arrives or the wait runs out.
      let done = false;
      const finish = () => { if (done) return; done = true; c.waiters.delete(finish); clearTimeout(timer); send(res, 200, since(c, after)); };
      const timer = setTimeout(finish, wait * 1000);
      c.waiters.add(finish);
      req.on('close', () => { if (!done) { done = true; c.waiters.delete(finish); clearTimeout(timer); } });
      return;
    }
    send(res, 405, { error: 'method not allowed' });
  });
}

module.exports = { createRelay };

if (require.main === module) {
  const port = Number(process.argv[2] || process.env.PORT || 8787);
  createRelay().listen(port, () => console.log(`KHOJ relay listening on :${port}`));
}
