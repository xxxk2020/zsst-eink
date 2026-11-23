#!/usr/bin/env python3
"""Generate the original, font-free example PNG using only Python's standard library."""
from pathlib import Path
import binascii
import struct
import zlib

width, height = 240, 100
rows = []
for y in range(height):
    row = bytearray()
    for x in range(width):
        frame = (10 <= x <= 229 and (9 <= y <= 11 or 88 <= y <= 90)) or (9 <= y <= 90 and (9 <= x <= 11 or 228 <= x <= 230))
        circles = any(17 ** 2 <= (x - cx) ** 2 + (y - 50) ** 2 <= 20 ** 2 for cx in (60, 120, 180))
        row.append(0 if frame or circles else 255)
    rows.append(b"\x00" + row)
def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", binascii.crc32(kind + data) & 0xffffffff)
png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 0, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(b"".join(rows), 9)) + chunk(b"IEND", b"")
target = Path(__file__).resolve().parents[1] / "examples/source/data/papers/images/diagram.png"
target.parent.mkdir(parents=True, exist_ok=True)
target.write_bytes(png)
print("Original example image generated")
