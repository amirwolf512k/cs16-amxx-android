#!/usr/bin/env python3
"""Generate a minimal valid WAD3 file (gfx.wad stand-in for the CI test)."""
import struct, sys

def miptex(name, w, h):
    m1 = bytes([0x20]) * (w * h)
    m2 = bytes([0x20]) * ((w // 2) * (h // 2))
    m3 = bytes([0x20]) * 4
    m4 = bytes([0x20])
    pal = bytes([0x80, 0x80, 0x80]) * 256
    return struct.pack('<16sII4I', name.encode(), w, h, 40,
                       40 + len(m1), 40 + len(m1) + len(m2),
                       40 + len(m1) + len(m2) + len(m3)) + m1 + m2 + m3 + m4 + pal

def build_wad3(names):
    lumps = []
    for n in names:
        if n == 'conchars':
            # raw 128x128 console charset, no header (xash oldstyle font)
            lumps.append((n, bytes([0x20]) * 16384))
        else:
            lumps.append((n, miptex(n, 8, 8)))
    body = b''
    direntries = []
    cur = 12
    for name, data in lumps:
        direntries.append(struct.pack('<IIIBBH16s', cur, len(data), len(data), 0x43, 0, 0, name.encode()[:15] + b'\0'))
        body += data
        cur += len(data)
    diroff = cur
    return b'WAD3' + struct.pack('<II', len(lumps), diroff) + body + b''.join(direntries)

if __name__ == '__main__':
    out = sys.argv[1]
    names = sys.argv[2:] or ['{CONS24', 'CONBACK', 'LAMBDA']
    open(out, 'wb').write(build_wad3(names))
    print('wrote %s' % out)
