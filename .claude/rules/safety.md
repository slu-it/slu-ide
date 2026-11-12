---
paths:
  - "modules/*/src/main/**/*.kt"
  - "ide/src/main/**/*.kt"
---

# Safety: the user's files, commands and secrets

The rules in short. `docs/safety.md` has the details: the ADR behind each rule, the functions to copy, the decided
exceptions and the known deviations. Read its section before writing code that touches one of these; a feature that
deviates says why in its ADR.

- **Files**: never overwrite or lose a file silently. Moves and renames never replace; a taken name the user typed
  is refused, a generated name is created with `CREATE_NEW` and retried. Replacing a target the app picked asks
  first and writes through a temp file and `ATOMIC_MOVE`. An action that reads a file with unsaved editor content
  asks, or says it uses the saved file.
- **Destructive actions** (delete, remove, reset, drop, prune, kill, force) ask first, name what is lost and that it
  is permanent, and have Cancel as the default button with the focus. Reversible actions do not ask.
- **Running what the project configures**: anything that would run without an explicit click (on open, on a file
  change, on save) registers a `ProjectTrustCheck` and runs only after `ProjectTrust` allows it. Reading or rendering
  project files is not running them.
- **Commands**: values from the user or the project never become shell code. Background CLI calls get argument
  lists, are never interactive, have a timeout or a cancel, and never run on the FX thread; commands shown in a
  terminal quote every value.
- **Secrets**: never log secrets, stdin, HTTP bodies, headers or query strings, and never show or log remote URLs
  (they can carry credentials). Show secret values masked by default. A stored secret is opt-in per value and goes
  through `Secrets`; the key is read off the FX thread.
- **Network**: fetching from remotes, registries or the web is an explicit action; nothing is fetched in the
  background or on open, except the decided cases listed in `docs/safety.md` (remote resources in the Markdown and
  HTML previews, the Gradle wrapper download). A new exception needs an ADR. Local servers bind `127.0.0.1` with a
  token in the path.
- **Untrusted input** (archives, documents, HTML, XML, CSS, markup in user files) is parsed with limits on what is
  actually read and with XML DTDs and external entities off. Escape every value written into HTML, CSS or an
  attribute, also the ones that look safe; pages rendered from untrusted documents run only Thoreau's script and
  load nothing remote (ADR 0165). Test with hostile input in every value the code writes (quotes and markup, remote
  URLs in every spelling, deep nesting, oversized and lying archives).
