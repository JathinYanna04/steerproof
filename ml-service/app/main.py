from pathlib import Path
import logging
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware

from app.config import get_settings
from app.schemas import (
    ParseRequest, ParseResponse,
    ExtractRequest, ExtractResponse,
    VerifyRequest, VerifyResponse, ClaimVerifyDetail,
    WriteRequest, WriteResponse,
    ReelAudioRequest, ReelAudioResponse,
    ReelBrollRequest, ReelBrollResponse
)
from app.write.writer import run_writer
from app.parse.docling_parser import parse_pdf
from app.parse.page_images import render_page_images
from app.parse.meta import extract_meta
from app.parse.fetch import FetchUrlRequest, FetchUrlResponse, fetch_paper_pdf
from app.extract.extractor import extract_claims
from app.verify.quote_check import check_quote_with_relink
from app.verify.number_check import check_numbers
from app.verify.semantic import run_semantic_verification
from app.llm.client import LLMClient

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("ml-service")

settings = get_settings()
app = FastAPI(title="SteerProof ML Service", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

llm_client = LLMClient(settings)

# Never log the key itself, only whether it was found.
logger.info(f"pixabay key loaded: {'yes' if settings.pixabay_api_key else 'no'}")

@app.get("/health")
def health():
    return {"status": "ok", "gemini_model": settings.gemini_model}

@app.post("/fetch-url", response_model=FetchUrlResponse)
async def fetch_url(req: FetchUrlRequest):
    try:
        pdf_path = await fetch_paper_pdf(req.paperId, req.url, settings.storage_dir)
        return FetchUrlResponse(pdfPath=pdf_path)
    except Exception as e:
        logger.error(f"Error fetching URL: {e}", exc_info=True)
        raise HTTPException(status_code=400, detail=str(e))

@app.post("/parse", response_model=ParseResponse)
def parse_endpoint(req: ParseRequest):
    try:
        pdf_full_path = str(settings.storage_dir / req.pdfPath)
        if not Path(pdf_full_path).exists():
            # Check relative to repo root
            alt = Path(__file__).resolve().parent.parent.parent / req.pdfPath
            if alt.exists():
                pdf_full_path = str(alt)
            else:
                raise HTTPException(status_code=404, detail=f"PDF not found at {pdf_full_path}")

        passages = parse_pdf(pdf_full_path, req.paperId, settings.storage_dir)
        page_images = render_page_images(pdf_full_path, req.paperId, settings.storage_dir)
        meta = extract_meta(passages, llm_client, pdf_path=pdf_full_path,
                            cache_dir=settings.storage_dir / "cache" / "meta")

        page_count = len(page_images)
        return ParseResponse(
            meta=meta,
            pageCount=page_count,
            passages=passages,
            pageImages=page_images
        )
    except Exception as e:
        logger.error(f"Error in /parse: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/extract", response_model=ExtractResponse)
def extract_endpoint(req: ExtractRequest):
    try:
        claims, calls, hits = extract_claims(
            paper_id=req.paperId,
            passages=req.passages,
            meta=req.meta,
            theme=req.theme,
            llm=llm_client
        )
        return ExtractResponse(
            claims=claims,
            llmCalls=calls,
            cacheHits=hits
        )
    except Exception as e:
        logger.error(f"Error in /extract: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/verify", response_model=VerifyResponse)
def verify_endpoint(req: VerifyRequest):
    try:
        passages_by_id = {p.id: p for p in req.passages}
        passages_by_ord = {p.ord: p for p in req.passages}

        # Step 1: Code-level checks (Rapidfuzz quote check + number check)
        pre_results = []
        for idx, claim in enumerate(req.claims):
            quote_score, best_pid = check_quote_with_relink(
                claim.quote, claim.passageId, passages_by_id, passages_by_ord
            )
            # Re-link passageId if neighbour was better
            claim.passageId = best_pid

            numbers_ok, missing_numbers = check_numbers(
                claim.scientificClaim, claim.consumerDraft, claim.quote
            )
            pre_results.append((idx, quote_score, numbers_ok, missing_numbers))

        # Step 2: Semantic verification via Gemini
        semantic_map, calls, hits = run_semantic_verification(
            req.claims, req.passages, llm_client
        )

        # Step 3: Combine
        final_details: list[ClaimVerifyDetail] = []
        for idx, quote_score, numbers_ok, missing_numbers in pre_results:
            claim = req.claims[idx]
            sem = semantic_map.get(idx)
            entailment = sem.entailment if sem else "SUPPORTED"
            risk = sem.risk if sem else "GREEN"
            risk_reason = sem.riskReason if sem else "Verified"
            population_fit = sem.populationFit if sem else "HIGH"
            pop_reason = sem.populationReason if sem else "General population"

            if entailment == "UNSUPPORTED":
                risk = "RED"
            elif entailment == "OVERCLAIM" and risk == "GREEN":
                risk = "AMBER"

            if not numbers_ok and risk == "GREEN":
                risk = "AMBER"

            final_details.append(ClaimVerifyDetail(
                index=idx,
                passageId=claim.passageId,
                quoteScore=quote_score,
                numbersOk=numbers_ok,
                missingNumbers=missing_numbers,
                entailment=entailment,
                risk=risk,
                riskReason=risk_reason,
                populationFit=population_fit,
                populationReason=pop_reason
            ))

        return VerifyResponse(
            results=final_details,
            llmCalls=calls,
            cacheHits=hits
        )
    except Exception as e:
        logger.error(f"Error in /verify: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/write", response_model=WriteResponse)
async def write_content(req: WriteRequest):
    try:
        response, _ = run_writer(req, llm_client)
        return response
    except Exception as e:
        logger.error(f"Error in /write: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/reel/audio", response_model=ReelAudioResponse)
async def reel_audio_endpoint(req: ReelAudioRequest):
    try:
        from app.reel.audio import generate_reel_audio
        scenes_data = [s.model_dump() for s in req.scenes]
        voice = req.voice or "en-IN-NeerjaNeural"
        target_id = req.assetId or req.paperId or "reel"
        res = await generate_reel_audio(
            asset_id=target_id,
            scenes=scenes_data,
            storage_dir=settings.storage_dir,
            voice=voice,
            language=req.language or "en"
        )
        return ReelAudioResponse(**res)
    except Exception as e:
        logger.error(f"Error in /reel/audio: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/reel/broll", response_model=ReelBrollResponse)
async def reel_broll_endpoint(req: ReelBrollRequest):
    try:
        from app.reel.broll import process_reel_broll
        scenes_data = [s.model_dump() for s in req.scenes]
        results = process_reel_broll(
            scenes=scenes_data,
            storage_dir=settings.storage_dir,
            api_key=req.apiKey
        )
        return ReelBrollResponse(results=results, scenes=results)
    except Exception as e:
        logger.error(f"Error in /reel/broll: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


