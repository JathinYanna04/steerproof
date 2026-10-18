from pathlib import Path
import pymupdf

def render_page_images(pdf_full_path: str, paper_id: str, storage_root: Path) -> list[str]:
    out_dir = storage_root / "pages" / paper_id
    out_dir.mkdir(parents=True, exist_ok=True)

    rel_paths: list[str] = []
    doc = pymupdf.open(pdf_full_path)

    for i, page in enumerate(doc):
        page_num = i + 1
        out_file = out_dir / f"{page_num}.png"
        if not out_file.exists():
            pix = page.get_pixmap(matrix=pymupdf.Matrix(2, 2))
            pix.save(str(out_file))
        rel_paths.append(f"pages/{paper_id}/{page_num}.png")

    return rel_paths
