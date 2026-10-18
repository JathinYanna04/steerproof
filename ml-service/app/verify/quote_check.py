from rapidfuzz import fuzz
from app.schemas import Passage

def norm(s: str) -> str:
    if not s:
        return ""
    # Normalize unicode minus, en-dash, em-dash, whitespace, and lowercase
    return " ".join(s.replace("\u2212", "-").replace("–", "-").replace("—", "-").lower().split())

def check_quote_with_relink(quote: str, current_passage_id: str, passages_by_id: dict[str, Passage], passages_by_ord: dict[int, Passage]) -> tuple[int, str]:
    """
    Computes fuzzy match score between quote and passage text.
    Also checks neighbour passages (ord - 1, ord + 1) to see if quote was split or slightly misattributed.
    Returns (best_score, best_passage_id).
    """
    current_passage = passages_by_id.get(current_passage_id)
    if not current_passage:
        return 0, current_passage_id
    
    norm_quote = norm(quote)
    if not norm_quote:
        return 0, current_passage_id
    
    current_score = int(fuzz.partial_ratio(norm_quote, norm(current_passage.text)))
    best_score = current_score
    best_pid = current_passage_id
    
    curr_ord = current_passage.ord
    for delta in (-1, 1):
        neighbour = passages_by_ord.get(curr_ord + delta)
        if neighbour:
            neighbour_score = int(fuzz.partial_ratio(norm_quote, norm(neighbour.text)))
            # If neighbour has a significantly better score, re-link
            if neighbour_score > best_score:
                best_score = neighbour_score
                best_pid = neighbour.id
                
    return best_score, best_pid
