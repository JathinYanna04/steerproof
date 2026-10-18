import os
import hashlib
from pathlib import Path
from typing import Optional

class DiskCache:
    def __init__(self, cache_dir: str):
        self.cache_dir = Path(cache_dir).resolve()
        self.cache_dir.mkdir(parents=True, exist_ok=True)

    def _path(self, key: str) -> Path:
        return self.cache_dir / f"{key}.json"

    def get(self, key: str) -> Optional[str]:
        p = self._path(key)
        if p.exists():
            try:
                return p.read_text(encoding="utf-8")
            except Exception:
                return None
        return None

    def set(self, key: str, value: str) -> None:
        p = self._path(key)
        p.write_text(value, encoding="utf-8")
