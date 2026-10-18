import os
import sys
import json
import subprocess
from pathlib import Path
from typing import Dict, List, Any, Optional

def get_ffprobe_info(file_path: Path) -> Dict[str, Any]:
    """Probes video duration and dimensions via ffprobe."""
    # 1. Duration
    cmd_dur = [
        "ffprobe", "-v", "error",
        "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1",
        str(file_path)
    ]
    res_dur = subprocess.run(cmd_dur, capture_output=True, text=True)
    duration = None
    if res_dur.returncode == 0 and res_dur.stdout.strip():
        try:
            duration = float(res_dur.stdout.strip())
        except ValueError:
            pass

    # 2. Dimensions
    cmd_dim = [
        "ffprobe", "-v", "error",
        "-select_streams", "v:0",
        "-show_entries", "stream=width,height",
        "-of", "csv=p=0:s=x",
        str(file_path)
    ]
    res_dim = subprocess.run(cmd_dim, capture_output=True, text=True)
    width, height = None, None
    if res_dim.returncode == 0 and res_dim.stdout.strip():
        parts = res_dim.stdout.strip().split("x")
        if len(parts) == 2:
            try:
                width, height = int(parts[0]), int(parts[1])
            except ValueError:
                pass

    return {
        "duration": duration,
        "width": width,
        "height": height
    }

def validate_library(library_path: Optional[Path] = None) -> int:
    """
    Validates library.json:
    - Files exist on disk
    - Non-empty tags
    - Durations and orientations match via ffprobe
    - Prints tag coverage list
    """
    if library_path is None:
        # Check standard locations
        candidates = [
            Path(os.environ.get("STORAGE_DIR", "")) / "broll-library" / "library.json",
            Path("/home/yjr/steerproof/storage/broll-library/library.json"),
            Path("../storage/broll-library/library.json"),
            Path("storage/broll-library/library.json")
        ]
        for c in candidates:
            if c.exists():
                library_path = c.resolve()
                break

    if not library_path or not library_path.exists():
        print(f"Error: library.json not found in storage/broll-library/")
        return 1

    broll_dir = library_path.parent
    print(f"=== SteerProof B-Roll Library Check ===")
    print(f"Manifest: {library_path}")
    print(f"Clips Dir: {broll_dir}\n")

    try:
        with open(library_path, "r", encoding="utf-8") as f:
            items = json.load(f)
    except Exception as e:
        print(f"✗ Error: Failed to parse {library_path} as JSON: {e}")
        return 1

    if not isinstance(items, list):
        print(f"✗ Error: library.json must contain a JSON array of clip objects.")
        return 1

    errors = []
    warnings = []
    tag_coverage: Dict[str, List[str]] = {}

    print(f"Found {len(items)} clip entry/entries in library.json:\n")

    for idx, item in enumerate(items, start=1):
        file_name = item.get("file", "")
        if not file_name:
            errors.append(f"Item #{idx}: missing 'file' attribute")
            continue

        clip_path = broll_dir / file_name
        if not clip_path.exists():
            errors.append(f"Item #{idx} ({file_name}): clip file does NOT exist at {clip_path}")
            continue

        # Check tags
        raw_tags = item.get("tags")
        if not raw_tags or not isinstance(raw_tags, list) or len(raw_tags) == 0:
            errors.append(f"Item #{idx} ({file_name}): 'tags' must be a non-empty list")
            continue

        valid_tags = [str(t).lower().strip() for t in raw_tags if str(t).strip()]
        if len(valid_tags) == 0:
            errors.append(f"Item #{idx} ({file_name}): 'tags' contains no non-empty strings")
            continue

        for tag in valid_tags:
            tag_coverage.setdefault(tag, []).append(file_name)

        # ffprobe validation
        probe = get_ffprobe_info(clip_path)
        probed_dur = probe.get("duration")
        stated_dur = item.get("seconds")

        if probed_dur is None:
            warnings.append(f"Item #{idx} ({file_name}): ffprobe could not detect duration")
        elif stated_dur is not None:
            diff = abs(probed_dur - float(stated_dur))
            if diff > 1.5:
                warnings.append(
                    f"Item #{idx} ({file_name}): stated duration ({stated_dur}s) differs from probed ({probed_dur:.2f}s)"
                )

        w, h = probe.get("width"), probe.get("height")
        probed_orientation = "unknown"
        if w and h:
            probed_orientation = "portrait" if h >= w else "landscape"
            stated_orientation = item.get("orientation", "").lower()
            if stated_orientation and stated_orientation != probed_orientation:
                warnings.append(
                    f"Item #{idx} ({file_name}): stated orientation '{stated_orientation}' differs from probed '{probed_orientation}' ({w}x{h})"
                )

        dim_str = f"{w}x{h}" if w and h else "unknown dim"
        dur_str = f"{probed_dur:.1f}s" if probed_dur else f"{stated_dur}s"
        source = item.get("source", "stock")
        creator = item.get("creator", "Unknown")

        print(f"  [{idx}/{len(items)}] ✓ {file_name}")
        print(f"        Duration: {dur_str} | Orientation: {probed_orientation} ({dim_str})")
        print(f"        Source: {source} by '{creator}' | Tags ({len(valid_tags)}): {', '.join(valid_tags)}")

    print("\n" + "=" * 55)
    print(f"Tag Coverage Summary ({len(tag_coverage)} unique tag(s)):")
    print("=" * 55)

    for tag in sorted(tag_coverage.keys()):
        matching_clips = tag_coverage[tag]
        print(f"  • {tag:<18}: {len(matching_clips)} clip(s) [{', '.join(matching_clips)}]")

    print("\n" + "=" * 55)
    if warnings:
        print(f"Warnings ({len(warnings)}):")
        for w in warnings:
            print(f"  ! {w}")
        print()

    if errors:
        print(f"✗ VALIDATION FAILED: {len(errors)} error(s) detected:")
        for err in errors:
            print(f"  ✗ {err}")
        return 1
    else:
        print(f"✓ VALIDATION PASSED: All {len(items)} library clip(s) verified successfully.")
        return 0

if __name__ == "__main__":
    from typing import Optional
    sys.exit(validate_library())
