import re

NUM_RANGE_REGEX = re.compile(r"(\d+(?:\.\d+)?)\s*(?:-|–|—|to)\s*(\d+(?:\.\d+)?)(?:\s*(?:%|\bpercent\b))?", re.IGNORECASE)
SINGLE_NUM_REGEX = re.compile(r"(?<![\w.])[-−]?\d+(?:[.,]\d+)?(?:\s*(?:%|\bpercent\b))?", re.IGNORECASE)

def normalize_text_for_numbers(text: str) -> str:
    if not text:
        return ""
    # Normalize unicode minuses and dashes
    s = text.replace("\u2212", "-").replace("–", "-").replace("—", "-")
    # Normalize percent word to %
    s = re.sub(r"\bpercent\b", "%", s, flags=re.IGNORECASE)
    s = re.sub(r"\bper cent\b", "%", s, flags=re.IGNORECASE)
    # Normalize comma decimal (e.g. 5,2 -> 5.2) if not thousands separator
    s = re.sub(r"(\d+),(\d+)", r"\1.\2", s)
    # Normalize "X to Y%" or "X - Y %" to "X-Y%"
    s = re.sub(r"(\d+(?:\.\d+)?)\s*(?:-|to)\s*(\d+(?:\.\d+)?)\s*%", r"\1-\2%", s, flags=re.IGNORECASE)
    # Normalize "X to Y" to "X-Y"
    s = re.sub(r"(\d+(?:\.\d+)?)\s+to\s+(\d+(?:\.\d+)?)", r"\1-\2", s, flags=re.IGNORECASE)
    # Collapse spaces around - and %
    s = re.sub(r"\s*-\s*", "-", s)
    s = re.sub(r"\s*%", "%", s)
    return s

def extract_numbers_and_ranges(text: str) -> list[str]:
    if not text:
        return []
    norm = normalize_text_for_numbers(text)
    
    found: list[str] = []
    
    # Extract ranges first: e.g. 20-40% or 20-40
    ranges = re.findall(r"\b\d+(?:\.\d+)?-\d+(?:\.\d+)?%?", norm)
    found.extend(ranges)
    
    # Mask out ranges in norm to avoid double matching
    masked = norm
    for r in ranges:
        masked = masked.replace(r, " ")
        
    # Extract individual numbers
    singles = SINGLE_NUM_REGEX.findall(masked)
    for m in singles:
        clean = "".join(m.split()).replace("percent", "%")
        if clean and clean not in ("-", "%"):
            found.append(clean)
            
    return list(dict.fromkeys(found))

def check_numbers(scientific_claim: str, consumer_draft: str | None, quote: str) -> tuple[bool, list[str]]:
    """
    Checks that every number mentioned in scientific_claim or consumer_draft
    appears in the quote (supporting range normalization and % vs percent).
    """
    norm_quote = normalize_text_for_numbers(quote)
    quote_tokens = set(extract_numbers_and_ranges(quote))
    clean_quote_str = "".join(norm_quote.split()).lower()

    claim_text = scientific_claim + (" " + consumer_draft if consumer_draft else "")
    claim_tokens = extract_numbers_and_ranges(claim_text)

    missing = []
    for token in claim_tokens:
        clean_tok = "".join(token.split()).lower()
        
        # 1. Direct token set match
        if token in quote_tokens or clean_tok in [t.lower() for t in quote_tokens]:
            continue
            
        # 2. Substring match in normalized quote text
        if clean_tok in clean_quote_str:
            continue
            
        # 3. If token is range like 20-40%, check if both endpoints exist or range exists
        if "-" in token:
            parts = token.rstrip("%").split("-")
            has_pct = token.endswith("%")
            # E.g. "20-40%" might appear in text as "20% to 40%"
            alt_pct = f"{parts[0]}%-{parts[1]}%"
            if alt_pct.lower() in clean_quote_str:
                continue
            alt_to = f"{parts[0]}to{parts[1]}" + ("%" if has_pct else "")
            if alt_to.lower() in clean_quote_str:
                continue

        # 4. Float equivalence check for single numbers
        if "-" not in token:
            try:
                num_val = float(token.rstrip("%"))
                matched_float = False
                for qn in quote_tokens:
                    if "-" not in qn:
                        try:
                            if (token.endswith("%") == qn.endswith("%")) and abs(float(qn.rstrip("%")) - num_val) < 1e-5:
                                matched_float = True
                                break
                        except ValueError:
                            pass
                if matched_float:
                    continue
            except ValueError:
                pass

        missing.append(token)

    unique_missing = list(dict.fromkeys(missing))
    return len(unique_missing) == 0, unique_missing
