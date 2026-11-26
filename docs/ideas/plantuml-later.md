# PlantUML: ideas left for later

What the first version of [PlantUML](../features/plantuml.md) ([ADR 0193](../adr/0193-plantuml-engine.md)) does not do.

- **Local includes through a Thoreau inliner.** PlantUML runs in its SANDBOX profile, which refuses every local file;
  its ALLOWLIST profile leaks (a string-prefix check, includes that bypass the list, reads from sibling folders). An
  inliner of our own would put the included text into the source before PlantUML sees it, confined to the project.
  ADR 0193 records what it has to do: extract `@startuml` blocks (`file!N`, `file!ID`), PlantUML's repeat rules of
  `!include`, `!include_many` and `!include_once`, `!includesub` with `!startsub`/`!endsub`, nested includes relative
  to the including file with the project root checked at each level and cycles stopped, a line map so that errors
  point at the `!include` line, paths built from variables, includes under a false `!if`, `%filename()` and
  `%dirpath()`.
- **URL includes** (`!include https://…`, `!includeurl`): an explicit fetch, which the safety rules allow only as a
  user action (`docs/safety.md`, "Network"), with a cache like the workflow graph's references.
- **The GPL build** of PlantUML: the ELK layout (`!pragma layout elk`), `<latex>`, JCCKit charts and QR codes; a
  licence decision for the bundled jar.
- **Clickable `[[links]]` and click-to-source.** Diagrams are `<img>`s so that nothing in an SVG runs; the SVGs carry
  `<a>` links and `data-source-line` attributes, which an inline, sanitised SVG could turn into links and a jump to the
  source line (as the workflow graph's boxes do).
- **A Graphviz path setting**, for a `dot` that the login shell and the usual folders do not find, and a lookup again
  without a restart after installing Graphviz.
- **Completion** of keywords, skinparams and stdlib include names.
- **Other output formats:** PDF, ASCII art (`-ttxt`), for an export or a text view.
- **`@startdot` and `@startditaa` blocks:** the bundled build fails on `@startdot` and has no ditaa; `@startdot` could
  go to the found `dot` directly.
