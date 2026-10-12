import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { resolveObjectURL } from 'node:buffer';
import vm from 'node:vm';
import test from 'node:test';
import createModule from '../../examples/talking-pet/vendor/opus-decoder.esm.js';
import { createRecordingPlayer, opusRecordingToWav } from '../../examples/talking-pet/vendor/recording-playback.js';

// Real encoded packets, including non-silent audio; not a mocked decoder.
const module = await createModule().ready;
const encoder = new module.OpusScriptHandler(16000, 1, 2049);
const input = module._malloc(640 * 2);
const output = module._malloc(3828);
const packets = [];
for (let frame = 0; frame < 12; frame += 1) {
  for (let sample = 0; sample < 320; sample += 1) {
    const value = Math.round(12000 * Math.sin(2 * Math.PI * 440 * (frame * 320 + sample) / 16000));
    // The public OpusScript API expands Buffer bytes into uint16 slots.
    module.HEAPU16[(input >> 1) + sample * 2] = value & 255;
    module.HEAPU16[(input >> 1) + sample * 2 + 1] = (value >> 8) & 255;
  }
  const length = encoder._encode(input, 640, output, 320);
  assert.ok(length > 0);
  packets.push(module.HEAPU8.slice(output, output + length));
}
module.OpusScriptHandler.destroy_handler(encoder);
module._free(input); module._free(output);
const data = new Uint8Array(packets.reduce((sum, packet) => sum + packet.length, 0));
let offset = 0;
for (const packet of packets) { data.set(packet, offset); offset += packet.length; }
const recording = { data, frameLengths: packets.map(packet => packet.length) };

class FakeAudio {
  constructor(support, errors = []) {
    this.support = support; this.errors = errors; this.played = []; this.listeners = new Map();
  }
  canPlayType(type) { assert.equal(type, 'audio/ogg; codecs=opus'); return this.support; }
  addEventListener(type, listener) { this.listeners.set(type, listener); }
  pause() {}
  load() { this.error = null; }
  removeAttribute() { this.src = ''; }
  async play() {
    this.played.push(resolveObjectURL(this.src));
    const error = this.errors.shift();
    if (error) throw error;
  }
}
const ogg = () => new Blob(['ogg fixture'], { type: 'audio/ogg; codecs=opus' });

test('WAV fallback decodes real Opus and matches unmodified upstream PCM', async () => {
  const raw = await readFile(new URL('../../third_party/opusscript/opusscript_native_nasm.js', import.meta.url), 'utf8');
  const context = vm.createContext({ console, window: {} });
  vm.runInContext(raw, context);
  const upstream = await vm.runInContext('Module().ready', context);
  const decoder = new upstream.OpusScriptHandler(16000, 1, 2049);
  const packetPtr = upstream._malloc(3828), pcmPtr = upstream._malloc(7680);
  const wav = await opusRecordingToWav(recording);
  const view = new DataView(await wav.arrayBuffer());
  assert.equal(wav.type, 'audio/wav');
  assert.equal(wav.size, 44 + 12 * 640);
  assert.equal(view.getUint16(20, true), 1); // PCM
  assert.equal(view.getUint16(22, true), 1); // mono
  assert.equal(view.getUint32(24, true), 16000);
  assert.equal(view.getUint32(40, true), 12 * 640);
  let energy = 0, peak = 0;
  const decoded = [];
  try {
    packets.forEach((packet, frame) => {
      upstream.HEAPU8.set(packet, packetPtr);
      assert.equal(decoder._decode(packetPtr, packet.length, pcmPtr), 320);
      const reference = Buffer.from(upstream.HEAPU16.subarray(pcmPtr >> 1, (pcmPtr >> 1) + 640));
      for (let sample = 0; sample < 320; sample += 1) {
        const value = view.getInt16(44 + frame * 640 + sample * 2, true);
        // Match the public OpusScript.decode() conversion: Buffer.from(Uint16Array)
        // truncates each heap slot to one byte, then PCM reads pairs as int16.
        assert.equal(value, reference.readInt16LE(sample * 2));
        decoded.push(value);
        energy += value * value;
        peak = Math.max(peak, Math.abs(value));
      }
    });
    const rms = Math.sqrt(energy / decoded.length);
    assert.ok(peak > 9000 && peak < 16000, `PCM peak ${peak}`);
    assert.ok(rms > 6000 && rms < 10000, `PCM RMS ${rms}`);
    let crossings = 0;
    const stable = decoded.slice(640); // skip codec startup delay
    for (let index = 1; index < stable.length; index += 1) {
      if (stable[index - 1] <= 0 && stable[index] > 0) crossings += 1;
    }
    const frequency = crossings * 16000 / stable.length;
    assert.ok(Math.abs(frequency - 440) < 15, `PCM frequency ${frequency}`);
  } finally {
    upstream.OpusScriptHandler.destroy_handler(decoder);
    upstream._free(packetPtr); upstream._free(pcmPtr);
  }
});

test('decoder initializes and decodes under CSP with eval and WASM disabled', async () => {
  const source = await readFile(new URL('../../examples/talking-pet/vendor/opus-decoder.esm.js', import.meta.url), 'utf8');
  const context = vm.createContext({ console, window: {}, packet: packets[0] }, {
    codeGeneration: { strings: false, wasm: false },
  });
  vm.runInContext(source.replace('export default Module;', ''), context);
  await vm.runInContext('Module().ready.then(m => { const h = new m.OpusScriptHandler(16000, 1, 2049); const i = m._malloc(3828), o = m._malloc(3840); m.HEAPU8.set(packet, i); if (h._decode(i, packet.length, o) !== 320) throw new Error("Decode failed"); m.OpusScriptHandler.destroy_handler(h); m._free(i); m._free(o); })', context);
});

test('unsupported formats go directly to WAV; supported formats retain Ogg', async () => {
  for (const support of ['', 'maybe', 'probably']) {
    const audio = new FakeAudio(support);
    const player = createRecordingPlayer(audio, ogg);
    assert.equal(await player.play(recording), support ? 'ogg-opus' : 'wav-pcm');
    assert.equal(audio.played[0].type, support ? 'audio/ogg; codecs=opus' : 'audio/wav');
    const url = audio.src;
    audio.listeners.get('ended')();
    assert.equal(resolveObjectURL(url), undefined);
    player.stop();
  }
});

test('optimistic format support retries WAV on NotSupportedError', async () => {
  const audio = new FakeAudio('probably', [new DOMException('Unsupported', 'NotSupportedError')]);
  const player = createRecordingPlayer(audio, ogg);
  assert.equal(await player.play(recording), 'wav-pcm');
  assert.deepEqual(audio.played.map(blob => blob.type), ['audio/ogg; codecs=opus', 'audio/wav']);
  player.stop();
});

test('permission denial stays an error without retrying another format', async () => {
  for (const support of ['probably', '']) {
    const denied = new DOMException('Permission denied', 'NotAllowedError');
    const audio = new FakeAudio(support, [denied]);
    const player = createRecordingPlayer(audio, ogg);
    await assert.rejects(player.play(recording), error => error === denied);
    assert.equal(audio.played.length, 1);
    assert.equal(audio.src, '');
  }
});

test('stop interrupts a long conversion between decoding batches', async () => {
  const repeated = Array.from({ length: 64 }, () => packets[0]);
  const longRecording = {
    data: new Uint8Array(repeated.flatMap(packet => Array.from(packet))),
    frameLengths: repeated.map(packet => packet.length),
  };
  const audio = new FakeAudio('');
  const player = createRecordingPlayer(audio, ogg);
  const pending = player.play(longRecording);
  setTimeout(() => player.stop(), 0);
  await assert.rejects(pending, { name: 'AbortError' });
  assert.equal(audio.played.length, 0);
  assert.equal(await player.play(recording), 'wav-pcm');
  player.stop();
});

test('stop cancels pending decode and the player can restart', async () => {
  const audio = new FakeAudio('');
  const player = createRecordingPlayer(audio, ogg);
  const pending = player.play(recording);
  player.stop();
  await assert.rejects(pending, { name: 'AbortError' });
  assert.equal(audio.played.length, 0);
  assert.equal(await player.play(recording), 'wav-pcm');
  const url = audio.src;
  player.stop();
  assert.equal(resolveObjectURL(url), undefined);
});

test('invalid frame boundaries/packets fail without poisoning the next decode', async () => {
  await assert.rejects(opusRecordingToWav({ data, frameLengths: [data.length - 1] }), TypeError);
  await assert.rejects(opusRecordingToWav({ data: new Uint8Array([255]), frameLengths: [1] }), /Invalid Opus packet/);
  assert.equal((await opusRecordingToWav(recording)).size, 44 + 12 * 640);
});

test('both plugins ship identical playback/decoder code and licenses', async () => {
  for (const file of ['recording-playback.js', 'opus-decoder.esm.js', 'opus-decoder-LICENSE.txt']) {
    assert.equal(
      await readFile(new URL(`../../examples/talking-pet/vendor/${file}`, import.meta.url), 'utf8'),
      await readFile(new URL(`../../examples/audio-capture-lab/vendor/${file}`, import.meta.url), 'utf8'),
    );
  }
});
