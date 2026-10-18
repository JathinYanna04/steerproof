# ml-service (FastAPI)

## Layout
```
ml-service/
├── requirements.txt
└── app/
    ├── main.py                 # routes
    ├── config.py               # env settings (pydantic-settings)
    ├── schemas.py              # all Pydantic models (shared request/response + LLM schemas)
    ├── llm/
    │   ├── client.py           # Gemini wrapper: structured output, cache, rate limit, retry, fallback
    │   ├── cache.py            # disk cache keyed by sha256
    │   └── ratelimit.py        # token bucket
    ├── parse/
    │   ├── docling_parser.py   # passages + bbox
    │   ├── page_images.py      # PyMuPDF page PNGs
    │   ├── meta.py             # title/authors/doi heuristics + LLM fallback
    │   └── fetch.py            # URL → PDF
    ├── extract/extractor.py
    ├── verify/
    │   ├── quote_check.py      # rapidfuzz
    │   ├── number_check.py     # regex numbers
    │   └── semantic.py         # Gemini verifier
    ├── write/writer.py
    ├── reel/
    │   ├── tts.py              # edge-tts + word boundaries
    │   └── broll.py            # Pexels search/download
    ├── prompts/                # extractor.md, verifier.md, writer.md, reel.md, hinglish.md (from docs/PROMPTS.md)
    └── config/
        ├── voice_spec.yaml     # Steer voice (from docs/BRAND.md)
        └── risk_rubric.yaml    # from docs/GUARDRAILS.md
```

## requirements.txt
```
fastapi
uvicorn[standard]
pydantic>=2
pydantic-settings
google-genai
docling
pymupdf
rapidfuzz
edge-tts
httpx
pyyaml
mutagen          # mp3 duration
tenacity
beautifulsoup4   # PMC page → pdf link
```

## 1. Parsing (Docling)

```python
from docling.document_converter import DocumentConverter

def parse(pdf_path: str, paper_id: str) -> list[Passage]:
    result = DocumentConverter().convert(pdf_path)
    doc = result.document
    passages, section, ord_ = [], None, 0
    for item, _level in doc.iterate_items():
        label = str(getattr(item, "label", "")).lower()
        if "section_header" in label or label.endswith("title"):
            section = item.text
        text = item.export_to_markdown(doc=doc) if "table" in label else getattr(item, "text", "")
        if not text or len(text.strip()) < 20 or not item.prov:
            continue
        prov = item.prov[0]
        page = doc.pages[prov.page_no]
        bbox = prov.bbox.to_top_left_origin(page_height=page.size.height)
        ord_ += 1
        passages.append(Passage(
            id=f"E-{paper_id[2:]}-{ord_:04d}", section=section, kind=normalize(label),
            page=prov.page_no, bbox={"l": bbox.l, "t": bbox.t, "r": bbox.r, "b": bbox.b},
            page_width=page.size.width, page_height=page.size.height,
            text=text.strip(), ord=ord_))
    return passages
```

Rules:
- **Drop** the references/bibliography section, author affiliations, funding, and conflict-of-interest passages from extraction input. Keep them stored.
- **Merge** consecutive paragraphs on the same page under the same section if each is under 200 characters.
- **Cache** the parsed output at `storage/parsed/{paperId}.json`. Docling is slow, so never re-parse.
- **Page images:** `fitz.open(pdf)`, then `page.get_pixmap(matrix=fitz.Matrix(2,2)).save(...)`. The frontend scales the bbox by `imageWidth / pageWidth`.
- **Fallback:** if Docling throws or returns fewer than 10 passages, use PyMuPDF `page.get_text("blocks")` (gives x0, y0, x1, y1 in top-left coordinates) and mark `kind="block"`.

## 2. Gemini client

```python
from google import genai
from google.genai import types

class LLM:
    def __init__(self, settings):
        self.client = genai.Client(api_key=settings.gemini_api_key)
        self.cache = DiskCache(settings.llm_cache_dir)
        self.limiter = TokenBucket(settings.llm_max_rpm)

    def structured(self, model: str, prompt: str, schema: type[BaseModel], temperature: float) -> tuple[BaseModel, bool]:
        key = sha256(f"{model}|{temperature}|{schema.__name__}|{prompt}")
        if (hit := self.cache.get(key)) is not None:
            return schema.model_validate_json(hit), True
        for attempt in range(2):
            self.limiter.acquire()
            try:
                resp = self.client.models.generate_content(
                    model=model, contents=prompt,
                    config=types.GenerateContentConfig(
                        response_mime_type="application/json",
                        response_schema=schema, temperature=temperature))
                obj = schema.model_validate_json(resp.text)
                self.cache.set(key, resp.text)
                return obj, False
            except RateLimitError:   # map google errors with status 429
                backoff(attempt)     # 5s, 15s, 45s
            except ValidationError:
                if attempt == 1: raise
        raise StageFailed("LLM failed after retries")
```

- Keep schemas simple: Gemini's `response_schema` supports objects, arrays, strings, numbers, booleans and enums. Avoid `dict[str, Any]`, unions, and `Optional` nesting.
- Long papers: if the passage text exceeds about 100k characters, split it into chunks by section and extract per chunk, then deduplicate claims by `(passage_id, rapidfuzz ratio of scientificClaim > 85)`.
- Log `llmCalls` and `cacheHits` per request and return them.

## 3. Extraction
- Input: passages formatted as `[E-0001-0042] (p.3, Results) text…`, plus paper meta and an optional theme.
- Prompt: `docs/PROMPTS.md › Extractor`. Target 8–15 claims.
- Post-process: drop claims whose `passageId` is not in the input set (log as "hallucinated passage id").

## 4. Verification (code first, then LLM)

**4a. Quote check**
```python
from rapidfuzz import fuzz
def norm(s): return " ".join(s.replace("\u2212","-").replace("–","-").lower().split())
score = int(fuzz.partial_ratio(norm(claim.quote), norm(passage.text)))
```
`score < 90` → `doctor_status = NEEDS_EVIDENCE`. Also try neighbour passages (ord ±1) and keep the best match; if a neighbour wins, re-link `passage_id`.

**4b. Number check**
```python
NUM = re.compile(r"(?<![\w.])[-−]?\d+(?:[.,]\d+)?\s?%?")
```
Every number in `scientificClaim` and `consumerDraft` must appear in the quote (after normalization: strip spaces, unify minus sign, `,`→`.` for decimals). Missing numbers set `numbersOk=false` and are listed in `missingNumbers`. Numbers 1–10 written as words are not checked.

**4c. Semantic verifier (Gemini, separate call, batched about 6 claims per call)**
Prompt: `docs/PROMPTS.md › Verifier`, plus the rubric from `config/risk_rubric.yaml`. It outputs entailment, risk, and population fit with reasons.

**Final status rule:**
- `quoteScore < 90` → `NEEDS_EVIDENCE`
- else → `PENDING` (`risk` and `entailment` are shown to the doctor, but they don't auto-reject; the doctor decides)
- `entailment == UNSUPPORTED` forces `risk = RED`.

## 5. Writer
- Input: only approved claims, using `editedCopy` if present, with their finding class and limitations, plus `voice_spec.yaml`.
- Prompt: `docs/PROMPTS.md › Writer`. Output shapes are defined there.
- After the call, validate in code:
  - every `claimIds` entry exists in the input set
  - `kind=="claim"` blocks have ≥1 claimId
  - numbers in each block appear in a linked claim's quote
  - character limits from `docs/BRAND.md`

  On failure, retry once with the error list appended to the prompt. If it fails again, return an error and the backend marks the asset `BLOCKED`.

## 6. Reel audio (edge-tts, free)
```python
import edge_tts, json
async def synth(text: str, voice: str, out_mp3: str, out_words: str):
    comm = edge_tts.Communicate(text, voice, rate="-5%", boundary="WordBoundary")
    words = []
    with open(out_mp3, "wb") as f:
        async for chunk in comm.stream():
            if chunk["type"] == "audio":
                f.write(chunk["data"])
            elif chunk["type"] == "WordBoundary":
                start = chunk["offset"] / 10_000            # 100-ns ticks → ms
                words.append({"text": chunk["text"], "startMs": start,
                              "endMs": start + chunk["duration"] / 10_000})
    json.dump(words, open(out_words, "w"))
```
- One mp3 per scene. Duration comes from `mutagen.mp3.MP3(path).info.length`.
- Voices: `en-IN-NeerjaNeural` (default, warm female) and `en-IN-PrabhatNeural`. For Hinglish use `hi-IN-SwaraNeural` with the script written in Roman Hinglish (test pronunciation; fall back to Devanagari-Hindi text if the result sounds wrong).
- If the installed edge-tts version has no `boundary` arg, word boundaries are emitted by default. Check `edge_tts.__version__`.

## 7. B-roll (Pexels, free)
```
GET https://api.pexels.com/videos/search?query={q}&orientation=portrait&per_page=10&size=medium
Header: Authorization: {PEXELS_API_KEY}
```
- Pick the first video with `duration >= scene seconds`, and from its `video_files` choose the portrait file with height closest to 1920 (≤ 1920). Download it to `storage/broll/{id}.mp4` (cached).
- **Query safety:** run `brollQuery` through `docs/GUARDRAILS.md › B-roll denylist` before searching. If it is blocked or there are 0 results, return null (render uses a cream motion background).
- Store photographer + URL for the credits in `sources.md`.

## 8. Hinglish (bonus)
Use the writer prompt with `docs/PROMPTS.md › Hinglish addendum`, then run verify 4b (numbers) against the English approved copy, plus one Gemini call checking that meaning is preserved block by block.
