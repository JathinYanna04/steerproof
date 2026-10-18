from pathlib import Path
import httpx
from bs4 import BeautifulSoup
from pydantic import BaseModel

class FetchUrlRequest(BaseModel):
    paperId: str
    url: str

class FetchUrlResponse(BaseModel):
    pdfPath: str

async def fetch_paper_pdf(paper_id: str, url: str, storage_root: Path) -> str:
    target_dir = storage_root / "papers"
    target_dir.mkdir(parents=True, exist_ok=True)
    target_file = target_dir / f"{paper_id}.pdf"

    if target_file.exists() and target_file.stat().st_size > 1000:
        return f"papers/{paper_id}.pdf"

    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    async with httpx.AsyncClient(follow_redirects=True, timeout=60.0, headers=headers) as client:
        # Check if direct pdf or web page
        resp = await client.get(url)
        content_type = resp.headers.get("content-type", "").lower()
        
        if "application/pdf" in content_type or url.lower().endswith(".pdf"):
            target_file.write_bytes(resp.content)
            return f"papers/{paper_id}.pdf"

        # Otherwise parse HTML to find pdf link (e.g. PMC page)
        soup = BeautifulSoup(resp.text, "html.parser")
        pdf_link = None
        for a in soup.find_all("a", href=True):
            href = a["href"]
            if ".pdf" in href.lower() or "/pdf/" in href.lower():
                if href.startswith("http"):
                    pdf_link = href
                else:
                    # relative url
                    from urllib.parse import urljoin
                    pdf_link = urljoin(str(resp.url), href)
                break

        if not pdf_link:
            raise ValueError(f"Could not find PDF link on page: {url}")

        pdf_resp = await client.get(pdf_link)
        target_file.write_bytes(pdf_resp.content)
        return f"papers/{paper_id}.pdf"
