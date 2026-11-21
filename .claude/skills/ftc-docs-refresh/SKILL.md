---
name: ftc-docs-refresh
description: Refresh the project's local copies of official FTC documents in doc/ftc-rules - currently the Team Q&A (questions and answers) and the Competition Manual (one Markdown file per section, plus the PDF) - so rules questions can be checked against them offline. Use before asking ftc-rules-expert about anything a Q&A answer or a recent manual change could affect, when it reports that a local copy is missing or behind, and after each Thursday Team Update. Needs Python; says what to do when Python is not installed.
allowed-tools: Bash(python3 ${CLAUDE_SKILL_DIR}/scripts/ftc_qa_dump.py *), Bash(python ${CLAUDE_SKILL_DIR}/scripts/ftc_qa_dump.py *), Bash(py ${CLAUDE_SKILL_DIR}/scripts/ftc_qa_dump.py *), Bash(python3 ${CLAUDE_SKILL_DIR}/scripts/ftc_manual_dump.py *), Bash(python ${CLAUDE_SKILL_DIR}/scripts/ftc_manual_dump.py *), Bash(py ${CLAUDE_SKILL_DIR}/scripts/ftc_manual_dump.py *), Bash(python3 --version), Bash(python --version), Bash(py --version)
---

# Refresh the local FTC document copies

`doc/ftc-rules/` holds local copies of official FTC documents, which `ftc-rules-expert` reads before going to the web. Each kind of document has its own script:

| Document | Script | Writes | Needs |
|---|---|---|---|
| Team Q&A | `scripts/ftc_qa_dump.py` | `doc/ftc-rules/ftc-qa.md`, every question and answer | Python 3.9 or later |
| Competition Manual | `scripts/ftc_manual_dump.py` | `doc/ftc-rules/manual/`, one Markdown file per section plus figures, and the official PDF in `doc/ftc-rules/` | Python 3.13 or later |

The official Q&A site builds its question list in the browser, so WebFetch sees an empty page. The questions and answers are embedded in that page as data, and `ftc_qa_dump.py` reads them with one plain web request. The manual script reads the current version from the game hub, then converts FIRST's HTML copy of the manual (a Word export) into clean Markdown.

Run all of them unless the person asked for only some. Each uses the Python standard library and nothing else. Many team laptops do not have Python, so check before running.

## 1. Check for Python

Try each of these and use the first that prints a version number:

```
python3 --version
python --version
py --version
```

On Windows, `python` can be a placeholder that only points to the Microsoft Store. If the command does not print a version number, it is not Python.

- 3.13 or later: run every script.
- 3.9 to 3.12: run the Q&A script only. Tell the person the manual refresh needs Python 3.13 or later, and treat the manual like step 2 below.
- Older than 3.9, or none: follow step 2.

## 2. If Python is missing or too old

Stop. Do not install Python yourself, do not rewrite the tools in another language, and do not try to collect the Q&A or the manual by hand with WebFetch. Tell the student plainly, in words like these:

> Python isn't installed on this computer (or is too old), and the refresh needs it. You have two choices:
>
> 1. Install Python 3.13 or later from python.org. On Windows, tick "Add python.exe to PATH" in the installer. Then open a new terminal and ask me again.
> 2. Ask a teammate or mentor who uses a Mac to run the refresh. Macs set up for coding normally have `python3` already. They run the commands below in their copy of the project, commit `doc/ftc-rules/ftc-qa.md` and `doc/ftc-rules/manual/`, and push. Then run `git pull` here.

Then say what still works without it: `ftc-rules-expert` can answer from the live Competition Manual and Team Updates, can see which questions have been answered, and will name any Q&A answers it could not read, with their links, so the student can read those pages in a browser.

Say how old the existing copies are, since an older copy is still useful: the `Fetched:` line in `doc/ftc-rules/ftc-qa.md`, and the `Manual version:` line in `doc/ftc-rules/manual/README.md`.

## 3. Run it

From the project root, with whichever Python command worked:

```
python3 "${CLAUDE_SKILL_DIR}/scripts/ftc_qa_dump.py" -o doc/ftc-rules/ftc-qa.md --if-older-than 12
python3 "${CLAUDE_SKILL_DIR}/scripts/ftc_manual_dump.py" -o doc/ftc-rules/manual --pdf-dir doc/ftc-rules
```

Q&A:

- `-o doc/ftc-rules/ftc-qa.md` is the path `ftc-rules-expert` looks for. Keep it.
- `--if-older-than 12` skips the download when the copy was fetched in the last 12 hours. Leave it off to force a refresh.
- The default address redirects to the current season's board, so nothing changes when a new season starts.

Manual:

- `-o doc/ftc-rules/manual` is the folder `ftc-rules-expert` looks for. Keep it.
- When the copy already has the hub's current version, the script only checks and stops. Add `--force` to convert again anyway, for example after the script itself was improved.
- The script replaces the whole folder, figures included. It refuses to run if the folder holds files it did not write; move those files rather than deleting them.
- `--pdf-dir doc/ftc-rules` also saves the official PDF as `competition-manual-<version>.pdf` and deletes older ones. The PDF stays out of git (`.gitignore`), because each version would add about 7 MB to the history. Leave the option off if the person does not want the PDF.
- The hub always shows the current season, so nothing changes when a new season starts. Downloading takes about a minute: the manual page plus about 70 figures.

## 4. Read the result

Each script prints one line on stdout, for example:

```
wrote doc/ftc-rules/ftc-qa.md: 52 questions, 46 answered (BIOBUZZ 2026-2027); new: Q54; newly answered: Q51
wrote doc/ftc-rules/manual: BIOBUZZ Competition Manual TU10 (Nov 19, 2026), 16 sections, 172 rules, 72 figures; changed: 10-game-details.md, 11-game-rules.md; pdf: doc/ftc-rules/competition-manual-TU10.pdf
up to date: doc/ftc-rules/manual already has manual version TU10; pdf: doc/ftc-rules/competition-manual-TU10.pdf
```

Pass those lines on to the person. If the Q&A line names new or newly answered questions, say which ones, since those are the answers nobody on the team has read yet. If the manual line names changed sections, say which ones, and offer to summarize the changes from `git diff doc/ftc-rules/manual`. The Team Update itself (`https://ftc-resources.firstinspires.org/ftc/game/tu-latest`) explains them officially.

| Exit code | Meaning | What to do |
|---|---|---|
| 0 | Wrote the files, or the copy was already current | Continue |
| 2 | Bad arguments, Python too old, or the output could not be written | Read the error. Fix the command or the path, or follow step 2 for the Python version |
| 3 | Could not reach the site | Check the internet connection. A laptop joined to the robot's Wi-Fi has none |
| 4 | A page loaded but its layout changed | Report it; the script needs updating. Keep the old copy and do not guess at answers or rules |
| "command not found", or 9009 on Windows | Python is not installed | Follow step 2 |

## After refreshing

- Commit `doc/ftc-rules/ftc-qa.md` and `doc/ftc-rules/manual/` so the whole team and every agent share the same copies. One person refreshing is enough for everyone, which is why a teammate can do it for a laptop without Python. `git diff` shows exactly which answers and which manual text changed.
- Do not edit these files by hand, figures included. The next run replaces them; run the scripts again instead.
- The copies are a convenience. Anything that matters for a design or strategy decision should be confirmed against the current official Competition Manual (the PDF outranks the Markdown copy, and the manual outranks the Q&A) and, for Q&A answers, on the question's live page.

## Other options

- Q&A: `--format json` for scripts, `--format text` for plain text, `--sort recent` to list the newest activity first. Without `-o` the dump goes to stdout.
- All scripts: `--timeout`, `--retries`, `-q` for no progress messages, and `--help`.

## Maintaining the scripts

Offline tests for the scripts: `python3 -m unittest discover -s tests` from this skill's folder.

To add another document (for example the Team Updates or the inspection checklist), follow the same pattern: a standard-library script in `scripts/` named `ftc_<document>_dump.py` that writes under `doc/ftc-rules/`, records the source version or fetch time in its output, skips work when the copy is current, prints a one-line summary on stdout, and uses the same exit codes; offline tests in `tests/`; a row in the table at the top and its command in step 3; its `allowed-tools` entries; and a line in `ftc-rules-expert`'s source table so the agent knows the copy exists. After changing the manual converter, run it with `--force` and read `git diff doc/ftc-rules/manual`. Every rule number should still start a paragraph in bold (`**R101**`), and the rule count in the summary line should not drop.
