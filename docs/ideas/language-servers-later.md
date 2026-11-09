# Idea: Language server features left for later

**Status:** idea, collected 2026-11-08 at the end of the round "Python code intelligence via ty on a general
language-server foundation" ([ADR 0141](../adr/0141-editor-api-for-language-features.md) to
[ADR 0148](../adr/0148-diagnostics-quick-fixes-semantic-highlighting.md),
[code-intelligence.md](../features/code-intelligence.md), [python.md](../features/python.md)); nothing decided or
built. Each needs a decision of its own.

## Editor features
- **Inlay hints** (types inline). The foundation already accepts `workspace/inlayHint/refresh`
  ([ADR 0142](../adr/0142-language-server-foundation.md)), but nothing asks for hints. RichTextFX's `CodeArea` holds
  plain text, so hints inside a line need a custom segment or document model; hints at the end of the line are the
  cheaper variant. Hover already shows types.
- **A Problems tab** listing the diagnostics of open files or of the whole project. Today they show only in the
  editor (underline, gutter icon, hover). Open files are a view over what the editors already hold; the whole project
  needs the server's pulled workspace diagnostics.
- **Completion details:** commit characters, trigger characters a server registers later (only the static ones are
  followed), and snippets (Thoreau declares no snippet support; a snippet that arrives anyway is inserted as plain
  text) ([ADR 0147](../adr/0147-completion-signature-help-and-docs.md)).
- **A switch for semantic highlighting**; it cannot be turned off yet (ADR 0148).

## Servers and files
- **Formatting through Ruff** as a second language server for Python. ty does not format
  ([ADR 0144](../adr/0144-python-via-ty.md)). The foundation runs one server per window and spec (ADR 0142), so
  several servers for a language need that rule changed and the features to choose or merge between them.
- **More languages on the foundation.** A language is a `LanguageServerSpec` plus, if Thoreau installs the server, an
  installer (ADR 0142, [ADR 0143](../adr/0143-tool-installs.md), ADR 0144). Candidates: Kotlin through
  [kotlin-lsp](https://kotlinlang.org/docs/kotlin-lsp.html) (Alpha; it imports the Gradle build, i.e. runs project
  code, so it belongs behind the trust question, see [code-navigation.md](code-navigation.md)), TypeScript, Java
  (jdtls).
- **Files outside the project folder and scratch files** get no server today (ADR 0142, 0144). Options: a second
  server rooted at the file's folder, or a server's single-file mode.
- **Shebang-only Python scripts** (no extension) are not served: the spec matches by file name only (ADR 0144).
  It would need a look at the first line when the file opens.
- **Watching site-packages of venvs outside the project.** A project's own venv is watched and its folder refreshed
  (removed and added again) after a `pip install` or `uv sync` ([ADR 0151](../adr/0151-workspace-folders-per-project.md)).
  Venvs outside the project (Poetry and Hatch caches, `UV_PROJECT_ENVIRONMENT`, Conda environments per project) are
  not found as project roots, so their packages still need Restart Python Language Server; a per-project interpreter
  setting would cover them. Each watched folder costs a `WatchService` registration (macOS polling, ADR 0142).
- **Removing workspace folders** whose files were all closed: they stay until the window closes or changes its
  folder; a folder goes only when a re-resolution leaves no open document in it (ADR 0151).
- **`workspace/applyEdit` from servers** is refused; ty does not need it (ADR 0142, 0148). It comes with the first
  server that does. Resource operations (create, rename, delete files) in workspace edits are refused too (ADR 0148).
- **A background update check for ty**, opt-in. Today Check for Updates is a click and nothing is fetched in the
  background ([python.md](../features/python.md)); a background check needs an ADR exception to the network rule in
  `.claude/rules/safety.md`.
- **Sigstore attestation verification** of ty downloads (for instance with sigstore-java; the signer identity would be
  Astral's release workflow), on top of the pinned sha256 and GitHub's API digest. ty's releases carry attestations
  (two per archive for 0.0.99, from `GET /repos/astral-sh/ty/attestations/sha256:<digest>`, checked 2026-11-07); the
  library's weight and the exact signer identity are not explored yet.

## Navigation
- **Code intelligence in library files outside the project** (the standard library, a global site-packages, ty's
  typeshed stubs). They open read-only ([ADR 0150](../adr/0150-library-code-read-only.md)) but without code
  intelligence ([ADR 0146](../adr/0146-navigation.md)), so hover and further Go to Definition stop there. Serving
  them needs the project's server to accept documents outside its workspace folder.
- **Back and Forward following file moves and renames.** Places are file, line and column and do not follow edits or
  renames (ADR 0146); following them needs the change stream of closed tabs too.

## Internal
- **Moving the databases console's completion popup onto `CaretPopup`**, so that both share one popup. The Ctrl+Space
  key already matches the console's (ADR 0147).
- **Cost of the following overlays** under a huge Replace All or rename over a document with semantic-token layers:
  every change moves every range of every following layer (ADR 0141). Not measured.
- **Tests that the Gradle, shell script, Dockerfile, Compose and `.gitignore` views forward the column** of
  `showPosition` ([ADR 0141](../adr/0141-editor-api-for-language-features.md)); the forwarding is written, only
  some of the views are covered.
