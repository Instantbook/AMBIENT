#!/usr/bin/env python3
"""Rebuild a scanned PDF into an EPUB that has REAL paragraphs.

Why this exists
---------------
Archive.org's EPUB export throws the page layout away. Measured on its
Cooper "Complete Works of Plato": 1831 of 1832 pages are a SINGLE <p>, with
zero <br> tags and zero newlines inside any paragraph. Its own stylesheet
says `p {text-indent: 4em}` - the producer meant one <p> per paragraph and
emitted one per PAGE. So the book arrives as a 2700-character block per
page, and nothing on the device can recover paragraphs that are not in the
file. The only structure left in that text is a NAME: speaker label, which
is why a four-paragraph speech stayed one block.

The paragraphs are not lost, though - they are geometry. A printed
paragraph opens with an indent, and a PDF with a text layer keeps every
word's coordinates. So the breaks are recovered by MEASURING:

  * the line starts on a page form two clusters - body lines and indented
    paragraph openings - and the boundary is the GAP between them, measured
    on that page alone. Per page, because the scan is not uniformly
    aligned: the book's median left edge is 33pt while page 900's is 6pt,
    so one global column finds zero indents on half the book. By the gap,
    because which cluster is larger flips with the material - page 305 is
    26 indented lines against 18 body ones, so a median or a mode picks the
    wrong cluster and finds no indents at all. The indent itself measures
    11.7pt median across the book.
  * short lines above the first long line, or below the last, are the
    running head and the folio - the things that were injecting
    "Theaetetus 223" into the middle of a sentence.
  * a bare number or Stephanus reference (155a) is marginal. Those land
    mid-sentence 137 times to 24 in the flat text, so they are lifted out
    of the flow rather than left to read as noise.

The classic "previous line was short, so the next one starts a paragraph"
rule is kept only as a FALLBACK, for a page whose lines form no two
clusters. Used alongside indentation it double-counts, because in a
dialogue every turn both ends short and indents - that is what produced
37.6 paragraphs a page on the first pass, against a true ~12.

Also not used: splitting on sentence ends, or on capitalised speaker names
(the real text has Meno:, Socrates:, SOCRATES: and OCR's SockATES: alike).
Nothing here is invented; every break is something the typesetter did.

Usage
-----
    python tools/pdf2epub.py IN.pdf OUT.epub [--title T] [--author A]
    python tools/pdf2epub.py IN.pdf --report [--first N] [--last N]
    python tools/pdf2epub.py IN.pdf --sample 900   # show one page's split

One XHTML per printed page is kept deliberately: page numbers stay
meaningful, "go to page" stays a spine index, and a page turn stays O(1)
however large the book.
"""
import argparse
import collections
import os
import re
import statistics
import sys
import zipfile

try:
    import fitz  # PyMuPDF
except ImportError:
    sys.exit("PyMuPDF is required:  pip install pymupdf")


LONG = 45        # chars before a line counts as a full body line
INDENT = 7.0     # pt right of the page's own column before a line indents
NOTE = re.compile(r"^\d{1,4}\s?[a-e]?$")            # folio, or Stephanus 155a
# A label ends in a COLON. Allowing a period too made ordinary
# sentences ("Good. ", "Yes. ", "Certainly. ") read as speakers.
SPEAKER = re.compile(r"^([A-Z][A-Za-z]{1,14})\s*:")


def page_lines(page):
    """[(x0, y0, x1, text)] for one page, in reading order."""
    out = []
    for block in page.get_text("dict")["blocks"]:
        for line in block.get("lines", []):
            text = "".join(s["text"] for s in line["spans"])
            text = text.replace(" ", " ").strip()
            if text:
                x0, y0, x1, _ = line["bbox"]
                out.append((x0, y0, x1, text))
    out.sort(key=lambda r: (r[1], r[0]))
    return out


def split_page(lines):
    """One page -> (paragraphs, marginal notes).

    Everything is measured against THIS page, because the scan's alignment
    drifts from page to page.
    """
    longs = [i for i, r in enumerate(lines) if len(r[3]) >= LONG]
    if not longs:
        # No body text: a plate, a part-title, or a blank. Keep any real
        # words as one paragraph rather than silently dropping the page.
        keep = [r[3] for r in lines if not NOTE.match(r[3])]
        return ([" ".join(keep)] if keep else []), []

    first, last = longs[0], longs[-1]
    core = lines[first:last + 1]
    limit = indent_limit([r[0] for r in core])

    notes = [r[3] for i, r in enumerate(lines)
             if (i < first or i > last) and NOTE.match(r[3])]

    right = statistics.median([r[2] for r in core])
    paras, cur = [], []
    prev_short = False
    for _x0i, (x0, _y0, x1, text) in enumerate(core):
        if NOTE.match(text):
            notes.append(text)
            continue
        if limit is None:
            # No two clusters on this page, so indentation says nothing.
            # Fall back to the typographic rule that a paragraph's LAST
            # line stops short of the margin. This is only ever a fallback:
            # where indents exist it double-counts, because in a dialogue
            # every turn both ends short and indents.
            opens = prev_short
        else:
            opens = x0 > limit
        if cur and opens:
            paras.append(cur)
            cur = []
        cur.append(text)
        prev_short = x1 < right - 40
    if cur:
        paras.append(cur)
    return [join(p) for p in paras if p], notes


def indent_limit(xs):
    """Where the indented lines start, or None if there is only one column.

    The body lines and the indented lines form two clusters, and which one
    is LARGER flips with the material: a page of running prose is mostly
    body lines, while a page of rapid dialogue is mostly paragraph openings
    (measured: page 305 is 26 indented against 18 body). So taking a median
    or a mode picks the wrong cluster on one of them and finds no indents
    at all. The gap between the clusters is the stable feature, so that is
    what is measured.
    """
    xs = sorted(xs)
    if len(xs) < 4:
        return None
    lo = xs[0]
    xs = [x for x in xs if x <= lo + 60]   # drop a marginal note at x=413
    best, at = 0.0, None
    for a, b in zip(xs, xs[1:]):
        if b - a > best:
            best, at = b - a, (a + b) / 2.0
    return at if best >= 5.0 else None


def join(lines):
    """Join a paragraph's lines, healing hyphenation at the line break."""
    out = ""
    for line in lines:
        line = line.strip()
        if not out:
            out = line
        elif out.endswith("-") and not out.endswith("--") and line[:1].islower():
            out = out[:-1] + line          # a word broken across two lines
        else:
            out = out + " " + line
    return re.sub(r"\s+", " ", out).strip()


def levenshtein(a, b, cap=3):
    """Edit distance, abandoned once it exceeds cap."""
    if abs(len(a) - len(b)) > cap:
        return cap + 1
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1,
                           prev[j - 1] + (ca != cb)))
        if min(cur) > cap:
            return cap + 1
        prev = cur
    return prev[-1]


CAST = """
    SOCRATES YOUNG_SOCRATES EUTHYPHRO MELETUS CRITO PHAEDO ECHECRATES
    CEBES SIMMIAS APOLLODORUS HERMOGENES CRATYLUS EUCLIDES TERPSION
    THEODORUS THEAETETUS VISITOR PARMENIDES ZENO PYTHODORUS ANTIPHON
    ARISTOTELES CEPHALUS ADEIMANTUS GLAUCON PROTARCHUS PHILEBUS
    ARISTODEMUS AGATHON PHAEDRUS PAUSANIAS ERYXIMACHUS ARISTOPHANES
    DIOTIMA ALCIBIADES FRIEND COMPANION BOY SLAVE DEMODOCUS THEAGES
    CHAEREPHON CRITIAS CHARMIDES LYSIMACHUS MELESIAS NICIAS LACHES
    HIPPOTHALES CTESIPPUS MENEXENUS LYSIS EUTHYDEMUS DIONYSODORUS
    CLINIAS HIPPOCRATES PROTAGORAS CALLIAS PRODICUS HIPPIAS CALLICLES
    GORGIAS POLUS MENO ANYTUS EUDICUS ION CLITOPHON POLEMARCHUS
    THRASYMACHUS CLEITOPHON TIMAEUS HERMOCRATES ATHENIAN MEGILLUS
    SISYPHUS ERASISTRATUS ERYXIAS AXIOCHUS INTERPRETER
"""


def squash(name):
    """Fold a label to a comparison key: letters only, no doubles.

    Doubled letters are the scan's commonest artefact (THEAETETUUS,
    PROTARCHUUS, SOCRATEES), and collapsing them on both sides removes that
    whole class of error before any distance is measured.
    """
    key = re.sub(r"[^A-Z]", "", name.upper())
    return re.sub(r"(.)+", r"", key)


def name_map(raw, cap_long=2, cap_short=1):
    """Map OCR-mangled speaker labels onto Plato's actual cast.

    An earlier version took the book's own most frequent spellings as
    canonical. That fails, and the census shows exactly why: the scan's
    errors are SYSTEMATIC, so a misreading repeats until it outvotes the
    truth. VISTROR stood 162 times beside VISITOR, POTUS 107 times against
    the correct POLUS twice, and CRITIAS - a real and separate character -
    was being folded into CUINIAS. Frequency cannot arbitrate when the
    noise is repeatable.

    The cast of the dialogues is instead a small, fixed, historical list,
    so that is the authority. Three properties make this safe:

      * only the label at the head of a paragraph is ever touched, never a
        word of the prose;
      * a name that already matches the cast is never remapped, so CRITIAS
        stays CRITIAS; and
      * a variant is only accepted when ONE cast name is closest - a tie is
        left alone rather than guessed, which is what keeps neighbours like
        CLINIAS and CRITIAS apart.
    """
    cast = {}
    for name in CAST.split():
        cast[squash(name)] = name.replace("_", " ")

    mapping, unmatched = {}, collections.Counter()
    for label, n in raw.items():
        key = re.sub(r"[^A-Z]", "", label.upper())
        if not key:
            continue
        sq = squash(label)
        if sq in cast:
            mapping[key] = cast[sq]          # already correct (or just case)
            continue
        cap = cap_long if len(sq) >= 6 else cap_short
        best, dist, tie = None, 99, False
        for csq, name in cast.items():
            d = levenshtein(sq, csq, cap)
            if d < dist:
                best, dist, tie = name, d, False
            elif d == dist:
                tie = True
        if best and dist <= cap and not tie:
            mapping[key] = best
        else:
            unmatched[label] += n
    return cast, mapping, unmatched


def fix_speaker(text, mapping):
    """Rewrite only the speaker label that opens this paragraph."""
    m = SPEAKER.match(text)
    if not m:
        return text
    name = mapping.get(re.sub(r"[^A-Z]", "", m.group(1).upper()))
    return (name + text[m.end(1):]) if name else text


def esc(s):
    return (s.replace("&", "&amp;").replace("<", "&lt;")
             .replace(">", "&gt;").replace('"', "&quot;"))


PAGE = ("<?xml version='1.0' encoding='utf-8'?>\n"
        "<html xmlns='http://www.w3.org/1999/xhtml'><head><title>{t}</title>"
        "<link rel='stylesheet' type='text/css' href='style.css'/>"
        "</head><body>\n{b}\n</body></html>")

CSS = ("p{margin:0 0 .35em;text-indent:1.3em}\n"
       "p.speak{text-indent:0;margin-top:.6em}\n"
       "p.note{text-indent:0;opacity:.5;font-size:.8em;margin-top:.8em}\n")


def convert(pdf, first=0, last=None):
    """Yield (index, paragraphs, notes, running stats) per page."""
    doc = fitz.open(pdf)
    last = doc.page_count if last is None else min(last, doc.page_count)
    stats = collections.Counter()
    for i in range(first, last):
        lines = page_lines(doc[i])
        paras, notes = split_page(lines)
        stats["lines"] += len(lines)
        stats["paras"] += len(paras)
        stats["notes"] += len(notes)
        stats["pages"] += 1
        yield i, paras, notes, stats


def report(pdf, first, last):
    counts, stats = [], collections.Counter()
    for _i, paras, _n, stats in convert(pdf, first, last):
        counts.append(len(paras))
    pages = max(1, stats["pages"])
    print("pages          : %d" % pages)
    print("lines          : %d" % stats["lines"])
    print("marginal notes : %d" % stats["notes"])
    print("PARAGRAPHS     : %d  (%.1f per page, median %d)"
          % (stats["paras"], stats["paras"] / pages,
             statistics.median(counts) if counts else 0))
    hist = collections.Counter(min(c, 25) for c in counts)
    print("per-page spread: "
          + " ".join("%d:%d" % kv for kv in sorted(hist.items())))


def sample(pdf, n):
    """Show how one page split - openings only, not the whole text."""
    for _i, paras, notes, _s in convert(pdf, n, n + 1):
        print("%d paragraphs, %d notes\n" % (len(paras), len(notes)))
        for p in paras:
            kind = "SPEAK" if SPEAKER.match(p) else "para "
            print("  [%s %4d] %s..." % (kind, len(p), p[:58]))
        if notes:
            print("\n  notes: %s" % " ".join(notes))


def page_context(paras, mapping, cast_names, cap=3):
    """Resolve labels this page's own cast can identify.

    2.3% of labels survive the global pass too mangled to match anything
    within a tight edit distance - Cuntas, Mecittus, Hirrtas. A dialogue
    page only ever has two or three speakers on it, though, so the local
    candidate set is tiny and a wider distance is safe there in a way it
    would never be against the whole cast. Still requires a unique winner.
    """
    here = set()
    for p in paras:
        m = SPEAKER.match(p)
        if m:
            name = mapping.get(re.sub(r"[^A-Z]", "", m.group(1).upper()))
            if name:
                here.add(name)
    if not here:
        return {}
    local = {}
    for p in paras:
        m = SPEAKER.match(p)
        if not m:
            continue
        key = re.sub(r"[^A-Z]", "", m.group(1).upper())
        if key in mapping or key in local:
            continue
        sq = squash(m.group(1))
        best, dist, tie = None, 99, False
        for name in here:
            d = levenshtein(sq, squash(name), cap)
            if d < dist:
                best, dist, tie = name, d, False
            elif d == dist:
                tie = True
        if best and dist <= cap and not tie:
            local[key] = best
    return local


def build(pdf, out, title, author):
    print("reading %s ..." % os.path.basename(pdf), flush=True)
    pages, stats = [], collections.Counter()
    for i, paras, notes, stats in convert(pdf):
        pages.append((paras, notes))
        if (i + 1) % 250 == 0:
            print("  ...%d pages" % (i + 1), flush=True)

    raw = collections.Counter()
    for paras, _n in pages:
        for p in paras:
            m = SPEAKER.match(p)
            if m:
                raw[m.group(1)] += 1
    cast, mapping, unmatched = name_map(raw)
    names = set(mapping.values())
    print("speaker labels   : %d distinct, %d unresolved by the cast list"
          % (len(raw), len(unmatched)))

    local_hits = 0
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip",
                   compress_type=zipfile.ZIP_STORED)
        # DOUBLE quotes throughout the two files the device parses: its
        # epubSpine() matches full-path="..." and attr() matches
        # key="([^"]*)" with regexes, so single-quoted attributes - valid
        # XML though they are - leave it with an empty spine and no pages.
        z.writestr("META-INF/container.xml",
                   '<?xml version="1.0"?><container version="1.0" '
                   'xmlns="urn:oasis:names:tc:opendocument:xmlns:container">'
                   '<rootfiles><rootfile full-path="EPUB/book.opf" '
                   'media-type="application/oebps-package+xml"/></rootfiles>'
                   '</container>')
        z.writestr("EPUB/style.css", CSS)

        items, refs = [], []
        for i, (paras, notes) in enumerate(pages):
            local = page_context(paras, mapping, names)
            local_hits += len(local)
            full = dict(mapping)
            full.update(local)
            chunks = []
            for p in paras:
                fixed = fix_speaker(p, full)
                chunks.append("<p%s>%s</p>"
                              % (" class='speak'" if SPEAKER.match(fixed)
                                 else "", esc(fixed)))
            if notes:
                chunks.append("<p class='note'>%s</p>" % esc(" ".join(notes)))
            name = "p%05d.xhtml" % (i + 1)
            z.writestr("EPUB/" + name,
                       PAGE.format(t=esc("%s %d" % (title, i + 1)),
                                   b="\n".join(chunks) or "<p></p>"))
            items.append('<item id="p%d" href="%s" '
                         'media-type="application/xhtml+xml"/>' % (i + 1, name))
            refs.append('<itemref idref="p%d"/>' % (i + 1))

        z.writestr("EPUB/book.opf",
                   '<?xml version="1.0" encoding="utf-8"?>\n'
                   '<package xmlns="http://www.idpf.org/2007/opf" version="3.0"'
                   ' unique-identifier="id"><metadata '
                   'xmlns:dc="http://purl.org/dc/elements/1.1/">'
                   '<dc:identifier id="id">pdf2epub</dc:identifier>'
                   '<dc:title>%s</dc:title><dc:language>en</dc:language>'
                   '<dc:creator>%s</dc:creator></metadata><manifest>'
                   '<item id="css" href="style.css" media-type="text/css"/>'
                   '%s</manifest><spine>%s</spine></package>'
                   % (esc(title), esc(author), "".join(items), "".join(refs)))

    print("page-context fixes: %d further labels" % local_hits)
    print("pages %d   paragraphs %d   (%.1f per page)"
          % (stats["pages"], stats["paras"],
             stats["paras"] / max(1, stats["pages"])))
    print("wrote %s  (%.1f MB)" % (out, os.path.getsize(out) / 1048576.0))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pdf")
    ap.add_argument("out", nargs="?")
    ap.add_argument("--title")
    ap.add_argument("--author", default="")
    ap.add_argument("--report", action="store_true")
    ap.add_argument("--sample", type=int)
    ap.add_argument("--first", type=int, default=0)
    ap.add_argument("--last", type=int)
    a = ap.parse_args()

    if a.sample is not None:
        return sample(a.pdf, a.sample)
    if a.report:
        return report(a.pdf, a.first, a.last)
    if not a.out:
        ap.error("an output path is required unless --report or --sample")
    build(a.pdf, a.out, a.title or
          os.path.splitext(os.path.basename(a.pdf))[0], a.author)


if __name__ == "__main__":
    main()
