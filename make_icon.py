# Generates the launcher icon (no text -> no CJK font dependency).
# Dark rounded square + brand-yellow play triangle, matching the site theme (#080808 / #ffd21c).
import os
from PIL import Image, ImageDraw, ImageFilter

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "app", "res")

BG_TOP = (26, 26, 28)
BG_BOT = (8, 8, 9)
BRAND = (255, 210, 28)


def icon(size: int) -> Image.Image:
    S = size * 4  # supersample for clean edges
    r = int(S * 0.22)

    # rounded-square gradient background
    grad = Image.new("RGBA", (S, S))
    gd = ImageDraw.Draw(grad)
    for y in range(S):
        t = y / (S - 1)
        c = (
            int(BG_TOP[0] + (BG_BOT[0] - BG_TOP[0]) * t),
            int(BG_TOP[1] + (BG_BOT[1] - BG_TOP[1]) * t),
            int(BG_TOP[2] + (BG_BOT[2] - BG_TOP[2]) * t),
            255,
        )
        gd.line([(0, y), (S, y)], fill=c)
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=r, fill=255)
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    img.paste(grad, (0, 0), mask)

    # play triangle geometry (optically centred, slightly right)
    w = S * 0.40
    h = S * 0.46
    cx = S * 0.50 + S * 0.035
    cy = S * 0.50
    tri = [
        (cx - w / 2, cy - h / 2),
        (cx - w / 2, cy + h / 2),
        (cx + w / 2, cy),
    ]

    # soft glow behind the triangle
    glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(glow).polygon(tri, fill=BRAND + (110,))
    glow = glow.filter(ImageFilter.GaussianBlur(S * 0.05))
    img = Image.alpha_composite(img, glow)

    d = ImageDraw.Draw(img)
    d.polygon(tri, fill=BRAND + (255,))

    # faint brand border so the icon reads on light launchers too
    d.rounded_rectangle(
        [S * 0.012, S * 0.012, S - 1 - S * 0.012, S - 1 - S * 0.012],
        radius=int(r * 0.97),
        outline=BRAND + (70,),
        width=max(1, int(S * 0.012)),
    )

    return img.resize((size, size), Image.LANCZOS)


DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

for folder, size in DENSITIES.items():
    dirpath = os.path.join(OUT, folder)
    os.makedirs(dirpath, exist_ok=True)
    path = os.path.join(dirpath, "ic_launcher.png")
    icon(size).save(path)
    print("wrote", path, size)

print("done")
