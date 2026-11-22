"""Offline tests for ftc_qa_dump.py. Run: python -m unittest discover -s tests"""
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "scripts"))
import ftc_qa_dump as qa  # noqa: E402

BOARD = {
    "feedUrl": "/boards/2099/FTC/QA/answers.atom",
    "board": {"name": "Team", "season": {"program": "FTC", "gameName": "TESTGAME",
                                         "yearString": "2098-2099"}},
    "questions": [
        {"number": 2, "status": "ACCEPTED", "title": "Second", "topic": "11.4", "rule": None,
         "content": "<p>Still waiting?</p>", "createdAt": 1700000000000,
         "modifiedAt": 1700000000000, "submittedAt": 1700000000000},
        {"number": 1, "status": "ANSWERED", "title": "First &amp; foremost", "topic": "12.1",
         "rule": "R101",
         "content": "<p>Is a <strong>ROBOT</strong> allowed to:</p><ol><li>do A</li>"
                    "<li>do B &amp; C</li></ol>",
         "createdAt": 1700000000000, "modifiedAt": 1700000500000, "submittedAt": 1700000000000,
         "answer": {"content": "<p>See <a href=\"https://example.org/tu-01\">Team Update 01"
                               "</a>.<br>Thanks&nbsp;all.</p>",
                    "createdAt": 1700000900000, "modifiedAt": 1700000900000}},
    ],
}
PAGE = ("<html><head><script>window.X = {};</script><script>Object.assign(window, "
        + json.dumps(BOARD) + ");</script></head><body>shell</body></html>")


class HtmlToText(unittest.TestCase):
    def test_lists_links_entities(self):
        text = qa.html_to_text(BOARD["questions"][1]["content"])
        self.assertEqual(text, "Is a ROBOT allowed to:\n\n1. do A\n2. do B & C")
        answer = qa.html_to_text(BOARD["questions"][1]["answer"]["content"])
        self.assertEqual(answer, "See Team Update 01 (https://example.org/tu-01).\nThanks all.")

    def test_empty(self):
        self.assertEqual(qa.html_to_text(None), "")
        self.assertEqual(qa.html_to_text("<p><br></p>"), "")


class Extract(unittest.TestCase):
    def test_finds_embedded_data(self):
        data = qa.extract_board_data(PAGE)
        self.assertEqual(len(data["questions"]), 2)

    def test_layout_change_raises(self):
        with self.assertRaises(qa.FormatError):
            qa.extract_board_data("<html>Object.assign(window, {\"other\": 1});</html>")
        with self.assertRaises(qa.FormatError):
            qa.extract_board_data("<html>nothing</html>")


class NormalizeAndRender(unittest.TestCase):
    def setUp(self):
        self.dump = qa.normalize(qa.extract_board_data(PAGE),
                                 "https://example.org/boards/2099/FTC/QA/", "2099-01-01T00:00:00Z")
        self.dump["questions"] = qa.sort_questions(self.dump["questions"], "number")

    def test_normalize(self):
        first, second = self.dump["questions"]
        self.assertEqual((first["number"], second["number"]), (1, 2))
        self.assertTrue(first["answered"])
        self.assertFalse(second["answered"])
        self.assertEqual(first["url"], "https://example.org/boards/2099/FTC/QA/qa/1")
        self.assertEqual(first["answered_at"], "2023-11-14T22:28:20Z")
        self.assertEqual(self.dump["game"], "TESTGAME")

    def test_recent_sort(self):
        recent = qa.sort_questions(self.dump["questions"], "recent")
        self.assertEqual([q["number"] for q in recent], [1, 2])

    def test_markdown_round_trip_and_changes(self):
        text = qa.render_markdown(self.dump)
        self.assertIn("## Q1: First &amp; foremost", text)
        self.assertIn("- Questions: 2 (1 answered, 1 awaiting an answer)", text)
        with tempfile.TemporaryDirectory() as folder:
            path = os.path.join(folder, "sub", "qa.md")
            qa.write_atomic(path, text)
            old = qa.previous_stamps(path, "md")
            self.assertEqual(set(old), {1, 2})
            self.assertEqual(qa.describe_changes(old, self.dump["questions"]),
                             "no changes since the earlier dump")
            # Q2 gets answered, Q3 appears, Q1 is edited
            later = [dict(q) for q in self.dump["questions"]]
            later[0]["modified_ms"] += 1
            later[1].update(answered=True, answer_modified_ms=5)
            later.append(dict(later[0], number=3))
            self.assertEqual(qa.describe_changes(old, later),
                             "new: Q3; newly answered: Q2; edited: Q1")
            self.assertLess(qa.existing_age_hours(path, "md"), 24 * 366 * 200)

    def test_json_and_text(self):
        parsed = json.loads(qa.render_json(self.dump))
        self.assertEqual(parsed["questions"][0]["rule"], "R101")
        self.assertIn("ANSWER:\n(not answered yet)", qa.render_text(self.dump))


class CommandLine(unittest.TestCase):
    def test_file_url_end_to_end(self):
        with tempfile.TemporaryDirectory() as folder:
            page = os.path.join(folder, "page.html")
            with open(page, "w", encoding="utf-8") as handle:
                handle.write(PAGE)
            out = os.path.join(folder, "qa.md")
            url = "file://" + page
            self.assertEqual(qa.main(["--url", url, "-o", out, "-q"]), qa.EXIT_OK)
            self.assertEqual(qa.main(["--url", url, "-o", out, "-q", "--if-older-than", "1"]),
                             qa.EXIT_OK)
            bad = os.path.join(folder, "bad.html")
            with open(bad, "w", encoding="utf-8") as handle:
                handle.write("<html>no data</html>")
            self.assertEqual(qa.main(["--url", "file://" + bad, "-q"]), qa.EXIT_FORMAT)
            self.assertEqual(qa.main(["--url", "file://" + folder + "/missing.html", "-q",
                                      "--retries", "0"]), qa.EXIT_NETWORK)


if __name__ == "__main__":
    unittest.main()
