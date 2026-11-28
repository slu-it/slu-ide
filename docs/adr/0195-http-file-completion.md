# 0195. Completion in `.http` files from our own source

- Status: accepted; builds on [0025](0025-http-client.md) (the `.http` subset) and the completion sources of
  [0145](0145-code-intelligence-module.md) (amendment 2026-11-21)
- Date: 2026-11-28

## Context
No language server serves `.http` files, so their editors had no completion. The parser, the variable resolver and the
environment files of `http-client` know everything a completion needs, including what no foreign tool knows: our
`$auth.token("id")` calls and the tags we read.

## Decision
`http-client` gets a completion engine of its own, and `ide` registers it as a `CompletionSource` (another package).
- **API** (root package, `HttpCompletion.kt`): `HttpCompletion.complete(file, text, caret, projectFolder)` returns a
  future of `HttpCompletions(anchor, items)`, items `HttpCompletionItem(label, insertText, detail)`, plus
  `HttpCompletion.triggerCharacters` (`{ $ . - /`). `ide` maps these DTOs to `SourceCompletions`, as
  `JShellCompletionSource` maps JShell's: `http-client` and `code-intelligence` are unrelated feature modules and do
  not depend on each other (ADR [0047](0047-module-dependencies-and-shared-modules.md)).
- **Threads:** called on the FX thread with a snapshot of the text; parsing and reading the environment files run on a
  virtual thread, which completes the future. The future always completes normally: an empty list where nothing
  applies, and for any failure (a broken environment file counts as empty already). Failures are logged at DEBUG by
  class name only, never with a message that could quote a value.
- **Fixed-part rule:** anchors and carets are whole-document UTF-16 offsets; each item replaces `text[anchor, caret)`.
  Let `wordStart` be the start of the run of word characters (letters, digits, `_`; not `-`, `/`, `$`, `.`, `{`, as
  `isWordCodePoint` of the code intelligence) before the caret. Only items whose label starts, ignoring case, with
  `text[anchor, wordStart)` are returned, and the editor filters the rest of the label by the word itself (the
  `filterText` of the 0145 amendment of 2026-11-28, which also gives every source its own trigger characters).
  `Content-T` anchored at the line start offers `Content-…` only; `{{$uu` anchored after the braces offers the `$`
  names.
- **What completes** (exact rules in the KDoc of `ContextDetector`): variables after an unclosed `{{` in request
  lines, URL continuations, headers, header continuations, inline bodies, `< path` lines and `@name =` lines, never in
  comments, tags, separators or scripts (groups in this order, each alphabetical, a label once: the file's `@name`s,
  the names of all environments of the env files that apply with the environments defining them as detail, the
  dynamic variables with an argument hint and `$env.` followed by the operating system's variable names, the
  `$auth.token("id")` and `$auth.idToken("id")` calls of every environment's `Security.Auth`); methods in the first
  word of a request line (or an empty line before the request) and the versions after `METHOD URL `; header names on
  the lines of the header block, from about 40 common request headers and the names used in the file; values of
  well-known headers; tags after `# @`. The context is decided by position (the first line of a block that is no
  comment, tag or `@variable` is the request line, the lines after it up to the first blank line are headers), since
  the line being typed is often still `OTHER` to the parser.
- **Names only:** an item holds names and environment names, never a value of any environment file, public or private
  (tested with recognizable values). Only names `Template` can read back are offered (no whitespace, parentheses or
  braces; auth ids also without `"`, `{{`, `}}`, control characters or U+2028/U+2029). The names of the dynamic
  variables are a list next to the `when` that resolves them, and a test resolves each, so the two cannot drift apart.
- **Braces are paired from the left**, as `Template.parse` does (not by the last `{{`): `{{{` is one open `{{`
  followed by `{`, so nothing is offered. `}}` after the caret behind only spaces or tabs counts as closing, since
  the template trims the inner text.
- **A name of the file and of an environment is one item**: the resolver reads the environment before the file's
  `@variable`, so the item stays in the file group's place and its detail lists the environments first, then
  `@ variable` (`dev, prod, @ variable`).
- **Names that need an argument insert `name(`**, not `name}}`, as `$env.` inserts its dot. Whether a `$` variable
  needs one is read from `DynamicVariables` itself (it gives no value without arguments), not from a second list;
  names with optional arguments (`$random.integer`) keep `name}}`.
- **Environment names from all environments**, not only the chosen one (the user's choice): a variable only one
  environment defines is still useful to find, and the detail says which.

Rejected:
- **kulala-ls** (a language server): needs a Node runtime, does not know `$auth.token` or our tags, and would drift
  from our parser.
- **The IANA header registry** as the header list: mostly headers that never appear in requests; a curated list is
  shorter and better.
- **Env names from only the chosen environment:** the user chose all.

## Consequences
- Completion needs no server and answers in milliseconds from the text and the small env files.
- The header names and values are curated by hand, and so is the order of the common methods; the methods and the
  tags themselves come from the parser's sets.
- Nested names (`{{db.host}}`) are not offered, only the top-level names of an environment, though the resolver also
  reads paths.
- Added to [../index.md](../index.md).
