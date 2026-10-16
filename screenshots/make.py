#!/usr/bin/env python3
"""Shelf (shoka) in the Kompakt stock icon style (see ../mudita-style/style.md):
81 grid, the shared ring, glyph stroke 2.5 round, ~38 units across, centred; black only.
Three books standing on a shelf and a fourth leaning against them."""
import math, subprocess, os
os.chdir(os.path.dirname(os.path.abspath(__file__)))
RING = open('ring.d').read().strip()
SW = 2.5

def box(x0, y0, w, h, r=1.5, n=4):
    pts = []
    for (cx, cy, a0) in [(x0+w-r, y0+r, -90), (x0+w-r, y0+h-r, 0), (x0+r, y0+h-r, 90), (x0+r, y0+r, 180)]:
        for i in range(n+1):
            t = math.radians(a0 + 90*i/n); pts.append((cx + r*math.cos(t), cy + r*math.sin(t)))
    return pts

def rot(pts, px, py, deg):
    a = math.radians(deg); ca, sa = math.cos(a), math.sin(a)
    return [(px + (x-px)*ca - (y-py)*sa, py + (x-px)*sa + (y-py)*ca) for x, y in pts]

def poly(pts): return 'M' + ' L'.join(f'{x:.3f},{y:.3f}' for x, y in pts) + ' Z'
def line(a, b): return f'M{a[0]:.3f},{a[1]:.3f} L{b[0]:.3f},{b[1]:.3f}'

base = 0.0  # shelf top, before centring
books = [box(0, base-30, 7.5, 30), box(7.5, base-25, 9, 25), box(16.5, base-33, 7, 33)]
# the fourth leans right against the third, its foot on the shelf
TH = 20; x0 = 23.5 + 27*math.sin(math.radians(TH)) + 0.6
lean = rot(box(x0, base-27, 8, 27), x0, base, -TH)
bands = [line((0, base-21), (7.5, base-21)), line((16.5, base-7), (23.5, base-7))]
shelf = line((-3, base), (x0 + 8*math.cos(math.radians(TH)) + 2, base))

allpts = [p for b in books + [lean] for p in b] + [(-3, base), (x0 + 8*math.cos(math.radians(TH)) + 2, base)]
xs = [p[0] for p in allpts]; ys = [p[1] for p in allpts]
K = 0.84  # scale to the stock 36-40 unit glyph
cx = (min(xs) + max(xs)) / 2; cy = (min(ys) + max(ys)) / 2
print(f'glyph ink box {(max(xs)-min(xs))*K+SW:.1f} x {(max(ys)-min(ys))*K+SW:.1f} (stock 36-40)')
import re
def mv(d): return re.sub(r'(-?\d+\.\d+),(-?\d+\.\d+)', lambda m: f'{40.5+(float(m.group(1))-cx)*K:.3f},{40.5+(float(m.group(2))-cy)*K:.3f}', d)
shapes = [mv(poly(b)) for b in books + [lean]]
strokes = [mv(s) for s in bands + [shelf]]

st = f'stroke="#000" stroke-width="{SW}" stroke-linecap="round" stroke-linejoin="round"'
svg = f'<svg xmlns="http://www.w3.org/2000/svg" width="81" height="81" viewBox="0 0 81 81">\n<path d="{RING}" fill="#000" fill-rule="evenodd"/>\n'
svg += ''.join(f'<path d="{s}" fill="#fff" {st}/>\n' for s in shapes)
svg += ''.join(f'<path d="{s}" fill="none" {st}/>\n' for s in strokes) + '</svg>\n'
open('shoka.svg', 'w').write(svg)
a = 'android:strokeColor="#FF000000" android:strokeWidth="2.5" android:strokeLineCap="round" android:strokeLineJoin="round"'
xml = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Shelf: launcher icon in the Mudita Kompakt stock style (81 grid, ring r=19.25 x 2.5,
     glyph stroke 2.5, black on transparent): three books standing on a shelf, a fourth
     leaning against them. Built by ~/Desktop/icons/shoka/make.py. -->
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
xml += ''.join(f'    <path android:pathData="{s}" android:fillColor="#FFFFFFFF" {a}/>\n' for s in shapes)
xml += ''.join(f'    <path android:pathData="{s}" android:fillColor="#00000000" {a}/>\n' for s in strokes)
xml += '</vector>\n'
open('ic_launcher_mudita.xml', 'w').write(xml)
for px in (512, 144, 48):
    subprocess.run(['rsvg-convert', '-w', str(px), '-h', str(px), '-b', 'white', 'shoka.svg', '-o', f'shoka-{px}.png'], check=True)
