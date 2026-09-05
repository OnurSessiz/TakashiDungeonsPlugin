#!/usr/bin/env python3
"""Builds the TakashiDungeons engineering handbook PDF.

Pipeline:
  content.html  --(macro expansion + highlighting)-->  book.html
  book.html     --(headless Chrome)-->                 pass1.pdf
  pass1.pdf     --(text extraction)-->                 heading -> page map
  book.html     --(TOC page numbers injected)-->       pass2.pdf
  pass2.pdf     --(reportlab stamp + pypdf outline)--> TakashiDungeons-Handbook.pdf
"""
import html
import io
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]          # scripts/handbook -> repo root
OUT = HERE / "out"
OUT.mkdir(exist_ok=True)

CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"

# --------------------------------------------------------------------------- highlighting

JAVA_KEYWORDS = set("""abstract assert boolean break byte case catch char class const continue
default do double else enum extends final finally float for goto if implements import instanceof
int interface long native new package private protected public return short static strictfp super
switch synchronized this throw throws transient try void volatile while var record yield sealed
permits non-sealed true false null""".split())

JAVA_TOKEN = re.compile(r"""
    (?P<doc>/\*\*.*?\*/)
  | (?P<block>/\*.*?\*/)
  | (?P<line>//[^\n]*)
  | (?P<str>"(?:\\.|[^"\\])*")
  | (?P<chr>'(?:\\.|[^'\\])*')
  | (?P<ann>@[A-Za-z_][A-Za-z0-9_.]*)
  | (?P<num>\b(?:0[xX][0-9a-fA-F_]+[lL]?|\d[\d_]*\.?[\d_]*(?:[eE][-+]?\d+)?[fFdDlL]?)\b)
  | (?P<word>[A-Za-z_$][A-Za-z0-9_$]*)
""", re.X | re.S)

YAML_TOKEN = re.compile(r"""
    (?P<comment>\#[^\n]*)
  | (?P<key>^[ \t]*-?[ \t]*[A-Za-z0-9_.\-]+(?=:))
  | (?P<str>"(?:\\.|[^"\\])*"|'(?:[^']|'')*')
  | (?P<num>\b\d+(?:\.\d+)?\b)
  | (?P<bool>\b(?:true|false|null|yes|no)\b)
""", re.X | re.M)

TEXT_TOKEN = re.compile(r"(?P<comment>\#[^\n]*)|(?P<key>^[A-Za-z ]+:)", re.M)


def hl_java(code: str) -> str:
    out, pos = [], 0
    for m in JAVA_TOKEN.finditer(code):
        out.append(html.escape(code[pos:m.start()]))
        pos = m.end()
        text = html.escape(m.group(0))
        kind = m.lastgroup
        if kind in ("doc", "block", "line"):
            cls = "c"
        elif kind in ("str", "chr"):
            cls = "s"
        elif kind == "ann":
            cls = "a"
        elif kind == "num":
            cls = "n"
        else:
            word = m.group(0)
            if word in JAVA_KEYWORDS:
                cls = "k"
            elif word[0].isupper():
                cls = "t"
            else:
                out.append(text)
                continue
        out.append(f'<span class="{cls}">{text}</span>')
    out.append(html.escape(code[pos:]))
    return "".join(out)


def hl_yaml(code: str) -> str:
    out, pos = [], 0
    for m in YAML_TOKEN.finditer(code):
        out.append(html.escape(code[pos:m.start()]))
        pos = m.end()
        text = html.escape(m.group(0))
        cls = {"comment": "c", "key": "y", "str": "s", "num": "n", "bool": "k"}[m.lastgroup]
        out.append(f'<span class="{cls}">{text}</span>')
    out.append(html.escape(code[pos:]))
    return "".join(out)


def hl_plain(code: str) -> str:
    out, pos = [], 0
    for m in TEXT_TOKEN.finditer(code):
        out.append(html.escape(code[pos:m.start()]))
        pos = m.end()
        text = html.escape(m.group(0))
        cls = "c" if m.lastgroup == "comment" else "y"
        out.append(f'<span class="{cls}">{text}</span>')
    out.append(html.escape(code[pos:]))
    return "".join(out)


HIGHLIGHTERS = {"java": hl_java, "yaml": hl_yaml, "text": hl_plain, "none": html.escape}


def dedent(code: str) -> str:
    lines = [ln for ln in code.split("\n")]
    while lines and not lines[0].strip():
        lines.pop(0)
    while lines and not lines[-1].strip():
        lines.pop()
    indents = [len(ln) - len(ln.lstrip()) for ln in lines if ln.strip()]
    cut = min(indents) if indents else 0
    return "\n".join(ln[cut:] if len(ln) >= cut else ln for ln in lines)


def code_figure(lang: str, caption: str, code: str, keep=False) -> str:
    body = HIGHLIGHTERS.get(lang, html.escape)(dedent(code))
    cap = f'<figcaption>{caption}</figcaption>' if caption.strip() else ""
    cls = "codefig" + (" keep" if keep else "")
    return f'<figure class="{cls}"><pre class="code">{body}</pre>{cap}</figure>'


# --------------------------------------------------------------------------- macros

CODE_BLOCK = re.compile(r"@@CODE\s+([a-z]+)\s*\|([^@\n]*)@@\n(.*?)\n@@END@@", re.S)
FILE_BLOCK = re.compile(r"@@FILE\s+([^|\n]+)\|(\d+)-(\d+)\|([a-z]+)\|([^@\n]*)@@")


def expand_macros(src: str) -> str:
    def code_sub(m):
        lang, caption, code = m.group(1), m.group(2), m.group(3)
        keep = caption.startswith("!")
        return code_figure(lang, caption.lstrip("!"), code, keep)

    def file_sub(m):
        path, a, b, lang, caption = m.groups()
        text = (REPO / path.strip()).read_text(encoding="utf-8").split("\n")
        chunk = "\n".join(text[int(a) - 1:int(b)])
        return code_figure(lang, caption, chunk)

    src = FILE_BLOCK.sub(file_sub, src)
    src = CODE_BLOCK.sub(code_sub, src)
    return src


# --------------------------------------------------------------------------- headings / TOC

HEADING = re.compile(r'<h([12])(?:\s+class="([^"]*)")?>(.*?)</h\1>', re.S)


def collect_headings(src: str):
    """Assign ids + invisible page markers to h1/h2, return (src, headings)."""
    headings = []
    counter = [0]

    def sub(m):
        level, cls, title = int(m.group(1)), m.group(2) or "", m.group(3)
        if "nolist" in cls:
            return m.group(0)
        counter[0] += 1
        hid = f"h{counter[0]}"
        label = ""
        lm = re.search(r'<span class="chapnum">(.*?)</span>', title, re.S)
        if lm:
            label = html.unescape(re.sub(r"<[^>]+>", "", lm.group(1))).strip()
        plain = re.sub(r'<span class="chapnum">.*?</span>', "", title, flags=re.S)
        plain = html.unescape(re.sub(r"<[^>]+>", "", plain)).strip()
        headings.append({"id": hid, "level": level, "title": plain, "label": label})
        clsattr = f' class="{cls}"' if cls else ""
        marker = f'<span class="pgmark">[[{hid}]]</span>'
        return f'<h{level} id="{hid}"{clsattr}>{title}{marker}</h{level}>'

    return HEADING.sub(sub, src), headings


def build_toc(headings, pages=None):
    rows = []
    for h in headings:
        num = pages.get(h["id"], "") if pages else "00"
        cls = "toc1" if h["level"] == 1 else "toc2"
        label = h.get("label") or ""
        tt = (f'<b class="tl">{html.escape(label)}</b> ' if label else "") + html.escape(h["title"])
        rows.append(
            f'<div class="{cls}"><span class="tt">{tt}</span>'
            f'<span class="dots"></span><span class="tp">{num}</span></div>')
    return "\n".join(rows)


# --------------------------------------------------------------------------- images

def prepare_images():
    """Downscales docs/images/*.png into OUT/img as JPEG.

    Chrome embeds a source PNG at full resolution; the two 1920x1080 screenshots alone pushed
    the rendered PDF past 50 MB. 1500 px wide JPEG is indistinguishable at print size.
    """
    from PIL import Image
    dst = OUT / "img"
    dst.mkdir(exist_ok=True)
    for src in sorted((REPO / "docs" / "images").glob("*.png")):
        target = dst / (src.stem + ".jpg")
        if target.exists() and target.stat().st_mtime >= src.stat().st_mtime:
            continue
        image = Image.open(src).convert("RGB")
        image.thumbnail((1500, 1500), Image.LANCZOS)
        image.save(target, "JPEG", quality=86, optimize=True)
        print(f"  image: {src.name} -> {target.name}")


# --------------------------------------------------------------------------- render

def render(html_path: Path, pdf_path: Path):
    if pdf_path.exists():
        pdf_path.unlink()
    profile = OUT / "chromeprofile"
    cmd = [CHROME, "--headless", "--disable-gpu", "--no-sandbox",
           "--no-pdf-header-footer", "--run-all-compositor-stages-before-draw",
           "--virtual-time-budget=20000",
           f"--user-data-dir={profile}",
           f"--print-to-pdf={pdf_path}", html_path.as_uri()]
    subprocess.run(cmd, check=True, capture_output=True, timeout=600)
    if not pdf_path.exists():
        raise RuntimeError("Chrome produced no PDF")


def page_map(pdf_path: Path, headings):
    from pypdf import PdfReader
    reader = PdfReader(str(pdf_path))
    pages = {}
    for i, page in enumerate(reader.pages, start=1):
        try:
            text = page.extract_text() or ""
        except Exception:
            text = ""
        for m in re.finditer(r"\[\[(h\d+)\]\]", text.replace("\n", "")):
            pages.setdefault(m.group(1), i)
    # tolerate marker split across extraction quirks
    return pages


# --------------------------------------------------------------------------- stamping

def divider_pages(pdf_path: Path):
    from pypdf import PdfReader
    reader = PdfReader(str(pdf_path))
    skip = set()
    for i, page in enumerate(reader.pages, start=1):
        try:
            text = page.extract_text() or ""
        except Exception:
            text = ""
        if "[[PARTPAGE]]" in text.replace(chr(10), ""):
            skip.add(i)
    return skip


def stamp(src_pdf: Path, dst_pdf: Path, headings, pages, front_matter=2, skip=frozenset()):
    from pypdf import PdfReader, PdfWriter
    from reportlab.pdfgen import canvas
    from reportlab.lib.pagesizes import A4
    from reportlab.lib.colors import Color

    reader = PdfReader(str(src_pdf))
    total = len(reader.pages)

    # page -> current part title, for the running footer
    part_of_page = {}
    current = ""
    ordered = sorted(((p, h) for h in headings for p in [pages.get(h["id"])] if p),
                     key=lambda t: (t[0], 0 if t[1]["level"] == 1 else 1))
    marks = {}
    for p, h in ordered:
        if h["level"] == 1:
            lbl = h.get("label")
            marks.setdefault(p, (lbl + '  ·  ' + h["title"]) if lbl else h["title"])
    for p in range(1, total + 1):
        if p in marks:
            current = marks[p]
        part_of_page[p] = current

    writer = PdfWriter()
    ink = Color(0.42, 0.40, 0.38)
    rule = Color(0.80, 0.78, 0.75)

    for i, page in enumerate(reader.pages, start=1):
        if i > front_matter and i not in skip:
            buf = io.BytesIO()
            w, h = float(page.mediabox.width), float(page.mediabox.height)
            c = canvas.Canvas(buf, pagesize=(w, h))
            c.setStrokeColor(rule)
            c.setLineWidth(0.4)
            c.line(48, 34, w - 48, 34)
            c.setFillColor(ink)
            c.setFont("Helvetica", 7.3)
            label = part_of_page.get(i, "")
            if len(label) > 68:
                label = label[:65] + "..."
            c.drawString(48, 23, label)
            c.setFont("Helvetica-Bold", 8.2)
            c.drawRightString(w - 48, 23, str(i))
            c.save()
            buf.seek(0)
            overlay = PdfReader(buf).pages[0]
            page.merge_page(overlay)
            # merge_page decompresses the content stream; without this the file grows ~10x.
            try:
                page.compress_content_streams()
            except Exception:
                pass
        writer.add_page(page)

    # PDF bookmarks
    parents = {}
    for h in headings:
        p = pages.get(h["id"])
        if not p:
            continue
        idx = min(p, total) - 1
        if h["level"] == 1:
            parents[1] = writer.add_outline_item(h["title"], idx)
        else:
            writer.add_outline_item(h["title"], idx, parent=parents.get(1))

    writer.add_metadata({
        "/Title": "TakashiDungeons - Engineering Handbook",
        "/Author": "Onur Sessiz",
        "/Subject": "Phases 1-3: generation core, instance lifecycle, mob system",
        "/Creator": "TakashiDungeons documentation build",
    })
    raw = dst_pdf.with_suffix(".raw.pdf")
    with open(raw, "wb") as fh:
        writer.write(fh)

    # pypdf explodes Chrome's object streams into loose objects, which multiplies the file size
    # roughly tenfold. Re-saving through qpdf rebuilds them: 51 MB -> 3 MB, same content.
    import pikepdf
    with pikepdf.open(raw) as pdf:
        pdf.save(dst_pdf, object_stream_mode=pikepdf.ObjectStreamMode.generate,
                 compress_streams=True)
    raw.unlink(missing_ok=True)


# --------------------------------------------------------------------------- main

def main():
    parts = sorted(HERE.glob("content_*.html"))
    if not parts:
        raise SystemExit("no content_*.html files found")
    content = "\n".join(p.read_text(encoding="utf-8") for p in parts)
    print("content parts:", ", ".join(p.name for p in parts))
    shell = (HERE / "shell.html").read_text(encoding="utf-8")
    prepare_images()

    body = expand_macros(content)
    body, headings = collect_headings(body)

    def assemble(pages=None):
        toc = build_toc(headings, pages)
        return shell.replace("<!--BODY-->", body).replace("<!--TOC-->", toc)

    book = OUT / "book.html"
    book.write_text(assemble(), encoding="utf-8")
    print("pass 1: rendering...")
    render(book, OUT / "pass1.pdf")
    pages = page_map(OUT / "pass1.pdf", headings)
    print(f"pass 1: located {len(pages)}/{len(headings)} headings")
    missing = [h["title"] for h in headings if h["id"] not in pages]
    if missing:
        print("  MISSING:", missing[:8])

    book.write_text(assemble(pages), encoding="utf-8")
    print("pass 2: rendering...")
    render(book, OUT / "pass2.pdf")
    pages2 = page_map(OUT / "pass2.pdf", headings)
    if pages2:
        pages = {**pages, **pages2}

    final = REPO / "docs" / "TakashiDungeons-Handbook.pdf"
    skip = divider_pages(OUT / "pass2.pdf")
    front = (min(skip) - 1) if skip else 2
    stamp(OUT / "pass2.pdf", final, headings, pages, front_matter=front, skip=skip)
    from pypdf import PdfReader
    n = len(PdfReader(str(final)).pages)
    print(f"done: {final}  ({n} pages)")


if __name__ == "__main__":
    main()
