#!/usr/bin/env bash
# PreToolUse hook (Agent|Task): denies the built-in Explore and Plan agents, which run on the main model (Opus).
# Reads the hook JSON on stdin; any other agent type, missing field or invalid JSON passes silently (exit 0).
type="$(jq -r '.tool_input.subagent_type // empty' 2>/dev/null || true)"
case "$type" in
  Explore | Plan)
    echo '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"The built-in Explore and Plan agents run on the main model (Opus). Use the `explorer` agent (Haiku) for searches, and plan in the main session (delegate skill)."}}'
    ;;
esac
exit 0
