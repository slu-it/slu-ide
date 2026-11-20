# JShell agent recordings

Line-delimited JSON between a client and the JShell agent (ADR 0191), the traffic the session's fake agent replays
(`FakeAgent`) and the in-process agent is checked against (`AgentRecordingsTest`). One file = one agent process. Each
line: `{"t": ms since spawn, "dir": "in" (client to agent) | "out" (agent to client), "msg": the parsed line}`, which
is record-lsp.sh's format; record-lsp.sh itself reads only Content-Length frames, so the spike's client `drive.py`
recorded them, scrubbing the same way: `${JDK}`, `${SCRATCH}` and `${HOME}` placeholders, `*Pid` values null.
Offsets (`rangeStart`, `rangeEnd`, `caret`, `start`, `end`, `anchor`) are Java UTF-16 code units; `line` is 0-based.

Command (JDK = Debian 25.0.5+8, linux-aarch64, unless named): `python3 -I drive.py ${JDK} <case> <case>.jsonl`, which
starts `${JDK}/bin/java -cp agent.jar:kotlin-stdlib.jar dev.sluide.jshell.agent.Agent` (Kotlin 2.4.21, `-jvm-target 9
-Xjdk-release=9`). The first 16 files come from the spike P0 (2026-11-20, its agent); the module's agent recorded all
of them again with the same messages (compared without `t`), and recorded the last six; since then `ready` no longer
carries `agentPid` (null in the older files), which Thoreau never read. Unless stated, the session
ends with `close` → `ended closed` and the agent exits with code 0.

Each row: the requests (`in` lines) and the lines that answer them, which is what the fake sends for each request.

| File | JDK | What it shows; request line → answer lines |
|---|---|---|
| value.jsonl | 25.0.5 | `int x = 5;` at the caret → `x ==> 5`. Line 1 at start; 2 → 3-5; 6 → 7 |
| print-without-newline.jsonl | 25.0.5 | `System.out.print("a")` → output `a` without newline; then a line holding two snippets, evaluated as a selection: err `e`, then out `b\n`, one snippetStarted each. 1; 2 → 3-5; 6 → 7-11; 12 → 13 |
| compile-error.jsonl | 25.0.5 | a diagnostic with document offsets after a comment line; a selection of two snippets, where the first lacks its `;` and the second has an error. 1; 2 → 3-5; 6 → 7-11; 12 → 13 |
| runtime-exception.jsonl | 25.0.5 | a method throwing at line 2, called at line 6: `thrown` with the frames as document lines; a cause chain. 1; 2 → 3-7; 8 → 9-11; 12 → 13 |
| multi-snippet.jsonl | 25.0.5 | Run All of a document with comments, blank lines, a missing `;`, two snippets on one line, a method, a class, an import (no value), a compile error and a trailing incomplete snippet. 1; 2 → 3-22; 23 → 24 |
| caret.jsonl | 25.0.5 | the caret inside a snippet, on a blank line after one (→ the one before), at the end of the document, and a selection over two snippets. 1; 2 → 3-9; 10 → 11-13; 14 → 15-17; 18 → 19-21; 22 → 23-27; 28 → 29 |
| stop.jsonl | 25.0.5 | `while (true) {}`: `evaluate` while it runs → error busy; `complete` while it runs → answered; `stop` → `stopped`, `finished`; the session survives (`x ==> 7`); stop of `Thread.sleep(60000)`. 1; 2 → 3-5; 6 → 7; 8 → 9; 10 → 11; 12 → 13-14; 15 → 16-18; 19 → 20; 21 → 22-23; 24 → 25 |
| system-exit.jsonl | 25.0.5 | `System.exit(3)` → `finished`, then `ended exit` with `exitStatus` 3; the agent exits with code 0 by itself (its output ends), no `close` sent. 1; 2 → 3-5; 6 → 7-11 |
| completion.jsonl | 25.0.5 | `"abc".` (76 items), `"abc".len`, a session variable (`greeting.`, `greeting.to`), `Math.ma` after another snippet (document anchor). 1; 2 → 3; 4 → 5; 6 → 7-9; 10 → 11; 12 → 13; 14 → 15; 16 → 17 |
| version.jsonl | 25.0.5 | `Runtime.version()` → `$1 ==> 25.0.5+8-1-deb13u1-Debian`. 1; 2 → 3-5; 6 → 7 |
| version-jdk21.jsonl | 21.0.13.1 (`java-21-openjdk-arm64`) | `Runtime.version()` → `$1 ==> 21.0.13.1+2-1-deb13u1-Debian`. 1; 2 → 3-5; 6 → 7 |
| stdin.jsonl | 25.0.5 | `System.in.read()` → `-1`; `Scanner.hasNextLine()` → `false` (end of input). 1; 2 → 3-5; 6 → 7-9; 10 → 11 |
| stdin-eof.jsonl | 25.0.5 | the client closes the agent's input with no `close` request → `ended closed`, exit 0: the end of input answers like `close` (value.jsonl 6 → 7). 1; 2 → 3-5; end of input → 6 |
| busy.jsonl | 25.0.5 | a second `evaluate` while one runs → `error busy` right away; the first one finishes normally. 1; 2 → 3; 4 → 5; then 6 without a request; 7 → 8 |
| late-output.jsonl | 25.0.5 | output from a thread the snippet started, after `finished`: an `output` event with `"id": null`. 1; 2 → 3-4, then 5 without a request; 6 → 7 |
| unicode.jsonl | 25.0.5 | `ü€😀` printed and as a value; the client's `rangeEnd` 30 counts code points (its Python `len`), the agent's `end` 31 counts UTF-16 units. 1; 2 → 3-7; 8 → 9 |
| killed.jsonl | 25.0.5 | the agent is killed (`kill -9`) while idle: its output ends after `finished`, no `ended`. 1; 2 → 3-5; then the end of the output |
| killed-running.jsonl | 25.0.5 | the agent is killed while `while (true) {}` runs: no `finished`, no `ended`. 1; 2 → 3; then the end of the output |
| complete-error.jsonl | 25.0.5 | `complete` with the caret past the text → `error` under its id (the shape of every error that ends a request). 1; 2 → 3; 4 → 5 |
| thrown-no-line.jsonl | 25.0.5 | an exception without stack frames (its `fillInStackTrace` overridden): `thrown` with `line` null. 1; 2 → 3-5; 6 → 7 |
| halt.jsonl | 25.0.5 | `Runtime.getRuntime().halt(5)`: no `Runtime.exit` log record, so `ended exit` with `exitStatus` null, as on JDKs before 21. 1; 2 → 3-5 |
| no-jshell.stderr | 25.0.5, jlinked without `jdk.jshell` | not traffic: the agent's standard error when the runtime has no JShell; its standard output ends without a line |
