import pytest
import shutil
from pathlib import Path
from unittest.mock import MagicMock
from pydantic import BaseModel
from app.llm.client import LLMClient
from app.config import Settings

class DummySchema(BaseModel):
    message: str

def test_cache_hit(tmp_path):
    settings = Settings(
        gemini_api_key="fake-key",
        llm_cache_dir=tmp_path / "cache"
    )
    llm = LLMClient(settings)
    
    # Mock the underlying Gemini client
    mock_resp = MagicMock()
    mock_resp.text = '{"message": "hello world"}'
    llm.client.models.generate_content = MagicMock(return_value=mock_resp)

    # First call: cache miss, calls client
    res1, hit1 = llm.structured("prompt 1", DummySchema, temperature=0.0)
    assert hit1 is False
    assert res1.message == "hello world"
    assert llm.client.models.generate_content.call_count == 1

    # Second identical call: cache hit, 0 client calls
    res2, hit2 = llm.structured("prompt 1", DummySchema, temperature=0.0)
    assert hit2 is True
    assert res2.message == "hello world"
    assert llm.client.models.generate_content.call_count == 1 # still 1!
