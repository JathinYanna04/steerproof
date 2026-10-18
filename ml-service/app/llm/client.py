import hashlib
import time
from typing import TypeVar, Type, Tuple, Optional
from pydantic import BaseModel, ValidationError
from google import genai
from google.genai import types
from google.genai.errors import APIError

from app.llm.cache import DiskCache
from app.llm.ratelimit import TokenBucket
from app.config import Settings

T = TypeVar("T", bound=BaseModel)

class LLMClient:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.client = genai.Client(api_key=settings.gemini_api_key)
        self.cache = DiskCache(settings.llm_cache_dir)
        self.limiter = TokenBucket(settings.llm_max_rpm)

    def structured(
        self,
        prompt: str,
        schema: Type[T],
        temperature: float = 0.0,
        model: Optional[str] = None
    ) -> Tuple[T, bool]:
        selected_model = model or self.settings.gemini_model
        prompt_hash = hashlib.sha256(f"{selected_model}|{temperature}|{schema.__name__}|{prompt}".encode("utf-8")).hexdigest()
        
        hit = self.cache.get(prompt_hash)
        if hit is not None:
            try:
                obj = schema.model_validate_json(hit)
                return obj, True
            except ValidationError:
                pass # corrupted cache, re-fetch

        backoff_delays = [5.0, 15.0, 45.0]
        last_error = None

        for attempt in range(2):
            self.limiter.acquire()
            try:
                resp = self.client.models.generate_content(
                    model=selected_model,
                    contents=prompt,
                    config=types.GenerateContentConfig(
                        response_mime_type="application/json",
                        response_schema=schema,
                        temperature=temperature,
                    )
                )
                text = resp.text
                obj = schema.model_validate_json(text)
                self.cache.set(prompt_hash, text)
                return obj, False
            except APIError as e:
                last_error = e
                # Check for 429
                if getattr(e, "code", None) == 429 or "RESOURCE_EXHAUSTED" in str(e):
                    delay = backoff_delays[min(attempt, len(backoff_delays) - 1)]
                    time.sleep(delay)
                else:
                    raise
            except ValidationError as e:
                last_error = e
                if attempt == 1:
                    raise
            except Exception as e:
                last_error = e
                if attempt == 1:
                    raise

        raise RuntimeError(f"LLM structured output failed after retries: {last_error}")
