import os
import re
import json
import time
import hashlib
import logging
from pathlib import Path
from typing import Optional, Dict, Any, List, Set, Tuple, Union
import urllib.parse
import urllib.request
import urllib.error

logger = logging.getLogger("ml-service.reel.broll")

# Denylist keywords from docs/GUARDRAILS.md
BROLL_DENYLIST = [
    "body", "belly", "waist", "fat", "obese", "scale", "weighing",
    "measuring tape", "gym", "workout", "bikini", "abs", "diet pills",
    "injection", "syringe", "pills", "hospital bed", "surgery", "doctor"
]

# Tag denylist words for Pixabay hits
PIXABAY_TAG_DENYLIST = [
    "people", "body", "fitness", "gym", "woman", "man",
    "girl", "boy", "model", "beach", "bikini", "scale", "diet",
    "pills", "injection", "hospital"
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

def check_pixabay_tags_denylist(tags_str: str) -> Tuple[bool, Optional[str]]:
    """
    Checks if a Pixabay hit's tags contain denylisted words.
    Returns (is_allowed, matched_word).
    """
    if not tags_str:
        return True, None
    raw_tags = [t.strip().lower() for t in tags_str.split(",")]
    for t in raw_tags:
        for deny in PIXABAY_TAG_DENYLIST:
            pattern = r"\b" + re.escape(deny) + r"\b"
            if re.search(pattern, t) or deny in t:
                return False, deny
    return True, None

def _tokenize_query(query: str) -> Set[str]:
    words = re.findall(r"[a-z0-9]+", query.lower())
    filtered = {w for w in words if len(w) > 1 and w not in STOP_WORDS}
    return filtered if filtered else set(words)

def _infer_category(query: str) -> Optional[str]:
    """Infers optional Pixabay category (&category=food|places)."""
    q_words = set(re.findall(r"[a-z0-9]+", query.lower()))
    food_words = {
        "food", "tea", "coffee", "chai", "roti", "meal", "eating",
        "snack", "breakfast", "dinner", "lunch", "drink", "beverage",
        "bread", "fruit", "kitchen", "cooking", "spice", "spices"
    }
    places_words = {
        "park", "outdoor", "outdoors", "nature", "city", "street",
        "garden", "walk", "walking", "office", "desk", "room", "table"
    }
    if q_words & food_words:
        return "food"
    if q_words & places_words:
        return "places"
    return None

def search_local_library(
    query: str,
    min_seconds: float,
    storage_dir: Path
) -> List[Dict[str, Any]]:
    """
    Matches query words against tags in storage/broll-library/library.json.
    Checked first only if the library has entries.
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

    if not isinstance(items, list) or len(items) == 0:
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
            if any(qw == t or qw in t or t in qw for t in tags):
                score += 1

        if score < 1:
            continue

        orientation = item.get("orientation", "portrait").lower()
        seconds = float(item.get("seconds", 0))

        prefer_portrait = 1 if orientation == "portrait" else 0
        duration_ok = 1 if seconds >= (min_seconds - 0.5) else 0

        candidates.append({
            "score": score,
            "prefer_portrait": prefer_portrait,
            "duration_ok": duration_ok,
            "seconds": seconds,
            "item": item
        })

    candidates.sort(
        key=lambda c: (c["score"], c["prefer_portrait"], c["duration_ok"], c["seconds"]),
        reverse=True
    )

    return candidates

def _get_search_cache(query: str, cache_dir: Path) -> Optional[Dict[str, Any]]:
    """Checks 24-hour disk cache for search query."""
    try:
        cache_dir.mkdir(parents=True, exist_ok=True)
        q_hash = hashlib.md5(query.lower().strip().encode("utf-8")).hexdigest()
        cache_file = cache_dir / f"search_{q_hash}.json"
        if cache_file.exists():
            mtime = cache_file.stat().st_mtime
            if (time.time() - mtime) < 24 * 3600:
                with open(cache_file, "r", encoding="utf-8") as f:
                    return json.load(f)
    except Exception as e:
        logger.warning(f"Error reading search cache: {e}")
    return None

def _save_search_cache(query: str, data: Dict[str, Any], cache_dir: Path) -> None:
    """Saves search response to 24-hour disk cache."""
    try:
        cache_dir.mkdir(parents=True, exist_ok=True)
        q_hash = hashlib.md5(query.lower().strip().encode("utf-8")).hexdigest()
        cache_file = cache_dir / f"search_{q_hash}.json"
        with open(cache_file, "w", encoding="utf-8") as f:
            json.dump(data, f)
    except Exception as e:
        logger.warning(f"Error saving search cache: {e}")

def _call_pixabay_api(url: str) -> Optional[Dict[str, Any]]:
    """
    Executes Pixabay API call with rate limit handling:
    On 429, waits X-RateLimit-Reset seconds, retries once, then returns None.
    """
    req = urllib.request.Request(url, headers={"User-Agent": "SteerProof/1.0"})
    for attempt in range(2):
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            if e.code == 429 and attempt == 0:
                reset_sec = e.headers.get("X-RateLimit-Reset") or e.headers.get("Retry-After")
                wait_sec = 5.0
                if reset_sec:
                    try:
                        wait_sec = max(1.0, float(reset_sec))
                    except ValueError:
                        pass
                logger.warning(f"Pixabay returned 429. Waiting {wait_sec}s before retry...")
                time.sleep(wait_sec)
                continue
            logger.error(f"Pixabay HTTP error: {e.code} {e.reason}")
            return None
        except Exception as e:
            logger.error(f"Pixabay request failed: {e}")
            return None
    return None

def _fetch_pixabay_video(
    query: str,
    min_seconds: float,
    key: str,
    storage_dir: Path
) -> Optional[Dict[str, Any]]:
    """
    Pixabay video search API:
    - &video_type=film, optional &category=food|places, safesearch=true, per_page=10.
    - Skip hits whose tags contain denylisted words.
    - Use videos.medium; fall back to small. NEVER large.
    - 24-hour disk cache of search responses keyed by query.
    - Permanent cache of mp4s by id in storage/broll/pixabay-{id}.mp4.
    - Credits from real response: user, user_id, pageURL.
      Format: "Video by {user} on Pixabay — {pageURL}".
    """
    broll_dir = storage_dir / "broll"
    broll_dir.mkdir(parents=True, exist_ok=True)
    cache_dir = storage_dir / "cache" / "pixabay"

    # Check 24-hour search cache
    data = _get_search_cache(query, cache_dir)
    if data is None:
        category = _infer_category(query)
        cat_param = f"&category={category}" if category else ""
        url = (
            f"https://pixabay.com/api/videos/?key={key}&q={urllib.parse.quote(query)}"
            f"&video_type=film{cat_param}&safesearch=true&per_page=10"
        )
        data = _call_pixabay_api(url)
        # If category narrowed down results to 0, retry without category
        if data and not data.get("hits") and category:
            url_fallback = (
                f"https://pixabay.com/api/videos/?key={key}&q={urllib.parse.quote(query)}"
                f"&video_type=film&safesearch=true&per_page=10"
            )
            data = _call_pixabay_api(url_fallback)

        if data is not None:
            _save_search_cache(query, data, cache_dir)

    if not data:
        return None

    hits = data.get("hits", [])
    if not hits:
        return None

    valid_candidates = []
    for h in hits:
        # 1. Denylist check on tags
        tags_str = h.get("tags", "")
        allowed, matched = check_pixabay_tags_denylist(tags_str)
        if not allowed:
            logger.info(f"Skipping Pixabay hit {h.get('id')} due to denylisted tag '{matched}'")
            continue

        # 2. Size selection: videos.medium, fall back to small. NEVER large.
        videos = h.get("videos", {})
        video_meta = None
        size_used = None
        if videos.get("medium") and videos["medium"].get("url"):
            video_meta = videos["medium"]
            size_used = "medium"
        elif videos.get("small") and videos["small"].get("url"):
            video_meta = videos["small"]
            size_used = "small"

        if not video_meta:
            continue

        dur = float(h.get("duration", 0))
        valid_candidates.append({
            "hit": h,
            "video_meta": video_meta,
            "size_used": size_used,
            "duration": dur,
            "duration_ok": 1 if dur >= min_seconds else 0
        })

    if not valid_candidates:
        return None

    # Pick first hit with duration >= min_seconds, else longest available
    valid_candidates.sort(key=lambda c: (c["duration_ok"], c["duration"]), reverse=True)
    chosen = valid_candidates[0]

    best_hit = chosen["hit"]
    video_meta = chosen["video_meta"]
    size_used = chosen["size_used"]
    hit_id = str(best_hit.get("id"))
    cached_file = broll_dir / f"pixabay-{hit_id}.mp4"

    # Permanent cache of mp4s by id
    if not cached_file.exists() or cached_file.stat().st_size == 0:
        download_url = video_meta["url"]
        logger.info(f"Downloading Pixabay clip {hit_id} to {cached_file}...")
        try:
            urllib.request.urlretrieve(download_url, str(cached_file))
        except Exception as e:
            logger.error(f"Failed to download Pixabay video {download_url}: {e}")
            return None

    user = best_hit.get("user", "Unknown")
    user_id = best_hit.get("user_id")
    page_url = best_hit.get("pageURL", "")
    credit_line = f"Video by {user} on Pixabay — {page_url}"

    return {
        "videoPath": f"broll/{cached_file.name}",
        "source": "pixabay",
        "url": page_url,
        "creator": user,
        "userId": user_id,
        "creditLine": credit_line,
        "tags": best_hit.get("tags", ""),
        "sizeUsed": size_used,
        "duration": float(best_hit.get("duration", 0)),
        "hitId": hit_id,
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
    Main b-roll search for reel scenes:
    1. Local library (storage/broll-library/library.json) checked first ONLY if it has entries.
       Never picks the same clip twice in one reel if another clip matches.
    2. Pixabay API (main source when library is empty or has no match).
    3. Return null (procedural cream motion background) if neither finds anything.
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
                "userId": None,
                "creditLine": None,
                "tags": None,
                "sizeUsed": None,
                "duration": None,
                "hitId": None,
                "pexelsId": None,
                "pexelsUrl": None,
                "photographer": None,
                "photographerUrl": None
            })
            continue

        # Denylist check on query
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
                "userId": None,
                "creditLine": None,
                "tags": None,
                "sizeUsed": None,
                "duration": None,
                "hitId": None,
                "pexelsId": None,
                "pexelsUrl": None,
                "photographer": None,
                "photographerUrl": None
            })
            continue

        # Layer 1: Local Library Check (checked first only if it has entries)
        library_candidates = search_local_library(query, min_sec, storage_dir)
        chosen_candidate = None

        if library_candidates:
            for cand in library_candidates:
                file_name = cand["item"]["file"]
                if file_name not in used_library_files:
                    chosen_candidate = cand["item"]
                    break

            if not chosen_candidate and not pixabay_key:
                chosen_candidate = library_candidates[0]["item"]

        if chosen_candidate:
            used_library_files.add(chosen_candidate["file"])
            creator = chosen_candidate.get("creator")
            source = chosen_candidate.get("source", "library")
            url = chosen_candidate.get("url")
            credit_line = f"Video by {creator} ({source}) — {url}" if url else f"Video by {creator}"

            results.append({
                "n": scene_num,
                "query": query,
                "videoPath": f"broll-library/{chosen_candidate['file']}",
                "reason": None,
                "source": source,
                "url": url,
                "creator": creator,
                "userId": None,
                "creditLine": credit_line,
                "tags": ",".join(chosen_candidate.get("tags", [])),
                "sizeUsed": chosen_candidate.get("orientation", "portrait"),
                "duration": float(chosen_candidate.get("seconds", 0)),
                "hitId": None,
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

        # If all library candidates were used and Pixabay had no hit, reuse best library candidate
        if library_candidates:
            fallback = library_candidates[0]["item"]
            creator = fallback.get("creator")
            source = fallback.get("source", "library")
            url = fallback.get("url")
            credit_line = f"Video by {creator} ({source}) — {url}" if url else f"Video by {creator}"
            results.append({
                "n": scene_num,
                "query": query,
                "videoPath": f"broll-library/{fallback['file']}",
                "reason": None,
                "source": source,
                "url": url,
                "creator": creator,
                "userId": None,
                "creditLine": credit_line,
                "tags": ",".join(fallback.get("tags", [])),
                "sizeUsed": fallback.get("orientation", "portrait"),
                "duration": float(fallback.get("seconds", 0)),
                "hitId": None,
                "pexelsId": None,
                "pexelsUrl": url,
                "photographer": creator,
                "photographerUrl": url
            })
            continue

        # Layer 3: Return null (procedural cream motion background)
        results.append({
            "n": scene_num,
            "query": query,
            "videoPath": None,
            "reason": "NO_MATCH",
            "source": None,
            "url": None,
            "creator": None,
            "userId": None,
            "creditLine": None,
            "tags": None,
            "sizeUsed": None,
            "duration": None,
            "hitId": None,
            "pexelsId": None,
            "pexelsUrl": None,
            "photographer": None,
            "photographerUrl": None
        })

    return results
