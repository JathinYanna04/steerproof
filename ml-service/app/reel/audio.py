import os
import re
import json
import hashlib
import shutil
import asyncio
import logging
from pathlib import Path
from typing import Optional, List, Dict, Any
import edge_tts
import mutagen.mp3

logger = logging.getLogger("ml-service.reel.audio")

DEFAULT_VOICE = os.environ.get("TTS_VOICE_EN", "en-IN-NeerjaNeural")

def _get_cache_key(voice: str, rate: str, text: str) -> str:
    raw = f"{voice}::{rate}::{text.strip()}"
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()

async def synth_scene(
    text: str,
    voice: str,
    rate: str,
    out_mp3_path: Path,
    out_words_path: Path,
    cache_dir: Path
) -> tuple[float, List[Dict[str, Any]], bool]:
    """
    Synthesizes a scene with edge-tts, caching by sha256(voice + rate + text).
    Returns (duration_sec, words, timing_approximate).
    """
    cache_dir.mkdir(parents=True, exist_ok=True)
    cache_key = _get_cache_key(voice, rate, text)
    cached_mp3 = cache_dir / f"{cache_key}.mp3"
    cached_words = cache_dir / f"{cache_key}.json"

    # 1. Check cache
    if cached_mp3.exists() and cached_words.exists() and cached_mp3.stat().st_size > 0:
        logger.info(f"Cache hit for audio: {cache_key[:8]}...")
        shutil.copy2(cached_mp3, out_mp3_path)
        shutil.copy2(cached_words, out_words_path)
        duration_sec = mutagen.mp3.MP3(str(out_mp3_path)).info.length
        with open(out_words_path, "r", encoding="utf-8") as f:
            words_data = json.load(f)
        return duration_sec, words_data.get("words", []), words_data.get("timingApproximate", False)

    # 2. Synthesize with edge-tts
    words: List[Dict[str, Any]] = []
    audio_bytes = bytearray()
    success = False

    for attempt in range(1, 3):
        try:
            logger.info(f"edge-tts attempt {attempt} for: {text[:40]}...")
            comm = edge_tts.Communicate(
                text=text,
                voice=voice,
                rate=rate,
                boundary="WordBoundary"
            )
            async for chunk in comm.stream():
                if chunk["type"] == "audio":
                    audio_bytes.extend(chunk["data"])
                elif chunk["type"] == "WordBoundary":
                    # 100-ns ticks -> ms
                    start_ms = round(chunk["offset"] / 10000)
                    end_ms = round((chunk["offset"] + chunk["duration"]) / 10000)
                    words.append({
                        "text": chunk["text"],
                        "startMs": start_ms,
                        "endMs": end_ms
                    })
            if len(audio_bytes) > 0:
                success = True
                break
        except Exception as e:
            logger.warning(f"edge-tts attempt {attempt} error: {e}")
            await asyncio.sleep(0.5)

    timing_approximate = False

    if success and len(audio_bytes) > 0:
        with open(out_mp3_path, "wb") as f:
            f.write(audio_bytes)
        duration_sec = mutagen.mp3.MP3(str(out_mp3_path)).info.length

        # If word boundaries came back empty, distribute words evenly
        if len(words) == 0:
            logger.warning("Word boundaries empty. Falling back to even distribution.")
            tokens = re.findall(r"\S+", text)
            if tokens:
                timing_approximate = True
                total_ms = duration_sec * 1000
                ms_per_word = total_ms / len(tokens)
                for idx, tok in enumerate(tokens):
                    words.append({
                        "text": tok,
                        "startMs": round(idx * ms_per_word),
                        "endMs": round((idx + 1) * ms_per_word)
                    })
    else:
        # Fallback silent audio if edge-tts completely failed
        logger.warning(f"edge-tts failed twice. Generating silent fallback audio.")
        timing_approximate = True
        tokens = re.findall(r"\S+", text) or ["silence"]
        ms_per_word = 400
        words = []
        for idx, tok in enumerate(tokens):
            words.append({
                "text": tok,
                "startMs": idx * ms_per_word,
                "endMs": (idx + 1) * ms_per_word
            })
        duration_sec = (len(tokens) * ms_per_word) / 1000.0
        import subprocess
        subprocess.run([
            "ffmpeg", "-y", "-f", "lavfi", "-i", "anullsrc=r=24000:cl=mono",
            "-t", str(duration_sec), "-acodec", "libmp3lame", str(out_mp3_path)
        ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)

    # Save words json
    with open(out_words_path, "w", encoding="utf-8") as f:
        json.dump({"timingApproximate": timing_approximate, "words": words}, f, indent=2)

    # Save to cache
    try:
        shutil.copy2(out_mp3_path, cached_mp3)
        shutil.copy2(out_words_path, cached_words)
    except Exception as e:
        logger.warning(f"Could not cache synthesized audio: {e}")

    return duration_sec, words, timing_approximate

async def generate_reel_audio(
    asset_id: str,
    scenes: List[Dict[str, Any]],
    storage_dir: Path,
    voice: Optional[str] = None,
    language: str = "en"
) -> Dict[str, Any]:
    """
    Synthesizes reel audio per scene into storage/audio/{asset_id}/scene-{n}.mp3
    and scene-{n}.words.json, and produces combined storage/audio/{asset_id}/reel.mp3.
    """
    target_voice = voice or os.environ.get("TTS_VOICE_EN", DEFAULT_VOICE)
    rate = "+18%"

    audio_dir = storage_dir / "audio" / asset_id
    audio_dir.mkdir(parents=True, exist_ok=True)
    cache_dir = storage_dir / "audio" / ".cache"

    scene_results = []
    scene_mp3_paths = []
    overall_timing_approximate = False

    for sc in scenes:
        scene_num = sc.get("n", 1)
        voiceover = sc.get("voiceover", "").strip()
        out_mp3 = audio_dir / f"scene-{scene_num}.mp3"
        out_words = audio_dir / f"scene-{scene_num}.words.json"

        dur_sec, words, approx = await synth_scene(
            text=voiceover,
            voice=target_voice,
            rate=rate,
            out_mp3_path=out_mp3,
            out_words_path=out_words,
            cache_dir=cache_dir
        )

        if approx:
            overall_timing_approximate = True

        dur_ms = round(dur_sec * 1000)
        scene_mp3_paths.append(out_mp3)

        scene_results.append({
            "n": scene_num,
            "voiceover": voiceover,
            "mp3Path": f"audio/{asset_id}/scene-{scene_num}.mp3",
            "durationMs": dur_ms,
            "wordCount": len(words),
            "words": words,
            "timingApproximate": approx,
            "onScreen": sc.get("onScreen", ""),
            "motion": sc.get("motion", "title"),
            "kind": sc.get("kind", "brand"),
            "claimIds": sc.get("claimIds", []),
            "brollQuery": sc.get("brollQuery", "")
        })

    # Concatenate all scene mp3s into reel.mp3
    combined_mp3 = audio_dir / "reel.mp3"
    concat_txt = audio_dir / "concat.txt"
    with open(concat_txt, "w") as f:
        for p in scene_mp3_paths:
            f.write(f"file '{p.resolve()}'\n")

    import subprocess
    try:
        subprocess.run([
            "ffmpeg", "-y", "-f", "concat", "-safe", "0",
            "-i", str(concat_txt),
            "-c", "copy",
            str(combined_mp3)
        ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
    finally:
        if concat_txt.exists():
            concat_txt.unlink()

    total_dur_sec = mutagen.mp3.MP3(str(combined_mp3)).info.length
    total_dur_ms = round(total_dur_sec * 1000)

    # Also build overall continuous words across reel
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

    return {
        "assetId": asset_id,
        "audioPath": f"audio/{asset_id}/reel.mp3",
        "totalDurationMs": total_dur_ms,
        "timingApproximate": overall_timing_approximate,
        "words": overall_words,
        "scenes": scene_results
    }
