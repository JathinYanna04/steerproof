import os
import re
import json
import logging
from pathlib import Path
from typing import Optional, Dict, Any, List
import urllib.parse
import urllib.request

logger = logging.getLogger("ml-service.reel.broll")

# Denylist keywords from docs/GUARDRAILS.md
BROLL_DENYLIST = [
    "body", "belly", "waist", "fat", "obese", "scale", "weighing",
    "measuring tape", "gym", "workout", "bikini", "abs", "diet pills",
    "injection", "syringe", "pills", "hospital bed", "surgery", "doctor"
]

def check_broll_denylist(query: str) -> tuple[bool, Optional[str]]:
    """
    Checks if a query violates the b-roll denylist.
    Returns (is_allowed, matched_keyword).
    """
    q_lower = query.lower()
    for word in BROLL_DENYLIST:
        pattern = r"\b" + re.escape(word) + r"\b"
        if re.search(pattern, q_lower) or word in q_lower:
            return False, word
    return True, None

def _fetch_pexels_video(query: str, min_seconds: float, key: str, storage_dir: Path) -> Optional[Dict[str, Any]]:
    broll_dir = storage_dir / "broll"
    broll_dir.mkdir(parents=True, exist_ok=True)

    url = f"https://api.pexels.com/videos/search?query={urllib.parse.quote(query)}&orientation=portrait&per_page=10"
    req = urllib.request.Request(url, headers={
        "Authorization": key,
        "User-Agent": "SteerProof/1.0"
    })

    with urllib.request.urlopen(req, timeout=10) as resp:
        data = json.loads(resp.read().decode("utf-8"))

    videos = data.get("videos", [])
    if not videos:
        return None

    # Pick first video with duration >= min_seconds
    best_video = None
    best_file = None
    for v in videos:
        duration = v.get("duration", 0)
        files = v.get("video_files", [])
        # Portrait files
        portrait_files = [f for f in files if f.get("height", 0) >= f.get("width", 0)]
        if not portrait_files:
            portrait_files = files
        if portrait_files:
            # Prefer height <= 1920 and closest to 1920 (prefer >= 1280)
            sorted_files = sorted(
                portrait_files,
                key=lambda f: (
                    0 if 1280 <= f.get("height", 0) <= 1920 else 1,
                    abs(f.get("height", 0) - 1920)
                )
            )
            best_video = v
            best_file = sorted_files[0]
            if duration >= min_seconds:
                break

    if not best_video or not best_file:
        return None

    video_id = str(best_video["id"])
    cached_file = broll_dir / f"{video_id}.mp4"
    if not cached_file.exists() or cached_file.stat().st_size == 0:
        download_url = best_file["link"]
        logger.info(f"Downloading Pexels clip {video_id} to {cached_file}...")
        urllib.request.urlretrieve(download_url, str(cached_file))

    return {
        "videoPath": f"broll/{cached_file.name}",
        "pexelsId": video_id,
        "pexelsUrl": best_video.get("url"),
        "photographer": best_video.get("user", {}).get("name"),
        "photographerUrl": best_video.get("user", {}).get("url")
    }

def search_pexels_broll(
    query: str,
    min_seconds: float,
    storage_dir: Path,
    api_key: Optional[str] = None
) -> Dict[str, Any]:
    # 1. Denylist check
    allowed, matched = check_broll_denylist(query)
    if not allowed:
        logger.warning(f"B-roll query '{query}' BLOCKED by denylist: '{matched}'")
        return {
            "query": query,
            "videoPath": None,
            "reason": "DENYLIST",
            "pexelsId": None,
            "pexelsUrl": None,
            "photographer": None,
            "photographerUrl": None
        }

    # 2. Check API key
    key = api_key or os.environ.get("PEXELS_API_KEY")
    if not key:
        return {
            "query": query,
            "videoPath": None,
            "reason": "PEXELS_API_KEY_UNSET",
            "pexelsId": None,
            "pexelsUrl": None,
            "photographer": None,
            "photographerUrl": None
        }

    # 3. Primary search
    try:
        res = _fetch_pexels_video(query, min_seconds, key, storage_dir)
        if res:
            res["query"] = query
            res["reason"] = None
            return res

        # Retry once with simpler query (first word only)
        first_word = query.split()[0] if query.split() else query
        if first_word and first_word.lower() != query.lower():
            logger.info(f"Retrying Pexels with first word '{first_word}'...")
            res_retry = _fetch_pexels_video(first_word, min_seconds, key, storage_dir)
            if res_retry:
                res_retry["query"] = query
                res_retry["reason"] = None
                return res_retry
    except Exception as e:
        logger.error(f"Error querying Pexels: {e}")
        return {
            "query": query,
            "videoPath": None,
            "reason": str(e),
            "pexelsId": None,
            "pexelsUrl": None,
            "photographer": None,
            "photographerUrl": None
        }

    return {
        "query": query,
        "videoPath": None,
        "reason": "NO_RESULTS",
        "pexelsId": None,
        "pexelsUrl": None,
        "photographer": None,
        "photographerUrl": None
    }

def process_reel_broll(
    scenes: List[Dict[str, Any]],
    storage_dir: Path,
    api_key: Optional[str] = None
) -> List[Dict[str, Any]]:
    results = []
    for sc in scenes:
        scene_num = sc.get("n", 1)
        query = sc.get("brollQuery", "").strip()
        min_sec = sc.get("minSeconds") or (sc.get("durationMs", 4000) / 1000.0)

        if not query:
            results.append({
                "n": scene_num,
                "query": "",
                "videoPath": None,
                "reason": "EMPTY_QUERY",
                "pexelsId": None,
                "pexelsUrl": None,
                "photographer": None,
                "photographerUrl": None
            })
            continue

        res = search_pexels_broll(query, min_sec, storage_dir, api_key)
        res["n"] = scene_num
        results.append(res)

    return results
