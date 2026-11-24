#!/usr/bin/env python3
"""Ikony Apace Mobile — prosto z oficjalnego favicon repozytorium Apace."""
from PIL import Image
import os

SRC = "/home/z/my-project/work/apace-src/images/Apace_Favicon.png"
OUT = "/home/z/my-project/apacemobile/res"

src = Image.open(SRC).convert("RGBA")

# launcher: wszystkie gęstości dpi
sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
for q, s in sizes.items():
    d = os.path.join(OUT, f"mipmap-{q}")
    os.makedirs(d, exist_ok=True)
    src.resize((s, s), Image.LANCZOS).save(os.path.join(d, "ic_launcher.png"))

# ikonka powiadomienia (status bar): biała sylwetka logo (kolor → biel, alpha zachowana)
os.makedirs(os.path.join(OUT, "drawable"), exist_ok=True)
for s in (24, 48):
    sil = src.resize((s, s), Image.LANCZOS).convert("RGBA")
    px = sil.load()
    for y in range(s):
        for x in range(s):
            r, g, b, a = px[x, y]
            px[x, y] = (255, 255, 255, a)
    name = "ic_stat.png" if s == 24 else "ic_stat48.png"
    sil.save(os.path.join(OUT, "drawable", name))

print("ikony z favicon Apace OK")
