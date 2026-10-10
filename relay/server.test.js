'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { createRelay } = require('./server');

function packet(crewHex, extra = 0) {
  const b = Buffer.alloc(29 + 10 + extra);
  b[0] = 0x4b; b[1] = 1; b[2] = 4;
  b.writeUInt32BE(parseInt(crewHex, 16), 12);
  return b;
}

test('relay stores, filters and long-polls packets per crew', async (t) => {
  const server = createRelay().listen(0);
  t.after(() => server.close());
  await new Promise(r => server.once('listening', r));
  const base = `http://127.0.0.1:${server.address().port}`;

  // A long poll that is waiting should be answered when a packet arrives.
  const poll = fetch(`${base}/v1/crews/0a0b0c0d/packets?after=0&wait=5`).then(r => r.json());
  await new Promise(r => setTimeout(r, 100));
  assert.strictEqual((await fetch(`${base}/v1/crews/0a0b0c0d/packets`, { method: 'POST', body: packet('0a0b0c0d') })).status, 204);
  const first = await poll;
  assert.strictEqual(first.packets.length, 1);
  assert.strictEqual(Buffer.from(first.packets[0], 'base64')[0], 0x4b);

  // Cursor: nothing new after `next`.
  const after = await (await fetch(`${base}/v1/crews/0a0b0c0d/packets?after=${first.next}`)).json();
  assert.strictEqual(after.packets.length, 0);

  // Wrong crew in header, garbage, and oversize packets are refused.
  assert.strictEqual((await fetch(`${base}/v1/crews/0a0b0c0d/packets`, { method: 'POST', body: packet('11111111') })).status, 400);
  assert.strictEqual((await fetch(`${base}/v1/crews/0a0b0c0d/packets`, { method: 'POST', body: Buffer.from('hello') })).status, 400);
  const big = await fetch(`${base}/v1/crews/0a0b0c0d/packets`, { method: 'POST', body: packet('0a0b0c0d', 400) }).then(r => r.status, () => 'reset');
  assert.ok(big === 'reset' || big >= 400);

  // Other crews do not see this crew's packets.
  const other = await (await fetch(`${base}/v1/crews/99999999/packets`)).json();
  assert.strictEqual(other.packets.length, 0);
  assert.strictEqual((await fetch(`${base}/v1/nope`)).status, 404);
});
