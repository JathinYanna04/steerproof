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
    process_reel_broll
)

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

def test_pixabay_search_and_resolution_selection(tmp_path):
    fake_pixabay_response = {
        "totalHits": 3,
        "hits": [
            {
                "id": 101,
                "user": "Alice",
                "user_id": 1234,
                "pageURL": "https://pixabay.com/videos/tea-101/",
                "duration": 8,
                "tags": "model, woman, tea",  # blocked by tag denylist
                "videos": {
                    "medium": {"url": "http://pixabay.com/101_med.mp4"}
                }
            },
            {
                "id": 102,
                "user": "Bob",
                "user_id": 5678,
                "pageURL": "https://pixabay.com/videos/tea-102/",
                "duration": 10,
                "tags": "tea, cup, steam",  # allowed
                "videos": {
                    "large": {"url": "http://pixabay.com/102_large.mp4"}  # ONLY large -> MUST BE SKIPPED
                }
            },
            {
                "id": 103,
                "user": "Charlie",
                "user_id": 9012,
                "pageURL": "https://pixabay.com/videos/tea-103/",
                "duration": 6,
                "tags": "tea, morning, cup",  # allowed
                "videos": {
                    "medium": {"url": "http://pixabay.com/103_med.mp4"},
                    "small": {"url": "http://pixabay.com/103_small.mp4"}
                }
            }
        ]
    }

    with patch("urllib.request.urlopen") as mock_urlopen, \
         patch("urllib.request.urlretrieve") as mock_urlretrieve:
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(fake_pixabay_response).encode("utf-8")
        mock_resp.__enter__.return_value = mock_resp
        mock_urlopen.return_value = mock_resp

        # Side effect creates the file to simulate download
        def fake_download(url, dest):
            Path(dest).write_text("mp4data")
        mock_urlretrieve.side_effect = fake_download

        result = _fetch_pixabay_video(
            query="steaming morning tea cup",
            min_seconds=5,
            key="test_key",
            storage_dir=tmp_path
        )

        assert result is not None
        assert result["hitId"] == "103"  # 101 had 'model' tag, 102 had only 'large'
        assert result["sizeUsed"] == "medium"
        assert result["creator"] == "Charlie"
        assert result["userId"] == 9012
        assert result["creditLine"] == "Video by Charlie on Pixabay — https://pixabay.com/videos/tea-103/"
        assert result["videoPath"] == "broll/pixabay-103.mp4"

        # Check 24-hour search cache was created
        cache_files = list((tmp_path / "cache" / "pixabay").glob("search_*.json"))
        assert len(cache_files) == 1

        # Second call should read from cache (mock_urlopen won't be called again)
        mock_urlopen.reset_mock()
        cached_result = _fetch_pixabay_video(
            query="steaming morning tea cup",
            min_seconds=5,
            key="test_key",
            storage_dir=tmp_path
        )
        assert cached_result is not None
        assert cached_result["hitId"] == "103"
        mock_urlopen.assert_not_called()

def test_process_reel_broll_uniqueness(tmp_path):
    broll_lib = tmp_path / "broll-library"
    broll_lib.mkdir()

    (broll_lib / "chai.mp4").write_text("dummy")
    (broll_lib / "desk.mp4").write_text("dummy")

    library_data = [
        {
            "file": "chai.mp4",
            "tags": ["tea", "drink"],
            "source": "fixture",
            "url": "",
            "creator": "TEST FIXTURE",
            "seconds": 10,
            "orientation": "portrait"
        },
        {
            "file": "desk.mp4",
            "tags": ["tea", "table"],
            "source": "fixture",
            "url": "",
            "creator": "TEST FIXTURE",
            "seconds": 10,
            "orientation": "portrait"
        }
    ]
    (broll_lib / "library.json").write_text(json.dumps(library_data))

    scenes = [
        {"n": 1, "brollQuery": "steaming hot tea cup", "minSeconds": 4},
        {"n": 2, "brollQuery": "evening green tea table", "minSeconds": 4}
    ]

    results = process_reel_broll(scenes, storage_dir=tmp_path)
    assert len(results) == 2
    assert results[0]["videoPath"] == "broll-library/chai.mp4"
    assert results[1]["videoPath"] == "broll-library/desk.mp4"
    assert results[0]["videoPath"] != results[1]["videoPath"]
