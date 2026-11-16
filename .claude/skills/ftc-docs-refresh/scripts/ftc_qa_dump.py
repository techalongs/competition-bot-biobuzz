#!/usr/bin/env python3
"""Dump the official FIRST Tech Challenge Team Q&A board to a local file.

The Q&A site builds its question list in the browser, so tools that only read
page text see an empty frame. The questions and answers are embedded in the
page as JSON, though, so one plain HTTP request is enough. No browser needed.

Usage:
    python ftc_qa_dump.py -o doc/ftc-rules/ftc-qa.md
    python ftc_qa_dump.py --format json > qa.json
    python ftc_qa_dump.py -o doc/ftc-rules/ftc-qa.md --if-older-than 12

Output goes to stdout unless -o is given. Progress and errors go to stderr.
With -o, a one-line summary is printed to stdout.

Exit codes:
    0  success (or the existing file is fresh enough with --if-older-than)
    2  bad arguments, or the output file could not be written
    3  network or HTTP failure
    4  the page loaded but its layout changed and no Q&A data was found

Python 3.9+, standard library only.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import sys
import tempfile
import time
import urllib.error
import urllib.request
from html.parser import HTMLParser

EXIT_OK = 0
EXIT_USAGE = 2
EXIT_NETWORK = 3
EXIT_FORMAT = 4

# This address redirects to the current season's board, so the script keeps
# working when the season changes. https://ftc.game/qa does the same.
DEFAULT_URL = "https://ftc-qa.firstinspires.org/"
USER_AGENT = "ftc-qa-dump/1.0 (FTC team tool; one request per run)"
DATA_MARKER = "Object.assign(window,"
STAMP_RE = re.compile(r"<!-- qa:(\d+) modified:(\d+) answer:(\d+) -->")
FETCHED_RE = re.compile(r"^- Fetched: (\S+)", re.M)


def log(message: str) -> None:
    print(message, file=sys.stderr)


# --------------------------------------------------------------------------
# Fetch
# --------------------------------------------------------------------------

class FetchError(Exception):
    pass


def fetch(url: str, timeout: float, retries: int) -> tuple[str, str]:
    """Return (final_url, page_text). Retries on transient failures."""
    last = "unknown error"
    for attempt in range(retries + 1):
        if attempt:
            time.sleep(2 * attempt)
        request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                charset = response.headers.get_content_charset() or "utf-8"
                return response.geturl(), response.read().decode(charset, "replace")
        except urllib.error.HTTPError as error:
            last = "HTTP %s from %s" % (error.code, url)
            if error.code < 500:
                break
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            last = "%s (%s)" % (getattr(error, "reason", error), url)
    raise FetchError(last)


# --------------------------------------------------------------------------
# Extract the embedded data
# --------------------------------------------------------------------------

class FormatError(Exception):
    pass


def extract_board_data(page: str) -> dict:
    """Find the JSON object the page hands to its scripts."""
    decoder = json.JSONDecoder()
    position = 0
    while True:
        index = page.find(DATA_MARKER, position)
        if index < 0:
            break
        start = page.find("{", index)
        position = index + len(DATA_MARKER)
        if start < 0:
            break
        try:
            data, _ = decoder.raw_decode(page, start)
        except ValueError:
            continue
        if isinstance(data, dict) and isinstance(data.get("questions"), list):
            return data
    raise FormatError(
        "no embedded question list found; the Q&A site layout has probably changed"
    )


# --------------------------------------------------------------------------
# HTML fragment -> plain text
# --------------------------------------------------------------------------

class _TextExtractor(HTMLParser):
    BLOCKS = {"p", "div", "blockquote", "h1", "h2", "h3", "h4", "h5", "h6", "tr"}

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self.lists: list[list] = []  # [kind, counter]
        self.link: str | None = None
        self.link_text: list[str] = []

    def handle_starttag(self, tag, attrs):
        if tag == "br":
            self.parts.append("\n")
        elif tag in self.BLOCKS:
            self.parts.append("\n\n")
        elif tag in ("ul", "ol"):
            self.lists.append([tag, 0])
            self.parts.append("\n")
        elif tag == "li":
            indent = "  " * max(len(self.lists) - 1, 0)
            if self.lists and self.lists[-1][0] == "ol":
                self.lists[-1][1] += 1
                bullet = "%d. " % self.lists[-1][1]
            else:
                bullet = "- "
            self.parts.append("\n" + indent + bullet)
        elif tag == "a":
            self.link = dict(attrs).get("href")
            self.link_text = []
        elif tag == "code":
            self.parts.append("`")

    def handle_endtag(self, tag):
        if tag in self.BLOCKS:
            self.parts.append("\n\n")
        elif tag in ("ul", "ol"):
            if self.lists:
                self.lists.pop()
            self.parts.append("\n")
        elif tag == "a":
            text = "".join(self.link_text).strip()
            if self.link and self.link != text:
                self.parts.append(" (%s)" % self.link)
            self.link = None
        elif tag == "code":
            self.parts.append("`")

    def handle_data(self, data):
        self.parts.append(data)
        if self.link is not None:
            self.link_text.append(data)


def html_to_text(fragment: str | None) -> str:
    if not fragment:
        return ""
    parser = _TextExtractor()
    parser.feed(fragment)
    parser.close()
    text = "".join(parser.parts).replace("\xa0", " ")
    lines = [re.sub(r"[ \t]+", " ", line).rstrip() for line in text.split("\n")]
    text = "\n".join(lines)
    return re.sub(r"\n{3,}", "\n\n", text).strip()


# --------------------------------------------------------------------------
# Normalize
# --------------------------------------------------------------------------

def iso_date(ms) -> str | None:
    if not isinstance(ms, (int, float)) or ms <= 0:
        return None
    moment = dt.datetime.fromtimestamp(ms / 1000, tz=dt.timezone.utc)
    return moment.strftime("%Y-%m-%dT%H:%M:%SZ")


def normalize(data: dict, final_url: str, fetched_at: str) -> dict:
    board = data.get("board") or {}
    season = board.get("season") or {}
    board_url = final_url.split("?")[0].rstrip("/")
    questions = []
    for raw in data.get("questions", []):
        if not isinstance(raw, dict) or raw.get("number") is None:
            continue
        answer = raw.get("answer") or {}
        number = raw["number"]
        questions.append({
            "number": number,
            "title": (raw.get("title") or "").strip(),
            "status": raw.get("status") or "UNKNOWN",
            "answered": bool(answer.get("content")),
            "section": raw.get("topic"),
            "rule": raw.get("rule"),
            "asked_at": iso_date(raw.get("submittedAt") or raw.get("createdAt")),
            "answered_at": iso_date(answer.get("createdAt")),
            "modified_ms": int(raw.get("modifiedAt") or 0),
            "answer_modified_ms": int(answer.get("modifiedAt") or 0),
            "url": "%s/qa/%s" % (board_url, number),
            "question": html_to_text(raw.get("content")),
            "answer": html_to_text(answer.get("content")),
        })
    return {
        "source_url": board_url,
        "fetched_at": fetched_at,
        "program": season.get("program"),
        "game": season.get("gameName"),
        "season": season.get("yearString"),
        "board": board.get("name"),
        "questions": questions,
    }


def sort_questions(questions: list, order: str) -> list:
    if order == "recent":
        return sorted(
            questions,
            key=lambda q: (max(q["modified_ms"], q["answer_modified_ms"]), q["number"]),
            reverse=True,
        )
    return sorted(questions, key=lambda q: q["number"])


# --------------------------------------------------------------------------
# Render
# --------------------------------------------------------------------------

def render_markdown(dump: dict) -> str:
    questions = dump["questions"]
    answered = sum(1 for q in questions if q["answered"])
    title = "FTC %s Q&A" % (dump.get("board") or "Team")
    if dump.get("game"):
        title += " - %s (%s)" % (dump["game"], dump.get("season") or "?")
    out = [
        "# " + title,
        "",
        "- Source: %s" % dump["source_url"],
        "- Fetched: %s" % dump["fetched_at"],
        "- Questions: %d (%d answered, %d awaiting an answer)"
        % (len(questions), answered, len(questions) - answered),
        "- Generated by ftc_qa_dump.py. Do not edit by hand; run the script again.",
        "- This is a local copy. Open a question's link to confirm an answer before",
        "  relying on it, and check the Competition Manual for how Q&A answers rank.",
        "",
    ]
    for q in questions:
        out.append("## Q%s: %s" % (q["number"], q["title"] or "(untitled)"))
        out.append("<!-- qa:%s modified:%s answer:%s -->"
                   % (q["number"], q["modified_ms"], q["answer_modified_ms"]))
        out.append("")
        out.append("- Status: %s" % ("answered" if q["answered"] else "awaiting an answer"))
        out.append("- Manual section: %s | Rule: %s"
                   % (q["section"] or "not given", q["rule"] or "not given"))
        out.append("- Asked: %s | Answered: %s"
                   % ((q["asked_at"] or "?")[:10], (q["answered_at"] or "not yet")[:10]))
        out.append("- Link: %s" % q["url"])
        out.append("")
        out.append("**Question**")
        out.append("")
        out.append(q["question"] or "(empty)")
        out.append("")
        out.append("**Answer**")
        out.append("")
        out.append(q["answer"] or "(not answered yet)")
        out.append("")
    return "\n".join(out).rstrip() + "\n"


def render_text(dump: dict) -> str:
    out = [
        "FTC %s Q&A  %s %s" % (dump.get("board") or "Team", dump.get("game") or "",
                               dump.get("season") or ""),
        "Source:  %s" % dump["source_url"],
        "Fetched: %s" % dump["fetched_at"],
        "",
    ]
    for q in dump["questions"]:
        out.append("=" * 72)
        out.append("Q%s  %s" % (q["number"], q["title"]))
        out.append("Section: %s  Rule: %s  Asked: %s  Answered: %s"
                   % (q["section"] or "-", q["rule"] or "-",
                      (q["asked_at"] or "?")[:10], (q["answered_at"] or "not yet")[:10]))
        out.append(q["url"])
        out.append("")
        out.append("QUESTION:")
        out.append(q["question"] or "(empty)")
        out.append("")
        out.append("ANSWER:")
        out.append(q["answer"] or "(not answered yet)")
        out.append("")
    return "\n".join(out).rstrip() + "\n"


def render_json(dump: dict) -> str:
    return json.dumps(dump, indent=2, ensure_ascii=False) + "\n"


RENDERERS = {"md": render_markdown, "text": render_text, "json": render_json}


# --------------------------------------------------------------------------
# Compare with the previous dump
# --------------------------------------------------------------------------

def previous_stamps(path: str, fmt: str) -> dict | None:
    """Map question number -> (modified_ms, answer_modified_ms) from an old dump."""
    try:
        with open(path, encoding="utf-8") as handle:
            old = handle.read()
    except OSError:
        return None
    if fmt == "json":
        try:
            return {q["number"]: (q["modified_ms"], q["answer_modified_ms"])
                    for q in json.loads(old)["questions"]}
        except (ValueError, KeyError, TypeError):
            return None
    stamps = {int(n): (int(m), int(a)) for n, m, a in STAMP_RE.findall(old)}
    return stamps or None


def describe_changes(old: dict | None, questions: list) -> str:
    if old is None:
        return "no earlier dump to compare with"
    new = [q["number"] for q in questions if q["number"] not in old]
    newly_answered = [q["number"] for q in questions
                      if q["number"] in old and q["answered"] and old[q["number"]][1] == 0]
    edited = [q["number"] for q in questions
              if q["number"] in old and q["number"] not in newly_answered
              and (q["modified_ms"], q["answer_modified_ms"]) != old[q["number"]]]
    current = {q["number"] for q in questions}
    removed = sorted(n for n in old if n not in current)
    if not (new or newly_answered or edited or removed):
        return "no changes since the earlier dump"

    def numbers(label, items):
        return "%s: %s" % (label, ", ".join("Q%s" % n for n in sorted(items)))

    parts = []
    if new:
        parts.append(numbers("new", new))
    if newly_answered:
        parts.append(numbers("newly answered", newly_answered))
    if edited:
        parts.append(numbers("edited", edited))
    if removed:
        parts.append(numbers("removed", removed))
    return "; ".join(parts)


def existing_age_hours(path: str, fmt: str) -> float | None:
    """Hours since the existing dump was fetched, or None if unknown."""
    try:
        with open(path, encoding="utf-8") as handle:
            head = handle.read(4000)
    except OSError:
        return None
    if fmt == "json":
        match = re.search(r'"fetched_at":\s*"([^"]+)"', head)
    elif fmt == "text":
        match = re.search(r"^Fetched: (\S+)", head, re.M)
    else:
        match = FETCHED_RE.search(head)
    if not match:
        return None
    try:
        fetched = dt.datetime.strptime(match.group(1), "%Y-%m-%dT%H:%M:%SZ")
    except ValueError:
        return None
    age = dt.datetime.now(dt.timezone.utc) - fetched.replace(tzinfo=dt.timezone.utc)
    return age.total_seconds() / 3600


def write_atomic(path: str, text: str) -> None:
    folder = os.path.dirname(os.path.abspath(path))
    os.makedirs(folder, exist_ok=True)
    handle, temp_path = tempfile.mkstemp(dir=folder, suffix=".tmp")
    try:
        with os.fdopen(handle, "w", encoding="utf-8", newline="\n") as out:
            out.write(text)
        os.replace(temp_path, path)
    except BaseException:
        try:
            os.unlink(temp_path)
        except OSError:
            pass
        raise


# --------------------------------------------------------------------------
# Command line
# --------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Save the official FTC Team Q&A board as Markdown, text, or JSON.",
        epilog="Exit codes: 0 ok, 2 bad arguments or unwritable output, 3 network failure, "
               "4 page layout changed.",
    )
    parser.add_argument("-o", "--out", metavar="FILE",
                        help="write here instead of stdout (parent folders are created)")
    parser.add_argument("--format", choices=sorted(RENDERERS), default="md",
                        help="output format (default: md)")
    parser.add_argument("--sort", choices=("number", "recent"), default="number",
                        help="question order (default: number, which keeps git diffs small)")
    parser.add_argument("--url", default=DEFAULT_URL,
                        help="board address (default redirects to the current season)")
    parser.add_argument("--if-older-than", type=float, metavar="HOURS",
                        help="with -o: skip the download when the existing file is newer than this")
    parser.add_argument("--timeout", type=float, default=30.0, help="seconds (default: 30)")
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


def main(argv=None) -> int:
    use_utf8_output()
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.if_older_than is not None and not args.out:
        parser.error("--if-older-than needs -o FILE")
    if args.timeout <= 0 or args.retries < 0:
        parser.error("--timeout must be positive and --retries cannot be negative")

    def progress(message: str) -> None:
        if not args.quiet:
            log(message)

    if args.if_older_than is not None:
        age = existing_age_hours(args.out, args.format)
        if age is not None and age < args.if_older_than:
            print("up to date: %s was fetched %.1f hours ago" % (args.out, age))
            return EXIT_OK

    progress("fetching %s" % args.url)
    try:
        final_url, page = fetch(args.url, args.timeout, args.retries)
    except FetchError as error:
        log("error: could not reach the Q&A site: %s" % error)
        return EXIT_NETWORK

    try:
        data = extract_board_data(page)
    except FormatError as error:
        log("error: %s (fetched %s)" % (error, final_url))
        return EXIT_FORMAT

    fetched_at = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    dump = normalize(data, final_url, fetched_at)
    dump["questions"] = sort_questions(dump["questions"], args.sort)
    if not dump["questions"]:
        progress("warning: the board loaded but lists no questions")

    text = RENDERERS[args.format](dump)
    answered = sum(1 for q in dump["questions"] if q["answered"])
    summary = "%d questions, %d answered (%s %s)" % (
        len(dump["questions"]), answered, dump.get("game") or "?", dump.get("season") or "?")

    if args.out:
        changes = describe_changes(previous_stamps(args.out, args.format), dump["questions"])
        try:
            write_atomic(args.out, text)
        except OSError as error:
            log("error: could not write %s: %s" % (args.out, error))
            return EXIT_USAGE
        print("wrote %s: %s; %s" % (args.out, summary, changes))
    else:
        sys.stdout.write(text)
        progress(summary)
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
