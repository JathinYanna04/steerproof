from pathlib import Path
from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=("../.env", ".env"),
        extra="ignore"
    )

    gemini_api_key: str = ""
    gemini_model_extract: str = "gemini-3.7-flash"
    gemini_model_verify: str = "gemini-3.7-flash"
    gemini_model_write: str = "gemini-3.7-flash"
    gemini_model: str = "gemini-3.7-flash"
    llm_cache_dir: Path = Path("./.cache/llm")
    llm_max_rpm: int = 8
    groq_api_key: str = ""
    pexels_api_key: str = ""
    tts_voice_en: str = "en-IN-NeerjaNeural"
    tts_voice_hi: str = "hi-IN-SwaraNeural"
    storage_dir: Path = Path("../storage")

def get_settings() -> Settings:
    return Settings()

settings = get_settings()
