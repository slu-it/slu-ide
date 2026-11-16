# Idea: Go to Definition

**Status:** idea, explored 2026-10-21; nothing decided or built. First language in mind: Kotlin.

**Update 2026-11-08:** option 3 (a language server) was taken: the client is built
([ADR 0142](../adr/0142-language-server-foundation.md)) and Go to Definition, usages and Back/Forward work
([ADR 0144](../adr/0144-python-via-ty.md), [0145](../adr/0145-code-intelligence-module.md),
[0146](../adr/0146-navigation.md)), for Python through ty. Kotlin remains open; see
[language-servers-later.md](language-servers-later.md). The text below is the exploration as it was.

**Update 2026-11-16:** Kotlin is put to rest after a spike of kotlin-lsp 271.2085.0, mainly because its builds report
an EAP licence that runs out a few weeks after release, so a pinned version would need an update about every month.
Findings and what would reopen it: [Spike of kotlin-lsp](#spike-of-kotlin-lsp-2026-11-16) at the end.

Knowing whether a piece of code is a function declaration, a call, a variable declaration or a use of it, and
navigating with Cmd+click (Ctrl+click on Linux) from a use to its declaration, e.g. from a function call to the
function.

## Where we stand

- Highlighting is lexical only: Kotlin goes through RSyntaxTextArea's `SYNTAX_STYLE_KOTLIN` lexer behind our
  `SyntaxHighlighter` (`RSyntaxHighlighter`, [ADR 0005](../adr/0005-syntax-highlighting.md)). It tells identifiers from
  keywords and strings, but not a declaration from a use.
- Input is no obstacle: code views have their own key and mouse handling ([ADR 0022](../adr/0022-editor-key-bindings.md)),
  and RichTextFX turns a mouse position into a text offset (`hit(x, y)`). "Shortcut held + hover underlines the word,
  click jumps" is plain UI work.
- Missing: `FileViewHost.openFile(file)` opens a file but cannot place the caret in it (Go to Line only works within
  the open file); there is no Back/Forward history of positions.

## Navigation UI (needed by every option)

- Hover underline while the shortcut key is held, click resolves and jumps.
- Opening a file at an offset through the host (caret placed, line scrolled into view).
- Back/Forward through visited positions (Cmd+[ / Alt+←); without it, jumping around gets frustrating fast.
- A small chooser when there is more than one target.
- One small interface behind it, independent of how names are resolved: given a file, its text and an offset, return
  the targets.

## Option 1: one file, from syntax alone

Parse the open file to tell declarations from uses; navigate to local variables, parameters and functions within the
same file.

- Kotlin's grammar (string templates, lambdas, generics, soft keywords) is too large for an own tokenizer as in
  [ADR 0031](../adr/0031-own-tokenizers.md). Candidates: tree-sitter-kotlin through the Java bindings (Foreign Function
  API, fine on JDK 25; a native library per platform, and releases are per platform anyway,
  [ADR 0003](../adr/0003-javafx-platform-jars.md)), or the Kotlin compiler's parser without its resolution step (a heavy
  jar).
- Effort: medium. Precision: good within one file, nothing across files.

## Option 2: project-wide name index (Sublime Text / ctags style)

Index the declarations of all `.kt` files of the project by name; narrow the candidates by imports and package, the
chooser for the rest. Kept up to date through the `FileWatcher`.

- Builds on option 1 plus the index. Effort: medium.
- Roughly right for the project's own top-level functions and classes. Wrong wherever types decide: in `foo.bar()`,
  which `bar` it is depends on the type of `foo`; overloads, extension functions; nothing from libraries or the JDK.
- Honest and lightweight, and the same approach works for every language that has a parser.

## Option 3: real name resolution through a language server (LSP)

An LSP client in Thoreau (e.g. lsp4j); a separate language-server process answers `textDocument/definition`.

- Precision: IDE level, including library sources.
- The client is a one-time investment: Java (jdtls), TypeScript, Python and others follow almost for free, and
  completion, diagnostics, hover and rename can come later.
- Kotlin: JetBrains' official [kotlin-lsp](https://kotlinlang.org/docs/kotlin-lsp.html) supports Gradle and Maven JVM
  projects but is still Alpha (as of the docs of May 2026), [partly
  closed-source](https://cdn.jsdelivr.net/gh/kotlin/kotlin-lsp@main/README.md) and mostly tested with VS Code; recent
  releases of its VS Code extension [ask for region and data-sharing choices on first
  start](https://newreleases.io/project/github/Kotlin/kotlin-lsp/release/kotlin-lsp%2Fv263.4702.0) (unknown whether the
  bare server does). The older community server (fwcd) is superseded by it.
- Hidden costs:
  - managing the server process and its memory (a whole IntelliJ core);
  - keeping it in sync with unsaved edits (the editor already tracks text changes);
  - indexing time before it can answer.
- Project trust: the server imports the Gradle build, i.e. runs the project's build scripts, so it belongs behind a
  `ProjectTrustCheck` ([ADR 0053](../adr/0053-project-trust.md)).
- Embedding the Kotlin compiler's analysis API directly is a worse variant of this option: huge, unstable API, tied to
  one compiler version, Kotlin only.

## Suggested approach

1. Build the navigation UI first, behind the resolver interface; it is needed whichever option wins.
2. Option 2 as the default fits a lightweight IDE; LSP as an optional upgrade where a server is installed.
3. Before committing to LSP: a timeboxed spike, a minimal lsp4j client sending `textDocument/definition` to kotlin-lsp
   on this repository, to measure startup time, memory and behaviour in practice.

## Spike of kotlin-lsp (2026-11-16)

**Status:** put to rest; nothing built. After Java through jdtls ([ADR 0179](../adr/0179-java-via-jdtls.md)), most of
what Kotlin needs exists: the language-server foundation, code intelligence, server tools, build roots, the Gradle and
Maven trust checks, the JDK per project. The spike was to decide how mixed Java/Kotlin builds and monorepos would work.
It ran about 30 minutes of a planned 90 (a run filled the sandbox's disk), so much is unmeasured.

**Setup.** kotlin-server 271.2085.0 (released 2026-11-12; `--version` says `271.2085.0-EAP`; server name "IntelliJ
Language Server by JetBrains"), the standalone linux-aarch64 archive from `download.jetbrains.com` with a `.sha256`
beside it (1.2 GB unpacked, bundles JBR 25.0.4.1). Started as `bin/intellij-server --stdio --system-path=<dir>
--data-sharing=none`, with Thoreau's client capabilities (ADR 0142) and roots-only folders as for jdtls. Scratch
projects: a mixed Java/Kotlin build (Gradle; the measured run used a JPS copy of it, since the Gradle import did not
get to finish), a monorepo with a pure Java Gradle build and a Kotlin Maven build beside it.

**The reason to stop: the licence.** The launcher has `--eula <hash>`, `--license-key`, `--license-server` and a
`license` command. `license status` says "This build does not require a license", but also "eap (Valid), licensed to
kotlin-server EAP User, valid through 2026-12-03 (16 days left)", three weeks after the release. Not measured: what
the server does after that date. If it stops, every pin expires within weeks and Thoreau would have to follow
JetBrains' release cadence (about monthly in 2026) or leave users with a dead server. The archive holds third-party
notices only (168 libraries) and no product EULA (`--show-eula`: "Bundle is malformed: EULA.txt is missing", exit
12); the server is partly closed-source. Whether Thoreau may download it on the user's behalf is open.

**Measured:**
- **No Java features.** In the mixed build every request on a `.java` file was empty: definition (Java→Kotlin),
  hover, completion, references, rename, document symbols, semantic tokens; a Java type error got no diagnostic within
  30 s. The server reads Java sources for Kotlin only. Full Java is a separate JetBrains product ("Java and Kotlin by
  IntelliJ IDEA"; read, not measured), so "mixed build roots go to the Kotlin server for both languages" does not work
  with this server.
- **Kotlin sees Java.** Definition Kotlin→Java works; references of a Kotlin function include the Java call; renaming a
  Kotlin function or property edits the Java callers and getters too. In a mixed build jdtls would stay blind only for
  Java code that refers to Kotlin (today's `· Kotlin sources not visible`, [java.md](../features/java.md)).
- **No folders added later.** The server ignores `workspace/didChangeWorkspaceFolders` (its log: "Notification handler
  for workspace/didChangeWorkspaceFolders is not found"), though it declares `changeNotifications: true`. Only folders
  in `initialize` are imported, right away. Build roots announced one by one (ADR 0179) would need a restart per new
  root, the `projects` initialization option (read in the server's code, not measured) or one server per build root.
- **Diagnostics unclear.** All 125 pull-diagnostic answers of the run were empty, Kotlin files included; whether
  Kotlin diagnostics work at all with Thoreau's client is open. The server never sent `publishDiagnostics`.
- **Import feedback** per folder, better than jdtls's: `intellij/workspaceImportState` with `{folderUri, tool, status:
  SUCCESS|FAILED, message}`, `intellij/importLog` with `started`/`succeeded`/`failed`, `$/progress` "Importing" and
  "Indexing" with percentages. Every import and edit is followed by `workspace/semanticTokens/refresh` and
  `workspace/diagnostic/refresh`. No `workspace/configuration`, no `client/registerCapability` (no file watchers).
- **Capabilities:** incremental sync, pull diagnostics, utf-16, `textDocumentContent` for the schemes `jar` and `jrt`
  (library and JDK sources), its own semantic token legend (`label`, `namedArgument`), no code action resolve, 20
  `executeCommand`s (`decompile`, `exportWorkspace`, ...).
- **Lifecycle:** `initialize` answers in about 1.3 s. After `shutdown`/`exit` the process still ran 60 s later (twice);
  it exits within about 2 s once stdin closes.
- **Cost:** 540 MB resident without a project; `-Xmx2048m` by default (`IJ_JAVA_OPTIONS` honoured); a Gradle daemon
  with 512 MB. The index takes about 300 MB of disk for a three-file project (JDK and stdlib), under
  `$XDG_CACHE_HOME/JetBrains/IntelliJServer/workspaces/<hash of the folder set>/`.
- **Files:** config, logs and local usage-statistics files under `--system-path`; the licence store under
  `~/.config/JetBrains/IntelliJServer/` (the JVM ignores `$HOME`; only the XDG variables redirect it). Nothing written
  into the projects except Gradle's own `.gradle/`.
- **Network:** with `--data-sharing=none` and no region the server runs; the only outbound call was an update check to
  `www.jetbrains.com/updates/updates.xml` carrying a user id and machine hash. Blocked, it logged "Update check failed"
  and kept working.

**Not measured:** Gradle imports to the end, import times and memory on a large build, Kotlin↔Java freshness after
unsaved edits, a window folder holding several builds, loose `.kt` files, library sources, two servers at once,
jdtls and kotlin-lsp side by side, macOS and Windows.

**What would reopen it:** a kotlin-lsp release whose builds don't expire (or a stable, non-EAP channel), and then a
spike of the open points above, diagnostics first. The design it would lead to: kotlin-lsp for `.kt` files only,
jdtls keeps `.java` files in mixed builds; build roots handed over at start (restart on a new root) unless the server
learns `didChangeWorkspaceFolders`.
