import pytest
from pathlib import Path
from unittest.mock import patch, MagicMock
from app.reel.broll import (
    check_broll_denylist,
    search_local_library,
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

def test_search_local_library(tmp_path):
    broll_lib = tmp_path / "broll-library"
    broll_lib.mkdir()

    # Create dummy mp4 files
    (broll_lib / "chai.mp4").write_text("dummy")
    (broll_lib / "roti.mp4").write_text("dummy")
    (broll_lib / "desk.mp4").write_text("dummy")

    library_data = [
        {
            "file": "chai.mp4",
            "tags": ["chai", "tea", "cup"],
            "source": "pexels",
            "url": "http://pexels.com/chai",
            "creator": "Photographer A",
            "seconds": 10,
            "orientation": "portrait"
        },
        {
            "file": "roti.mp4",
            "tags": ["roti", "plate", "food"],
            "source": "pixabay",
            "url": "http://pixabay.com/roti",
            "creator": "Photographer B",
            "seconds": 8,
            "orientation": "portrait"
        },
        {
            "file": "desk.mp4",
            "tags": ["desk", "notes", "tea"],
            "source": "pixabay",
            "url": "http://pixabay.com/desk",
            "creator": "Photographer C",
            "seconds": 12,
            "orientation": "landscape"
        }
    ]

    import json
    (broll_lib / "library.json").write_text(json.dumps(library_data))

    # Query matching chai & tea
    candidates = search_local_library("hot cup of chai", min_seconds=5, storage_dir=tmp_path)
    assert len(candidates) >= 1
    # chai.mp4 has 2 tag matches ("cup", "chai") -> highest score
    assert candidates[0]["item"]["file"] == "chai.mp4"

    # Query matching tea (chai.mp4 vs desk.mp4): both have 1 match, but chai is portrait so should rank higher
    tea_candidates = search_local_library("drinking morning tea", min_seconds=5, storage_dir=tmp_path)
    assert len(tea_candidates) >= 2
    assert tea_candidates[0]["item"]["file"] == "chai.mp4"  # portrait preferred over landscape

def test_process_reel_broll_uniqueness(tmp_path):
    broll_lib = tmp_path / "broll-library"
    broll_lib.mkdir()

    (broll_lib / "chai.mp4").write_text("dummy")
    (broll_lib / "desk.mp4").write_text("dummy")

    import json
    library_data = [
        {
            "file": "chai.mp4",
            "tags": ["tea", "drink"],
            "source": "pexels",
            "url": "http://pexels.com/chai",
            "creator": "Photographer A",
            "seconds": 10,
            "orientation": "portrait"
        },
        {
            "file": "desk.mp4",
            "tags": ["tea", "table"],
            "source": "pixabay",
            "url": "http://pixabay.com/desk",
            "creator": "Photographer B",
            "seconds": 10,
            "orientation": "portrait"
        }
    ]
    (broll_lib / "library.json").write_text(json.dumps(library_data))

    # Two scenes that both match 'tea':
    scenes = [
        {"n": 1, "brollQuery": "steaming hot tea cup", "minSeconds": 4},
        {"n": 2, "brollQuery": "evening green tea table", "minSeconds": 4}
    ]

    results = process_reel_broll(scenes, storage_dir=tmp_path)
    assert len(results) == 2
    # Ensure scene 1 and scene 2 picked different files (never pick same clip twice if another matches)
    assert results[0]["videoPath"] == "broll-library/chai.mp4"
    assert results[1]["videoPath"] == "broll-library/desk.mp4"
    assert results[0]["videoPath"] != results[1]["videoPath"]
