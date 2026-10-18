import os
import re
import json
import time
import hashlib
import logging
from pathlib import Path
from typing import Optional, Dict, Any, List, Set, Tuple
import urllib.parse
import urllib.request
import urllib.error

logger = logging.getLogger("ml-service.reel.broll")

PIXABAY_VIDEOS_URL = "https://pixabay.com/api/videos/"
SEARCH_CACHE_TTL_SECONDS = 24 * 3600
# Upper bound on a single 429 back-off so a bad header can't hang a render.
MAX_RATE_LIMIT_WAIT_SECONDS = 120.0

# Denylist keywords for the writer's brollQuery (docs/GUARDRAILS.md)
BROLL_DENYLIST = [
    "body", "belly", "waist", "fat", "obese", "scale", "weighing",
    "measuring tape", "gym", "workout", "bikini", "abs", "diet pills",
    "injection", "syringe", "pills", "hospital bed", "surgery", "doctor"
]

# Tag denylist for Pixabay hits (WS4 spec 4.3). Matched against whole word
# tokens: Pixabay `tags` is a comma-separated list of phrases ("tea, hot drink"),
# so each phrase is split into words and a hit is skipped when any word equals a
# denylisted term. Whole-word matching keeps "man" from matching "manual" or
# "mango"; simple plurals ("women", "models") are normalised first.
PIXABAY_TAG_DENYLIST = {
    "people", "person", "body", "fitness", "gym", "woman", "man", "girl", "boy",
    "model", "beach", "bikini", "scale", "diet", "pills", "injection", "hospital",
    "surgery", "belly", "fat", "weight",
}

_PLURAL_FORMS = {
    "women": "woman", "men": "man", "girls": "girl", "boys": "boy",
    "models": "model", "persons": "person", "bodies": "body", "beaches": "beach",
    "bikinis": "bikini", "scales": "scale", "diets": "diet", "pill": "pills",
    "injections": "injection", "hospitals": "hospital", "surgeries": "surgery",
    "bellies": "belly", "weights": "weight", "gyms": "gym",
}

# Concrete nouns preferred when simplifying a query for the one retry (4.4).
CONCRETE_NOUNS = {
    "chai", "tea", "coffee", "cup", "mug", "roti", "tawa", "chapati", "bread",
    "rice", "dal", "curry", "spices", "spice", "vegetables", "vegetable", "fruit",
    "fruits", "market", "bowl", "plate", "thali", "kitchen", "counter", "stove",
    "desk", "laptop", "office", "notebook", "book", "calendar", "clock", "window",
    "park", "path", "garden", "street", "road", "tree", "trees", "sunrise",
    "table", "water", "glass", "lamp", "plant", "leaves", "rain", "city",
}

# Adjectives / gerunds that are never the "most concrete noun" of a query.
_DESCRIPTORS = {
    "steaming", "hot", "fresh", "freshly", "cooked", "bright", "clean", "morning",
    "evening", "quiet", "calm", "warm", "cozy", "busy", "empty", "small", "big",
    "minimalist", "wooden", "turning", "open", "slow", "close", "up", "closeup",
    "indian", "traditional", "black", "green", "white", "golden", "soft",
}

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
        if re.search(pattern, q_lower):
            return False, word
    return True, None


def _tag_tokens(tags_str: str) -> List[str]:
    words = re.findall(r"[a-z]+", (tags_str or "").lower())
    return [_PLURAL_FORMS.get(w, w) for w in words]


def check_pixabay_tags_denylist(tags_str: str) -> Tuple[bool, Optional[str]]:
    """
    Checks if a Pixabay hit's tags contain a denylisted word (whole-word match).
    Returns (is_allowed, matched_word).
    """
    for token in _tag_tokens(tags_str):
        if token in PIXABAY_TAG_DENYLIST:
            return False, token
    return True, None


def _tokenize_query(query: str) -> Set[str]:
    words = re.findall(r"[a-z0-9]+", query.lower())
    filtered = {w for w in words if len(w) > 1 and w not in STOP_WORDS}
    return filtered if filtered else set(words)


def simplify_query(query: str) -> Optional[str]:
    """
    The most concrete noun of a query, used for the single retry when the full
    query has no usable hit. Prefers a known concrete noun (first occurrence),
    else the last word that isn't a stop word or descriptor. None when the
    simplified query would equal the original.
    """
    words = [w for w in re.findall(r"[a-z]+", query.lower()) if w not in STOP_WORDS]
    pick = next((w for w in words if w in CONCRETE_NOUNS), None)
    if pick is None:
        nouns = [w for w in words if w not in _DESCRIPTORS]
        pick = nouns[-1] if nouns else None
    if not pick or pick == query.strip().lower():
        return None
    return pick


def _infer_category(query: str) -> Optional[str]:
    """Infers optional Pixabay category (&category=food|places) when obvious."""
    q_words = set(re.findall(r"[a-z0-9]+", query.lower()))
    food_words = {
        "food", "tea", "coffee", "chai", "roti", "tawa", "meal", "snack",
        "breakfast", "dinner", "lunch", "drink", "beverage", "bread", "fruit",
        "cooking", "spice", "spices", "vegetable", "vegetables", "rice", "dal",
    }
    places_words = {
        "park", "outdoor", "outdoors", "nature", "city", "street", "garden",
        "market", "path",
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

class PixabayError(Exception):
    """Search failed for a reason other than 'no results' (network, HTTP, 429 twice)."""


def _search_cache_file(query: str, category: Optional[str], cache_dir: Path) -> Path:
    key = f"{query.lower().strip()}|{category or ''}"
    return cache_dir / f"search_{hashlib.md5(key.encode('utf-8')).hexdigest()}.json"


def _get_search_cache(query: str, category: Optional[str], cache_dir: Path) -> Optional[Dict[str, Any]]:
    """Returns the cached search response if it is younger than 24 hours."""
    cache_file = _search_cache_file(query, category, cache_dir)
    try:
        if cache_file.exists() and (time.time() - cache_file.stat().st_mtime) < SEARCH_CACHE_TTL_SECONDS:
            with open(cache_file, "r", encoding="utf-8") as f:
                return json.load(f)
    except Exception as e:
        logger.warning(f"Error reading Pixabay search cache: {e}")
    return None


def _save_search_cache(query: str, category: Optional[str], data: Dict[str, Any], cache_dir: Path) -> None:
    try:
        cache_dir.mkdir(parents=True, exist_ok=True)
        with open(_search_cache_file(query, category, cache_dir), "w", encoding="utf-8") as f:
            json.dump(data, f)
    except Exception as e:
        logger.warning(f"Error saving Pixabay search cache: {e}")


def _scrub(msg: str, key: str) -> str:
    return msg.replace(key, "***") if key else msg


def _call_pixabay_api(url: str, key: str = "") -> Dict[str, Any]:
    """
    GETs a Pixabay API URL. On 429, waits X-RateLimit-Reset seconds and retries
    once. Raises PixabayError on any failure; the key is never logged.
    """
    req = urllib.request.Request(url, headers={"User-Agent": "SteerProof/1.0"})
    for attempt in range(2):
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            if e.code == 429 and attempt == 0:
                reset = e.headers.get("X-RateLimit-Reset") if e.headers else None
                try:
                    wait_sec = float(reset) if reset else 1.0
                except ValueError:
                    wait_sec = 1.0
                wait_sec = min(max(wait_sec, 0.0), MAX_RATE_LIMIT_WAIT_SECONDS)
                logger.warning(f"Pixabay returned 429; waiting {wait_sec}s before one retry")
                time.sleep(wait_sec)
                continue
            raise PixabayError(f"HTTP {e.code}")
        except Exception as e:
            raise PixabayError(_scrub(str(e), key) or type(e).__name__)
    raise PixabayError("HTTP 429")


def _search_pixabay(query: str, key: str, cache_dir: Path) -> Dict[str, Any]:
    """
    Video search per the Pixabay API: safesearch=true, video_type=film,
    per_page=10, plus &category=food|places when obvious. If the category
    narrows the result to zero hits, searches once more without it.
    Responses are cached for 24 hours keyed by query + category.
    """
    category = _infer_category(query)
    categories = [category, None] if category else [None]
    data: Dict[str, Any] = {"totalHits": 0, "hits": []}
    for cat in categories:
        cached = _get_search_cache(query, cat, cache_dir)
        if cached is not None:
            data = cached
        else:
            params = {
                "key": key, "q": query, "safesearch": "true",
                "video_type": "film", "per_page": "10",
            }
            if cat:
                params["category"] = cat
            data = _call_pixabay_api(f"{PIXABAY_VIDEOS_URL}?{urllib.parse.urlencode(params)}", key)
            _save_search_cache(query, cat, data, cache_dir)
        if data.get("hits"):
            break
    return data


def _pick_hit(
    hits: List[Dict[str, Any]],
    min_seconds: float,
    used_ids: Set[str],
) -> Tuple[Optional[Dict[str, Any]], str]:
    """
    Chooses a hit: tags not denylisted, id not used in this reel, a medium or
    small rendition (never large). Prefers clips at least min_seconds long,
    else keeps Pixabay's relevance order. Returns (candidate, reason_if_none).
    """
    if not hits:
        return None, "NO_HITS"
    candidates = []
    for order, h in enumerate(hits):
        hit_id = str(h.get("id"))
        if hit_id in used_ids:
            continue
        allowed, matched = check_pixabay_tags_denylist(h.get("tags", ""))
        if not allowed:
            logger.info(f"Skipping Pixabay hit {hit_id}: denylisted tag '{matched}'")
            continue
        videos = h.get("videos") or {}
        size_used = next((s for s in ("medium", "small") if (videos.get(s) or {}).get("url")), None)
        if not size_used:
            continue
        duration = float(h.get("duration") or 0)
        candidates.append({
            "hit": h, "size_used": size_used, "video_meta": videos[size_used],
            "duration": duration, "rank": (0 if duration >= min_seconds else 1, order),
        })
    if not candidates:
        return None, "ALL_DENYLISTED"
    return min(candidates, key=lambda c: c["rank"]), ""


def _download_clip(hit_id: str, url: str, broll_dir: Path) -> Path:
    """Permanent mp4 cache by Pixabay id: storage/broll/pixabay-{id}.mp4."""
    broll_dir.mkdir(parents=True, exist_ok=True)
    dest = broll_dir / f"pixabay-{hit_id}.mp4"
    if not dest.exists() or dest.stat().st_size == 0:
        logger.info(f"Downloading Pixabay clip {hit_id} to {dest}")
        tmp = dest.with_suffix(".part")
        urllib.request.urlretrieve(url, str(tmp))
        tmp.replace(dest)
    return dest


def _fetch_pixabay_video(
    query: str,
    min_seconds: float,
    key: str,
    storage_dir: Path,
    used_ids: Optional[Set[str]] = None,
) -> Tuple[Optional[Dict[str, Any]], Optional[str]]:
    """
    Searches Pixabay for one scene. Returns (result, None) or (None, reason)
    where reason is NO_HITS, ALL_DENYLISTED or ERROR:<msg>. Credits come
    only from the API response (user, user_id, pageURL).
    """
    used_ids = used_ids if used_ids is not None else set()
    try:
        data = _search_pixabay(query, key, storage_dir / "cache" / "pixabay")
    except PixabayError as e:
        return None, f"ERROR:{e}"

    chosen, reason = _pick_hit(data.get("hits") or [], min_seconds, used_ids)
    if not chosen:
        return None, reason

    hit = chosen["hit"]
    hit_id = str(hit.get("id"))
    try:
        clip = _download_clip(hit_id, chosen["video_meta"]["url"], storage_dir / "broll")
    except Exception as e:
        return None, f"ERROR:download failed: {_scrub(str(e), key)}"

    user = hit.get("user")
    page_url = hit.get("pageURL")
    return {
        "videoPath": f"broll/{clip.name}",
        "source": "pixabay",
        "url": page_url,
        "creator": user,
        "userId": hit.get("user_id"),
        "creditLine": f"Video by {user} on Pixabay — {page_url}",
        "tags": hit.get("tags", ""),
        "sizeUsed": chosen["size_used"],
        "width": chosen["video_meta"].get("width"),
        "height": chosen["video_meta"].get("height"),
        "duration": chosen["duration"],
        "hitId": hit_id,
    }, None


def _null_result(n: int, query: str, reason: str) -> Dict[str, Any]:
    return {
        "n": n, "query": query, "videoPath": None, "reason": reason,
        "source": None, "url": None, "creator": None, "userId": None,
        "creditLine": None, "tags": None, "sizeUsed": None, "duration": None,
        "hitId": None,
    }


def _library_result(n: int, query: str, item: Dict[str, Any]) -> Dict[str, Any]:
    creator = item.get("creator")
    source = item.get("source", "library")
    url = item.get("url")
    return {
        "n": n, "query": query,
        "videoPath": f"broll-library/{item['file']}",
        "reason": None, "source": source, "url": url, "creator": creator,
        "userId": None,
        "creditLine": f"Video by {creator} ({source}) — {url}" if url else f"Video by {creator}",
        "tags": ",".join(item.get("tags", [])),
        "sizeUsed": item.get("orientation", "portrait"),
        "duration": float(item.get("seconds", 0)),
        "hitId": None,
    }


def resolve_pixabay_key(api_key: Optional[str] = None) -> str:
    """Request override, else PIXABAY_API_KEY from .env (via settings) or the environment."""
    if api_key is not None:
        return api_key
    try:
        from app.config import get_settings
        key = get_settings().pixabay_api_key
    except Exception:
        key = ""
    return key or os.environ.get("PIXABAY_API_KEY", "")


def process_reel_broll(
    scenes: List[Dict[str, Any]],
    storage_dir: Path,
    api_key: Optional[str] = None,
    used_ids: Optional[List[str]] = None,
) -> List[Dict[str, Any]]:
    """
    B-roll for all scenes of one reel, processed together so no Pixabay id or
    library file is used twice in the same reel:
    1. Local library (storage/broll-library/library.json), only if it has entries.
    2. Pixabay: full query, then one retry with the most concrete noun.
    3. Null with a reason (EMPTY_QUERY, DENYLIST:<term>, NO_HITS,
       ALL_DENYLISTED, ERROR:<msg>, NO_API_KEY): the renderer then uses the
       procedural background.
    `used_ids` lets a caller exclude Pixabay ids already used elsewhere in the reel.
    """
    results = []
    used_library_files: Set[str] = set()
    used_pixabay_ids: Set[str] = {str(i) for i in (used_ids or [])}
    pixabay_key = resolve_pixabay_key(api_key)

    for sc in scenes:
        n = sc.get("n", 1)
        query = (sc.get("brollQuery") or "").strip()
        min_sec = float(sc.get("minSeconds") or ((sc.get("durationMs") or 4000) / 1000.0))

        if not query:
            results.append(_null_result(n, "", "EMPTY_QUERY"))
            continue

        allowed, matched = check_broll_denylist(query)
        if not allowed:
            logger.warning(f"B-roll query '{query}' blocked by denylist: '{matched}'")
            results.append(_null_result(n, query, f"DENYLIST:{matched}"))
            continue

        library_pick = next(
            (c["item"] for c in search_local_library(query, min_sec, storage_dir)
             if c["item"]["file"] not in used_library_files),
            None,
        )
        if library_pick:
            used_library_files.add(library_pick["file"])
            results.append(_library_result(n, query, library_pick))
            continue

        if not pixabay_key:
            results.append(_null_result(n, query, "NO_API_KEY"))
            continue

        res, reason = _fetch_pixabay_video(query, min_sec, pixabay_key, storage_dir, used_pixabay_ids)
        searched = query
        if res is None and not (reason or "").startswith("ERROR:"):
            simpler = simplify_query(query)
            if simpler:
                logger.info(f"No usable Pixabay hit for '{query}' ({reason}); retrying with '{simpler}'")
                res, retry_reason = _fetch_pixabay_video(simpler, min_sec, pixabay_key, storage_dir, used_pixabay_ids)
                searched = simpler
                # Report the most informative failure: an error, else
                # ALL_DENYLISTED if either search had hits, else NO_HITS.
                if res is None and (retry_reason.startswith("ERROR:") or reason == "NO_HITS"):
                    reason = retry_reason

        if res is None:
            results.append(_null_result(n, query, reason or "NO_HITS"))
            continue

        used_pixabay_ids.add(res["hitId"])
        res.update({"n": n, "query": query, "searchedQuery": searched, "reason": None})
        results.append(res)

    return results
