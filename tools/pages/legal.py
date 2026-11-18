import html
import re
import sys
from pathlib import Path

# python tools/pages/legal.py <folder of the page>: privacy-policy.md and terms.md of the repository as pages of it

ROOT = Path(__file__).resolve().parents[2]
PAGES = {"privacy-policy.md": "Privacy policy", "terms.md": "Terms of use"}
REPOSITORY = "https://github.com/vasmarfas/networkhelm/blob/master/"
STYLE ="body{font:16px/1.6 system-ui,sans-serif;max-width:46rem;margin:2rem auto;padding:0 1rem;color:#1d2733}a{color:#1a5fb4}code{background:#eef1f4;padding:0 .25em}"


def link(target):
    if re.match(r"(https?:|mailto:|#)", target):
        return target
    return REPOSITORY + target.removeprefix("./")


def inline(text):
    text = html.escape(text, quote=False)
    text = re.sub(r"`([^`]+)`", r"<code>\1</code>", text)
    text = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", text)
    return re.sub(r"\[([^\]]+)\]\(([^)]+)\)", lambda m: f'<a href="{link(m.group(2))}">{m.group(1)}</a>', text)


def convert(source):
    out, paragraph, items = [], [], []

    def flush():
        if paragraph:
            out.append("<p>" + inline(" ".join(paragraph)) + "</p>")
            paragraph.clear()
        if items:
            out.append("<ul>" + "".join(f"<li>{inline(item)}</li>" for item in items) + "</ul>")
            items.clear()

    for line in source.splitlines():
        heading = re.match(r"(#{1,3}) (.+)", line)
        if heading:
            flush()
            level = len(heading.group(1))
            out.append(f"<h{level}>{inline(heading.group(2))}</h{level}>")
        elif line.startswith("- "):
            if paragraph:
                flush()
            items.append(line[2:])
        elif line.strip():
            if items:
                flush()
            paragraph.append(line.strip())
        else:
            flush()
    flush()
    return "\n".join(out)


target = Path(sys.argv[1])
for name, title in PAGES.items():
    body = convert((ROOT / name).read_text(encoding="utf-8"))
    page = f'<!doctype html>\n<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>NetworkHelm: {title}</title><style>{STYLE}</style></head><body>\n{body}\n</body></html>\n'
    (target / name.replace(".md", ".html")).write_text(page, encoding="utf-8", newline="\n")
    print("wrote", name.replace(".md", ".html"))
