#!/usr/bin/env python3
"""Regenerate every brand asset from branding/logo-source.jpg.

Needs Pillow and numpy (`pip install pillow numpy`) and macOS `iconutil` for
the .icns. Run from anywhere:  python3 branding/make_brand.py

The source is a logo on a dark navy ground. Since 2026-09-21 the outputs are
re-printed in StreamCut's riso world (DESIGN.md): the mark's blue becomes riso
blue ink with grain, and the icon tile is plum card stock with pink and blue
ink blooms instead of the photographed navy. The source file is untouched, so
the original look is one revert of this script away. This writes:
  - the desktop app icons (.icns/.ico/.png) under every legacy file name --
    upstream still looks icons up by colour key, so each key must resolve to
    the one logo, whatever colour a user picked before;
  - the Compose icon drawables used for the window icon;
  - app_brand_mark.png: the mark knocked out of the navy, for the launch
    screen and the wordmark lockup;
  - the retired wordmark PNGs, overwritten with the mark so no code path can
    surface the old artwork.
"""
import pathlib
import subprocess
import tempfile

import numpy as np
from PIL import Image, ImageFilter

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent
ICONS = REPO / "composeApp/src/desktopMain/resources/icons"
DRAWABLE = REPO / "composeApp/src/commonMain/composeResources/drawable"
ICON_KEYS = ["original", "arctic_blue", "emerald", "rose_gold", "copper", "graphite"]

src = Image.open(HERE / "logo-source.jpg").convert("RGB")
px = np.asarray(src).astype(np.float32)
maxch = px.max(axis=2)

# Locate the mark: the navy never gets near a bright channel.
ys, xs = np.where(maxch > 110)
x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
band = np.zeros_like(maxch, dtype=bool)
band[max(0, y0 - 140):y1 + 141, max(0, x0 - 140):x1 + 141] = True
band[max(0, y0 - 40):y1 + 41, max(0, x0 - 40):x1 + 41] = False
bg = np.median(px[band], axis=0)
lo = max(float(np.percentile(maxch[band], 99)) + 22.0, 80.0)
hi = lo + 74.0
print(f"mark bbox x {x0}-{x1} y {y0}-{y1}  bg {bg.round()}  alpha ramp {lo:.0f}-{hi:.0f}")

# --- mark, knocked out of the navy --------------------------------------
pad = 28
crop = px[y0 - pad:y1 + pad + 1, x0 - pad:x1 + pad + 1]
alpha = np.clip((crop.max(axis=2) - lo) / (hi - lo), 0.0, 1.0)
# The ground carries a halftone texture peaking just under the ramp; a 3x3
# median removes those isolated dots without eroding real edges.
alpha = np.asarray(Image.fromarray((alpha * 255).astype(np.uint8)).filter(ImageFilter.MedianFilter(3))) / 255.0
alpha[alpha < 0.12] = 0.0
a3 = alpha[..., None]
# Un-blend the navy from edge pixels so the mark has no dark fringe elsewhere.
colour = np.clip((crop - (1.0 - a3) * bg) / np.where(a3 > 1e-3, a3, 1.0), 0, 255)
mark = Image.fromarray(np.dstack([colour, alpha * 255]).astype(np.uint8), "RGBA")
mark = mark.resize((round(mark.width * 640 / mark.height), 640), Image.LANCZOS)

# --- riso re-print -------------------------------------------------------
STOCK = np.array([20, 16, 25], np.float32)
PAPER = np.array([242, 236, 228], np.float32)
RISO_BLUE = np.array([61, 90, 254], np.float32)
RISO_PINK = np.array([255, 72, 176], np.float32)
rng = np.random.default_rng(1966)

def grain(h, w, strength):
    """Stochastic paper grain in [-strength, strength]."""
    return (rng.random((h, w), dtype=np.float32) - 0.5) * 2.0 * strength

def riso_mark(img):
    """The mark printed in two inks: blue parts in riso blue, the rest paper."""
    a = np.asarray(img).astype(np.float32)
    rgb, alpha = a[..., :3], a[..., 3] / 255.0
    blueness = np.clip((rgb[..., 2] - rgb[..., 0]) / 160.0, 0.0, 1.0)[..., None]
    shade = (rgb.mean(axis=2, keepdims=True) / 255.0) * 0.35 + 0.65
    col = (PAPER * (1 - blueness) + RISO_BLUE * blueness * shade)
    col = np.clip(col + grain(*alpha.shape, 14.0)[..., None], 0, 255)
    # Ink breaks up a little at its edges, like a drum that ran thin.
    speck = rng.random(alpha.shape) < (1.0 - alpha) * 0.9
    alpha = np.where((alpha < 0.95) & speck, alpha * 0.4, alpha)
    return Image.fromarray(np.dstack([col, alpha * 255]).astype(np.uint8), "RGBA")

def bloom_field(size, blooms):
    """Plum stock with screen-blended, eased ink blooms and grain."""
    y, x = np.mgrid[0:size, 0:size].astype(np.float32) / size
    out = np.tile(STOCK, (size, size, 1))
    for ink, cx_, cy_, r, squash, strength in blooms:
        d = np.sqrt((x - cx_) ** 2 + ((y - cy_) / squash) ** 2) / r
        t = np.clip(1.0 - d, 0.0, 1.0)
        a = (t ** 1.6 * strength)[..., None]
        layer = ink * a
        out = 255.0 - (255.0 - out) * (255.0 - layer) / 255.0   # screen blend
    out = out + grain(size, size, 10.0)[..., None]
    return np.clip(out, 0, 255)

riso = riso_mark(mark)

def riso_tile(size):
    field = bloom_field(size, [
        (RISO_PINK, 0.22, 0.2, 1.0, 0.8, 0.66),
        # Blue kept to the far corner: behind the mark it would drown the
        # mark's own blue ink.
        (RISO_BLUE, 0.98, 1.0, 0.55, 0.9, 0.5),
    ])
    tile_img = Image.fromarray(field.astype(np.uint8), "RGB").convert("RGBA")
    m = riso.resize((round(riso.width * size * 0.72 / riso.height), round(size * 0.72)), Image.LANCZOS)
    tile_img.alpha_composite(m, ((size - m.width) // 2 + round(size * 0.02), (size - m.height) // 2))
    return tile_img

# --- squircle icons -----------------------------------------------------
def superellipse_mask(size, n=5.0, ss=4):
    """Continuous-corner squircle (Apple's icon shape), supersampled."""
    big = size * ss
    y, x = np.mgrid[0:big, 0:big].astype(np.float32)
    r = big / 2.0
    inside = (np.abs((x + 0.5 - r) / r) ** n + np.abs((y + 0.5 - r) / r) ** n) <= 1.0
    return Image.fromarray((inside * 255).astype(np.uint8), "L").resize((size, size), Image.LANCZOS)

cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
side = round(max(x1 - x0, y1 - y0) / 0.64)                 # mark fills ~64% of the tile
side = min(side, 2 * min(cx, src.width - cx), 2 * min(cy, src.height - cy))
tile = src.crop((cx - side // 2, cy - side // 2, cx + side // 2, cy + side // 2))

def squircle(size):
    body = riso_tile(size)
    body.putalpha(superellipse_mask(size))
    return body

# macOS template: 824 body on a 1024 canvas with a soft shadow.
mac = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
body = squircle(824)
shadow_alpha = Image.new("L", (1024, 1024), 0)
shadow_alpha.paste(body.getchannel("A"), (100, 110))
shadow = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
shadow.putalpha(shadow_alpha.filter(ImageFilter.GaussianBlur(14)).point(lambda v: int(v * 0.38)))
mac = Image.alpha_composite(mac, shadow)
mac.alpha_composite(body, (100, 100))

# Windows/Linux/window icon: near full-bleed, no macOS margin.
tight = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
tight.alpha_composite(squircle(984), (20, 20))

# --- write --------------------------------------------------------------
with tempfile.TemporaryDirectory() as tmp:
    iconset = pathlib.Path(tmp) / "app.iconset"
    iconset.mkdir()
    for base in (16, 32, 128, 256, 512):
        mac.resize((base, base), Image.LANCZOS).save(iconset / f"icon_{base}x{base}.png")
        mac.resize((base * 2, base * 2), Image.LANCZOS).save(iconset / f"icon_{base}x{base}@2x.png")
    icns = pathlib.Path(tmp) / "app.icns"
    subprocess.run(["iconutil", "-c", "icns", "-o", str(icns), str(iconset)], check=True)
    icns_bytes = icns.read_bytes()

png256 = tight.resize((256, 256), Image.LANCZOS)
written = []
for stem in ["nuvio-app-icon-transparent"] + [f"app-icon-{k}-transparent" for k in ICON_KEYS]:
    (ICONS / f"{stem}.icns").write_bytes(icns_bytes)
    tight.save(ICONS / f"{stem}.ico", format="ICO",
               sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    png256.save(ICONS / f"{stem}.png", optimize=True)
    written += [f"{stem}.icns", f"{stem}.ico", f"{stem}.png"]
for key in ICON_KEYS:
    for name in (f"app_icon_{key}.png", f"app_icon_{key}_transparent.png"):
        png256.save(DRAWABLE / name, optimize=True)
        written.append(name)
riso.save(DRAWABLE / "app_brand_mark.png", optimize=True)
written.append("app_brand_mark.png")
for old in sorted(DRAWABLE.glob("app_logo_wordmark*.png")):
    riso.save(old, optimize=True)
    written.append(old.name)
print(f"wrote {len(written)} files; mark {mark.size}")
