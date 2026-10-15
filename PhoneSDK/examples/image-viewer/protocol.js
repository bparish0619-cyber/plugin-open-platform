import { t, localizedError } from './i18n.js';
import { WIDTH, HEIGHT, FRAME_BYTES } from './image.js';
import { compressLz4 } from './lz4.js';
export const REQUEST_CHANNEL = 0x4956;
export const RESPONSE_CHANNEL = 0x4957;
export const OP = Object.freeze({ hello: 1, begin: 2, chunk: 3, commit: 4, beginLz4: 5 });
export function hash(bytes) {
  let value = 2166136261;
  for (const byte of bytes) value = Math.imul(value ^ byte, 16777619) >>> 0;
  return value;
}
export function packet(op, sequence, session, offset = 0, body = new Uint8Array()) {
  const bytes = new Uint8Array(12 + body.length);
  const view = new DataView(bytes.buffer);
  bytes[0] = 1; bytes[1] = op;
  view.setUint16(2, sequence, true); view.setUint32(4, session, true);
  view.setUint32(8, offset, true); bytes.set(body, 12);
  return bytes;
}
export function parseAck(bytes) {
  if (!(bytes instanceof Uint8Array) || bytes.length !== 20 || bytes[0] !== 1) return null;
  const v = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  return { op: bytes[1] & 127, response: !!(bytes[1] & 128), sequence: v.getUint16(2, true),
    session: v.getUint32(4, true), offset: v.getUint32(8, true), status: bytes[12], lz4: !!(bytes[13] & 1),
    width: v.getUint16(14, true), height: v.getUint16(16, true), chunkSize: v.getUint16(18, true) };
}

export class ImageTransfer {
  constructor(gm, { timeout = 5000, retries = 2 } = {}) {
    this.gm = gm; this.timeout = timeout; this.retries = retries; this.pending = null;
    this.off = gm.plugin.onMessage(({ channel, data }) => {
      if (channel !== RESPONSE_CHANNEL) return;
      const ack = parseAck(data), p = this.pending;
      if (ack?.response && p && ack.session === p.session && ack.sequence === p.sequence && ack.op === p.op) p.resolve(ack);
    });
  }
  close() { this.cancel(); this.off(); }
  cancel() { this.pending?.reject(localizedError('error.transferCancelled')); }
  async request(bytes, signal) {
    const v = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    for (let attempt = 0; ; attempt++) {
      signal?.throwIfAborted();
      let timer, abort;
      try {
        const ack = await new Promise((resolve, reject) => {
          this.pending = { op: bytes[1], sequence: v.getUint16(2, true), session: v.getUint32(4, true), resolve, reject };
          abort = () => reject(signal.reason ?? localizedError('error.abort'));
          signal?.addEventListener('abort', abort, { once: true });
          timer = setTimeout(() => { const e = localizedError('error.ackTimeout'); e.code = 'ACK_TIMEOUT'; reject(e); }, this.timeout);
          // Register the waiter BEFORE sending; some Hosts deliver a synchronous reply.
          Promise.resolve().then(() => this.gm.plugin.sendMessage(REQUEST_CHANNEL, bytes)).catch(reject);
        });
        if (ack.status > 1 || (ack.status === 1 && bytes[1] !== OP.commit)) {
          throw localizedError('error.rejected', { status: ack.status, reason: () => t(ack.status >= 2 && ack.status <= 6 ? `reject.${ack.status}` : 'reject.unknown') });
        }
        return ack;
      } catch (error) {
        if (error.code !== 'ACK_TIMEOUT' || attempt >= this.retries) throw error;
      } finally {
        clearTimeout(timer); signal?.removeEventListener('abort', abort); this.pending = null;
      }
    }
  }
  async send(frame, onProgress = () => {}, signal, preparedLz4) {
    if (this.busy) throw localizedError('error.busy');
    if (!(frame instanceof Uint8Array) || frame.length !== FRAME_BYTES) throw localizedError('error.frame');
    this.busy = true;
    const session = crypto.getRandomValues(new Uint32Array(1))[0] || 1;
    try {
      const info = await this.request(packet(OP.hello, 0, session), signal);
      if (info.width !== WIDTH || info.height !== HEIGHT || info.chunkSize < 1 || info.chunkSize > 4096) {
        throw localizedError('error.companion');
      }
      const compressed = preparedLz4 ?? compressLz4(frame);
      const useLz4 = info.lz4 && compressed.length < frame.length;
      const payload = useLz4 ? compressed : frame;
      const stats = { encoding: useLz4 ? 'LZ4' : 'GRAY4', originalBytes: frame.length, wireBytes: payload.length,
        reasonKey: useLz4 ? null : (!info.lz4 ? 'reason.noLz4' : 'reason.noSaving'),
        get reason() { return this.reasonKey ? t(this.reasonKey) : ''; } };
      const started = performance.now();
      onProgress(0, payload.length, stats);
      const begin = new Uint8Array(useLz4 ? 16 : 8), v = new DataView(begin.buffer);
      v.setUint16(0, WIDTH, true); v.setUint16(2, HEIGHT, true); v.setUint32(4, hash(frame), true);
      if (useLz4) { v.setUint32(8, payload.length, true); v.setUint32(12, frame.length, true); }
      const start = await this.request(packet(useLz4 ? OP.beginLz4 : OP.begin, 1, session, 0, begin), signal);
      if (start.offset !== 0) throw localizedError('error.beginAck');
      let sequence = 2;
      for (let offset = 0; offset < payload.length; offset += info.chunkSize) {
        const end = Math.min(payload.length, offset + info.chunkSize);
        const ack = await this.request(packet(OP.chunk, sequence++, session, offset, payload.subarray(offset, end)), signal);
        if (ack.offset !== end) throw localizedError('error.chunkAck');
        onProgress(end, payload.length, stats);
      }
      const result = await this.request(packet(OP.commit, sequence, session, payload.length), signal);
      if (result.offset !== payload.length) throw localizedError('error.incompleteAck');
      return { ...result, ...stats, elapsedMs: performance.now() - started };
    } finally { this.busy = false; }
  }
}
