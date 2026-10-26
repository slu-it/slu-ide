#!/bin/bash
# Claude Code status line: "<model> (<effort>) | session N% · week N% | ctx N%"
# Reads the statusLine JSON from stdin. Missing fields are omitted.

input=$(cat)

if command -v jq >/dev/null 2>&1; then
  model=$(printf '%s' "$input" | jq -r '.model.display_name // empty')
  effort=$(printf '%s' "$input" | jq -r '.effort.level // empty')
  five=$(printf '%s' "$input" | jq -r '.rate_limits.five_hour.used_percentage // empty')
  week=$(printf '%s' "$input" | jq -r '.rate_limits.seven_day.used_percentage // empty')
  ctx=$(printf '%s' "$input" | jq -r '.context_window.used_percentage // empty')
else
  # Fallback without jq: flatten the JSON and pull values out with sed.
  flat=$(printf '%s' "$input" | tr -d '\n')
  str() { printf '%s' "$flat" | sed -nE "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"([^\"]*)\".*/\1/p"; }
  num() { printf '%s' "$flat" | sed -nE "s/.*\"$1\"[[:space:]]*:[[:space:]]*\{[^}]*\"used_percentage\"[[:space:]]*:[[:space:]]*([0-9.]+).*/\1/p"; }
  model=$(str display_name)
  effort=$(printf '%s' "$flat" | sed -nE 's/.*"effort"[[:space:]]*:[[:space:]]*\{[^}]*"level"[[:space:]]*:[[:space:]]*"([^"]*)".*/\1/p')
  five=$(num five_hour)
  week=$(num seven_day)
  ctx=$(printf '%s' "$flat" | sed -nE 's/.*"context_window"[[:space:]]*:[[:space:]]*\{.*"used_percentage"[[:space:]]*:[[:space:]]*([0-9.]+).*/\1/p')
fi

pct() { printf '%.0f%%' "$1"; }

groups=()

# Group 1: model and effort
g1=""
if [ -n "$model" ]; then
  g1="$model"
  [ -n "$effort" ] && g1="$g1 ($effort)"
fi
[ -n "$g1" ] && groups+=("$g1")

# Group 2: rate limits
g2=""
[ -n "$five" ] && g2="session $(pct "$five")"
if [ -n "$week" ]; then
  [ -n "$g2" ] && g2="$g2 · "
  g2="${g2}week $(pct "$week")"
fi
[ -n "$g2" ] && groups+=("$g2")

# Group 3: context window
[ -n "$ctx" ] && groups+=("ctx $(pct "$ctx")")

out=""
for g in "${groups[@]}"; do
  [ -n "$out" ] && out="$out | "
  out="$out$g"
done
printf '%s' "$out"
