#!/usr/bin/env python3
"""Shelf (shoka): books on a shelf, second drawing. Three upright books of different widths
and heights and a fourth leaning on them, taller in the frame than the first try.
Kompakt stock style: 81 grid, shared ring, stroke 2.5 round, black only."""
import math, subprocess, os
os.chdir(os.path.dirname(os.path.abspath(__file__)))
RING = open('ring.d').read().strip()
SW = 2.5
def rrect(x0, y0, w, h, r=2.0, n=4):
    pts = []
    for (cx, cy, a0) in [(x0+w-r, y0+r, -90), (x0+w-r, y0+h-r, 0), (x0+r, y0+h-r, 90), (x0+r, y0+r, 180)]:
        for i in range(n+1):
            t = math.radians(a0 + 90*i/n); pts.append((cx + r*math.cos(t), cy + r*math.sin(t)))
    return pts
def rot(pts, px, py, deg):
    a = math.radians(deg); c, s = math.cos(a), math.sin(a)
    return [(px + (x-px)*c - (y-py)*s, py + (x-px)*s + (y-py)*c) for x, y in pts]
def poly(p): return 'M' + ' L'.join(f'{x:.3f},{y:.3f}' for x, y in p) + ' Z'
def path(p): return 'M' + ' L'.join(f'{x:.3f},{y:.3f}' for x, y in p)

S = 59.0                       # shelf line
K = 0.86                       # scale to the stock 36-44 unit glyph
b1 = rrect(22.0, S-32, 9, 32)  # tall, wide
b2 = rrect(31.0, S-26, 7, 26)  # short, thin
b3 = rrect(38.0, S-34, 8, 34)  # tallest
TH = 22; h4 = 29; w4 = 8.5
x4 = 46.0 + h4*math.sin(math.radians(TH)) + 0.4
b4 = rot(rrect(x4, S-h4, w4, h4), x4, S, -TH)   # leans left onto b3, foot on the shelf
bands = [path([(22.0, S-25), (31.0, S-25)]), path([(22.0, S-21), (31.0, S-21)]),
         path([(38.0, S-8), (46.0, S-8)])]
right = max(x for x, _ in b4) + 1.5
shelf = path([(20.0, S), (right-0.5, S)])
allx = [20.0, right-0.5]; cx = (min(allx) + max(allx)) / 2; dx = 40.5 - cx
top = min(y for _, y in b3); dy = 40.5 - (top + S) / 2
def mv(d):
    import re
    return re.sub(r'(-?\d+\.\d+),(-?\d+\.\d+)', lambda m: f'{40.5+(float(m.group(1))-cx)*K:.3f},{40.5+(float(m.group(2))+dy-40.5)*K:.3f}', d)
fills = [mv(poly(b)) for b in (b1, b2, b3, b4)]
lines = [mv(p) for p in bands + [shelf]]
print(f'ink box {(right-20.5)*K+SW:.1f} x {(S-top)*K+SW:.1f}')
st = f'stroke="#000" stroke-width="{SW}" stroke-linecap="round" stroke-linejoin="round"'
svg = f'<svg xmlns="http://www.w3.org/2000/svg" width="81" height="81" viewBox="0 0 81 81">\n<path d="{RING}" fill="#000" fill-rule="evenodd"/>\n'
svg += ''.join(f'<path d="{s}" fill="#fff" {st}/>\n' for s in fills)
svg += ''.join(f'<path d="{s}" fill="none" {st}/>\n' for s in lines) + '</svg>\n'
open('shoka.svg', 'w').write(svg)
a = 'android:strokeColor="#FF000000" android:strokeWidth="2.5" android:strokeLineCap="round" android:strokeLineJoin="round"'
xml = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Shelf: launcher icon in the Mudita Kompakt stock style (81 grid, ring r=19.25 x 2.5,
     glyph stroke 2.5, black on transparent): three books standing on a shelf, a fourth
     leaning against them. Built by screenshots/make.py. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="81dp"
    android:height="81dp"
    android:viewportWidth="81"
    android:viewportHeight="81">
    <path
        android:pathData="{RING}"
        android:fillColor="#FF000000"
        android:fillType="evenOdd"/>
'''
xml += ''.join(f'    <path android:pathData="{s}" android:fillColor="#FFFFFFFF" {a}/>\n' for s in fills)
xml += ''.join(f'    <path android:pathData="{s}" android:fillColor="#00000000" {a}/>\n' for s in lines) + '</vector>\n'
open('ic_launcher_mudita.xml', 'w').write(xml)
subprocess.run(['rsvg-convert', '-w', '512', '-h', '512', '-b', 'white', 'shoka.svg', '-o', 'icon-512.png'], check=True)
