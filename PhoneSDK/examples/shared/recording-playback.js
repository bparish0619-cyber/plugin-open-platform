// Shared by the two audio examples; distributed into their vendor directories.
// Keep the decoder local: old iOS needs PCM and the host does not allow WASM/eval.
let decoderModule;
const OGG_OPUS = 'audio/ogg; codecs=opus';

function checkAbort(signal) {
  if (signal?.aborted) throw new DOMException('Playback cancelled', 'AbortError');
}

export async function opusRecordingToWav(recording, { signal } = {}) {
  checkAbort(signal);
  const { data, frameLengths } = recording ?? {};
  if (!(data instanceof Uint8Array) || !Array.isArray(frameLengths) || !frameLengths.length) {
    throw new TypeError('Opus recording data is required');
  }
  let total = 0;
  for (const length of frameLengths) {
    if (!Number.isInteger(length) || length < 1 || length > 3828) throw new TypeError('Invalid Opus frame length');
    total += length;
  }
  if (total !== data.length) throw new TypeError('Opus data does not match frame boundaries');
  // The capture contract is 16 kHz, mono, 20 ms per packet (320 samples).
  const pcmBytes = frameLengths.length * 320 * 2;
  if (pcmBytes > 64 * 1024 * 1024) throw new RangeError('Recording is too large for WAV playback');
  decoderModule ??= import('./opus-decoder.esm.js').then(({ default: createModule }) => createModule().ready);
  const module = await decoderModule;
  checkAbort(signal);
  const decoder = new module.OpusScriptHandler(16000, 1, 2049);
  const input = module._malloc(3828);
  // OpusScriptHandler writes each PCM byte into a separate uint16 slot.
  // A decoded int16 sample therefore occupies FOUR bytes in its output heap.
  const output = module._malloc(1920 * 4);
  try {
    const wav = new Uint8Array(44 + pcmBytes);
    const view = new DataView(wav.buffer);
    const text = (at, value) => wav.set(new TextEncoder().encode(value), at);
    text(0, 'RIFF'); view.setUint32(4, 36 + pcmBytes, true); text(8, 'WAVE');
    text(12, 'fmt '); view.setUint32(16, 16, true); view.setUint16(20, 1, true);
    view.setUint16(22, 1, true); view.setUint32(24, 16000, true);
    view.setUint32(28, 32000, true); view.setUint16(32, 2, true); view.setUint16(34, 16, true);
    text(36, 'data'); view.setUint32(40, pcmBytes, true);
    let offset = 0;
    for (let index = 0; index < frameLengths.length; index += 1) {
      checkAbort(signal);
      const length = frameLengths[index];
      module.HEAPU8.set(data.subarray(offset, offset + length), input);
      const samples = decoder._decode(input, length, output);
      if (samples !== 320) throw new Error(`Invalid Opus packet: expected 320 samples, decoded ${samples}`);
      for (let sample = 0; sample < samples; sample += 1) {
        const byteIndex = (output >> 1) + sample * 2;
        const low = module.HEAPU16[byteIndex] & 255;
        const high = module.HEAPU16[byteIndex + 1] & 255;
        view.setUint16(44 + index * 640 + sample * 2, low | (high << 8), true);
      }
      offset += length;
      // Let UI/stop requests run during decoding on older phones.
      if (index % 32 === 31) await new Promise(resolve => setTimeout(resolve, 0));
    }
    checkAbort(signal);
    return new Blob([wav], { type: 'audio/wav' });
  } finally {
    module.OpusScriptHandler.destroy_handler(decoder);
    module._free(input);
    module._free(output);
  }
}

export function createRecordingPlayer(audio, toOgg) {
  let operation;
  let url;
  const release = () => {
    if (url) URL.revokeObjectURL(url);
    url = undefined;
  };
  const stop = () => {
    operation?.abort();
    audio.pause();
    audio.removeAttribute('src');
    audio.load();
    release();
  };
  audio.addEventListener('ended', release);
  audio.addEventListener('error', release);
  return {
    stop,
    async play(recording) {
      stop();
      const controller = operation = new AbortController();
      const { signal } = controller;
      const playBlob = async blob => {
        checkAbort(signal);
        release();
        url = URL.createObjectURL(blob);
        audio.src = url;
        await audio.play();
        checkAbort(signal);
      };
      try {
        if (audio.canPlayType(OGG_OPUS)) {
          try {
            await playBlob(toOgg(recording));
            return 'ogg-opus';
          } catch (error) {
            checkAbort(signal);
            // A positive capability answer is advisory. Retry only format errors;
            // permission/gesture failures must remain visible to the caller.
            if (error?.name !== 'NotSupportedError' && audio.error?.code !== 4) throw error;
          }
        }
        await playBlob(await opusRecordingToWav(recording, { signal }));
        return 'wav-pcm';
      } catch (error) {
        if (operation === controller) stop();
        throw error;
      }
    },
  };
}
