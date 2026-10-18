import os
import sys
import json
import subprocess
import urllib.request
from pathlib import Path

def rerender_paper(paper_id: str, asset_id: str, citation: str, api_key: str = None):
    print(f"\n==================================================")
    print(f"Processing Reel for {paper_id} ({asset_id})")
    print(f"==================================================")

    # 1. Fetch REEL asset
    req = urllib.request.urlopen(f"http://localhost:8080/api/papers/{paper_id}/assets")
    assets = json.loads(req.read().decode("utf-8"))
    reel_asset = next((a for a in assets if a["type"] == "REEL"), None)
    if not reel_asset:
        print(f"Error: No REEL asset found for {paper_id}")
        return None

    scenes = reel_asset["content"]["scenes"]

    # 2. Query B-Roll from ml-service
    broll_req_data = {
        "assetId": asset_id,
        "paperId": paper_id,
        "scenes": [
            {
                "n": s["n"],
                "brollQuery": s.get("brollQuery", ""),
                "minSeconds": 4.0
            }
            for s in scenes
        ]
    }
    if api_key:
        broll_req_data["apiKey"] = api_key

    broll_req = urllib.request.Request(
        "http://localhost:8001/reel/broll",
        data=json.dumps(broll_req_data).encode("utf-8"),
        headers={"Content-Type": "application/json"}
    )
    broll_resp = json.loads(urllib.request.urlopen(broll_req).read().decode("utf-8"))
    broll_results = {r["n"]: r for r in broll_resp.get("results", [])}

    # 3. Audio Synthesis
    audio_req = urllib.request.Request(
        "http://localhost:8001/reel/audio",
        data=json.dumps({
            "assetId": asset_id,
            "scenes": scenes,
            "language": "en",
            "voice": "en-IN-NeerjaNeural"
        }).encode("utf-8"),
        headers={"Content-Type": "application/json"}
    )
    audio_resp = json.loads(urllib.request.urlopen(audio_req).read().decode("utf-8"))
    print(f"✓ Audio synthesized: {audio_resp['totalDurationMs']} ms total duration")

    # 4. Prepare scenes for Remotion
    rendered_scenes = []
    updated_asset_scenes = []
    report_rows = []

    for s in audio_resp["scenes"]:
        n = s["n"]
        bmeta = broll_results.get(n, {})
        video_path = bmeta.get("videoPath")
        broll_src = f"http://localhost:8002/files/{video_path}" if video_path else None

        rendered_scenes.append({
            "n": n,
            "onScreen": s.get("onScreen", ""),
            "motion": s.get("motion", "title"),
            "kind": s.get("kind", "brand"),
            "claimIds": s.get("claimIds", []),
            "audioSrc": f"http://localhost:8002/files/audio/{asset_id}/scene-{n}.mp3",
            "durationMs": s["durationMs"],
            "words": s["words"],
            "brollSrc": broll_src
        })

        # Save metadata back to asset
        orig_s = next(x for x in scenes if x["n"] == n)
        u_scene = dict(orig_s)
        u_scene.update({
            "videoPath": video_path,
            "brollSrc": broll_src,
            "source": bmeta.get("source"),
            "creator": bmeta.get("creator"),
            "userId": bmeta.get("userId"),
            "creditLine": bmeta.get("creditLine"),
            "url": bmeta.get("url"),
            "tags": bmeta.get("tags"),
            "sizeUsed": bmeta.get("sizeUsed"),
            "hitId": bmeta.get("hitId")
        })
        updated_asset_scenes.append(u_scene)

        report_rows.append({
            "scene": n,
            "query": bmeta.get("query", ""),
            "hitId": bmeta.get("hitId") or "N/A",
            "tags": bmeta.get("tags") or "N/A",
            "size": bmeta.get("sizeUsed") or "N/A",
            "duration": f"{bmeta.get('duration')}s" if bmeta.get("duration") else "N/A",
            "creditLine": bmeta.get("creditLine") or "Procedural motion background"
        })

    # 5. Call render-service POST /reel
    print(f"Rendering Remotion reel with {len(rendered_scenes)} scenes...")
    reel_req = urllib.request.Request(
        "http://localhost:8002/reel",
        data=json.dumps({
            "paperId": paper_id,
            "scenes": rendered_scenes,
            "citation": citation,
            "draft": True
        }).encode("utf-8"),
        headers={"Content-Type": "application/json"}
    )
    reel_resp = json.loads(urllib.request.urlopen(reel_req, timeout=120).read().decode("utf-8"))
    print(f"✓ Remotion render completed: {reel_resp}")

    # 6. Capture frame grabs contact sheet (10%, 30%, 50%, 70%, 90%)
    video_file = Path(f"storage/renders/{paper_id}/reel.mp4")
    if not video_file.exists():
        print(f"Error: {video_file} not found after render")
        return None

    # Get duration via ffprobe
    probe_cmd = [
        "ffprobe", "-v", "error", "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1", str(video_file)
    ]
    dur_str = subprocess.check_output(probe_cmd).decode("utf-8").strip()
    total_dur = float(dur_str)

    debug_dir = Path("storage/debug")
    debug_dir.mkdir(parents=True, exist_ok=True)
    frame_files = []
    percentages = [0.10, 0.30, 0.50, 0.70, 0.90]

    for idx, pct in enumerate(percentages):
        timestamp = total_dur * pct
        frame_path = debug_dir / f"{paper_id}-frame-{idx+1}.png"
        subprocess.run([
            "ffmpeg", "-y", "-ss", f"{timestamp:.2f}",
            "-i", str(video_file),
            "-frames:v", "1", "-q:v", "2",
            str(frame_path)
        ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
        frame_files.append(frame_path)

    # Tile frames side-by-side using ffmpeg
    contact_sheet = debug_dir / f"{paper_id}-reel-frames.png"
    filter_complex = "".join([f"[{i}:v]" for i in range(len(frame_files))]) + f"hstack=inputs={len(frame_files)}[v]"
    hstack_cmd = ["ffmpeg", "-y"]
    for ff in frame_files:
        hstack_cmd.extend(["-i", str(ff)])
    hstack_cmd.extend(["-filter_complex", filter_complex, "-map", "[v]", str(contact_sheet)])
    subprocess.run(hstack_cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
    for ff in frame_files:
        ff.unlink(missing_ok=True)
    print(f"✓ Saved contact sheet: {contact_sheet}")

    # 7. Update REEL asset content in Postgres
    updated_content = dict(reel_asset["content"])
    updated_content["scenes"] = updated_asset_scenes
    content_json_str = json.dumps(updated_content).replace("'", "''")

    update_sql = f"""
    UPDATE assets
    SET content = '{content_json_str}',
        status = 'APPROVED',
        render_paths = '["renders/{paper_id}/reel.mp4"]'
    WHERE id = '{asset_id}';
    """
    subprocess.run([
        "docker", "exec", "-i", "steerproof-postgres",
        "psql", "-U", "steer", "-d", "steerproof", "-c", update_sql
    ], check=True, stdout=subprocess.DEVNULL)
    print(f"✓ Updated asset {asset_id} in database")

    return report_rows

def main():
    api_key = os.environ.get("PIXABAY_API_KEY")
    if not api_key and len(sys.argv) > 1:
        api_key = sys.argv[1].strip()

    if not api_key:
        print("WARNING: PIXABAY_API_KEY is not set. Clips will fall back to procedural motion.")

    p1_rows = rerender_paper(
        "P-0001", "A-0001-REEL",
        "Hall et al., 2018 · Med Clin North Am",
        api_key
    )
    p2_rows = rerender_paper(
        "P-0002", "A-0002-REEL",
        "Wilding et al., 2022 · Diabetes Obes Metab",
        api_key
    )

    print("\n" + "="*80)
    print("REEL B-ROLL REPORT (P-0001):")
    print("="*80)
    print(f"{'Scene':<6} | {'Query':<30} | {'Hit ID':<10} | {'Size':<8} | {'Dur':<6} | {'Credit Line'}")
    print("-" * 100)
    for r in (p1_rows or []):
        print(f"{r['scene']:<6} | {r['query']:<30} | {r['hitId']:<10} | {r['size']:<8} | {r['duration']:<6} | {r['creditLine']}")

    print("\n" + "="*80)
    print("REEL B-ROLL REPORT (P-0002):")
    print("="*80)
    print(f"{'Scene':<6} | {'Query':<30} | {'Hit ID':<10} | {'Size':<8} | {'Dur':<6} | {'Credit Line'}")
    print("-" * 100)
    for r in (p2_rows or []):
        print(f"{r['scene']:<6} | {r['query']:<30} | {r['hitId']:<10} | {r['size']:<8} | {r['duration']:<6} | {r['creditLine']}")

if __name__ == "__main__":
    main()
