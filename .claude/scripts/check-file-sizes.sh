#!/usr/bin/env bash
# Lists production Kotlin files over the soft size limit of ADR 0054, largest first. Reports only, never fails.
set -euo pipefail
cd "$(dirname "$0")/../.."

limit="${1:-600}"
over=$(git ls-files '*/src/main/*.kt' | xargs wc -l | grep -v ' total$' | awk -v limit="$limit" '$1 > limit' | sort -rn)

if [ -z "$over" ]; then
  echo "No production Kotlin file has more than $limit lines."
else
  echo "Production Kotlin files over $limit lines (split candidates, ADR 0054):"
  echo "$over"
fi
