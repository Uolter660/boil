# Renders the launcher icon (black tile, white wiggly stroke) as a PNG without third-party libs.
import math, struct, sys, zlib

S = 192
SS = 3  # supersampling
W = S * SS

def stroke_pts():
    pts = []
    for i in range(400):
        u = i / 399
        x = 0.22 + 0.56 * u
        y = 0.5 + 0.13 * math.sin(u * math.pi * 2.6 + 0.4) + 0.007 * math.sin(u * 23)
        pts.append((x * W, y * W))
    return pts

def main(out):
    pts = stroke_pts()
    rad = 0.042 * W
    cov = bytearray(W * W)
    for (px, py) in pts[::2]:
        x0, x1 = int(px - rad), int(px + rad) + 1
        y0, y1 = int(py - rad), int(py + rad) + 1
        r2 = rad * rad
        for y in range(max(0, y0), min(W, y1)):
            dy = y - py
            row = y * W
            for x in range(max(0, x0), min(W, x1)):
                dx = x - px
                if dx * dx + dy * dy <= r2:
                    cov[row + x] = 1
    corner = 0.23 * S
    raw = bytearray()
    for y in range(S):
        raw.append(0)
        for x in range(S):
            # rounded-square tile mask
            cx = min(max(x + 0.5, corner), S - corner)
            cy = min(max(y + 0.5, corner), S - corner)
            inside = (x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2 <= corner * corner
            if not inside:
                raw += b"\x00\x00\x00\x00"
                continue
            c = 0
            for sy in range(SS):
                row = (y * SS + sy) * W + x * SS
                for sx in range(SS):
                    c += cov[row + sx]
            v = round(11 + (244 - 11) * c / (SS * SS))
            raw += bytes((v, v, v, 255))
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", S, S, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b"")
    open(out, "wb").write(png)

main(sys.argv[1])
