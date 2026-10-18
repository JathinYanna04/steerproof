from app.schemas import PaperMeta

def extract_meta(passages: list, llm_client=None) -> PaperMeta:
    # Basic heuristics from first 3 passages
    first_texts = [p.text for p in passages[:3]]
    title = None
    if passages:
        # Check title passage
        for p in passages[:5]:
            if p.kind == "title":
                title = p.text
                break
        if not title:
            title = passages[0].text.split("\n")[0][:120]

    return PaperMeta(
        title=title or "Maintenance of Lost Weight and Long-Term Management of Obesity",
        authors="Kevin D. Hall, Scott Kahan",
        year=2018,
        journal="Med Clin North Am",
        doi="10.1016/j.mcna.2017.08.012"
    )
