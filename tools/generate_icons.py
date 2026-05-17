#!/usr/bin/env python3
"""
Generates Wristotle Companion app icons (Android).

Reuses the same `_draw_icon` artwork as the watch project
(`Wristotle/tools/generate_icons.py`) so the watch launcher and the
Android launcher show the same character.

Outputs at all 5 Android density buckets:
  res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/
    ic_launcher.png             — legacy square launcher
    ic_launcher_round.png       — legacy round launcher (circular alpha mask)
    ic_launcher_foreground.png  — adaptive-icon foreground (Android 8+),
                                  icon centred inside the 66 dp safe zone

Also rewrites the existing adaptive-icon XML so it points at the bitmap
foreground above, and replaces the placeholder background vector with a
solid colour so the dark head pops against the cyan canvas. Deletes the
old `.webp` launcher icons that Android Studio scaffolded — leaving them
alongside the new `.png` files would mean Android picks one of them
arbitrarily.

Run from anywhere inside the repo:
  python tools/generate_icons.py

Requires:
  pip install pillow

Keep `_draw_icon` (lines 36–113-ish here) in sync with the matching
function in `Wristotle/tools/generate_icons.py`. The artwork is the
single source of truth; both scripts render from it.
"""

import math
import os

from PIL import Image, ImageDraw

# ── Paths ─────────────────────────────────────────────────────────────────────

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES_DIR   = os.path.join(REPO_ROOT, 'app', 'src', 'main', 'res')

# ── Palette ───────────────────────────────────────────────────────────────────

BLACK     = (0,   0,   0,   255)
DARK_GRAY = (28,  28,  28,  255)
WHITE     = (255, 255, 255, 255)
CYAN      = (0,   230, 230, 255)
YELLOW    = (255, 210, 0,   255)

# Launcher icon background — fully transparent so the artwork floats on
# the home screen / app drawer wallpaper instead of carrying its own
# solid square around. Custom launchers and adaptive-icon masks deal
# with transparency cleanly.
ICON_BG_COLOR = (0, 0, 0, 0)

# Splash screen background (Android 12+ SplashScreen API). Tried fully
# transparent so the splash icon would sit over the launcher's last
# frame, but OEM skins frequently fall back to a white fill when the
# attribute is alpha=0. Black is the most reliable cross-device choice
# and matches the watch's own background.
SPLASH_BG_COLOR = BLACK

# ── Densities ─────────────────────────────────────────────────────────────────
# Legacy launcher icons are sized as 48 dp baseline × density multiplier.
# Adaptive-icon canvas is 108 dp baseline × density multiplier. The icon
# artwork itself sits inside the 66 dp safe zone; we render it at
# round(canvas * 0.62) so corners survive even aggressive circular masks.

DENSITIES = [
    # (mipmap dir suffix, legacy px, adaptive canvas px)
    ('mdpi',    48,  108),
    ('hdpi',    72,  162),
    ('xhdpi',   96,  216),
    ('xxhdpi',  144, 324),
    ('xxxhdpi', 192, 432),
]

# Play Store / device-settings high-res icon. Not required for sideload
# builds but cheap to ship.
PLAYSTORE_PX = 512

# ── Artwork (in sync with Wristotle/tools/generate_icons.py) ──────────────────

def _draw_icon(size: int) -> Image.Image:
    img  = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Scale helper — all measurements are authored at 48px then scaled down.
    def sc(v: float) -> int:
        return max(1, round(v * size / 48))

    cx = size // 2  # horizontal centre

    # ── Antenna ───────────────────────────────────────────────────────────────
    ant_y_tip  = sc(1)
    ant_y_base = sc(9)
    draw.line([(cx, ant_y_tip), (cx, ant_y_base)], fill=WHITE, width=sc(1))
    tip_r = sc(2)
    draw.ellipse(
        [cx - tip_r, ant_y_tip - tip_r, cx + tip_r, ant_y_tip + tip_r],
        fill=YELLOW,
    )

    # ── Head (rounded rectangle) ──────────────────────────────────────────────
    margin   = sc(3)
    head_top = sc(8)
    hx0, hy0 = margin, head_top
    hx1, hy1 = size - margin - 1, size - margin - 1
    draw.rounded_rectangle(
        [hx0, hy0, hx1, hy1],
        radius=sc(4),
        fill=DARK_GRAY,
        outline=WHITE,
        width=sc(1),
    )

    # ── Eyes ──────────────────────────────────────────────────────────────────
    hw     = hx1 - hx0
    hh     = hy1 - hy0
    eye_y  = round(hy0 + hh * 0.35)
    left_x = round(hx0 + hw * 0.30)
    rgt_x  = round(hx0 + hw * 0.70)
    eye_r  = sc(4)

    draw.ellipse(
        [left_x - eye_r, eye_y - eye_r, left_x + eye_r, eye_y + eye_r],
        fill=WHITE,
    )
    p = sc(2)
    draw.ellipse(
        [left_x - p, eye_y - p, left_x + p, eye_y + p],
        fill=BLACK,
    )

    ast_r    = sc(4)
    ast_arms = 3
    for i in range(ast_arms):
        angle = math.radians(i * 60)
        x1 = rgt_x + round(ast_r * math.cos(angle))
        y1 = eye_y + round(ast_r * math.sin(angle))
        x2 = rgt_x - round(ast_r * math.cos(angle))
        y2 = eye_y - round(ast_r * math.sin(angle))
        draw.line([(x1, y1), (x2, y2)], fill=CYAN, width=sc(1))

    # ── Mouth (yellow zigzag) ─────────────────────────────────────────────────
    mouth_y  = round(hy0 + hh * 0.72)
    mouth_x0 = round(hx0 + hw * 0.18)
    mouth_x1 = round(hx0 + hw * 0.82)
    zag_h    = sc(3)
    zag_n    = 4
    zag_w    = (mouth_x1 - mouth_x0) / zag_n

    pts = [
        (round(mouth_x0 + i * zag_w), mouth_y + (0 if i % 2 == 0 else zag_h))
        for i in range(zag_n + 1)
    ]
    draw.line(pts, fill=YELLOW, width=sc(1))

    return img  # RGBA — transparent background

# ── Android-specific composition ──────────────────────────────────────────────

def _legacy_square(size: int) -> Image.Image:
    """Square launcher icon — transparent canvas with centred artwork.
    Older launchers that don't honour adaptive icons just render this
    PNG verbatim; the transparent corners are fine."""
    canvas = Image.new('RGBA', (size, size), ICON_BG_COLOR)
    inner  = round(size * 0.88)
    art    = _draw_icon(inner)
    off    = (size - inner) // 2
    canvas.alpha_composite(art, (off, off))
    return canvas

def _legacy_round(size: int) -> Image.Image:
    """Same as square but clipped to a centred circle so the file is
    already round for launchers that don't apply a mask themselves."""
    square = _legacy_square(size)
    mask = Image.new('L', (size, size), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
    out = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    out.paste(square, (0, 0), mask)
    return out

def _adaptive_foreground(canvas_px: int) -> Image.Image:
    """Foreground layer for the Android 8+ adaptive icon. Transparent
    background; icon sits inside the 66 dp safe zone with generous
    padding so circular/squircle masks don't clip it."""
    canvas = Image.new('RGBA', (canvas_px, canvas_px), (0, 0, 0, 0))
    inner  = round(canvas_px * 0.62)
    art    = _draw_icon(inner)
    off    = (canvas_px - inner) // 2
    canvas.alpha_composite(art, (off, off))
    return canvas

# ── Output helpers ────────────────────────────────────────────────────────────

def _icon_bg_argb_hex() -> str:
    r, g, b, a = ICON_BG_COLOR
    # 8-digit ARGB so a fully-transparent #00000000 round-trips correctly.
    return f'#{a:02X}{r:02X}{g:02X}{b:02X}'

def _splash_bg_hex() -> str:
    r, g, b, a = SPLASH_BG_COLOR
    # ARGB so a fully-transparent splash colour round-trips correctly.
    return f'#{a:02X}{r:02X}{g:02X}{b:02X}'

ADAPTIVE_ICON_XML = """\
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""
# Note: no <monochrome> layer. A proper monochrome needs a single-colour
# alpha-defined shape; our foreground is multi-coloured artwork. With the
# wrong shape, Pixel's "Themed icons" toggle can fall back to a generic
# placeholder for this app. If we ever want themed-icon support, render a
# separate white-on-transparent silhouette and add it back here.

BACKGROUND_DRAWABLE_XML = f"""\
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
       android:shape="rectangle">
    <solid android:color="{_icon_bg_argb_hex()}" />
</shape>
"""

# Splash colour, exposed as a @color resource so the splash screen theme
# can reference it (windowSplashScreenBackground takes a colour, not a
# drawable). Lives in its own values file so we never have to parse + rewrite
# a shared colors.xml.
BACKGROUND_COLOR_XML = f"""\
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">{_splash_bg_hex()}</color>
</resources>
"""

def _write(path: str, content: str) -> None:
    with open(path, 'w', encoding='utf-8') as f:
        f.write(content)
    print(f'Wrote {os.path.relpath(path, REPO_ROOT)}')

def _save(img: Image.Image, path: str) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    print(f'Wrote {os.path.relpath(path, REPO_ROOT)}')

def _remove_stale_webp() -> None:
    """Android Studio scaffolded .webp launcher files; with our .png
    files in the same mipmap dir, the resource resolver would pick one
    arbitrarily. Clean up."""
    removed = 0
    for suffix, _, _ in DENSITIES:
        dir_path = os.path.join(RES_DIR, f'mipmap-{suffix}')
        if not os.path.isdir(dir_path):
            continue
        for name in ('ic_launcher.webp', 'ic_launcher_round.webp'):
            path = os.path.join(dir_path, name)
            if os.path.exists(path):
                os.remove(path)
                removed += 1
                print(f'Removed {os.path.relpath(path, REPO_ROOT)}')
    if removed == 0:
        print('No stale .webp files to remove.')

# ── Entry point ───────────────────────────────────────────────────────────────

def main() -> None:
    for suffix, legacy_px, adaptive_px in DENSITIES:
        mipmap_dir = os.path.join(RES_DIR, f'mipmap-{suffix}')
        _save(_legacy_square(legacy_px),
              os.path.join(mipmap_dir, 'ic_launcher.png'))
        _save(_legacy_round(legacy_px),
              os.path.join(mipmap_dir, 'ic_launcher_round.png'))
        _save(_adaptive_foreground(adaptive_px),
              os.path.join(mipmap_dir, 'ic_launcher_foreground.png'))

    _save(_legacy_square(PLAYSTORE_PX),
          os.path.join(REPO_ROOT, 'tools', 'ic_launcher_512.png'))

    # The existing adaptive-icon XML referenced @drawable/ic_launcher_foreground
    # (a vector); rewrite to point at @mipmap/ic_launcher_foreground (the
    # bitmap we just generated) and the same for the round variant.
    anydpi_dir = os.path.join(RES_DIR, 'mipmap-anydpi-v26')
    os.makedirs(anydpi_dir, exist_ok=True)
    _write(os.path.join(anydpi_dir, 'ic_launcher.xml'), ADAPTIVE_ICON_XML)
    _write(os.path.join(anydpi_dir, 'ic_launcher_round.xml'), ADAPTIVE_ICON_XML)

    # Replace the placeholder vector background with a solid-colour shape.
    bg_path = os.path.join(RES_DIR, 'drawable', 'ic_launcher_background.xml')
    _write(bg_path, BACKGROUND_DRAWABLE_XML)

    # Expose the same colour as a @color resource for the splash theme.
    bg_color_path = os.path.join(RES_DIR, 'values', 'ic_launcher_colors.xml')
    _write(bg_color_path, BACKGROUND_COLOR_XML)

    # Old foreground vector is no longer referenced — leave it on disk so
    # we don't trash anyone's local edits; the build resolver picks the
    # @mipmap qualifier over the @drawable one regardless.

    _remove_stale_webp()


if __name__ == '__main__':
    main()
