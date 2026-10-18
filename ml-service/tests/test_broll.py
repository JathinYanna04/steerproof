import pytest
import json
import time
from pathlib import Path
from unittest.mock import patch, MagicMock
from app.reel.broll import (
    check_broll_denylist,
    check_pixabay_tags_denylist,
    search_local_library,
    _fetch_pixabay_video,
    process_reel_broll,
    simplify_query,
)
import urllib.error

def test_broll_denylist():
    # Denylisted terms
    for term in ["weighing scale", "gym workout", "hospital bed surgery", "doctor with injection", "fat belly"]:
        allowed, matched = check_broll_denylist(term)
        assert not allowed, f"Expected '{term}' to be blocked"
        assert matched is not None

    # Allowed lifestyle & metabolic terms
    for term in ["steaming black tea cup", "fresh roti on plate", "morning desk notes", "walking outdoors"]:
        allowed, matched = check_broll_denylist(term)
        assert allowed, f"Expected '{term}' to be allowed"
        assert matched is None

def test_pixabay_tags_denylist():
    # Denylisted hit tags
    denied_tags = [
        "gym, workout, fitness",
        "doctor, hospital, medicine",
        "woman, fashion, model",
        "man, running, body",
        "bikini, beach, sun",
        "diet, pills, slim",
        "syringe, injection, clinical"
    ]
    for tag_str in denied_tags:
        allowed, matched = check_pixabay_tags_denylist(tag_str)
        assert not allowed, f"Expected tags '{tag_str}' to be blocked (matched '{matched}')"

    # Allowed hit tags
    allowed_tags = [
        "tea, cup, morning, hot",
        "roti, plate, food, dinner",
        "desk, notes, book, paper",
        "nature, tree, forest, sunlight",
        "kitchen, spices, cooking"
    ]
    for tag_str in allowed_tags:
        allowed, matched = check_pixabay_tags_denylist(tag_str)
        assert allowed, f"Expected tags '{tag_str}' to be allowed"
        assert matched is None

def test_search_local_library(tmp_path):
    broll_lib = tmp_path / "broll-library"
    broll_lib.mkdir()

    # Empty library should return []
    (broll_lib / "library.json").write_text("[]")
    assert search_local_library("tea", 5, tmp_path) == []

    # Create dummy mp4 files
    (broll_lib / "chai.mp4").write_text("dummy")
    (broll_lib / "roti.mp4").write_text("dummy")
    (broll_lib / "desk.mp4").write_text("dummy")

    library_data = [
        {
            "file": "chai.mp4",
            "tags": ["chai", "tea", "cup"],
            "source": "fixture",
            "url": "",
            "creator": "TEST FIXTURE",
            "seconds": 10,
            "orientation": "portrait"
        },
        {
            "file": "roti.mp4",
            "tags": ["roti", "plate", "food"],
            "source": "fixture",
            "url": "",
            "creator": "TEST FIXTURE",
            "seconds": 8,
            "orientation": "portrait"
        },
        {
            "file": "desk.mp4",
            "tags": ["desk", "notes", "tea"],
            "source": "fixture",
            "url": "",
            "creator": "TEST FIXTURE",
            "seconds": 12,
            "orientation": "landscape"
        }
    ]

    (broll_lib / "library.json").write_text(json.dumps(library_data))

    # Query matching chai & tea
    candidates = search_local_library("hot cup of chai", min_seconds=5, storage_dir=tmp_path)
    assert len(candidates) >= 1
    assert candidates[0]["item"]["file"] == "chai.mp4"

    # Query matching tea: chai.mp4 vs desk.mp4: chai is portrait so should rank higher
    tea_candidates = search_local_library("drinking morning tea", min_seconds=5, storage_dir=tmp_path)
    assert len(tea_candidates) >= 2
    assert tea_candidates[0]["item"]["file"] == "chai.mp4"

def _hit(i, tags="tea, cup, steam", sizes=("medium", "small"), duration=8, user="Uploader"):
    return {
        "id": i, "user": f"{user}{i}", "user_id": 1000 + i,
        "pageURL": f"https://pixabay.com/videos/clip-{i}/",
        "duration": duration, "tags": tags,
        "videos": {s: {"url": f"https://cdn.example/{i}_{s}.mp4", "width": 640, "height": 360} for s in sizes},
    }


def _json_resp(payload):
    resp = MagicMock()
    resp.read.return_value = json.dumps(payload).encode("utf-8")
    resp.__enter__.return_value = resp
    return resp


def _fake_download(url, dest):
    Path(dest).write_text("mp4data")


class _Api:
    """urlopen stand-in that answers per query (q=...) and records requested URLs."""

    def __init__(self, by_query):
        self.by_query = by_query
        self.urls = []

    def __call__(self, req, timeout=None):
        url = req.full_url
        self.urls.append(url)
        from urllib.parse import urlparse, parse_qs
        q = parse_qs(urlparse(url).query)["q"][0]
        return _json_resp(self.by_query.get(q, {"totalHits": 0, "hits": []}))


def test_pixabay_tags_denylist_whole_words():
    for tags in ["tea, women, cup", "people, street", "weight loss, food", "beach, sunset", "person, coffee"]:
        assert not check_pixabay_tags_denylist(tags)[0], tags
    # whole-word match: substrings of allowed words must not trip the denylist
    for tags in ["manual, notebook", "mango, fruit", "fatigue, sleep", "modelling clay, art", "scaled fish, market"]:
        assert check_pixabay_tags_denylist(tags)[0], tags


def test_pixabay_search_size_choice_and_credits(tmp_path):
    api = _Api({"steaming morning tea cup": {"totalHits": 3, "hits": [
        _hit(101, tags="model, woman, tea"),        # denylisted
        _hit(102, sizes=("large",)),                # only large -> never used
        _hit(103, sizes=("small",)),                # small fallback
    ]}})
    with patch("urllib.request.urlopen", side_effect=api), \
         patch("urllib.request.urlretrieve", side_effect=_fake_download) as dl:
        res, reason = _fetch_pixabay_video("steaming morning tea cup", 5, "test-key-123", tmp_path)
    assert reason is None
    assert res["hitId"] == "103" and res["sizeUsed"] == "small"
    assert dl.call_args[0][0] == "https://cdn.example/103_small.mp4"
    assert res["creator"] == "Uploader103" and res["userId"] == 1103
    assert res["creditLine"] == "Video by Uploader103 on Pixabay — https://pixabay.com/videos/clip-103/"
    assert res["videoPath"] == "broll/pixabay-103.mp4"
    assert (tmp_path / "broll" / "pixabay-103.mp4").exists()
    url = api.urls[0]
    for param in ["safesearch=true", "video_type=film", "per_page=10", "category=food"]:
        assert param in url


def test_pixabay_prefers_medium_over_small(tmp_path):
    api = _Api({"tea": {"totalHits": 1, "hits": [_hit(7)]}})
    with patch("urllib.request.urlopen", side_effect=api), \
         patch("urllib.request.urlretrieve", side_effect=_fake_download):
        res, _ = _fetch_pixabay_video("tea", 4, "test-key-123", tmp_path)
    assert res["sizeUsed"] == "medium"


def test_pixabay_search_cache_24h(tmp_path):
    api = _Api({"tea": {"totalHits": 1, "hits": [_hit(7)]}})
    with patch("urllib.request.urlopen", side_effect=api), \
         patch("urllib.request.urlretrieve", side_effect=_fake_download) as dl:
        _fetch_pixabay_video("tea", 4, "test-key-123", tmp_path)
        _fetch_pixabay_video("tea", 4, "test-key-123", tmp_path)
        assert len(api.urls) == 1          # second search served from cache
        assert dl.call_count == 1          # mp4 cached permanently by id
        # expire the cache -> searches again, mp4 still not re-downloaded
        for f in (tmp_path / "cache" / "pixabay").glob("search_*.json"):
            old = time.time() - 25 * 3600
            import os
            os.utime(f, (old, old))
        _fetch_pixabay_video("tea", 4, "test-key-123", tmp_path)
        assert len(api.urls) == 2
        assert dl.call_count == 1


def test_pixabay_429_waits_reset_and_retries_once(tmp_path):
    def err429():
        return urllib.error.HTTPError("u", 429, "Too Many", {"X-RateLimit-Reset": "3"}, None)
    ok = _json_resp({"totalHits": 1, "hits": [_hit(9)]})
    with patch("urllib.request.urlopen", side_effect=[err429(), ok]), \
         patch("urllib.request.urlretrieve", side_effect=_fake_download), \
         patch("app.reel.broll.time.sleep") as sleep:
        res, reason = _fetch_pixabay_video("tea", 4, "test-key-123", tmp_path)
    sleep.assert_called_once_with(3.0)
    assert res["hitId"] == "9"

    with patch("urllib.request.urlopen", side_effect=[err429(), err429()]), \
         patch("app.reel.broll.time.sleep"):
        res, reason = _fetch_pixabay_video("coffee", 4, "test-key-123", tmp_path / "other")
    assert res is None and reason == "ERROR:HTTP 429"


def test_error_reason_never_contains_key(tmp_path):
    with patch("urllib.request.urlopen", side_effect=OSError("boom for https://x/?key=SECRETKEY")):
        res, reason = _fetch_pixabay_video("tea", 4, "SECRETKEY", tmp_path)
    assert res is None and reason.startswith("ERROR:") and "SECRETKEY" not in reason


def test_no_id_reused_within_one_reel(tmp_path):
    same = {"totalHits": 2, "hits": [_hit(1), _hit(2)]}
    api = _Api({"chai cup": same, "kitchen counter": same, "wall clock": same})
    scenes = [{"n": i + 1, "brollQuery": q, "minSeconds": 4}
              for i, q in enumerate(["chai cup", "kitchen counter", "wall clock"])]
    with patch("urllib.request.urlopen", side_effect=api), \
         patch("urllib.request.urlretrieve", side_effect=_fake_download):
        results = process_reel_broll(scenes, storage_dir=tmp_path, api_key="test-key-123", used_ids=[])
    assert [r["hitId"] for r in results[:2]] == ["1", "2"]
    assert results[2]["videoPath"] is None
    assert results[2]["reason"] == "ALL_DENYLISTED"   # every remaining hit already used


def test_simplified_query_retry(tmp_path):
    api = _Api({"cup": {"totalHits": 1, "hits": [_hit(5)]}})
    with patch("urllib.request.urlopen", side_effect=api), \
         patch("urllib.request.urlretrieve", side_effect=_fake_download):
        results = process_reel_broll([{"n": 1, "brollQuery": "steaming cup on desk"}],
                                     storage_dir=tmp_path, api_key="test-key-123")
    assert results[0]["hitId"] == "5"
    assert results[0]["query"] == "steaming cup on desk"
    assert results[0]["searchedQuery"] == "cup"
    assert simplify_query("roti tawa") == "roti"
    assert simplify_query("bright clean clinic room") == "room"
    assert simplify_query("chai") is None


def test_null_reasons(tmp_path):
    api = _Api({
        "chai cup": {"totalHits": 0, "hits": []},
        "chai": {"totalHits": 0, "hits": []},
        "vegetable market": {"totalHits": 1, "hits": [_hit(3, tags="market, woman")]},
        "vegetable": {"totalHits": 1, "hits": [_hit(4, tags="people, vegetable")]},
    })
    scenes = [
        {"n": 1, "brollQuery": "chai cup"},
        {"n": 2, "brollQuery": "vegetable market"},
        {"n": 3, "brollQuery": "gym workout"},
        {"n": 4, "brollQuery": ""},
    ]
    with patch("urllib.request.urlopen", side_effect=api), \
         patch("urllib.request.urlretrieve", side_effect=_fake_download):
        r = process_reel_broll(scenes, storage_dir=tmp_path, api_key="test-key-123")
    assert [x["reason"] for x in r] == ["NO_HITS", "ALL_DENYLISTED", "DENYLIST:gym", "EMPTY_QUERY"]
    assert all(x["videoPath"] is None and x["creditLine"] is None for x in r)
    # simplified retry happened for scenes 1 and 2 (category fallback adds extra calls)
    queried = {u.split("q=")[1].split("&")[0] for u in api.urls}
    assert {"chai", "vegetable"} <= queried

    with patch("urllib.request.urlopen", side_effect=OSError("network down")):
        r = process_reel_broll([{"n": 1, "brollQuery": "wall clock"}], storage_dir=tmp_path, api_key="test-key-123")
    assert r[0]["reason"] == "ERROR:network down"

    r = process_reel_broll([{"n": 1, "brollQuery": "wall clock"}], storage_dir=tmp_path, api_key="")
    assert r[0]["reason"] == "NO_API_KEY"


def test_process_reel_broll_library_uniqueness(tmp_path):
    broll_lib = tmp_path / "broll-library"
    broll_lib.mkdir()
    (broll_lib / "chai.mp4").write_text("dummy")
    (broll_lib / "desk.mp4").write_text("dummy")
    library_data = [
        {"file": "chai.mp4", "tags": ["tea", "drink"], "source": "fixture", "url": "",
         "creator": "TEST FIXTURE", "seconds": 10, "orientation": "portrait"},
        {"file": "desk.mp4", "tags": ["tea", "table"], "source": "fixture", "url": "",
         "creator": "TEST FIXTURE", "seconds": 10, "orientation": "portrait"},
    ]
    (broll_lib / "library.json").write_text(json.dumps(library_data))
    scenes = [
        {"n": 1, "brollQuery": "steaming hot tea cup", "minSeconds": 4},
        {"n": 2, "brollQuery": "evening green tea table", "minSeconds": 4},
        {"n": 3, "brollQuery": "tea", "minSeconds": 4},
    ]
    results = process_reel_broll(scenes, storage_dir=tmp_path, api_key="")
    assert results[0]["videoPath"] == "broll-library/chai.mp4"
    assert results[1]["videoPath"] == "broll-library/desk.mp4"
    # both library clips used and no Pixabay key -> null, never a reused clip
    assert results[2]["videoPath"] is None and results[2]["reason"] == "NO_API_KEY"
