import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "check-doc-links.py"
spec = importlib.util.spec_from_file_location("doc_links", SCRIPT)
links = importlib.util.module_from_spec(spec)
spec.loader.exec_module(links)


class DocLinksTest(unittest.TestCase):
    def test_missing_target_is_reported_with_file_and_line(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "README.md").write_text("Intro\n\n[missing](docs/gone.md)\n", encoding="utf-8")
            self.assertEqual(links.check(root), ["README.md:3: docs/gone.md"])

    def test_relative_encoded_and_reference_links_resolve(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "docs").mkdir()
            (root / "a b.md").touch()
            (root / "docs" / "guide.md").write_text(
                '[file](../a%20b.md#part)\n[file](<../a b.md> "title")\n'
                '[reference][x]\n[x]: ../a%20b.md "title"\n', encoding="utf-8")
            self.assertEqual(links.check(root), [])

    def test_external_urls_anchors_and_code_examples_are_ignored(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "README.md").write_text(
                '[site](https://example.com/no.md) [email](mailto:a@example.com) [anchor](#part)\n'
                '```markdown\n[example](not-a-file.md)\n```\n'
                '`[inline](not-a-file.md)`\n<!-- [hidden](not-a-file.md) -->\n', encoding="utf-8")
            self.assertEqual(links.check(root), [])

    def test_images_and_missing_references_are_checked(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "README.md").write_text(
                '![image](images/missing.png)\n[x]: absent.md\n', encoding="utf-8")
            self.assertEqual(links.check(root), ["README.md:1: images/missing.png", "README.md:2: absent.md"])

    def test_build_and_private_directories_are_excluded(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for folder in ("target", "tmp", "secrets", ".git", "node_modules", "docs/superpowers"):
                destination = root / folder
                destination.mkdir(parents=True)
                (destination / "example.md").write_text('[example](gone.md)', encoding="utf-8")
            self.assertEqual(links.check(root), [])


if __name__ == "__main__":
    unittest.main()
