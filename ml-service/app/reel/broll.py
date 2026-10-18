import os
import re
import logging
from pathlib import Path
from typing import Optional, Dict, Any, List
import urllib.parse
import urllib.request
import json

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
        # Check boundary or direct phrase match
        pattern = r"\b" + re.escape(word) + r"\b"
        if re.search(pattern, q_lower) or word in q_lower:
            return False, word
    return True, None

def search_pexels_broll(
    query: str,
    min_duration_sec: float,
    storage_dir: Path,
    api_key: Optional[str] = None
) -> Dict[str, Any]:
    """
    Searches Pexels video API for a query, checks denylist, downloads the best portrait clip,
    and caches it under storage/broll/{video_id}.mp4.
    If denied or api_key missing or no results, returns videoPath=None.
    """
    # 1. Denylist check
    allowed, matched = check_broll_denylist(query)
    if not allowed:
        logger.warning(f"B-roll query '{query}' BLOCKED by denylist rule: '{matched}'")
        return {
            "query": query,
            "allowed": False,
            "deniedReason": f"Matched denylist keyword: '{matched}'",
            "videoPath": None,
            "photographer": None,
            "url": None
        }

    # 2. Check API key
    key = api_key or os.environ.get("PEXELS_API_KEY")
    if not key:
        logger.info(f"PEXELS_API_KEY not set. Falling back to plain motion background for query '{query}'.")
        return {
            "query": query,
            "allowed": True,
            "deniedReason": "PEXELS_API_KEY is not configured",
            "videoPath": None,
            "photographer": None,
            "url": None
        }

    broll_dir = storage_dir / "broll"
    broll_dir.mkdir(parents=True, exist_ok=True)

    # 3. Request Pexels API
    try:
        url = f"https://api.pexels.com/videos/search?query={urllib.parse.quote(query)}&orientation=portrait&per_page=10&size=medium"
        req = urllib.request.Request(url, headers={
            "Authorization": key,
            "User-Agent": "SteerProof/1.0"
        })
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode("utf-8"))

        videos = data.get("videos", [])
        if not videos:
            logger.info(f"Pexels returned 0 videos for query '{query}'.")
            return {
                "query": query,
                "allowed": True,
                "deniedReason": "No videos found matching query",
                "videoPath": None,
                "photographer": None,
                "url": None
            }

        # Find best portrait video with duration >= min_duration_sec
        best_video = None
        best_file = None
        for v in videos:
            duration = v.get("duration", 0)
            files = v.get("video_files", [])
            portrait_files = [f for f in files if f.get("height", 0) >= f.get("width", 0)]
            if not portrait_files:
                portrait_files = files
            if portrait_files:
                # pick closest to 1080x1920
                sorted_files = sorted(
                    portrait_files,
                    key=lambda f: abs(f.get("height", 0) - 1920)
                )
                best_video = v
                best_file = sorted_files[0]
                if duration >= min_duration_sec:
                    break

        if not best_video or not best_file:
            return {
                "query": query,
                "allowed": True,
                "deniedReason": "No suitable portrait files found",
                "videoPath": None,
                "photographer": None,
                "url": None
            }

        video_id = best_video["id"]
        cached_file = broll_dir / f"{video_id}.mp4"
        if not cached_file.exists():
            download_url = best_file["link"]
            logger.info(f"Downloading Pexels clip {video_id} to {cached_file}...")
            urllib.request.urlretrieve(download_url, str(cached_file))

        return {
            "query": query,
            "allowed": True,
            "deniedReason": None,
            "videoPath": f"broll/{cached_file.name}",
            "photographer": best_video.get("user", {}).get("name"),
            "url": best_video.get("url")
        }
    except Exception as e:
        logger.error(f"Error querying Pexels API: {e}")
        return {
            "query": query,
            "allowed": True,
            "deniedReason": str(e),
            "videoPath": None,
            "photographer": None,
            "url": None
        }

def process_reel_broll(
    paper_id: str,
    scenes: List[Dict[str, Any]],
    storage_dir: Path,
    api_key: Optional[str] = None
) -> List[Dict[str, Any]]:
    """
    Processes brollQuery for each scene in the reel.
    """
    results = []
    for sc in scenes:
        scene_num = sc.get("n", 1)
        query = sc.get("brollQuery", "").strip()
        min_sec = (sc.get("durationMs", 4000)) / 1000.0
        if not query:
            results.append({
                "n": scene_num,
                "query": "",
                "allowed": True,
                "videoPath": None,
                "photographer": None,
                "url": None
            })
            continue

        res = search_pexels_broll(query, min_sec, storage_dir, api_key)
        res["n"] = scene_num
        results.append(res)

    return results
