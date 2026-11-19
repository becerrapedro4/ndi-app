"""Genera el ícono legacy (PNG por densidad) de NDI App.

El trazado del wordmark coincide con el vector adaptativo de
res/drawable/ic_launcher_foreground.xml (viewport 108x108).
"""
import os

from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")
S = 1024
K = S / 108.0  # escala del viewport del vector

WHITE = (238, 246, 255)
BLUE = (79, 169, 255)
# El wordmark va al 78% para que entre entero en la máscara circular del launcher.
SCALE = 0.78
STROKE = 6.5 * K * SCALE


def pt(x, y):
    return ((54 + (x - 54) * SCALE) * K, (54 + (y - 54) * SCALE) * K)


def stroke(draw, points, color, width=STROKE):
    draw.line([pt(*p) for p in points], fill=color, width=int(width), joint="curve")
    radius = width / 2
    for x, y in points:
        cx, cy = pt(x, y)
        draw.ellipse([cx - radius, cy - radius, cx + radius, cy + radius], fill=color)


def bezier(p0, p1, p2, p3, steps=40):
    pts = []
    for i in range(steps + 1):
        t = i / steps
        u = 1 - t
        x = u**3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t**3 * p3[0]
        y = u**3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t**3 * p3[1]
        pts.append((x, y))
    return pts


def background():
    img = Image.new("RGB", (S, S))
    draw = ImageDraw.Draw(img)
    top, bottom = (14, 44, 74), (4, 15, 27)
    for y in range(S):
        t = y / (S - 1)
        draw.line(
            [(0, y), (S, y)],
            fill=tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)),
        )

    # Halo azul detrás del wordmark.
    glow = Image.new("L", (S, S), 0)
    gdraw = ImageDraw.Draw(glow)
    cx, cy = S * 0.5, S * 0.36
    radius = S * 0.66
    steps = 96
    for i in range(steps, 0, -1):
        t = i / steps
        r = radius * t
        alpha = int(150 * (1 - t) ** 2)
        gdraw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=alpha)
    accent = Image.new("RGB", (S, S), (34, 118, 200))
    img = Image.composite(accent, img, glow.point(lambda v: int(v * 0.75)))
    return img


def wordmark(img):
    draw = ImageDraw.Draw(img)
    # N
    stroke(draw, [(26, 70), (26, 38), (44, 70), (44, 38)], WHITE)
    # D: asta y curva del bowl, igual que el vector adaptativo
    stroke(draw, [(52, 38), (52, 70)], WHITE)
    stroke(draw, [(52, 38), (58, 38)], WHITE)
    stroke(draw, [(52, 70), (58, 70)], WHITE)
    bowl = bezier((58, 38), (68, 38), (74, 45), (74, 54)) + bezier(
        (74, 54), (74, 63), (68, 70), (58, 70)
    )
    stroke(draw, bowl, WHITE)
    # I en azul, como el acento de la interfaz
    stroke(draw, [(84, 38), (84, 70)], BLUE, width=STROKE * 1.1)


def rounded(img, radius_ratio=0.22):
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, S - 1, S - 1], radius=int(S * radius_ratio), fill=255
    )
    out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def main():
    icon = rounded(background())
    wordmark(icon)
    for density, size in {
        "mdpi": 48,
        "hdpi": 72,
        "xhdpi": 96,
        "xxhdpi": 144,
        "xxxhdpi": 192,
    }.items():
        folder = os.path.join(ROOT, "mipmap-" + density)
        os.makedirs(folder, exist_ok=True)
        scaled = icon.resize((size, size), Image.LANCZOS)
        scaled.save(os.path.join(folder, "ic_launcher.png"))
        scaled.save(os.path.join(folder, "ic_launcher_round.png"))
    icon.resize((512, 512), Image.LANCZOS).save(
        os.path.join(os.path.dirname(__file__), "..", "..", "web", "app-icon.png")
    )
    print("íconos generados")


if __name__ == "__main__":
    main()
