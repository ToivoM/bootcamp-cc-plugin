"""Generate the plugin's icons from assets/bootcamp-logo.png.

Run from the repository root with the tools virtualenv (not a dependency of the plugin):

    python3 -m venv tools/.venv && tools/.venv/bin/pip install -r tools/requirements.txt
    tools/.venv/bin/python tools/generate_icons.py            # candidates + previews in tools/preview/
    tools/.venv/bin/python tools/generate_icons.py --install  # writes the chosen icons (see CHOSEN)

The source logo is pixel art drawn on TWO grids:
- the boots on a 25 px grid (cell edges at x = 15 + 25k, y = 4 + 25k): 24 x 18 cells
- the BOOTCAMP text on a ~6.44 px grid (fitted from the colour edges): 104 x 21 cells
The two grids are not integer multiples of each other (25 / 6.44 = 3.88), so there is no single "original
pixel size". Each part is reduced to its own grid by sampling the centre of every cell (nearest neighbour).
The background is already transparent; the white sticker border and the grey half-transparent shadow are not
on either grid and are dropped. A sticker border is drawn again at the target resolution where wanted.

In the source the text covers the bottom of the boots. For the boots on their own, two more rows (18 and 19)
are completed: every cell where enough of the boots shows between and behind the letters is taken from the
source (EVIDENCE_MIN_PIXELS of a boot-only colour; the boots' darkest brown is also the text's outline colour,
so it is not counted as evidence), and only the cells the letters hide completely come from BOOT_FILL_IN.
tools/preview/boots_completion_*.png shows which is which.

Every enlargement uses integer factors and nearest neighbour. The wordmark used in small icons and the 16 x 16
"B" are drawn here by rule in the logo's style (same colours: dark outline, orange-brown fill, light highlight
on the top edges of the fill, a dark base row), because the original text cannot be reduced to 48 px legibly.
"""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "assets" / "bootcamp-logo.png"
PREVIEW = ROOT / "tools" / "preview"

# Grids measured from the source (see the module docstring).
BOOT_STEP, BOOT_X0, BOOT_Y0 = 25.0, 15.0, 4.0
TEXT_STEP_X, TEXT_X0 = 6.4415, 6.05
TEXT_STEP_Y, TEXT_Y0 = 6.4445, 3.1
TEXT_TOP = 654  # source rows above this belong to the boots' sole

# Completing the bottom of the boots (see the module docstring). Native boot grid: 24 columns.
BOOT_EXTRA_ROWS = 2
EVIDENCE_MIN_PIXELS = 10
BOOT_ONLY_COLOURS = {  # colours used by the boots but not by the text
    (0, 0, 0): "K",  # outline
    (69, 45, 42): "m",  # mid brown
    (108, 72, 60): "l",  # light brown
    (149, 103, 82): "h",  # highlight
}
BOOT_COLOURS = {**{v: k for k, v in BOOT_ONLY_COLOURS.items()}, "d": (38, 23, 21)}  # d: darkest brown
# Drawn by hand, only where the letters hide the boots completely (" " = taken from the source, or empty).
# Row 18: the left boot's sole continues the light-to-dark pattern of the soles above (mid brown, dark at the
# toe end, as in the right boot's sole). Row 19: one outline pixel joins the visible parts of the left boot's
# bottom outline.
BOOT_FILL_IN = [
    "        mm  m md        ",  # row 18
    "           K            ",  # row 19
]

# Logo colours (RGBA).
OUTLINE = (38, 23, 21, 255)
FILL = (171, 81, 48, 255)
HIGHLIGHT = (207, 117, 43, 255)
WHITE = (255, 255, 255, 255)
CLEAR = (0, 0, 0, 0)
RUNELITE_DARK = (0x1E, 0x1E, 0x1E, 255)
DISCORD_DARK = (0x31, 0x33, 0x38, 255)

# Which candidates become the plugin's real icons (set after choosing from the previews).
CHOSEN = {
    "hub": "hub_boots",  # icon.png: the completed boots at 2x, 48 x 40
    "nav": "nav_b4",  # sidebar button: the serif B, 16 x 16
    "overlay": "overlay_boots_2x",  # broadcast overlay: the completed boots at 2x, 48 x 40
}


# --- reading the source ------------------------------------------------------------------------------


def _is_art(px: np.ndarray) -> bool:
    """Opaque and not the white sticker border (the shadow is half transparent, so it is excluded too)."""
    r, g, b, a = (int(v) for v in px)
    return a == 255 and not (r > 200 and g > 200 and b > 200)


def _sample(src: np.ndarray, x0: float, y0: float, sx: float, sy: float, nx: int, ny: int, keep) -> np.ndarray:
    out = np.zeros((ny, nx, 4), np.uint8)
    for j in range(ny):
        for i in range(nx):
            cx, cy = round(x0 + (i + 0.5) * sx), round(y0 + (j + 0.5) * sy)
            if 0 <= cx < src.shape[1] and 0 <= cy < src.shape[0] and keep(cx, cy) and _is_art(src[cy, cx]):
                out[j, i] = src[cy, cx]
    return out


def _trim(a: np.ndarray) -> tuple[np.ndarray, int, int]:
    ys, xs = np.nonzero(a[..., 3])
    return a[ys.min() : ys.max() + 1, xs.min() : xs.max() + 1], int(xs.min()), int(ys.min())


def _boot_evidence(src: np.ndarray, x: int, y: int) -> tuple[str, int] | None:
    """The boot-only colour seen most in one 25 x 25 cell (and how many pixels), or None."""
    counts: dict[str, int] = {}
    for px in src[y : y + 25, x : x + 25].reshape(-1, 4):
        key = BOOT_ONLY_COLOURS.get(tuple(int(v) for v in px[:3])) if px[3] == 255 else None
        if key:
            counts[key] = counts.get(key, 0) + 1
    if not counts:
        return None
    key = max(counts, key=counts.get)
    return key, counts[key]


def complete_boots(src: np.ndarray, boots: np.ndarray, origin: tuple[float, float]) -> tuple[np.ndarray, np.ndarray]:
    """The boots with their bottom rows completed, and a map of where each completed cell came from:
    1 = seen in the source between or behind the letters, 2 = drawn (BOOT_FILL_IN)."""
    h, w = boots.shape[:2]
    out = np.zeros((h + BOOT_EXTRA_ROWS, w, 4), np.uint8)
    out[:h] = boots
    origin_map = np.zeros((h + BOOT_EXTRA_ROWS, w), np.uint8)
    for r in range(BOOT_EXTRA_ROWS):
        for i in range(w):
            x, y = round(origin[0] + i * BOOT_STEP), round(origin[1] + (h + r) * BOOT_STEP)
            seen = _boot_evidence(src, x, y)
            drawn = BOOT_FILL_IN[r][i]
            if seen and seen[1] >= EVIDENCE_MIN_PIXELS:
                if drawn != " ":
                    raise SystemExit(f"BOOT_FILL_IN row {h + r} col {i} draws over a visible source cell")
                out[h + r, i] = (*BOOT_COLOURS[seen[0]], 255)
                origin_map[h + r, i] = 1
            elif drawn != " ":
                out[h + r, i] = (*BOOT_COLOURS[drawn], 255)
                origin_map[h + r, i] = 2
    return out, origin_map


def extract(src: np.ndarray) -> dict:
    """Boots and text at their own grid resolution, plus where they were in the source (in source pixels).
    "boots" is completed at the bottom; "boots_raw" is what the source shows above the text."""
    bx0, by0 = BOOT_X0 + 25 * 7, BOOT_Y0 + 25 * 7
    boots, bi, bj = _trim(_sample(src, bx0, by0, BOOT_STEP, BOOT_STEP, 26, 19, lambda x, y: y < TEXT_TOP))
    boots_px = (bx0 + bi * BOOT_STEP, by0 + bj * BOOT_STEP)
    completed, origin_map = complete_boots(src, boots, boots_px)
    tx0, ty0 = TEXT_X0 + TEXT_STEP_X * 26, TEXT_Y0 + TEXT_STEP_Y * 100
    text, ti, tj = _trim(_sample(src, tx0, ty0, TEXT_STEP_X, TEXT_STEP_Y, 110, 26, lambda x, y: y >= TEXT_TOP))
    return {
        "boots": completed,
        "boots_origin": origin_map,
        "boots_raw": boots,
        "boots_px": boots_px,
        "text": text,
        "text_px": (tx0 + ti * TEXT_STEP_X, ty0 + tj * TEXT_STEP_Y),
    }


# --- image helpers ---------------------------------------------------------------------------------------


def scale(a: np.ndarray, k: int) -> np.ndarray:
    """Integer nearest-neighbour enlargement."""
    return np.repeat(np.repeat(a, k, axis=0), k, axis=1)


def _mask(a: np.ndarray) -> np.ndarray:
    return a[..., 3] > 0


def _dilate(mask: np.ndarray, diagonal: bool = True) -> np.ndarray:
    m = np.pad(mask, 1)
    out = m.copy()
    shifts = [(-1, 0), (1, 0), (0, -1), (0, 1)] + ([(-1, -1), (-1, 1), (1, -1), (1, 1)] if diagonal else [])
    for dy, dx in shifts:
        out |= np.roll(np.roll(m, dy, 0), dx, 1)
    return out[1:-1, 1:-1]


def pad(a: np.ndarray, n: int) -> np.ndarray:
    return np.pad(a, ((n, n), (n, n), (0, 0)))


def add_border(a: np.ndarray, colour=WHITE) -> np.ndarray:
    """A 1 px sticker border around the shape (the image grows by 1 px on each side)."""
    a = pad(a, 1)
    ring = _dilate(_mask(a)) & ~_mask(a)
    a[ring] = colour
    return a


def border_inside(a: np.ndarray, colour=WHITE) -> np.ndarray:
    """A 1 px sticker border drawn over the outermost pixels of the shape (keeps the size). Meant for shapes
    with an outline at least 2 px thick, such as the boots at 2x: the outline stays 1 px wide inside it."""
    a = a.copy()
    m = _mask(a)
    outside = np.pad(~m, 1, constant_values=True)
    touches_outside = _dilate(outside, diagonal=False)[1:-1, 1:-1]
    a[m & touches_outside] = colour
    return a


def paste(canvas: np.ndarray, a: np.ndarray, x: int, y: int) -> None:
    h, w = a.shape[:2]
    region = canvas[y : y + h, x : x + w]
    m = a[..., 3] > 0
    region[m] = a[m]


def save(a: np.ndarray, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(a, "RGBA").save(path, optimize=True)


# --- lettering in the logo's style -------------------------------------------------------------------------


def style_letters(fill_mask: np.ndarray, base_rows: int = 1) -> np.ndarray:
    """Turn a fill mask into logo-style lettering: dark 1 px outline, orange-brown fill, light highlight on
    every top edge of the fill, and `base_rows` extra dark rows under the outline (the logo's heavy base)."""
    h, w = fill_mask.shape
    m = np.zeros((h + 2 + base_rows, w + 2), bool)
    m[1 : h + 1, 1 : w + 1] = fill_mask
    out = np.zeros(m.shape + (4,), np.uint8)
    outline = _dilate(m)
    for _ in range(base_rows):
        outline |= np.roll(outline, 1, axis=0)
    out[outline] = OUTLINE
    out[m] = FILL
    top_edge = m & ~np.roll(m, 1, axis=0)
    out[top_edge] = HIGHLIGHT
    return out


def glyphs(rows: list[str]) -> np.ndarray:
    return np.array([[c == "#" for c in r] for r in rows], bool)


# 4 x 6 letters with 2 px stems, for the small wordmark under the boots.
FONT_4X6 = {
    "B": ["###.", "##.#", "###.", "##.#", "##.#", "###."],
    "O": [".##.", "##.#", "##.#", "##.#", "##.#", ".##."],
    "T": ["####", ".##.", ".##.", ".##.", ".##.", ".##."],
    "C": [".###", "##..", "##..", "##..", "##..", ".###"],
    "A": [".##.", "##.#", "##.#", "####", "##.#", "##.#"],
    "M": ["#..#", "####", "####", "#..#", "#..#", "#..#"],
    "P": ["###.", "##.#", "##.#", "###.", "##..", "##.."],
}


def wordmark(text: str = "BOOTCAMP") -> np.ndarray:
    """The small BOOTCAMP: letters 4 px wide with a shared 1 px outline between them."""
    gap = 1
    w = len(text) * 4 + (len(text) - 1) * gap
    mask = np.zeros((6, w), bool)
    for i, ch in enumerate(text):
        mask[:, i * (4 + gap) : i * (4 + gap) + 4] = glyphs(FONT_4X6[ch])
    return style_letters(mask)


# 16 x 16 sidebar "B" candidates: fill masks (outline, highlight and base are added by style_letters).
NAV_B = {
    # Thick: 3 px stem like the logo's letters, 11 x 12 fill -> 13 x 15 with outline and base.
    "nav_b1": [
        "#########..",
        "##########.",
        "###....####",
        "###.....###",
        "###....####",
        "#########..",
        "##########.",
        "###.....###",
        "###......##",
        "###.....###",
        "##########.",
        "#########..",
    ],
    # Medium: 2 px strokes, rounder bowls, 10 x 12 fill.
    "nav_b2": [
        "########..",
        "#########.",
        "##.....##.",
        "##.....##.",
        "##....###.",
        "########..",
        "#########.",
        "##.....###",
        "##......##",
        "##.....###",
        "#########.",
        "########..",
    ],
    # Thick with the logo's slab serifs (the top and bottom bars reach 1 px left of the stem), 12 x 12 fill.
    "nav_b4": [
        "##########..",
        "###########.",
        ".###....####",
        ".###.....###",
        ".###....####",
        ".#########..",
        ".##########.",
        ".###.....###",
        ".###.....###",
        ".###....####",
        "###########.",
        "##########..",
    ],
    # Small, for a white sticker border: 9 x 10 fill -> 11 x 13 with outline and base, 13 x 15 with border.
    "nav_b3": [
        "#######..",
        "########.",
        "##....##.",
        "##...###.",
        "#######..",
        "########.",
        "##....###",
        "##.....##",
        "########.",
        "#######..",
    ],
}


def centred(a: np.ndarray, w: int, h: int, bg=CLEAR) -> np.ndarray:
    canvas = np.zeros((h, w, 4), np.uint8)
    canvas[:] = bg
    y, x = (h - a.shape[0]) // 2, (w - a.shape[1]) // 2
    paste(canvas, a, x, y)
    return canvas


def stack(top: np.ndarray, bottom: np.ndarray, gap: int) -> np.ndarray:
    """top above bottom, both centred; bottom drawn last (like the logo's text in front of the boots)."""
    w = max(top.shape[1], bottom.shape[1])
    h = top.shape[0] + gap + bottom.shape[0]
    canvas = np.zeros((h, w, 4), np.uint8)
    paste(canvas, top, (w - top.shape[1]) // 2, 0)
    paste(canvas, bottom, (w - bottom.shape[1]) // 2, top.shape[0] + gap)
    return canvas


def full_logo(parts: dict) -> np.ndarray:
    """The whole logo at the text's resolution: boots enlarged 4x (their cells are 3.88 text pixels, the
    nearest integer is 4), bottom-aligned and centred where they were in the source; the text in front."""
    raw = parts["boots_raw"]  # the text hides the bottom anyway: the whole logo stays as in the source
    boots, text = scale(raw, 4), parts["text"]
    (bx, by), (tx, ty) = parts["boots_px"], parts["text_px"]
    boots_bottom = (by + raw.shape[0] * BOOT_STEP - ty) / TEXT_STEP_Y
    boots_centre_x = (bx + raw.shape[1] * BOOT_STEP / 2 - tx) / TEXT_STEP_X
    bxo = round(boots_centre_x - boots.shape[1] / 2)
    byo = round(boots_bottom - boots.shape[0])
    left, top = min(0, bxo), min(0, byo)
    w = max(text.shape[1], bxo + boots.shape[1]) - left
    h = max(text.shape[0], byo + boots.shape[0]) - top
    canvas = np.zeros((h, w, 4), np.uint8)
    paste(canvas, boots, bxo - left, byo - top)
    paste(canvas, text, -left, -top)
    return canvas


# --- candidates ------------------------------------------------------------------------------------------


def candidates(parts: dict) -> dict[str, np.ndarray]:
    boots2 = scale(parts["boots"], 2)  # 48 x 36: exactly the Plugin Hub icon width
    word = wordmark()
    c: dict[str, np.ndarray] = {}
    # Plugin Hub icon.png (at most 48 x 72).
    c["hub_logo"] = stack(boots2, word, gap=1)
    c["hub_logo_border"] = stack(border_inside(boots2), add_border(word), gap=0)
    c["hub_boots"] = boots2
    c["hub_boots_border"] = border_inside(boots2)
    # Sidebar button, 16 x 16.
    for name, rows in NAV_B.items():
        letter = style_letters(glyphs(rows))
        c[name] = centred(letter, 16, 16)
    c["nav_b3_border"] = centred(add_border(style_letters(glyphs(NAV_B["nav_b3"]))), 16, 16)
    # Broadcast overlay logo: the boots can only be 20 px (1x) or 40 px (2x) tall with integer factors.
    c["overlay_boots_1x"] = parts["boots"]
    c["overlay_boots_2x"] = boots2
    c["overlay_boots_2x_border"] = border_inside(boots2)
    return c


def discord_avatars(parts: dict) -> dict[str, np.ndarray]:
    """512 x 512 avatars. Discord shows avatars as circles, so the logo is scaled 3x (321 px wide) to keep
    the corners of the text inside the circle."""
    logo = full_logo(parts)
    big = scale(logo, 3)
    big_border = scale(add_border(logo), 3)
    return {
        "avatar_transparent_border": centred(big_border, 512, 512),
        "avatar_dark_border": centred(big_border, 512, 512, DISCORD_DARK),
        "avatar_dark": centred(big, 512, 512, DISCORD_DARK),
    }


# --- previews --------------------------------------------------------------------------------------------


def on_background(images: list[np.ndarray], bg, factors=(1, 4), gap: int = 12) -> np.ndarray:
    """Every image at each factor, side by side on a solid background."""
    tiles = [scale(img, k) for k in factors for img in images]
    w = sum(t.shape[1] for t in tiles) + gap * (len(tiles) + 1)
    h = max(t.shape[0] for t in tiles) + 2 * gap
    canvas = np.zeros((h, w, 4), np.uint8)
    canvas[:] = bg
    x = gap
    for t in tiles:
        paste(canvas, t, x, gap + (h - 2 * gap - t.shape[0]) // 2)
        x += t.shape[1] + gap
    return canvas


def circle_preview(a: np.ndarray) -> np.ndarray:
    """How Discord crops an avatar."""
    mask = Image.new("L", (a.shape[1], a.shape[0]), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, a.shape[1] - 1, a.shape[0] - 1), fill=255)
    out = a.copy()
    out[np.array(mask) == 0] = (0x1E, 0x1F, 0x22, 255)
    return out


def completion_previews(src: np.ndarray, parts: dict) -> None:
    """8x comparison: the source area with the text, the old crop and the completed boots; and the completed
    boots with the completed cells marked (yellow = seen in the source, red = drawn)."""
    boots, raw, origin = parts["boots"], parts["boots_raw"], parts["boots_origin"]
    k = 8
    w, h = boots.shape[1] * k, boots.shape[0] * k
    bx, by = parts["boots_px"]
    box = (round(bx), round(by), round(bx + boots.shape[1] * BOOT_STEP), round(by + boots.shape[0] * BOOT_STEP))
    save(np.array(Image.fromarray(src).crop(box)), PREVIEW / "boots_completion_source_area_1to1.png")
    # The same area sampled on the boots' grid with everything in it (text, sticker border), so the three
    # panels line up cell for cell.
    area = np.zeros((boots.shape[0], boots.shape[1], 4), np.uint8)
    for r in range(boots.shape[0]):
        for i in range(boots.shape[1]):
            px = src[round(by + (r + 0.5) * BOOT_STEP), round(bx + (i + 0.5) * BOOT_STEP)]
            if px[3] > 0:
                area[r, i] = (*px[:3], 255)
    area = scale(area, k)
    old = np.zeros((boots.shape[0], boots.shape[1], 4), np.uint8)
    old[: raw.shape[0]] = raw
    panels = [area, scale(old, k), scale(boots, k)]
    marked = scale(boots, k).copy()
    for (r, i), v in np.ndenumerate(origin):
        if v:
            colour = (255, 210, 0, 255) if v == 1 else (255, 40, 40, 255)
            y0, x0 = r * k, i * k
            marked[y0 : y0 + k, x0 : x0 + 2] = colour
            marked[y0 : y0 + k, x0 + k - 2 : x0 + k] = colour
            marked[y0 : y0 + 2, x0 : x0 + k] = colour
            marked[y0 + k - 2 : y0 + k, x0 : x0 + k] = colour
            if v == 2:  # tint the drawn colour red, so it stays visible
                inner = marked[y0 + 2 : y0 + k - 2, x0 + 2 : x0 + k - 2]
                inner[..., :3] = (inner[..., :3].astype(int) + np.array([255, 40, 40])) // 2
    gap = 16
    sheet = np.zeros((h + 2 * gap, 3 * w + 4 * gap, 4), np.uint8)
    sheet[:] = (0x1E, 0x1E, 0x1E, 255)
    for n, p in enumerate(panels):
        paste(sheet, p, gap + n * (w + gap), gap)
    save(sheet, PREVIEW / "boots_completion_compare_x8.png")
    marked_sheet = np.zeros((h + 2 * gap, w + 2 * gap, 4), np.uint8)
    marked_sheet[:] = (0x1E, 0x1E, 0x1E, 255)
    paste(marked_sheet, marked, gap, gap)
    save(marked_sheet, PREVIEW / "boots_completion_marked_x8.png")


def write_previews(src: np.ndarray, parts: dict, cands: dict[str, np.ndarray], avatars: dict[str, np.ndarray]) -> None:
    shutil.rmtree(PREVIEW, ignore_errors=True)  # only generated previews live here
    completion_previews(src, parts)
    save(parts["boots"], PREVIEW / "source" / "boots_native.png")
    save(parts["boots_raw"], PREVIEW / "source" / "boots_native_before_completion.png")
    save(parts["text"], PREVIEW / "source" / "text_native.png")
    save(full_logo(parts), PREVIEW / "source" / "logo_native.png")
    save(scale(full_logo(parts), 4), PREVIEW / "source" / "logo_native_x4.png")
    for name, img in cands.items():
        save(img, PREVIEW / "candidates" / f"{name}.png")
        save(scale(img, 8), PREVIEW / f"{name}_x8.png")
    groups = {
        "hub": [n for n in cands if n.startswith("hub_")],
        "nav": [n for n in cands if n.startswith("nav_")],
        "overlay": [n for n in cands if n.startswith("overlay_")],
    }
    for group, names in groups.items():
        save(on_background([cands[n] for n in names], RUNELITE_DARK), PREVIEW / f"{group}_on_runelite_dark.png")
    mocks = [overlay_mock(cands[n]) for n in groups["overlay"]]
    sheet = np.zeros((sum(m.shape[0] for m in mocks), mocks[0].shape[1], 4), np.uint8)
    y = 0
    for m in mocks:
        sheet[y : y + m.shape[0]] = m
        y += m.shape[0]
    save(sheet, PREVIEW / "overlay_mock.png")
    save(scale(sheet, 2), PREVIEW / "overlay_mock_x2.png")
    for name, img in avatars.items():
        save(img, PREVIEW / "discord" / f"{name}.png")
        save(circle_preview(img), PREVIEW / "discord" / f"{name}_as_circle.png")


def overlay_mock(logo: np.ndarray, title: str = "[Bootcamp CC]", lines=("Mass at Falador in 10 minutes,", "bring food and a spade")) -> np.ndarray:
    """A rough mock of the broadcast overlay (360 px wide, RuneLite's standard overlay background) with the
    logo on the left, centred vertically. The font is Pillow's default, not RuneScape's: sizes are close,
    looks are not."""
    pad_px, line_h = 6, 14
    text_h = line_h * (1 + len(lines)) + 2
    h = max(text_h, logo.shape[0]) + 2 * pad_px + 2
    game = np.zeros((h + 24, 384, 4), np.uint8)
    game[:] = (0x5A, 0x50, 0x3C, 255)  # a game-like ground colour behind the overlay
    box = Image.new("RGBA", (360, h), (70, 61, 50, 156))
    draw = ImageDraw.Draw(box)
    draw.rectangle((0, 0, 359, h - 1), outline=(0, 0, 0, 160))
    Image.Image.paste(box, Image.fromarray(logo, "RGBA"), (pad_px, (h - logo.shape[0]) // 2), Image.fromarray(logo, "RGBA"))
    tx = pad_px + logo.shape[1] + 6
    ty = (h - text_h) // 2 + 1
    draw.text((tx, ty), title, fill=(0xE0, 0x8A, 0x1E, 255))
    for i, line in enumerate(lines):
        draw.text((tx, ty + line_h * (i + 1)), line, fill=(255, 255, 255, 255))
    draw.rectangle((1, h - 3, 1 + 250, h - 2), fill=(0xE0, 0x8A, 0x1E, 255))
    out = Image.fromarray(game, "RGBA")
    out.alpha_composite(box, (12, 12))
    return np.array(out)


def install(cands: dict[str, np.ndarray]) -> None:
    missing = [k for k, v in CHOSEN.items() if v is None]
    if missing:
        raise SystemExit(f"Choose first: set CHOSEN[{', '.join(missing)}] in {Path(__file__).name}")
    save(cands[CHOSEN["hub"]], ROOT / "icon.png")
    resources = ROOT / "src" / "main" / "resources" / "com" / "bootcampcc"
    save(cands[CHOSEN["nav"]], resources / "nav_icon.png")
    save(cands[CHOSEN["overlay"]], resources / "overlay_logo.png")
    print("Installed", CHOSEN)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--install", action="store_true", help="write the CHOSEN icons into the plugin")
    args = parser.parse_args()
    src = np.array(Image.open(SOURCE).convert("RGBA"))
    parts = extract(src)
    cands = candidates(parts)
    if args.install:
        install(cands)
        return
    write_previews(src, parts, cands, discord_avatars(parts))
    for name, img in cands.items():
        print(f"{name:28s} {img.shape[1]:3d} x {img.shape[0]:3d}")
    print("Previews in", PREVIEW.relative_to(ROOT))


if __name__ == "__main__":
    main()
