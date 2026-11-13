#!/usr/bin/env bash
# Verifies docs/index.md: every Markdown file in docs/ (except the index itself) is linked from it, and every table
# row keeps the limits of its header: a summary of at most 120 characters, at most 12 keywords, no `\|` in a cell.
set -euo pipefail
cd "$(dirname "$0")/../.."

max_summary_chars=120
max_keywords=12

status=0
while IFS= read -r file; do
  rel="${file#docs/}"
  if ! grep -qF "($rel)" docs/index.md; then
    echo "Not listed in docs/index.md: $rel" >&2
    status=1
  fi
done < <(find docs -name '*.md' ! -path docs/index.md | sort)

# Python, not awk: it counts characters on both macOS and Linux (mawk counts bytes).
python3 -I - "$max_summary_chars" "$max_keywords" <<'PY' || status=1
import re
import sys

max_chars, max_terms = int(sys.argv[1]), int(sys.argv[2])
bad = False
columns = None  # cell count of the current table, from its header row
with open("docs/index.md", encoding="utf-8") as index:
    for number, line in enumerate(index, 1):
        if not line.startswith("|"):
            continue
        where = f"docs/index.md:{number}"
        if "\\|" in line:
            print(f"{where}: contains \\| (write Source/Split)", file=sys.stderr)
            bad = True
            continue
        cells = [cell.strip() for cell in line.rstrip("\n").split("|")[1:-1]]
        if not line.startswith("| ["):
            if not line.startswith("|-"):
                columns = len(cells)
            continue
        # A | inside a cell, even in backticks, splits it: the row gets more cells than its table.
        if len(cells) != columns:
            print(f"{where}: {len(cells)} cells, table has {columns} (a | inside a cell?)", file=sys.stderr)
            bad = True
            continue
        summary, keywords = cells[1], cells[2]
        if len(summary) > max_chars:
            print(f"{where}: summary {len(summary)} chars (max {max_chars})", file=sys.stderr)
            bad = True
        # Commas inside backticks, as in `f(a, b)`, do not separate terms.
        terms = len([term for term in re.split(r",(?=(?:[^`]*`[^`]*`)*[^`]*$)", keywords) if term.strip()])
        if terms > max_terms:
            print(f"{where}: keywords {terms} terms (max {max_terms})", file=sys.stderr)
            bad = True
sys.exit(1 if bad else 0)
PY

[ "$status" -eq 0 ] && echo "docs/index.md lists all documents and all rows are within the limits."
exit "$status"
