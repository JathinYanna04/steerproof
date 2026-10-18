import os
import re
import json
import logging
from pathlib import Path
from typing import Optional, Dict, Any, List, Set, Tuple
import urllib.parse
import urllib.request

logger = logging.getLogger("ml-service.reel.broll")

# Denylist keywords from docs/GUARDRAILS.md
BROLL_DENYLIST = [
    "body", "belly", "waist", "fat", "obese", "scale", "weighing",
    "measuring tape", "gym", "workout", "bikini", "abs", "diet pills",
    "injection", "syringe", "pills", "hospital bed", "surgery", "doctor"
]

STOP_WORDS = {
    "a", "an", "the", "in", "on", "at", "for", "with", "of", "and", "or",
    "by", "to", "from", "is", "it", "this", "that", "into", "onto"
}

def check_broll_denylist(query: str) -> Tuple[bool, Optional[str]]:
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

def _tokenize_query(query: str) -> Set[str]:
    words = re.findall(r"[a-z0-9]+", query.lower())
    filtered = {w for w in words if len(w) > 1 and w not in STOP_WORDS}
    return filtered if filtered else set(words)

def search_local_library(
    query: str,
    min_seconds: float,
    storage_dir: Path
) -> List[Dict[str, Any]]:
    """
    Matches query words against tags in storage/broll-library/library.json.
    Returns candidate clips sorted by (score, prefer_portrait, duration_ok, seconds).
    Only candidates with score >= 1 are returned.
    """
    library_json = storage_dir / "broll-library" / "library.json"
    if not library_json.exists():
        return []

    try:
        with open(library_json, "r", encoding="utf-8") as f:
            items = json.load(f)
    except Exception as e:
        logger.error(f"Failed to read {library_json}: {e}")
        return []

    if not isinstance(items, list):
        return []

    query_words = _tokenize_query(query)
    candidates = []

    for item in items:
        file_name = item.get("file", "")
        if not file_name:
            continue

        file_path = storage_dir / "broll-library" / file_name
        if not file_path.exists():
            continue

        raw_tags = item.get("tags", [])
        tags = [str(t).lower().strip() for t in raw_tags]

        # Calculate word overlap score
        score = 0
        for qw in query_words:
            # Full match or substring match with any tag
            if any(qw == t or qw in t or t in qw for t in tags):
                score += 1

        if score < 1:
            continue

        orientation = item.get("orientation", "portrait").lower()
        seconds = float(item.get("seconds", 0))

        # Sorting metrics:
        # 1. score (highest score first)
        # 2. prefer_portrait (1 for portrait, 0 for landscape)
        # 3. duration_ok (1 if seconds >= min_seconds, 0 otherwise)
        # 4. seconds (longer duration)
        prefer_portrait = 1 if orientation == "portrait" else 0
        duration_ok = 1 if seconds >= (min_seconds - 0.5) else 0

        candidates.append({
            "score": score,
            "prefer_portrait": prefer_portrait,
            "duration_ok": duration_ok,
            "seconds": seconds,
            "item": item
        })

    # Sort descending
    candidates.sort(
        key=lambda c: (c["score"], c["prefer_portrait"], c["duration_ok"], c["seconds"]),
        reverse=True
    )

    return candidates

def _fetch_pixabay_video(
    query: str,
    min_seconds: float,
    key: str,
    storage_dir: Path
) -> Optional[Dict[str, Any]]:
    """
    Layer 2: Pixabay video search API.
    GET https://pixabay.com/api/videos/?key={key}&q={query}&safesearch=true&per_page=10
    Picks the first hit with duration >= min_seconds and downloads 'medium' or 'large' size.
    """
    broll_dir = storage_dir / "broll"
    broll_dir.mkdir(parents=True, exist_ok=True)

    url = (
        f"https://pixabay.com/api/videos/?key={key}&q={urllib.parse.quote(query)}"
        f"&safesearch=true&per_page=10"
    )
    req = urllib.request.Request(url, headers={"User-Agent": "SteerProof/1.0"})

    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        logger.error(f"Pixabay request failed for query '{query}': {e}")
        return None

    hits = data.get("hits", [])
    if not hits:
        return None

    # Pick first hit with duration >= min_seconds, or best available hit
    best_hit = None
    for h in hits:
        dur = float(h.get("duration", 0))
        if dur >= min_seconds:
            best_hit = h
            break
    if not best_hit:
        best_hit = hits[0]

    videos = best_hit.get("videos", {})
    # Choose medium or large
    video_meta = videos.get("medium") or videos.get("large") or videos.get("small")
    if not video_meta or not video_meta.get("url"):
        return None

    hit_id = str(best_hit.get("id"))
    cached_file = broll_dir / f"pixabay-{hit_id}.mp4"

    if not cached_file.exists() or cached_file.stat().st_size == 0:
        download_url = video_meta["url"]
        logger.info(f"Downloading Pixabay clip {hit_id} to {cached_file}...")
        urllib.request.urlretrieve(download_url, str(cached_file))

    user = best_hit.get("user")
    page_url = best_hit.get("pageURL")

    return {
        "videoPath": f"broll/{cached_file.name}",
        "source": "pixabay",
        "url": page_url,
        "creator": user,
        "pexelsId": f"pixabay-{hit_id}",
        "pexelsUrl": page_url,
        "photographer": user,
        "photographerUrl": page_url
    }

def process_reel_broll(
    scenes: List[Dict[str, Any]],
    storage_dir: Path,
    api_key: Optional[str] = None
) -> List[Dict[str, Any]]:
    """
    Main two-layer b-roll search for reel scenes:
    1. Local library (storage/broll-library/library.json) checked first.
       Never picks the same clip twice in one reel if another clip matches.
    2. Pixabay API (only if no library match scores >= 1 tag, and PIXABAY_API_KEY is set).
    3. Return null (cream motion background) if neither finds anything.
    """
    results = []
    used_library_files: Set[str] = set()

    pixabay_key = api_key or os.environ.get("PIXABAY_API_KEY")

    for sc in scenes:
        scene_num = sc.get("n", 1)
        query = sc.get("brollQuery", "").strip()
        min_sec = float(sc.get("minSeconds") or (sc.get("durationMs", 4000) / 1000.0))

        if not query:
            results.append({
                "n": scene_num,
                "query": "",
                "videoPath": None,
                "reason": "EMPTY_QUERY",
                "source": None,
                "url": None,
                "creator": None,
                "pexelsId": None,
                "pexelsUrl": None,
                "photographer": None,
                "photographerUrl": None
            })
            continue

        # Denylist check
        allowed, matched = check_broll_denylist(query)
        if not allowed:
            logger.warning(f"B-roll query '{query}' BLOCKED by denylist: '{matched}'")
            results.append({
                "n": scene_num,
                "query": query,
                "videoPath": None,
                "reason": f"DENYLIST:{matched}",
                "source": None,
                "url": None,
                "creator": None,
                "pexelsId": None,
                "pexelsUrl": None,
                "photographer": None,
                "photographerUrl": None
            })
            continue

        # Layer 1: Local Library Check
        library_candidates = search_local_library(query, min_sec, storage_dir)
        chosen_candidate = None

        if library_candidates:
            # Prefer clip that hasn't been used yet in this reel
            for cand in library_candidates:
                file_name = cand["item"]["file"]
                if file_name not in used_library_files:
                    chosen_candidate = cand["item"]
                    break

            # If all matching candidates are already used:
            # Only reuse if Pixabay is not available or doesn't return anything
            if not chosen_candidate and not pixabay_key:
                chosen_candidate = library_candidates[0]["item"]

        if chosen_candidate:
            used_library_files.add(chosen_candidate["file"])
            creator = chosen_candidate.get("creator")
            source = chosen_candidate.get("source", "pexels")
            url = chosen_candidate.get("url")

            results.append({
                "n": scene_num,
                "query": query,
                "videoPath": f"broll-library/{chosen_candidate['file']}",
                "reason": None,
                "source": source,
                "url": url,
                "creator": creator,
                "pexelsId": None,
                "pexelsUrl": url,
                "photographer": creator,
                "photographerUrl": url
            })
            continue

        # Layer 2: Pixabay API
        if pixabay_key:
            pixabay_res = _fetch_pixabay_video(query, min_sec, pixabay_key, storage_dir)
            if pixabay_res:
                pixabay_res["n"] = scene_num
                pixabay_res["query"] = query
                pixabay_res["reason"] = None
                results.append(pixabay_res)
                continue

        # If all library candidates were used, and Pixabay had no hit, fall back to best library candidate
        if library_candidates:
            fallback = library_candidates[0]["item"]
            creator = fallback.get("creator")
            source = fallback.get("source", "pexels")
            url = fallback.get("url")
            results.append({
                "n": scene_num,
                "query": query,
                "videoPath": f"broll-library/{fallback['file']}",
                "reason": None,
                "source": source,
                "url": url,
                "creator": creator,
                "pexelsId": None,
                "pexelsUrl": url,
                "photographer": creator,
                "photographerUrl": url
            })
            continue

        # Layer 3: Return null (cream motion background)
        results.append({
            "n": scene_num,
            "query": query,
            "videoPath": None,
            "reason": "NO_MATCH",
            "source": None,
            "url": None,
            "creator": None,
            "pexelsId": None,
            "pexelsUrl": None,
            "photographer": None,
            "photographerUrl": None
        })

    return results
