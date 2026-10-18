"""Contact sheet (carousel slides + static sizes) and mid-scene reel frame grabs for one paper.

usage: python3 scripts/contact_sheets.py P-0001 [--api http://localhost:8080] [--storage storage] [--out storage/debug]

Writes <out>/<paper>-contact-sheet.png and <out>/<paper>-reel-frames.png. Needs ffmpeg.
"""
import argparse
import json
import subprocess
import tempfile
import urllib.request
from pathlib import Path


def ffmpeg(*args: str) -> None:
    subprocess.run(["ffmpeg", "-v", "error", "-y", *args], check=True)


def tile(images: list[Path], out: Path, height: int) -> None:
    inputs = [a for img in images for a in ("-i", str(img))]
    scaled = "".join(f"[{i}:v]scale=-2:{height}[v{i}];" for i in range(len(images)))
    stacked = "".join(f"[v{i}]" for i in range(len(images))) + f"hstack=inputs={len(images)}"
    ffmpeg(*inputs, "-filter_complex", scaled + stacked, str(out))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("paper")
    ap.add_argument("--api", default="http://localhost:8080")
    ap.add_argument("--storage", default="storage")
    ap.add_argument("--out", default="storage/debug")
    a = ap.parse_args()

    storage, out = Path(a.storage), Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    assets = json.load(urllib.request.urlopen(f"{a.api}/api/papers/{a.paper}/assets"))
    by_type = {x["type"]: x for x in assets}

    images = []
    for t in ("CAROUSEL", "STATIC"):
        for p in (by_type.get(t) or {}).get("renderUrls") or []:
            path = storage / p
            if path.suffix == ".png" and path.exists():
                images.append(path)
    if images:
        tile(images, out / f"{a.paper}-contact-sheet.png", 1350)
        print(f"contact sheet: {len(images)} images -> {out / f'{a.paper}-contact-sheet.png'}")

    reel = by_type.get("REEL")
    mp4 = next((storage / p for p in (reel or {}).get("renderUrls") or [] if p.endswith(".mp4")), None)
    if reel and mp4 and mp4.exists():
        with tempfile.TemporaryDirectory() as tmp:
            frames = []
            for s in reel["content"]["scenes"]:
                if s.get("startSec") is None:
                    continue
                t = (s["startSec"] + s["endSec"]) / 2
                f = Path(tmp) / f"scene-{s['n']}.png"
                ffmpeg("-ss", f"{t:.2f}", "-i", str(mp4), "-frames:v", "1", str(f))
                frames.append(f)
            if frames:
                tile(frames, out / f"{a.paper}-reel-frames.png", 1920)
                print(f"reel frames: {len(frames)} scenes -> {out / f'{a.paper}-reel-frames.png'}")


if __name__ == "__main__":
    main()
