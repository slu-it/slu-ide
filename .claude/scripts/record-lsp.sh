#!/usr/bin/env bash
# Records the traffic of a stdio JSON-RPC server (a language server such as jdtls) for test fakes (ADR 0178,
# `.claude/rules/testing.md`, `docs/testing.md` "Fakes of external servers"): starts the server command, passes stdin
# and stdout through unchanged, and writes every message to <log.jsonl> as one JSON object per line: {"t":
# milliseconds since start, "dir": "in" (client to server) or "out" (server to client), "msg": the parsed message}.
# Each message is logged before it is passed on, so a reply never precedes its request in the log. stderr goes
# through unchanged. Frames are LSP-style (Content-Length headers); a body that is no JSON is logged as {"raw": "..."}.
# The log is scrubbed, the traffic is not: every --placeholder PATH=NAME (and always $HOME=HOME) turns PATH, also as a
# file URI, into ${NAME}, and "processId" values become null, so that a recording holds no user's paths and replays on
# another machine (the fake puts its own paths back). Check the log for names before committing it anyway.
# Use it where the client lets you name the server command (a custom server path, a spike's own client), e.g.
#   .claude/scripts/record-lsp.sh --placeholder /tmp/demo=PROJECT modules/java/src/test/resources/jdtls/import.jsonl \
#     -- /path/to/jdtls -data /tmp/ws
# The log is written fresh each time (record again after a server update: one file is one session). Commit it with a
# line in the folder's README.md: the tool's version, the command and what was done.
# Usage: .claude/scripts/record-lsp.sh [--placeholder PATH=NAME]... <log.jsonl> -- <server command...>
# Exit: the server's exit code (128+n after signal n); 2 for usage errors; 3 when a frame could not be read (the
# server is stopped).
set -euo pipefail

usage="Usage: $0 [--placeholder PATH=NAME]... <log.jsonl> -- <server command...>"
placeholders=("$HOME=HOME")
while [ "$#" -gt 0 ] && [ "$1" = "--placeholder" ]; do
  [ "$#" -ge 2 ] && [[ "$2" == *=* ]] || { echo "$usage" >&2; exit 2; }
  placeholders+=("$2")
  shift 2
done
[ "$#" -ge 3 ] && [ "$2" = "--" ] || { echo "$usage" >&2; exit 2; }
log="$1"
shift 2
mkdir -p "$(dirname "$log")"
# The code goes in through -c: stdin belongs to the client's traffic.
code="$(cat <<'PY'
import json, os, subprocess, sys, threading, time, urllib.parse

log_path, count = sys.argv[1], int(sys.argv[2])
pairs = [p.split("=", 1) for p in sys.argv[3:3 + count]]
command = sys.argv[3 + count:]
# Longest path first, so that a project inside the home folder becomes ${PROJECT}, not ${HOME}/...
# Each path also as the server may report it, resolved (macOS: /tmp is /private/tmp).
paths = {}
for path, name in pairs:
    for variant in (path.rstrip("/"), os.path.realpath(path).rstrip("/")):
        if variant: paths[variant] = name
replacements = []
for path, name in sorted(paths.items(), key=lambda p: -len(p[0])):
    uri = "file://" + urllib.parse.quote(path)
    replacements += [(uri, "file://${%s}" % name), (path, "${%s}" % name)]
start = time.monotonic()
lock = threading.Lock()
log = open(log_path, "w", encoding="utf-8")

def scrub(value):
    if isinstance(value, dict):
        # Keys too: LSP keys maps by document URI (WorkspaceEdit.changes).
        return {scrub(k): (None if k == "processId" else scrub(v)) for k, v in value.items()}
    if isinstance(value, list): return [scrub(v) for v in value]
    if isinstance(value, str):
        for old, new in replacements: value = value.replace(old, new)
    return value

def record(direction, body):
    entry = {"t": int((time.monotonic() - start) * 1000), "dir": direction}
    try: entry["msg"] = scrub(json.loads(body))
    except ValueError: entry["raw"] = scrub(body.decode("utf-8", "replace"))
    log.write(json.dumps(entry) + "\n")
    log.flush()

def read_frame(source):
    """(headers, body) of the next frame, None at the end of the stream."""
    headers = b""
    while not headers.endswith(b"\r\n\r\n"):
        byte = source.read(1)
        if not byte: return None
        headers += byte
    length = None
    for line in headers.decode("ascii", "replace").split("\r\n"):
        if line.lower().startswith("content-length:"): length = int(line.split(":", 1)[1])
    if length is None: raise ValueError("a frame without Content-Length")
    body = source.read(length)
    if len(body) != length: raise ValueError("a frame cut short")
    return headers, body

def pump(source, sink, direction):
    """Copies frames from source to sink byte for byte, logging each one before passing it on: a request is in the
    log before the server can see it, so its reply always comes later. (No lock around the write: a full pipe in one
    direction must not hold up the other.)"""
    try:
        while True:
            frame = read_frame(source)
            if frame is None: break
            with lock: record(direction, frame[1])
            try:
                sink.write(frame[0] + frame[1])
                sink.flush()
            except OSError:
                # The other side is gone: the server exited (its code still counts), or the client stopped reading.
                if direction == "out": server.terminate()
                break
    except Exception as e:
        sys.stderr.write("record-lsp: %s (%s); stopping the server\n" % (e, direction))
        server.kill()
        os._exit(3)
    try: sink.close()
    except OSError: pass

server = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE)
threading.Thread(target=pump, args=(sys.stdin.buffer, server.stdin, "in"), daemon=True).start()
pump(server.stdout, sys.stdout.buffer, "out")
code = server.wait()
log.close()  # stdout was flushed and closed by the "out" pump
# os._exit, not sys.exit: the stdin thread may still block in a read, and the interpreter's shutdown aborts on that.
os._exit(128 - code if code < 0 else code)
PY
)"
exec python3 -I -c "$code" "$log" "${#placeholders[@]}" "${placeholders[@]}" "$@"
