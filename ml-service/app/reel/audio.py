import os
import re
import json
import asyncio
import logging
import subprocess
from pathlib import Path
from typing import Optional, List, Dict, Any
import edge_tts
import mutagen.mp3

logger = logging.getLogger("ml-service.reel.audio")

DEFAULT_VOICE = "en-IN-NeerjaNeural"

async def _synth_scene_with_retry(
    text: str,
    voice: str,
    out_mp3_path: Path,
    max_retries: int = 2
) -> tuple[bool, List[Dict[str, Any]]]:
    """
    Attempts to synthesize text using edge-tts with word boundaries.
    Retries up to max_retries.
    Returns (success, words).
    """
    for attempt in range(1, max_retries + 1):
        try:
            logger.info(f"Synthesizing audio (attempt {attempt}/{max_retries}) for text: {text[:50]}...")
            comm = edge_tts.Communicate(
                text=text,
                voice=voice,
                rate="-5%",
                boundary="WordBoundary"
            )
            words = []
            audio_bytes = bytearray()
            async for chunk in comm.stream():
                if chunk["type"] == "audio":
                    audio_bytes.extend(chunk["data"])
                elif chunk["type"] == "WordBoundary":
                    # offset is in 100-nanosecond units (ticks) -> / 10000 = milliseconds
                    start_ms = round(chunk["offset"] / 10000)
                    end_ms = round((chunk["offset"] + chunk["duration"]) / 10000)
                    words.append({
                        "text": chunk["text"],
                        "startMs": start_ms,
                        "endMs": end_ms
                    })
            if len(audio_bytes) > 0:
                with open(out_mp3_path, "wb") as f:
                    f.write(audio_bytes)
                return True, words
        except Exception as e:
            logger.warning(f"edge-tts attempt {attempt} failed: {e}")
            await asyncio.sleep(0.5)

    return False, []

def _generate_fallback_audio(
    text: str,
    out_mp3_path: Path
) -> tuple[float, List[Dict[str, Any]]]:
    """
    Fallback if edge-tts fails after 2 attempts:
    Generates a dummy silent MP3 using ffmpeg and estimates word timestamps
    at an average reading rate (~140 wpm = 430 ms/word).
    """
    logger.warning(f"Generating fallback dummy-timestamped audio for {out_mp3_path.name}")
    tokens = re.findall(r"\S+", text)
    if not tokens:
        tokens = ["Silence"]
    
    ms_per_word = 430
    words = []
    current_ms = 200
    for token in tokens:
        start_ms = current_ms
        end_ms = current_ms + ms_per_word - 50
        words.append({
            "text": token,
            "startMs": start_ms,
            "endMs": end_ms
        })
        current_ms += ms_per_word
    
    total_duration_sec = (current_ms + 200) / 1000.0
    # Generate silent mp3 with ffmpeg
    subprocess.run([
        "ffmpeg", "-y", "-f", "lavfi", "-i", f"anullsrc=r=24000:cl=mono",
        "-t", str(total_duration_sec), "-q:a", "9", "-acodec", "libmp3lame",
        str(out_mp3_path)
    ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)

    return total_duration_sec, words

async def generate_reel_audio(
    paper_id: str,
    scenes: List[Dict[str, Any]],
    storage_dir: Path,
    voice: str = DEFAULT_VOICE
) -> Dict[str, Any]:
    """
    Synthesizes audio for each scene in the reel and combines into storage/audio/{paper_id}-reel.mp3.
    Returns scene audio info and word timestamps.
    """
    audio_dir = storage_dir / "audio"
    audio_dir.mkdir(parents=True, exist_ok=True)

    scene_results = []
    scene_mp3_paths = []
    
    for sc in scenes:
        scene_num = sc.get("n", 1)
        voiceover = sc.get("voiceover", "").strip()
        scene_mp3 = audio_dir / f"{paper_id}-scene-{scene_num}.mp3"
        
        success, words = await _synth_scene_with_retry(voiceover, voice, scene_mp3, max_retries=2)
        if success and scene_mp3.exists():
            duration_sec = mutagen.mp3.MP3(str(scene_mp3)).info.length
        else:
            duration_sec, words = _generate_fallback_audio(voiceover, scene_mp3)

        duration_ms = round(duration_sec * 1000)
        scene_mp3_paths.append(scene_mp3)
        scene_results.append({
            "n": scene_num,
            "audioPath": f"audio/{scene_mp3.name}",
            "durationMs": duration_ms,
            "words": words,
            "onScreen": sc.get("onScreen", ""),
            "motion": sc.get("motion", "title"),
            "kind": sc.get("kind", "brand"),
            "claimIds": sc.get("claimIds", []),
            "brollQuery": sc.get("brollQuery", "")
        })

    # Combine all scenes into single storage/audio/{paper_id}-reel.mp3 using ffmpeg concat
    combined_mp3 = audio_dir / f"{paper_id}-reel.mp3"
    concat_list_file = audio_dir / f"{paper_id}-concat.txt"
    with open(concat_list_file, "w") as f:
        for p in scene_mp3_paths:
            f.write(f"file '{p.resolve()}'\n")

    try:
        subprocess.run([
            "ffmpeg", "-y", "-f", "concat", "-safe", "0",
            "-i", str(concat_list_file),
            "-c", "copy",
            str(combined_mp3)
        ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
    finally:
        if concat_list_file.exists():
            concat_list_file.unlink()

    total_duration_sec = mutagen.mp3.MP3(str(combined_mp3)).info.length
    total_duration_ms = round(total_duration_sec * 1000)

    # Compute continuous overall words across the entire reel
    overall_words = []
    offset_ms = 0
    for sc in scene_results:
        for w in sc["words"]:
            overall_words.append({
                "scene": sc["n"],
                "text": w["text"],
                "startMs": w["startMs"] + offset_ms,
                "endMs": w["endMs"] + offset_ms
            })
        offset_ms += sc["durationMs"]

    # Save words JSON
    words_json_path = audio_dir / f"{paper_id}-words.json"
    with open(words_json_path, "w") as f:
        json.dump(overall_words, f, indent=2)

    return {
        "audioPath": f"audio/{combined_mp3.name}",
        "totalDurationMs": total_duration_ms,
        "words": overall_words,
        "scenes": scene_results
    }
