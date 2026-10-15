import { localizedError } from './i18n.js';
// Use the same Host-managed file selection/streaming path as Novel Reader.
export async function pickImageFile(gm) {
  const picked = await gm.files.pick({ extensions: ['png', 'jpg', 'jpeg', 'bmp', 'webp'], allowMultiple: false });
  return picked.files?.[0] ?? null;
}

export function throwIfAborted(signal) {
  if (signal?.aborted) throw signal.reason ?? localizedError('error.abort');
}

export function delay(ms, signal) {
  throwIfAborted(signal);
  return new Promise((resolve, reject) => {
    const finish = () => { signal?.removeEventListener('abort', abort); resolve(); };
    const timer = setTimeout(finish, ms);
    const abort = () => {
      clearTimeout(timer); signal.removeEventListener('abort', abort);
      reject(signal.reason ?? localizedError('error.abort'));
    };
    signal?.addEventListener('abort', abort, { once: true });
  });
}

export async function waitForFilePickerReturn(gm, signal) {
  // Native picker may return its result just before the App's resume event.
  for (let attempt = 0; attempt < 41; attempt++) {
    throwIfAborted(signal);
    const { state } = await gm.runtime.getLifecycleState();
    throwIfAborted(signal);
    if (state === 'running') return;
    if (state !== 'suspended') throw localizedError('error.stopped');
    if (attempt < 40) await delay(100, signal);
  }
  throw localizedError('error.notResumed');
}

export async function openImageRead(gm, fileId, signal) {
  const retries = [100, 300]; // Same narrowly scoped ticket retry as Novel Reader.
  for (let attempt = 0; ; attempt++) {
    throwIfAborted(signal);
    try { return await gm.files.openRead(fileId, { signal }); }
    catch (error) {
      if (error?.code !== 'INTERNAL_ERROR' ||
          !/Host file stream failed: HTTP 404\b/u.test(error.message) ||
          attempt >= retries.length || signal?.aborted) throw error;
      await delay(retries[attempt], signal);
    }
  }
}

export function imageMime(bytes) {
  if (bytes.length >= 8 && [137,80,78,71,13,10,26,10].every((b,i) => bytes[i] === b)) return 'image/png';
  if (bytes.length >= 3 && bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255) return 'image/jpeg';
  if (bytes.length >= 2 && bytes[0] === 66 && bytes[1] === 77) return 'image/bmp';
  if (bytes.length >= 12 && [82,73,70,70].every((b,i) => bytes[i] === b) &&
      [87,69,66,80].every((b,i) => bytes[i+8] === b)) return 'image/webp';
  throw localizedError('error.format');
}
