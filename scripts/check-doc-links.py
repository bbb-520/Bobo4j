#!/usr/bin/env python3
"""Check local Markdown file/image destinations without accessing the network."""
import os
import re
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit

EXCLUDED = {".git", ".agents", ".codex", ".aws", "target", "node_modules", "tmp", "secrets", "build", "__pycache__"}
DESTINATION = r'(<[^>\n]+>|(?:\\.|[^()\s]|\([^()\n]*\))+)'
INLINE = re.compile(r'!?\[[^\]\n]*\]\(\s*' + DESTINATION + r'(?:[ \t]+["\'][^\n]*?["\'])?\s*\)')
REFERENCE = re.compile(r'^ {0,3}\[[^\]\n]+\]:\s*' + DESTINATION)


def visible_lines(source):
    # Preserve line numbers while omitting comments and fenced/inline examples.
    source = re.sub(r'<!--[\s\S]*?-->', lambda m: '\n' * m[0].count('\n'), source)
    fence = None
    for line in source.splitlines():
        marker = re.match(r'^ {0,3}(`{3,}|~{3,})', line)
        if fence:
            if marker and marker[1][0] == fence[0] and len(marker[1]) >= len(fence):
                fence = None
            yield ''
        elif marker:
            fence = marker[1]
            yield ''
        else:
            yield re.sub(r'(`+).*?\1', '', line)


def check(root):
    root = Path(root).resolve()
    failures = []
    for directory, folders, files in os.walk(root, followlinks=False):
        folders[:] = sorted(folder for folder in folders if folder not in EXCLUDED
                            and not (Path(directory).name == 'docs' and folder == 'superpowers'))
        for name in sorted(files):
            if not name.lower().endswith('.md'):
                continue
            document = Path(directory) / name
            for number, line in enumerate(visible_lines(document.read_text(encoding='utf-8-sig')), 1):
                matches = list(INLINE.finditer(line))
                reference = REFERENCE.match(line)
                if reference:
                    matches.append(reference)
                for match in matches:
                    target = match[1].strip('<>')
                    if target.startswith(('#', '//')) or urlsplit(target).scheme:
                        continue
                    local = unquote(urlsplit(target).path)
                    local = re.sub(r'\\([ ()])', r'\1', local)
                    if local and not (document.parent / local).exists():
                        failures.append(f'{document.relative_to(root).as_posix()}:{number}: {target}')
    return failures


if __name__ == '__main__':
    errors = check(Path(__file__).resolve().parents[1])
    for error in errors:
        print(error)
    print(f'Local documentation links: {len(errors)} missing target(s).')
    sys.exit(bool(errors))
