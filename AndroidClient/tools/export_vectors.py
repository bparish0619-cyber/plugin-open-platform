#!/usr/bin/env python3
"""Export reference GM vectors from GlassSDK/docs/examples/bluetooth_wire.py.

Each output line: name<TAB>service<TAB>command<TAB>body_hex<TAB>frame_limit<TAB>frame_hex,frame_hex...
GmCodecTest re-encodes every body and compares the frames byte for byte.
"""
import importlib.util
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location(
    'bluetooth_wire', ROOT / 'GlassSDK' / 'docs' / 'examples' / 'bluetooth_wire.py')
wire = importlib.util.module_from_spec(spec)
spec.loader.exec_module(wire)

out = sys.stdout
for name, service, command, body, _ in wire.examples():
    for limit in (20, 64, 244, 512):
        frames = wire.frames(1, service, command, body, limit)
        out.write('\t'.join([name, str(service), str(command), body.hex() or '-', str(limit),
                             ','.join(frame.hex() for frame in frames)]) + '\n')
# Fragmented 600-byte application message from PROTOCOL.md.
body = wire.application_body(1, b'A' * 600)
for limit in (20, 512):
    frames = wire.frames(1, 15, 40, body, limit)
    out.write('\t'.join(['App message 600 bytes', '15', '40', body.hex(), str(limit),
                         ','.join(frame.hex() for frame in frames)]) + '\n')
