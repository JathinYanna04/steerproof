import re
from pathlib import Path

PROMPTS_DIR = Path(__file__).resolve().parent

def load_prompt(name: str, vars: dict[str, str | None]) -> str:
    """Loads a prompt template from app/prompts/<name>.md and replaces variables."""
    prompt_file = PROMPTS_DIR / f"{name}.md"
    if not prompt_file.exists():
        raise FileNotFoundError(f"Prompt file not found: {prompt_file}")
    
    content = prompt_file.read_text(encoding="utf-8")
    
    # Handle {{#var}}...{{/var}} conditional blocks
    def repl_section(match):
        var_name = match.group(1)
        inner = match.group(2)
        val = vars.get(var_name)
        if val:
            return inner.replace(f"{{{{{var_name}}}}}", str(val))
        return ""
    
    content = re.sub(r"\{\{#(\w+)\}\}(.*?)\{\{/\1\}\}", repl_section, content, flags=re.DOTALL)
    
    # Handle direct {{var}} replacement
    for k, v in vars.items():
        placeholder = f"{{{{{k}}}}}"
        content = content.replace(placeholder, str(v) if v is not None else "")
        
    return content
