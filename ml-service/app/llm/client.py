import hashlib
import time
from typing import TypeVar, Type, Tuple, Optional
from pydantic import BaseModel, ValidationError
from google import genai
from google.genai import types
from google.genai.errors import APIError, ServerError

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
        model: Optional[str] = None,
        cache_key_extra: str = ""
    ) -> Tuple[T, bool]:
        selected_model = model or self.settings.gemini_model
        # cache_key_extra lets callers scope the cache (e.g. paperId + claim payload + prompt version)
        key_material = f"{selected_model}|{temperature}|{schema.__name__}|{prompt}"
        if cache_key_extra:
            key_material += f"|{cache_key_extra}"
        prompt_hash = hashlib.sha256(key_material.encode("utf-8")).hexdigest()
        
        hit = self.cache.get(prompt_hash)
        if hit is not None:
            try:
                obj = schema.model_validate_json(hit)
                return obj, True
            except ValidationError:
                pass # corrupted cache, re-fetch

        backoff_delays = [3.0, 8.0, 20.0, 40.0]
        max_attempts = 4
        last_error = None

        for attempt in range(max_attempts):
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
            except (APIError, ServerError) as e:
                last_error = e
                err_str = str(e)
                code = getattr(e, "code", None)
                is_transient = (
                    code in (429, 503) or
                    "RESOURCE_EXHAUSTED" in err_str or
                    "UNAVAILABLE" in err_str or
                    "high demand" in err_str
                )
                if is_transient and attempt < max_attempts - 1:
                    delay = backoff_delays[min(attempt, len(backoff_delays) - 1)]
                    time.sleep(delay)
                else:
                    raise
            except ValidationError as e:
                last_error = e
                if attempt >= max_attempts - 1:
                    raise
                time.sleep(1.0)
            except Exception as e:
                last_error = e
                if attempt < max_attempts - 1:
                    time.sleep(2.0)
                else:
                    raise

        if last_error:
            raise last_error
        raise RuntimeError(f"LLM structured output failed after retries")
