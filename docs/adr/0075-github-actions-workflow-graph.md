# 0075. GitHub Actions workflow graph

- Status: accepted
- Date: 2026-10-22

## Context
GitHub Actions workflows (`.github/workflows/*.yml`) are hard to take in as YAML: which jobs wait for which, what
runs on which matrix, which triggers start them. A graph beside the source, like the Bicep resource graph, shows
that. Drawing it needs GitHub's semantics, not just YAML: `on` in its string, list and mapping forms, `needs` as a
string or a list, matrices, reusable workflow jobs, conditions, and GitHub's own errors for invalid files. A parser
of our own would drift from GitHub's; the user chose exact upstream semantics.

## Decision
- **GitHub's official parser.** `@actions/workflow-parser` (the parser of the Actions VS Code extension, MIT) is
  bundled as one browser script, `vendor/workflow-parser/workflow-parser.min.js` in `previews` (about 278 KB).
  It is **built, not copied**: the build inputs in `previews/src/main/js/workflow-parser/` pin exact versions in an
  npm lockfile with integrity hashes, install with `npm ci --ignore-scripts` and bundle with a pinned esbuild
  (`build.sh`, ES2020, browser platform, no Node built-ins). Its `convertWorkflowTemplate` is `async` (it may fetch
  reusable workflows); our entry (`entry.js`) runs its converters synchronously over the package's internal
  modules, and `check.mjs --compare` checks that the result matches the official function. This amends
  [ADR 0017](0017-webview-previews.md)'s vendoring, which only covered libraries copied as released.
- **Run in a hidden `WebEngine`** (`WebViewWorkflowParser`): one standalone engine without a `WebView`, its page
  holding only the bundle, created on the FX thread when first needed and kept. Calls come from a background thread,
  one at a time, each at most 10 seconds; inputs are passed as call arguments, never written into script text; a
  failure or timeout replaces the engine. Rejected:
  - **GraalJS** in the JVM: 50–70 MB more, interpreter-only on a stock JDK, and Truffle does not run from the
    nested jars of the fat jar without extra work.
  - **Parsing inside the preview page**: every page load would carry the parser, and Kotlin could not shape the
    graph (limits, notes) or test it without WebKit.
  - **An own Kotlin parser**: drifts from GitHub's semantics and error messages.
- **The JS side only translates**: the entry turns the parser's result into our own JSON (schema 1, documented in
  the build inputs' README). Kotlin does everything else in the plain library module `github-actions` (no Spring, no
  JavaFX): `WorkflowJson` decodes the JSON into a model, `WorkflowGraphs` draws it as a Mermaid flowchart with
  escaped labels, size limits and a note line for problems. `previews` implements its `WorkflowParser`.
- **A per-view preview** (`WorkflowPreview`, created by `newPreview`): parsing blocks, so the page says "Reading the
  workflow…" while a background thread parses the saved text, then the view renders again with the graph
  (`Preview.attach`'s rerender), which the preview keeps for that file name and text. Only the latest text counts.
- **Path-matched viewer** (`WorkflowFileViewer`, [ADR 0074](0074-path-matched-file-viewers.md)): `.yml` and `.yaml`
  directly in `.github/workflows`; other YAML stays with the YAML code viewer. It opens in Source, like Bicep.
- **`uses:` is a black box for now:** actions and reusable workflows are drawn as one box with their reference.
  Resolving them (local files first, then remote ones through a git cache) is planned.
- **Amended (2026-10-22): local `uses:` are followed.** Reusable workflows go through the same parser; action
  manifests (`action.yml`) are read in Kotlin with **SnakeYAML** (managed by the Spring Boot BOM, already in the fat
  jar), as a node tree only (`compose`, every scalar kept as text; nothing is constructed, non-standard `!!` tags are
  refused; at most 50 aliases, nesting 50, 1 MB). Guards, as constants with tests: paths confined to the repository's
  root (normalised, then checked and opened as the real path), cycles on the expansion stack, at most 10 nested
  expansions (GitHub's limit), 50 distinct files read per graph, 1 MB per file, and the diagram limits: an expansion
  is drawn on trial and left out whole if it does not fit; later ones are still tried, up to 10 that did not fit (a
  graph costs at most about ten budgets' work), and a called workflow is not even read once its expansion could not
  be drawn. A file is expanded once per graph; other uses say where (`expanded in build / compile`). Parser work is
  bounded too: after the parser throws once, it is not called again for that graph, such a graph is retried and kept
  like one whose own parse failed, and an outdated text stops reading its references. The graph stays cached by the
  workflow's own text; there is no file watching.

## Consequences
- The graph has GitHub's semantics and errors; updating them is a rebuild of the bundle (README of the build inputs),
  and a changed `convertWorkflowTemplate` shows up in `check.mjs --compare`.
- The jar grows by about 280 KB. JavaFX's WebKit runs the ES2020 bundle; the UI tests parse the repository's
  workflows and the fixtures with it and check their error counts and job ids, recorded from a `check.mjs` run.
- A workflow is parsed whenever it is opened or saved (or changes on disk), also while only its source shows. The
  parsing itself runs on the FX thread (WebKit runs scripts only there), about 5 ms for a usual workflow; a script
  that hangs would block the UI, which the timeout cannot stop. The 278 KB script is read from the jar once.
- A parser failure (timeout, engine not starting) shows its note and the text is tried once more right away, with a
  new engine; a second failure is kept with the text. After that, edit the file or reopen it: the view renders
  unchanged text again only when asked to, so saving or reloading it does not retry. GitHub's own errors are kept
  with the text like a graph.
- Mermaid's limits apply (500 edges, 50,000 characters): large workflows are cut, with a note saying so; with
  nested expansions the text estimate counts their deeper indents and longer ids.
- The first workflow parsed creates the engine (a few hundred milliseconds); it stays for the app's lifetime.
