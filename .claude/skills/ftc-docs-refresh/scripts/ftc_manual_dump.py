#!/usr/bin/env python3
"""Save the current FTC Competition Manual as one Markdown file per section.

FIRST publishes the manual as a PDF and as an HTML page exported from Word.
This script reads the hub page for the current version, downloads the HTML
page and its figures, and writes clean Markdown that is easy to search:

    <out>/README.md          version, section index, how to read the files
    <out>/01-introduction.md ... one file per manual section
    <out>/images/            the manual's figures

Usage:
    python3 ftc_manual_dump.py -o doc/ftc-rules/manual
    python3 ftc_manual_dump.py -o doc/ftc-rules/manual --pdf-dir doc/ftc-rules
    python3 ftc_manual_dump.py -o doc/ftc-rules/manual --force

When the copy in <out> already has the hub's version, nothing is downloaded
unless --force is given. The output folder is replaced as a whole, so the
script refuses to touch a folder holding files it did not write.

With -o, a one-line summary is printed to stdout. Progress and errors go to stderr.

Exit codes:
    0  success (or the copy was already current)
    2  bad arguments, wrong Python version, or the output could not be written
    3  network or HTTP failure
    4  a page loaded but its layout changed, so the manual could not be read

Python 3.13+, standard library only.
"""
from __future__ import annotations

import sys

if sys.version_info < (3, 13):
    sys.stderr.write("error: ftc_manual_dump.py needs Python 3.13 or later (this is %d.%d)\n"
                     % sys.version_info[:2])
    sys.exit(2)

import argparse
import codecs
import datetime as dt
import html
import os
import re
import shutil
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from html.parser import HTMLParser

EXIT_OK = 0
EXIT_USAGE = 2
EXIT_NETWORK = 3
EXIT_FORMAT = 4

# The hub always shows the current season, so nothing changes when a new season starts.
DEFAULT_HUB = "https://ftc-resources.firstinspires.org/ftc/game"
USER_AGENT = "ftc-manual-dump/1.0 (FTC team tool; runs after each Team Update)"
VERSION_RE = re.compile(r"^- \*\*Manual version:\*\* (\S+)", re.M)
SECTION_FILE_RE = re.compile(r"^\d{2}-[a-z0-9-]+\.md$")
RULE_START_RE = re.compile(r"^\*\*([A-Z]\d{3}[a-z]?)\*\*", re.M)
RULE_NUMBER_RE = re.compile(r"[A-Z]\d{3}[a-z]?")


def log(message: str) -> None:
    print(message, file=sys.stderr)


# --------------------------------------------------------------------------
# Fetch
# --------------------------------------------------------------------------

class FetchError(Exception):
    pass


class FormatError(Exception):
    pass


def fetch(url: str, timeout: float, retries: int) -> tuple[str, bytes]:
    """Return (final_url, body). Retries on transient failures."""
    last = "unknown error"
    for attempt in range(retries + 1):
        if attempt:
            time.sleep(2 * attempt)
        request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return response.geturl(), response.read()
        except urllib.error.HTTPError as error:
            last = "HTTP %s from %s" % (error.code, url)
            if error.code < 500:
                break
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            last = "%s (%s)" % (getattr(error, "reason", error), url)
    raise FetchError(last)


def decode_page(body: bytes, default: str = "utf-8") -> str:
    """Decode with the charset the page declares. Word exports declare windows-1252."""
    match = re.search(rb"charset=[\"']?([A-Za-z0-9_.-]+)", body[:4096])
    encoding = default
    if match:
        try:
            encoding = codecs.lookup(match.group(1).decode("ascii")).name
        except LookupError:
            pass
    return body.decode(encoding, "replace")


# --------------------------------------------------------------------------
# A small HTML tree (the standard library has a parser but no tree)
# --------------------------------------------------------------------------

VOID_TAGS = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta",
             "param", "source", "track", "wbr"}
DROP_TAGS = {"head", "style", "script", "title", "meta", "link", "o:p"}


class Node:
    __slots__ = ("tag", "attrs", "children", "parent")

    def __init__(self, tag: str, attrs: dict | None = None) -> None:
        self.tag = tag
        self.attrs = attrs or {}
        self.children: list = []
        self.parent: Node | None = None

    def append(self, child) -> None:
        if isinstance(child, Node):
            child.parent = self
        self.children.append(child)

    def iter(self, *tags: str):
        """Every descendant element, in document order, optionally filtered by tag."""
        for child in list(self.children):
            if isinstance(child, Node):
                if not tags or child.tag in tags:
                    yield child
                yield from child.iter(*tags)

    def find(self, *tags: str) -> Node | None:
        return next(self.iter(*tags), None)

    def text(self) -> str:
        return "".join(c if isinstance(c, str) else c.text() for c in self.children)

    def style(self) -> str:
        return (self.attrs.get("style") or "").lower()

    def replace_with(self, *items) -> None:
        siblings = self.parent.children
        index = next(i for i, c in enumerate(siblings) if c is self)
        for item in items:
            if isinstance(item, Node):
                item.parent = self.parent
        siblings[index:index + 1] = list(items)

    def unwrap(self) -> None:
        self.replace_with(*self.children)

    def remove(self) -> None:
        self.replace_with()

    def wrap(self, tag: str) -> Node:
        wrapper = Node(tag)
        self.replace_with(wrapper)
        wrapper.append(self)
        return wrapper


class TreeBuilder(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.root = Node("#root")
        self.stack = [self.root]

    def handle_starttag(self, tag, attrs):
        node = Node(tag, {k: (v or "") for k, v in attrs})
        self.stack[-1].append(node)
        if tag not in VOID_TAGS:
            self.stack.append(node)

    def handle_startendtag(self, tag, attrs):
        self.stack[-1].append(Node(tag, {k: (v or "") for k, v in attrs}))

    def handle_endtag(self, tag):
        for depth in range(len(self.stack) - 1, 0, -1):
            if self.stack[depth].tag == tag:
                del self.stack[depth:]
                return

    def handle_data(self, data):
        self.stack[-1].append(data)


def parse_html(page: str) -> Node:
    builder = TreeBuilder()
    builder.feed(page)
    builder.close()
    return builder.root


def block_text(node: Node) -> list[str]:
    """Plain text, one entry per line, with breaks at block elements."""
    blocks = {"p", "div", "li", "tr", "td", "th", "h1", "h2", "h3", "h4", "h5", "h6", "br",
              "table", "ul", "ol", "section", "header", "footer", "nav", "main", "article"}
    parts: list[str] = []

    def walk(node):
        for child in node.children:
            if isinstance(child, str):
                parts.append(child)
            elif child.tag not in DROP_TAGS:
                if child.tag in blocks:
                    parts.append("\n")
                walk(child)
                if child.tag in blocks:
                    parts.append("\n")

    walk(node)
    lines = (re.sub(r"\s+", " ", line).strip() for line in "".join(parts).split("\n"))
    return [line for line in lines if line]


# --------------------------------------------------------------------------
# Hub page: versions
# --------------------------------------------------------------------------

HUB_VERSION_RE = re.compile(r"^\s*-\s*Version (\S+) \(updated ([^)]+)\)")


def parse_hub(page: str) -> dict:
    """Each document is a link followed by text like "- Version TU09 (updated Nov 12, 2026)"."""
    root = parse_html(page)
    info: dict = {"sections": {}}
    for line in block_text(root):
        if m := re.search(r"(\d{4}-\d{4}) Game & Season Materials", line):
            info["season"] = m.group(1)
    for a in root.iter("a"):
        siblings = a.parent.children
        index = next(i for i, c in enumerate(siblings) if c is a)
        after = siblings[index + 1] if index + 1 < len(siblings) else ""
        m = HUB_VERSION_RE.match(after) if isinstance(after, str) else None
        if not m:
            continue
        path = urllib.parse.urlparse(a.attrs.get("href", "")).path.rstrip("/")
        label = re.sub(r"\s+", " ", a.text().replace("™", "")).strip()  # drop the ™ sign
        if path.endswith("/cm-html"):
            info["version"], info["date"] = m.group(1), m.group(2)
        elif path.endswith("/manual"):
            info["pdf_version"] = m.group(1)
            info["game"] = re.sub(r"\s*Competition Manual.*$", "", label) or None
        elif s := re.search(r"/manual-(\d+)$", path):
            info["sections"][int(s.group(1))] = "%s (%s)" % (m.group(1), m.group(2))
    if "version" not in info:
        raise FormatError("the hub page does not list a Competition Manual HTML version; "
                          "its layout has probably changed")
    return info


# --------------------------------------------------------------------------
# Clean the Word export
# --------------------------------------------------------------------------

BOX_COLORS = ("#ed7d31", "#f4b083", "#4472c4")  # orange note boxes, blue section intros
KEEP_ATTRS = {"img": {"src", "alt"}, "a": {"href"}, "td": {"rowspan", "colspan"},
              "th": {"rowspan", "colspan"}}


def merge_runs(node: Node) -> None:
    """Word splits bold and italic text into many runs; join neighbours like **a****b**."""
    for child in node.children:
        if isinstance(child, Node):
            merge_runs(child)
    merged: list = []
    for child in node.children:
        previous = merged[-1] if merged else None
        if (isinstance(child, Node) and isinstance(previous, Node)
                and child.tag == previous.tag and child.tag in ("strong", "em", "del")
                and not RULE_NUMBER_RE.fullmatch(previous.text().strip())):  # keep **R101** apart
            for grandchild in child.children:
                previous.append(grandchild)
        else:
            merged.append(child)
    node.children = merged


def list_marker(kind: str, n: int) -> str:
    if kind == "A":
        return chr(ord("A") + n - 1)
    if kind == "a":
        return chr(ord("a") + n - 1)
    if kind in ("i", "I"):
        numerals = ["", "i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x",
                    "xi", "xii", "xiii", "xiv", "xv"]
        roman = numerals[n] if n < len(numerals) else str(n)
        return roman.upper() if kind == "I" else roman
    return str(n)


FIGURE_NAME_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")


def figure_name(src: str) -> str:
    """Local file name for a figure. The src comes from a downloaded page, so it is
    decoded before splitting (%2F cannot sneak in a folder) and must be a plain name."""
    path = urllib.parse.unquote(urllib.parse.urlparse(src).path).replace("\\", "/")
    if ".." in path.split("/"):
        raise FormatError("unexpected figure path %r in the manual page" % src)
    name = path.rsplit("/", 1)[-1]
    if not name:
        return ""
    if not FIGURE_NAME_RE.fullmatch(name) or ".." in name:
        raise FormatError("unexpected figure name %r in the manual page" % name)
    return name


def safe_join(folder: str, name: str) -> str:
    """Join, then make sure the result is still inside folder."""
    root = os.path.realpath(folder)
    path = os.path.realpath(os.path.join(root, name))
    if os.path.dirname(path) != root:
        raise FormatError("refusing to write %r outside %s" % (name, folder))
    return path


def clean(root: Node) -> tuple[Node, dict[str, str]]:
    """Strip Word's formatting. Returns (body, {image name: original src})."""
    body = root.find("body") or root

    for node in list(body.iter()):
        if node.tag in DROP_TAGS or re.search(r"display:\s*none", node.style()):
            node.remove()

    # List-marker spacers: <span style="font:7.0pt 'Times New Roman'">&nbsp;&nbsp;</span>
    for node in list(body.iter("span")):
        if re.search(r"font:\s*7\.0pt", node.style()):
            node.replace_with(" ")

    for node in list(body.iter("div")):
        style = node.style()
        if "border" in style and any(color in style for color in BOX_COLORS):
            node.tag = "blockquote"
        else:
            node.unwrap()

    images: dict[str, str] = {}
    for node in body.iter("img"):
        src = node.attrs.get("src", "")
        name = figure_name(src)
        if not name:
            continue
        if name in images and images[name] != src:
            raise FormatError("two different figures are both named %s" % name)
        images[name] = src
        node.attrs = {"src": "images/" + name, "alt": node.attrs.get("alt", "")}

    for node in list(body.iter("a")):
        href = node.attrs.get("href", "")
        if not href or href.startswith("#"):
            node.unwrap()

    # Rule headlines are bold in the manual.
    for node in list(body.iter("span")):
        classes = node.attrs.get("class", "")
        if re.match(r"Headline-", classes) and node.text().strip():
            node.wrap("strong")

    for node in list(body.iter("b", "i", "s", "strike", "del")):
        node.tag = {"b": "strong", "i": "em"}.get(node.tag, "del")
    for node in list(body.iter("span")):
        if "line-through" in node.style():
            node.wrap("del")

    # The few real <ol> lists use letters. Keep the manual's markers as text, since rule
    # citations refer to them (R102.A) and Markdown ordered lists can only number.
    for ol in list(body.iter("ol")):
        kind = ol.attrs.get("type") or "1"
        start = int(ol.attrs.get("start") or 1)
        items = [c for c in ol.children if isinstance(c, Node) and c.tag == "li"]
        for k, li in enumerate(items):
            li.tag = "p"
            li.attrs = {}
            li.children.insert(0, list_marker(kind, start + k) + ". ")
        ol.unwrap()

    for node in list(body.iter("span", "font", "u")):
        node.unwrap()

    for node in body.iter():
        allowed = KEEP_ATTRS.get(node.tag, set())
        node.attrs = {k: v for k, v in node.attrs.items() if k in allowed}

    # Table cells: join paragraphs so simple tables can become Markdown tables.
    for cell in body.iter("td", "th"):
        if cell.find("ul", "table", "img"):
            continue
        paragraphs = [c for c in cell.children if isinstance(c, Node) and c.tag == "p"]
        for k, p in enumerate(paragraphs):
            if k:
                p.children.insert(0, " ")
            p.unwrap()

    # A first row of all-bold cells is the header row.
    for table in body.iter("table"):
        if table.find("thead", "th"):
            continue
        row = table.find("tr")
        cells = [c for c in row.children if isinstance(c, Node) and c.tag in ("td", "th")] if row else []
        if cells and all(is_all_bold(c) for c in cells):
            for c in cells:
                c.tag = "th"
                for b in list(c.iter("strong")):
                    b.unwrap()

    for p in list(body.iter("p")):
        if not p.text().strip() and not p.find("img"):
            p.remove()

    merge_runs(body)
    return body, images


def is_all_bold(cell: Node) -> bool:
    text = cell.text().strip()
    bold = "".join(b.text() for b in cell.iter("strong")).strip()
    return bool(text) and re.sub(r"\s+", "", text) == re.sub(r"\s+", "", bold)


# --------------------------------------------------------------------------
# Markdown
# --------------------------------------------------------------------------

def escape(text: str) -> str:
    text = text.replace("\\", "\\\\")
    for char in "*`":
        text = text.replace(char, "\\" + char)
    # Underscores inside words (part numbers like am-5850_Full) never start emphasis.
    text = re.sub(r"(?<![A-Za-z0-9])_|_(?![A-Za-z0-9])", r"\\_", text)
    return re.sub(r"<(?=[A-Za-z/!?])", r"\\<", text)


def emphasis(marker: str, inner: str) -> str:
    if not inner.strip():
        return inner
    lead = inner[:len(inner) - len(inner.lstrip())]
    trail = inner[len(inner.rstrip()):]
    return lead + marker + inner.strip() + marker + trail


def inline(node: Node) -> str:
    parts = []
    for child in node.children:
        if isinstance(child, str):
            parts.append(escape(re.sub(r"\s+", " ", child.replace(" ", " "))))
        elif child.tag == "strong":
            parts.append(emphasis("**", inline(child)))
        elif child.tag == "em":
            parts.append(emphasis("*", inline(child)))
        elif child.tag == "del":
            parts.append(emphasis("~~", inline(child)))
        elif child.tag == "br":
            parts.append("\\\n")
        elif child.tag == "img":
            alt = child.attrs.get("alt", "").replace("[", "").replace("]", "")
            parts.append("![%s](%s)" % (re.sub(r"\s+", " ", alt).strip(), child.attrs["src"]))
        elif child.tag == "a":
            href = child.attrs["href"]
            label = inline(child).strip()
            plain = href[7:] if href.startswith("mailto:") else href
            parts.append("<%s>" % plain if label == escape(plain) else "[%s](%s)" % (label, href))
        elif child.tag in ("sup", "sub"):
            parts.append("<%s>%s</%s>" % (child.tag, inline(child), child.tag))
        else:
            parts.append(inline(child))
    return re.sub(r" {2,}", " ", "".join(parts))


def paragraph(text: str) -> str:
    # Line breaks: no space after one, and none at the start or end of a paragraph.
    text = re.sub(r"\\\n +", "\\\n", text)
    text = re.sub(r"^(?:\s|\\\n)+|(?:\s|\\\n)+$", "", text)
    if re.match(r"[#>+-] |#+$|[-*_]{3,}$", text):
        text = "\\" + text
    return text


INLINE_TAGS = {"strong", "em", "del", "a", "img", "br", "sup", "sub"}


def blocks(node: Node) -> list[str]:
    out: list[str] = []
    pending = Node("#inline")

    def flush():
        nonlocal pending
        text = paragraph(inline(pending))
        if text.strip():
            out.append(text)
        pending = Node("#inline")

    for child in node.children:
        if isinstance(child, str) or child.tag in INLINE_TAGS:
            pending.append(child)
            continue
        flush()
        if m := re.fullmatch(r"h([1-6])", child.tag):
            out.append("#" * int(m.group(1)) + " " + inline(child).strip())
        elif child.tag == "p":
            text = paragraph(inline(child))
            if text.strip():
                out.append(text)
        elif child.tag == "blockquote":
            inner = "\n\n".join(blocks(child))
            out.append("\n".join(("> " + line) if line else ">" for line in inner.split("\n")))
        elif child.tag == "table":
            out.append(table(child))
        elif child.tag == "ul":
            items = [c for c in child.children if isinstance(c, Node) and c.tag == "li"]
            out.append("\n".join("- " + inline(li).strip() for li in items))
        else:
            out.extend(blocks(child))
    flush()
    return out


def rows_of(table_node: Node) -> list[list[Node]]:
    rows = []
    for tr in table_node.iter("tr"):
        if tr.parent is table_node or tr.parent.tag in ("thead", "tbody", "tfoot"):
            rows.append([c for c in tr.children if isinstance(c, Node) and c.tag in ("td", "th")])
    return rows


def table(node: Node) -> str:
    rows = rows_of(node)
    if not rows:
        return ""
    simple = not any(
        cell.attrs.get("rowspan", "1") != "1" or cell.attrs.get("colspan", "1") != "1"
        or cell.find("p", "table", "img", "ul", "blockquote", "h1", "h2", "h3", "h4")
        for row in rows for cell in row)
    if simple:
        return pipe_table(rows)
    return html_table(node)


def pipe_table(rows: list[list[Node]]) -> str:
    width = max(len(r) for r in rows)

    def cell(c: Node) -> str:
        text = inline(c).replace("\\\n", " ").replace("\n", " ").strip()
        return text.replace("|", "\\|")

    def line(cells: list[str]) -> str:
        cells = cells + [""] * (width - len(cells))
        return "| " + " | ".join(cells) + " |"

    if all(c.tag == "th" for c in rows[0]):
        header, body = [cell(c) for c in rows[0]], rows[1:]
    else:
        header, body = [""] * width, rows
    lines = [line(header), "|" + "|".join(["---"] * width) + "|"]
    lines += [line([cell(c) for c in r]) for r in body]
    return "\n".join(lines)


def html_inline(node: Node) -> str:
    parts = []
    for child in node.children:
        if isinstance(child, str):
            parts.append(html.escape(re.sub(r"\s+", " ", child.replace(" ", " ")), quote=False))
        elif child.tag in ("br", "img"):
            if child.tag == "br":
                parts.append("<br>")
            else:
                parts.append('<img src="%s" alt="%s">' % (
                    html.escape(child.attrs["src"]), html.escape(child.attrs.get("alt", ""))))
        elif child.tag == "table":
            parts.append(html_table(child))
        elif child.tag in ("strong", "em", "del", "sup", "sub"):
            # Keep spaces outside the tag so "<strong>AndyMark</strong> NeveRest" keeps its space.
            inner = html_inline(child)
            if inner.strip():
                lead = inner[:len(inner) - len(inner.lstrip())]
                trail = inner[len(inner.rstrip()):]
                parts.append("%s<%s>%s</%s>%s" % (lead, child.tag, inner.strip(), child.tag, trail))
            else:
                parts.append(inner)
        elif child.tag in ("p", "ul", "li", "blockquote"):
            parts.append("<%s>%s</%s>" % (child.tag, html_inline(child).strip(), child.tag))
        elif child.tag == "a":
            parts.append('<a href="%s">%s</a>' % (html.escape(child.attrs["href"]), html_inline(child)))
        else:
            parts.append(html_inline(child))
    return re.sub(r" {2,}", " ", "".join(parts))


def html_table(node: Node) -> str:
    lines = ["<table>"]
    for row in rows_of(node):
        lines.append("<tr>")
        for c in row:
            spans = "".join(' %s="%s"' % (k, c.attrs[k]) for k in ("rowspan", "colspan")
                            if c.attrs.get(k, "1") != "1")
            lines.append("<%s%s>%s</%s>" % (c.tag, spans, html_inline(c).strip(), c.tag))
        lines.append("</tr>")
    lines.append("</table>")
    return "\n".join(lines)


def tidy(markdown: str) -> str:
    markdown = re.sub(r"[ \t]+\n", "\n", markdown)
    markdown = re.sub(r"\n{3,}", "\n\n", markdown)
    return markdown.strip() + "\n"


def slug(title: str) -> str:
    title = re.sub(r"^\d+\s*", "", title)
    title = re.sub(r"\([A-Z]\)", "", title)
    return re.sub(r"[^A-Za-z0-9]+", "-", title).strip("-").lower()


def split_sections(body: Node) -> list[dict]:
    """One section per <h1>. Anything before the first <h1> (cover, contents) is dropped."""
    total = sum(1 for _ in body.iter("h1"))
    sections: list[dict] = []
    for child in body.children:
        if isinstance(child, Node) and child.tag == "h1":
            container = Node("#section")
            sections.append({"heading": child, "body": container})
        if sections:
            sections[-1]["body"].children.append(child)
    if not sections or len(sections) != total:
        raise FormatError("could not find the manual's section headings; "
                          "the HTML layout has probably changed")
    for section in sections:
        plain = re.sub(r"\s+", " ", section["heading"].text().replace(" ", " ")).strip()
        m = re.match(r"(\d+)\s+(.*)", plain)
        if not m:
            raise FormatError("section heading without a number: %r" % plain)
        section["number"] = int(m.group(1))
        section["title"] = re.sub(r"^\d+\s+", "", inline(section["heading"]).strip())
        section["file"] = "%02d-%s.md" % (section["number"], slug(m.group(2)))
    return sections


def render_section(section: dict, info: dict) -> str:
    header = ("<!-- Generated by ftc_manual_dump.py from the official %s Competition Manual HTML, "
              "version %s (%s). Do not edit by hand. The PDF outranks this copy. -->\n\n"
              % (info.get("game") or "FTC", info["version"], info["date"]))
    return header + tidy("\n\n".join(blocks(section["body"])))


def render_readme(info: dict, sections: list[dict], rules: int, images: int,
                  downloaded: str, hub_url: str) -> str:
    game = info.get("game") or "FTC"
    rows = "\n".join(
        "| %d | [%s](%s) | %s | %s |" % (s["number"], s["file"], s["file"], s["title"],
                                         info["sections"].get(s["number"], "?"))
        for s in sections)
    return """# {game} Competition Manual (Markdown copy)

- **Manual version:** {version} (updated {date})
- **Season:** {season}
- **Downloaded:** {downloaded} from {hub} (HTML version)
- **Contents:** {count} sections, {rules} rules, {images} figures

This is a convenience copy for searching and reading offline. The official PDF is the source of truth and outranks this copy (see Section 1). Every Thursday Team Update can change the manual, so compare the version above with the hub before relying on this copy, and use the live manual for anything that changed since.

The PDF is not kept in git. Download it from {hub}/manual, or run the refresh with `--pdf-dir doc/ftc-rules`.

Do not edit these files by hand. Run the `ftc-docs-refresh` skill (or `ftc_manual_dump.py`) again instead; `git diff` then shows exactly what a Team Update changed.

## Sections

| # | File | Section | Section version |
|---|---|---|---|
{rows}

## How to read these files

- Each rule starts a paragraph with its number in bold, then its headline in bold, for example `**R101** **\\*It is your team's ROBOT.**`. A leading `*` on a headline marks an evergreen rule. Grep for a rule number such as `R102` to find it.
- Lettered and numbered items keep the manual's own markers (`A.`, `i.`, `1.`), so a citation like R102.A can be matched.
- Quoted blocks (`>`) are the manual's shaded note boxes and section intro boxes. They explain intent but carry no rule weight.
- Tables with merged cells are kept as HTML tables. Other tables are Markdown tables.
- Figures are in `images/`, with their captions as text above them.
- The cover page and table of contents are left out, and internal cross-reference links are plain text.
""".format(game=game, version=info["version"], date=info["date"],
           season=info.get("season") or "?", downloaded=downloaded, hub=hub_url,
           count=len(sections), rules=rules, images=images, rows=rows)


# --------------------------------------------------------------------------
# Output folder
# --------------------------------------------------------------------------

def existing_version(out_dir: str) -> str | None:
    try:
        with open(os.path.join(out_dir, "README.md"), encoding="utf-8") as handle:
            m = VERSION_RE.search(handle.read())
    except OSError:
        return None
    return m.group(1) if m else None


def foreign_files(out_dir: str) -> list[str]:
    """Entries in out_dir that this script would not have written."""
    if not os.path.isdir(out_dir):
        return []
    strange = []
    for name in os.listdir(out_dir):
        if name in ("README.md", ".DS_Store") or SECTION_FILE_RE.match(name):
            continue
        if name == "images" and os.path.isdir(os.path.join(out_dir, name)):
            continue
        strange.append(name)
    return sorted(strange)


def changed_files(old_dir: str, new_dir: str) -> list[str]:
    def md(folder):
        if not os.path.isdir(folder):
            return {}
        result = {}
        for name in os.listdir(folder):
            if SECTION_FILE_RE.match(name):
                with open(os.path.join(folder, name), encoding="utf-8") as handle:
                    # The first line carries the version, which changes every time.
                    result[name] = handle.read().split("\n", 1)[-1]
        return result

    old, new = md(old_dir), md(new_dir)
    return sorted(n for n in set(old) | set(new) if old.get(n) != new.get(n))


def replace_dir(new_dir: str, out_dir: str) -> None:
    backup = None
    if os.path.exists(out_dir):
        backup = tempfile.mkdtemp(dir=os.path.dirname(os.path.abspath(out_dir)), prefix=".old-manual-")
        os.rmdir(backup)
        os.replace(out_dir, backup)
    try:
        os.replace(new_dir, out_dir)
    except OSError:
        if backup:
            os.replace(backup, out_dir)
        raise
    if backup:
        shutil.rmtree(backup, ignore_errors=True)


def save_pdf(hub_url: str, info: dict, pdf_dir: str, args, progress) -> str:
    version = re.sub(r"[^A-Za-z0-9.-]", "-", info.get("pdf_version") or info["version"])
    path = os.path.join(pdf_dir, "competition-manual-%s.pdf" % version)
    if not os.path.exists(path):  # a published version's PDF never changes
        progress("fetching %s/manual" % hub_url)
        _, body = fetch(hub_url + "/manual", args.timeout, args.retries)
        if not body.startswith(b"%PDF"):
            raise FormatError("the manual link did not return a PDF")
        os.makedirs(pdf_dir, exist_ok=True)
        handle, temp_path = tempfile.mkstemp(dir=pdf_dir, suffix=".tmp")
        with os.fdopen(handle, "wb") as out:
            out.write(body)
        os.replace(temp_path, path)
    for name in os.listdir(pdf_dir):
        older = os.path.join(pdf_dir, name)
        if re.fullmatch(r"competition-manual-.+\.pdf", name) and older != path:
            os.remove(older)
            progress("removed older %s" % older)
    return path


# --------------------------------------------------------------------------
# Command line
# --------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Save the current FTC Competition Manual as one Markdown file per section.",
        epilog="Exit codes: 0 ok, 2 bad arguments or unwritable output, 3 network failure, "
               "4 page layout changed.",
    )
    parser.add_argument("-o", "--out", metavar="DIR", required=True,
                        help="folder for the Markdown files (replaced as a whole)")
    parser.add_argument("--pdf-dir", metavar="DIR",
                        help="also save the official PDF here as competition-manual-<version>.pdf "
                             "and delete older ones")
    parser.add_argument("--force", action="store_true",
                        help="convert again even when the copy already has the current version")
    parser.add_argument("--hub", default=DEFAULT_HUB,
                        help="current season's game page (default: %(default)s)")
    parser.add_argument("--timeout", type=float, default=60.0, help="seconds (default: 60)")
    parser.add_argument("--retries", type=int, default=2,
                        help="extra attempts after a transient failure (default: 2)")
    parser.add_argument("-q", "--quiet", action="store_true", help="no progress on stderr")
    return parser


def use_utf8_output():
    """Windows consoles default to a legacy code page that cannot print every character."""
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass


def convert(page: str, info: dict) -> tuple[list[dict], dict[str, str], dict[str, str]]:
    """Return (sections, {file name: markdown}, {image name: original src})."""
    body, images = clean(parse_html(page))
    sections = split_sections(body)
    files = {s["file"]: render_section(s, info) for s in sections}
    used = {name: src for name, src in images.items()
            if any("images/" + name in text for text in files.values())}
    return sections, files, used


def main(argv=None) -> int:
    use_utf8_output()
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.timeout <= 0 or args.retries < 0:
        parser.error("--timeout must be positive and --retries cannot be negative")
    hub_url = args.hub.rstrip("/")
    out_dir = os.path.normpath(args.out)

    def progress(message: str) -> None:
        if not args.quiet:
            log(message)

    strange = foreign_files(out_dir)
    if strange:
        log("error: %s holds files this script did not write (%s); it replaces the whole "
            "folder, so choose another folder or move them" % (out_dir, ", ".join(strange)))
        return EXIT_USAGE

    try:
        progress("fetching %s" % hub_url)
        _, hub_body = fetch(hub_url, args.timeout, args.retries)
        info = parse_hub(decode_page(hub_body))

        pdf_note = ""
        if args.pdf_dir:
            pdf_note = "; pdf: %s" % save_pdf(hub_url, info, args.pdf_dir, args, progress)

        if not args.force and existing_version(out_dir) == info["version"]:
            print("up to date: %s already has manual version %s%s" % (out_dir, info["version"], pdf_note))
            return EXIT_OK

        progress("fetching %s/cm-html" % hub_url)
        page_url, page_body = fetch(hub_url + "/cm-html", args.timeout, args.retries)
        sections, files, images = convert(decode_page(page_body, "cp1252"), info)
        rules = sum(len(RULE_START_RE.findall(text)) for text in files.values())
        if not rules:
            raise FormatError("no rules found in the converted manual; the HTML layout has "
                              "probably changed")

        parent = os.path.dirname(os.path.abspath(out_dir))
        os.makedirs(parent, exist_ok=True)
        work = tempfile.mkdtemp(dir=parent, prefix=".new-manual-")
        try:
            os.makedirs(os.path.join(work, "images"))
            progress("fetching %d figures" % len(images))
            for name, src in sorted(images.items()):
                _, data = fetch(urllib.parse.urljoin(page_url, src), args.timeout, args.retries)
                with open(safe_join(os.path.join(work, "images"), name), "wb") as handle:
                    handle.write(data)
            for name, text in files.items():
                with open(os.path.join(work, name), "w", encoding="utf-8", newline="\n") as handle:
                    handle.write(text)
            downloaded = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%d")
            with open(os.path.join(work, "README.md"), "w", encoding="utf-8", newline="\n") as handle:
                handle.write(render_readme(info, sections, rules, len(images), downloaded, hub_url))
            changes = changed_files(out_dir, work)
            had_copy = os.path.isdir(out_dir)
            replace_dir(work, out_dir)
        except BaseException:
            shutil.rmtree(work, ignore_errors=True)
            raise
    except FetchError as error:
        log("error: could not reach the FTC resources site: %s" % error)
        return EXIT_NETWORK
    except FormatError as error:
        log("error: %s" % error)
        return EXIT_FORMAT
    except OSError as error:
        log("error: could not write %s: %s" % (out_dir, error))
        return EXIT_USAGE

    if not had_copy:
        change_note = "no earlier copy to compare with"
    elif changes:
        change_note = "changed: " + ", ".join(changes)
    else:
        change_note = "no section text changed"
    print("wrote %s: %s Competition Manual %s (%s), %d sections, %d rules, %d figures; %s%s" % (
        out_dir, info.get("game") or "FTC", info["version"], info["date"], len(sections), rules,
        len(images), change_note, pdf_note))
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
