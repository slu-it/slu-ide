# 0191. JShell engine: an agent on the chosen JDK

- Status: accepted
- Date: 2026-11-20

## Context
Thoreau gets a Java REPL: `.jsh` files whose snippets run in a live JShell session, with completion from JShell's own
analysis. The snippets must run on the JDK the user expects: the one chosen for the file, else the project's JDK as
jdtls resolves it (`JavaHomes`: project setting, login shell's `JAVA_HOME`, Thoreau's environment, Thoreau's own JDK;
ADR 0179). That JDK is usually another one than Thoreau's runtime, which in the packaged app is jlinked with only the
modules Thoreau needs and no `bin/java` (ADR 0011). A spike (P0, JDK 21.0.13 and 25.0.5 on linux-aarch64) measured
three ways:
- **A: the `jshell` tool with a custom feedback mode.** No document positions for snippets or diagnostics; SIGINT to
  stop a snippet kills `jshell` and leaves its execution JVM running (orphaned at 95% CPU); commands sent on stdin while
  a snippet ran were swallowed by it as input.
- **B: a small agent on the chosen JDK** that uses that JDK's `jdk.jshell` API and talks to Thoreau over stdin/stdout.
- **C: the `jdk.jshell` API in Thoreau with a remote execution JVM.** The API compiles with Thoreau's runtime; it has no
  way to run another JDK's compiler, and the packaged runtime has no JShell at all.

## Decision
Option B, in the new module `jshell` (Java family, next to `java`). Public API in the root package `dev.sluide.jshell`
(`JShellSession`, `EvalEvent`, `SessionEvent`, `Completions`, `JShellStartException`, `AgentLauncher`, `JShellJdks`),
the Thoreau side's internals in `dev.sluide.jshell.engine`, the agent in `dev.sluide.jshell.agent`.

**Protocol.** One JSON object per line, UTF-8, offsets in UTF-16 code units of the whole document, lines 0-based. Requests:
`evaluate {id, text, rangeStart, rangeEnd}`, `complete {id, text, caret}`, `stop`, `close` (the end of stdin does the
same). Messages: `ready {javaVersion, javaHome}`; per evaluation `snippetStarted {start, end, line}`,
`output {text, error}`, `value {feedback}` (JShell's feedback: `x ==> 5`, `created method f(int)`), `diagnostic {start,
end, line, message, error}`, `thrown {text, line}` (frames of session snippets as `line N`), `stopped`, `finished`;
`completions {anchor, items: [{text, smart}]}`; `error {message}` ends the request it answers (busy, bad request,
internal: an evaluation then gets the message as error output and `Finished`, a completion fails); output with `id`
null after `finished` (a thread the snippet started) goes to the session listener; `ended {reason: exit | closed,
exitStatus}`. The agent splits the text line by line as the `jshell` tool reads a script (`analyzeCompletion` per
line, two snippets on one line stay two) and runs the selected snippets one `eval` at a time; a caret runs the snippet
containing it, else the last one ending before it, else the first.

**Threads.** Agent: `main` reads stdin and handles `stop` at once; evaluations run on `agent-eval`, completions on
`agent-complete` (also while a snippet runs: 4000 concurrent completions during 40 evaluations failed none); one
synchronized writer; the agent's own `System.out` goes to stderr. The agent is not busy any more before it sends
`finished`, so a client may send the next evaluation as soon as it reads it; `ended exit` comes after the running
evaluation's `finished`. JShell drops a `stop` that comes while the snippet still compiles, so the agent sends it again
every 100 ms until the evaluation ends. Thoreau: requests written by the calling thread under one lock; one reader
thread (`jshell-reader`, `NamedThreads`) dispatches by id to the evaluation's listener, the completion futures and the
session listener, and logs what a listener throws; one thread (`jshell-stderr`) keeps the last 8 KiB of stderr (every
JVM here prints "Picked up JAVA_TOOL_OPTIONS").

**Lifecycle.** `JShellSession.start` waits up to 30 s for `ready`; an agent that dies before it (a runtime without
`jdk.jshell`: `NoClassDefFoundError`) throws `JShellStartException` with the stderr tail. A hidden start snippet runs in
the execution JVM: a watchdog that halts it when the agent is gone (without it a killed agent left the execution JVM
running at 95% CPU; with it the JVM was gone after 0.2 s) and a handler of the `java.lang.Runtime` logger that reports
`System.exit`'s status (JDK 21+, null before). `System.exit(3)` → `finished`, then `ended exit 3`, and the agent exits.
An exit from a thread after its evaluation ended can be noticed before its log line arrives: then its status is null.
`close()` closes the agent's stdin, waits 2 s, then kills the agent and its descendants; Thoreau dying closes the stdin
too, and the agent exits. The agent's output ending without `ended` is `Ended("crash")` with the stderr tail.

**Output coalescing.** 100,000 printlns arrive as 100,000 `output` messages within 0.4 s. The reader joins consecutive
`output` messages of one evaluation and stream that are already buffered when it takes the first (`BufferedReader.
ready()`), up to 64 KiB per delivered event; one larger message is delivered whole.

**JDK choice.** `Jdks.hasJShell`: major 9 or newer and the `release` file's `MODULES` lists `jdk.jshell` (a jlinked
runtime without it passes `Jdks.read` but cannot run the agent; no `MODULES` line counts as a full JDK).
`JavaHomes.resolve` takes an `accept` predicate (default all, so jdtls is unchanged); a rejected JDK is skipped like an
invalid one with "… has no JShell (JDK 8); using …". `JShellJdks` keeps a file's chosen JDK in its data (field `jshell`,
`{"jdk": "<home>"}`, moved with the file by the store) and falls back to the chain with a problem when it is gone.

**Agent build.** The agent stays Kotlin. Its sources live in `src/main/kotlin/dev/sluide/jshell/agent/`, so the unit
tests and the mutation runs see them (compiled with the module for Java 25, left out of the module jar). A second
compilation (`agent` source set over the same files) builds them with `-jvm-target 9 -Xjdk-release=9`: a low jvmTarget
alone let JDK 16 API through in the spike. `agent.jar` (≈40 KB) and kotlin-stdlib (≈1.85 MB, the catalog's Kotlin
version, through the `agentStdlib` configuration) are resources of the module jar (`jshell-agent/`), so they reach the
fat jar. `AgentFiles.install` copies them to `~/.sluide/jshell/<first 16 hex digits of their SHA-256>/` (temporary file
and atomic move, so two windows installing at once both end with whole files) and returns the class path. Older
folders stay: another running Thoreau may use them.

**JSON.** The agent has its own small codec (`agent/Json.kt`, nesting limited to 16, malformed lines answered with an
error): it runs on JDK 9 with nothing but kotlin-stdlib. Thoreau's side reads and writes with Jackson, which the project
data already uses; sharing the codec as source would put agent code into the Java 25 API surface for no gain.

**Tests.** The recordings of the spike (and six of the module's agent: killed, killed-running, complete-error,
thrown-no-line, halt, no-jshell.stderr) under `src/testFixtures/resources/jshell/` are the contract: `FakeAgent` replays them
to the session, and `AgentRecordingsTest` checks that the agent, run in the test JVM with a real execution JVM, answers
each recording's requests with its messages. `JShellRealTest` runs the real agent process on the JDKs
`SLUIDE_TEST_JSHELL` names.

## Consequences
- About 650 ms to the first result (≈460 ms to `ready` with the start snippet, ≈200 ms for the first evaluation); about
  1.9 MB on disk per agent version.
- JShell needs JDK 9 or newer with `jdk.jshell`. Measured on 21 and 25; JDKs 9 to 20 are not measured (the agent
  compiles against JDK 9's API, `System.exit`'s status is null before 21).
- The execution JVM inherits Thoreau's environment and working directory; its class path is the JDK only.
- The packaged app's own runtime has the JShell modules and `bin/java` (ADR 0011, amendment), so it is the last JDK
  fallback.
- A version change of JShell's feedback or completion lists shows in `AgentRecordingsTest` on the build JDK; record again
  then (`docs/testing.md`, "Fakes of external servers").

## Amendment (2026-11-21): the module is in both families
`jshell` is in the Java family (it runs on the JDKs the Java support resolves: `JShellJdks` takes `JavaHomes` and
`Jdks`) and in the editor family: its `.jsh` view is a file view (`JShellFileViewer`, a public `SourceFileViewer`
around the public code editor, `SourceSplit` and `WrappingToolbar`), the same shape as `shell-scripts`. So it depends
on `java`, `project-data` and `file-viewers`, and the family rules of `docs/architecture.md` ("Module rules") name it
in both lists. The alternative, a separate module for the view, would split one capability in two for no gain: the
view is the only user of the session, and `ide` wires both.
