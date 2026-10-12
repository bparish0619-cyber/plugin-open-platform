# Offline Opus playback dependency

Pinned source: `opusscript@0.1.1`, libopus 1.4, from the npm registry tarball
`https://registry.npmjs.org/opusscript/-/opusscript-0.1.1.tgz`.
Tarball SHA-1: `322c126b753ddbf9b0b209d0060a432805b6a34d`.
Upstream: https://github.com/abalabahaha/opusscript

`opusscript_native_nasm.js` is the unmodified upstream pure JavaScript build.
`csp-bindings.js` replaces its Emscripten embind invoker generator with an
equivalent closure, so initializing bindings does not call the Function
constructor under the App's CSP. Only the invoker generator is changed; the
compiled libopus codec remains unchanged. The browser ESM also shadows Node's
process global to avoid CommonJS loader calls during tests.

Run `node tools/sync-audio-playback.mjs` from PhoneSDK after editing the shared
`examples/shared/recording-playback.js` or adapter. It generates decoder/player
copies and both licenses for talking-pet and audio-capture-lab only.
Tests compare decoded PCM with the unmodified upstream build and initialize
the adapted decoder with both string code generation and WASM disabled.

The handler's C++ `_decode` output is NOT a packed Int16Array: each PCM byte
is stored in a uint16 slot, low byte followed by high byte. Allocate four heap
bytes per decoded sample and reconstruct each signed PCM sample from those two
slots before writing a 16-bit WAV. The public OpusScript wrapper does this via
`Buffer.from(HEAPU16.subarray(...))`. Its `_encode` input uses the same expanded
byte representation. Test fixtures must use that layout too; comparing heap
slots directly can falsely validate very quiet, corrupt audio. Regression tests
check reference PCM, signed samples, sine-wave amplitude/RMS and frequency.
