"""
Live Pixabay evidence for the reels (WS4 4.2).

    cd ml-service && PYTHONPATH=. .venv/bin/python -m app.reel.broll_evidence \
        --out ../docs/reports/ws4-broll-evidence.md --frames <dir> P-0001 P-0002

1. One raw search for "chai cup": totalHits plus the first hit's id and tags,
   exactly as the API returned them.
2. B-roll selection for every scene's brollQuery of each paper's current REEL
   asset (read from the backend), with one mid-clip frame per chosen clip.
Never prints the API key.
"""
import argparse
import json
import subprocess
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from app.config import get_settings
from app.reel.broll import (
    PIXABAY_VIDEOS_URL, PixabayError, _call_pixabay_api, process_reel_broll, resolve_pixabay_key,
)


def _reel_scenes(backend: str, paper_id: str) -> list[dict]:
    with urllib.request.urlopen(f"{backend}/api/papers/{paper_id}/assets", timeout=15) as r:
        assets = json.loads(r.read().decode("utf-8"))
    reel = next((a for a in assets if a.get("type") == "REEL"), None)
    return (reel or {}).get("content", {}).get("scenes", []) if reel else []


def _frame(clip: Path, out_png: Path) -> bool:
    out_png.parent.mkdir(parents=True, exist_ok=True)
    res = subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-ss", "1", "-i", str(clip), "-frames:v", "1", str(out_png)],
        capture_output=True,
    )
    return res.returncode == 0 and out_png.exists()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("papers", nargs="+")
    ap.add_argument("--out", required=True)
    ap.add_argument("--frames", default=None, help="directory for one frame per chosen clip")
    ap.add_argument("--backend", default="http://localhost:8080")
    args = ap.parse_args()

    settings = get_settings()
    key = resolve_pixabay_key()
    lines = [
        "# WS4 b-roll evidence (Pixabay)",
        "",
        f"Generated {datetime.now(timezone.utc).isoformat(timespec='seconds')} by "
        "`python -m app.reel.broll_evidence`. When a key is loaded, every id, tag, size and credit "
        "below is copied from the live API response; nothing is filled in by hand.",
        "",
        f"- pixabay key loaded: {'yes' if key else 'no'}",
        "",
        "## Live search: \"chai cup\"",
        "",
    ]
    if not key:
        lines.append("Not run: `PIXABAY_API_KEY` is empty in `.env`, so no request was sent.")
    else:
        params = {"key": key, "q": "chai cup", "safesearch": "true", "video_type": "film", "per_page": "10"}
        try:
            data = _call_pixabay_api(f"{PIXABAY_VIDEOS_URL}?{urllib.parse.urlencode(params)}", key)
            hits = data.get("hits") or []
            lines.append(f"- totalHits: {data.get('totalHits')}")
            if hits:
                lines.append(f"- first hit id: {hits[0].get('id')}")
                lines.append(f"- first hit tags: {hits[0].get('tags')}")
            else:
                lines.append("- no hits returned")
        except PixabayError as e:
            lines.append(f"- request failed: {e}")

    storage = Path(settings.storage_dir)
    for paper_id in args.papers:
        scenes = _reel_scenes(args.backend, paper_id)
        lines += ["", f"## {paper_id} reel", ""]
        if not scenes:
            lines.append("No REEL asset found.")
            continue
        results = process_reel_broll(
            [{"n": s.get("n"), "brollQuery": s.get("brollQuery", ""), "minSeconds": 4.0} for s in scenes],
            storage_dir=storage,
        )
        lines += [
            "| Scene | Query | Searched | Chosen id | Tags | Size | Credit line or null reason |",
            "|---|---|---|---|---|---|---|",
        ]
        for r in results:
            size = r.get("sizeUsed") or ""
            if r.get("width") and r.get("height"):
                size = f"{size} ({r['width']}×{r['height']})"
            lines.append(
                f"| {r['n']} | {r['query']} | {r.get('searchedQuery') or ''} | {r.get('hitId') or ''} | "
                f"{r.get('tags') or ''} | {size} | {r.get('creditLine') or 'null: ' + str(r.get('reason'))} |"
            )
            if args.frames and r.get("videoPath"):
                png = Path(args.frames) / f"{paper_id}-scene-{r['n']}-pixabay-{r.get('hitId')}.png"
                _frame(storage / r["videoPath"], png)

    Path(args.out).write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
