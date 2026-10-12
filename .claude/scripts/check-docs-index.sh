#!/usr/bin/env bash
# Verifies that every Markdown file in docs/ (except the index itself) is linked from docs/index.md.
set -euo pipefail
cd "$(dirname "$0")/../.."

status=0
while IFS= read -r file; do
  rel="${file#docs/}"
  if ! grep -qF "($rel)" docs/index.md; then
    echo "Not listed in docs/index.md: $rel" >&2
    status=1
  fi
done < <(find docs -name '*.md' ! -path docs/index.md | sort)

[ "$status" -eq 0 ] && echo "docs/index.md lists all documents."
exit "$status"
