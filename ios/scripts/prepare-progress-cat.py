"""Build the user-approved local cutout and procedural walk cycle (Pillow).

These checked-in PNGs are consumed directly by prepare-resources.mjs. This
authoring script is not needed by Xcode or CI and makes no network/API calls.
"""
from pathlib import Path
import math
from PIL import Image, ImageDraw, ImageOps

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "DesignSources/progress-cat-reference.png"
DEST = ROOT / "DesignAssets"
PREVIEW = ROOT / "DesignPreviews"


def cutout():
    # All four reference poses show the same cat. Use one registered subject so
    # its headphones, face and torso cannot jump between animation frames.
    source = ImageOps.grayscale(Image.open(SOURCE).crop((15, 180, 274, 439))).convert("RGB")
    mask = ImageOps.grayscale(source).point(lambda v: 255 if v >= 210 else 0)
    ImageDraw.floodfill(mask, (0, 0), 128, thresh=0)
    pixels = []
    for rgb, exterior in zip(source.getdata(), mask.getdata()):
        if exterior != 128:
            pixels.append((*rgb, 255))
            continue
        # Remove the exterior white matte, including antialiased whisker edges;
        # enclosed white fur remains opaque and reads on dark player surfaces.
        alpha = min(1.0, max(0.0, (255 - min(rgb)) / 205))
        if alpha == 0:
            pixels.append((0, 0, 0, 0))
        else:
            color = tuple(round(max(0, min(255, (v - 255 * (1 - alpha)) / alpha))) for v in rgb)
            pixels.append((*color, round(alpha * 255)))
    result = Image.new("RGBA", source.size)
    result.putdata(pixels)
    canvas = Image.new("RGBA", (288, 288))
    canvas.alpha_composite(result, (14, 14))
    return canvas


def displacement(x, y, phase):
    t = min(1.0, max(0.0, (y - 175) / 84))
    t = t * t * (3 - 2 * t)
    dx = dy = 0.0
    # The four paws are offset by quarter cycles. Keep the upper body registered
    # while the lower legs alternately reach, plant, sweep back and lift.
    for center, offset, amplitude in [(61, 1.5 * math.pi, 10), (143, .5 * math.pi, 8),
                                      (201, 0, 11), (239, math.pi, 8)]:
        influence = math.exp(-((x - center) / 27) ** 2) * t
        dx += amplitude * math.cos(phase + offset) * influence
        dy -= 8 * max(0.0, -math.sin(phase + offset)) * influence
    return dx, dy


def walk_frame(image, index):
    phase = 2 * math.pi * index / 8
    def inverse(x, y):
        sx, sy = x, y
        for _ in range(5):
            dx, dy = displacement(sx, sy, phase)
            sx, sy = x - dx, y - dy
        return sx, sy
    mesh = []
    for top in range(0, 288, 6):
        for left in range(0, 288, 6):
            right, bottom = min(288, left + 6), min(288, top + 6)
            quad = sum((inverse(x, y) for x, y in [(left, top), (left, bottom), (right, bottom), (right, top)]), ())
            mesh.append(((left, top, right, bottom), quad))
    return image.transform(image.size, Image.Transform.MESH, mesh, Image.Resampling.BICUBIC)


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    PREVIEW.mkdir(parents=True, exist_ok=True)
    frames = [walk_frame(cutout(), index) for index in range(8)]
    for index, frame in enumerate(frames):
        frame.save(DEST / f"progress-cat-{index}.png", optimize=True)
    sheet = Image.new("RGBA", (288 * 4, 288 * 2))
    for index, frame in enumerate(frames):
        sheet.alpha_composite(frame, ((index % 4) * 288, (index // 4) * 288))
    sheet.save(PREVIEW / "progress-cat-frames.png")
    # A design preview only; the app animates the transparent PNGs on CALayer.
    preview_frames = []
    for tick in range(64):
        preview = Image.new("RGB", (640, 180), "#211820")
        draw = ImageDraw.Draw(preview)
        x = round(52 + (640 - 104) * tick / 63)
        draw.rounded_rectangle((52, 137, 588, 143), radius=3, fill="#4a3846")
        if x > 52:
            draw.rounded_rectangle((52, 137, x, 143), radius=3, fill="#ffcedd")
        sprite = frames[tick % 8].resize((104, 104), Image.Resampling.LANCZOS)
        preview.paste(sprite, (x - 52, 42), sprite)
        preview_frames.append(preview)
    preview_frames[0].save(PREVIEW / "walking-cat-progress.gif", save_all=True,
                           append_images=preview_frames[1:], duration=100, loop=0, optimize=False)
    print("Prepared eight transparent cat frames and design preview.")


if __name__ == "__main__":
    main()
