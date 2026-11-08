# 0148. Diagnostics, quick fixes, workspace edits and semantic highlighting

- Status: accepted; amends [0145](0145-code-intelligence-module.md) (`CaretPopup` `choosable`,
  `DocumentBinding.bindingOf`); see [0141](0141-editor-api-for-language-features.md),
  [0142](0142-language-server-foundation.md), [0144](0144-python-via-ty.md), [0115](0115-converting-tool.md),
  [0084](0084-testability-logic-out-of-controls.md)
- Date: 2026-11-08

## Context
With the document binding in place ([ADR 0145](0145-code-intelligence-module.md)), the server's findings have to reach
the editor: errors and warnings in the text, a way to apply the fixes the server offers, and the colours only the
server can tell (a parameter, `self`). ty pulls rather than pushes diagnostics (it registers `textDocument/diagnostic`
dynamically and asks for a refresh after file events), answers quick fixes with `changes` edits (add an import, an
ignore comment; checked against ty 0.0.99), offers only `ty.printDebugInformation` as command and sends semantic tokens
in full only, with custom types `selfParameter`, `clsParameter` and `builtinConstant`. Rename (next round) will change
files that are not open. Answers arrive while the user types, so each must be matched to the text it was made for.

## Decision
- **Diagnostics sources** (`diagnostics/DocumentDiagnostics`, `DiagnosticsState`): both LSP sources, shown together.
  Pushed sets (`publishDiagnostics`) replace the pushed ones and are dropped when they name an older document version.
  Pulls (`textDocument/diagnostic`, when the server supports it, statically or registered later) go out after open,
  300 ms after the last change (a constructor parameter, through a `RequestSlot`), on `workspace/diagnostic/refresh`,
  after a restart (`onConnected`, then the features listener) and after a move; with the registration's `identifier`
  and the last `resultId` as `previousResultId`. An answer that arrives after the document version changed is dropped
  and asked again after the delay; `ServerCancelled` and `ContentModified` errors are asked again too. A full report
  replaces the pulled ones; an unchanged report places the last full report's diagnostics again on the current text
  (the server says they are its answer for it). When the server stops running (crash, restart, window) every
  diagnostic goes with the result id, so a gone server's findings never look current; a server that stops answering
  pulls takes the pulled ones with it; a move starts the document anew. Related documents of a report are ignored.
  The overlay is set only when its ranges change.
- **Placement:** diagnostics are converted to editor offsets when they arrive (on the document's copy, one line index
  for all) and from then on follow the edits as the editor's following overlay does (unchanged before, moved after,
  dropped when touching an edit), so the hover, the gutter and the quick fix request see where they are now. An empty
  range covers the character after it, or before it at a line's end; nothing on an empty line. The diagnostic's own
  range follows too: a quick fix request sends the diagnostics with their ranges in the current text.
- **Display:** overlay layer `ci-diagnostics` with `followEdits`; classes `diagnostic-error` (red wavy underline),
  `diagnostic-warning` (amber wavy), `diagnostic-information` (blue dotted), hints none; tag `Unnecessary` adds
  `diagnostic-unnecessary` (opacity 0.5), `Deprecated` `diagnostic-deprecated` (strikethrough). A diagnostic without
  severity counts as an error. Colours in `diagnostics.css` with a dark block, added with `addStylesheet`.
- **Gutter:** one 12 px column per editor for its life (columns cannot be removed, and a file renamed back and forth
  binds again), kept by the feature in a `WeakHashMap` by editor and pointed at the current binding. Each line shows
  the icon of its worst diagnostic (hints none) with the messages as tooltip; the markers are worked out lazily after
  a change, and `refreshGutter` runs only when a shown line's marker changed.
- **Hover:** a `HoverProvider` listed before the server's hover gives one section per diagnostic at the offset:
  severity icon, message (wrapped, line breaks kept), `source · code` and the `codeDescription` link as dim text.
- **Quick fixes** (`quickfix/QuickFixes`): Alt+Enter (an editor key binding, free in the editors) and "Show Quick
  Fixes" in the context menu (disabled with "(server not running)" or "(not offered by the server)") ask
  `textDocument/codeAction` for the selection or the caret with the diagnostics overlapping it (at a caret: those it is
  in or at either end of), `only` unset, trigger kind Invoked. The `CaretPopup` lists preferred actions first, then
  the others, then the disabled ones with their reason; `CaretPopup` gets `choosable`, so Enter on a disabled one keeps
  the popup. Taking one resolves it first (`codeAction/resolve`) if it has neither edit nor command, then applies its
  edit through the workspace-edit applier and, if that succeeded, runs its command (`workspace/executeCommand`). A
  document changed since the request refuses the fix ("The text changed. Ask for the quick fixes again."); a refused
  edit runs no command. Failures show as a message at the caret, naming the files changed when only a closed file's
  write failed after the open ones were edited.
- **`workspace/applyEdit` stays refused** ([ADR 0142](0142-language-server-foundation.md)): ty's quick fixes carry
  their edits and it has no command that edits, so no client handler is needed this round.
- **Workspace edits** (`edits/WorkspaceEdits`, also for rename): `documentChanges` if present (LSP prefers them), else
  `changes`; creating, renaming and deleting files, snippet edits, URIs that are no local file and several
  `TextDocumentEdit`s of one file (LSP applies them one after the other on the changed text) refuse the whole edit.
  Everything is checked before anything changes: a `TextDocumentEdit`'s version must equal the bound document's (a
  version for a file that is not bound, open or closed, refuses too), every position must lie in its file's text (a
  character past its line is the line's end, the line after the last one the end of the text) and must not split a
  character outside the Basic Multilingual Plane, edits must not overlap (inserts at one offset keep their order).
  Lines are found once per text. Then per file, sorted and applied from the end:
  - a file bound in this window (`CodeIntelligence.bindingFor`, reached through `DocumentBinding.bindingOf`; by path,
    else by real path, as servers may name `/private/var/…` for `/var/…` on macOS): one `applyEdits` call, so one
    undo step, on the binding's own text;
  - a file open in another editor of the window (`FileViewHost.currentText`): `host.editText`, unsaved, only if its
    text equals the file on disk (compared off the FX thread, line breaks and BOM aside): unsaved text the server never
    saw refuses ("has unsaved changes the server does not know");
  - a closed file (library code too is refused while it is read-only, by real paths,
    [ADR 0150](0150-library-code-read-only.md)): only inside the window's project folder (by real paths; "outside
    the project" otherwise; P9's preview may later list such files and ask), read off the FX thread (writable, UTF-8 strictly, a BOM kept, at most
    10 MiB, else refused), edited with positions over `\r\n`, `\r` and `\n` and the new texts' line breaks in the
    file's own (its first one), and written atomically off the FX thread through its real path (temp file beside it,
    `ATOMIC_MOVE`, permissions kept, after `writeAtomically` of [ADR 0115](0115-converting-tool.md)); a file whose
    bytes changed since it was read, or that became read-only (the move would replace it anyway), is not written.
    Then `languageServers.filesChanged(CHANGED)`.
  The open files are recorded on the FX thread before the files are read (a bound editor's document version, another
  editor's text) and checked again when the reading is done: typing in the meantime would move the text under
  versionless edits (`changes`, which ty's quick fixes and rename use), so any change, and a closed file opened in
  the meantime, refuses the whole edit. The result lists the files changed, the number of edits and the failures; a
  refusal changes nothing, a write that fails after the open files were edited is reported as a failure of that file.
  Unexpected errors on the file thread are logged and reported the same way.
- **Semantic highlighting** (`semantic/`): when the server answers `textDocument/semanticTokens/full`, the whole
  document's tokens after open, 500 ms after the last change (latest wins), on `workspace/semanticTokens/refresh` and
  after a restart; an answer for an older document version is dropped and asked again, and the colours go when the
  server stops running. Tokens are decoded with the legend of the server that answered (relative positions, modifier
  bits) and mapped: `parameter` → `sem-parameter`, `selfParameter` and
  `clsParameter` → `sem-self`, `builtinConstant` → `sem-builtin-constant` (the keyword colour), modifier `deprecated`
  → `sem-deprecated` (strikethrough); everything else keeps the lexical colours. Overlay layer `ci-semantic` with
  `followEdits`; subtle colours with a dark variant in `semantic-tokens.css`. No setting to turn it off yet.
- **Testability** ([ADR 0084](0084-testability-logic-out-of-controls.md)): the rules are pure functions and plain
  classes (`DiagnosticsState`, `widened`, `followEdit`, `lineMarkers`, `diagnosticsAt`, `diagnosticsFor`,
  `hoverContent`, `withRange`, `quickFixesOf`, `quickFixFailure`, `checkEdits`, `TextLines`, `lineEndingOf`,
  `planOf`, `decodeTokens`, `semanticClasses`); `WorkspaceEdits` works on an FX-free `WorkspaceEditTarget` with the file work on an injected
  executor; delays are constructor parameters.

Rejected:
- **Keeping diagnostics in LSP positions:** after an edit they point at the wrong text until the next answer; placed
  offsets that follow the edits agree with the overlay.
- **Applying open and closed files without a joint check:** a stale version in one file would leave the others
  changed; every file is checked first.
- **`host.editText` for bound editors too:** it replaces the whole text and cannot check the document version.
- **A client handler for `workspace/applyEdit`:** nothing in ty needs it; it comes with the first server that does.

## Consequences
- Python editors show ty's errors, warnings and unused names as they type, a gutter column (12 px, also without
  diagnostics) and the diagnostics first in the hover; Alt+Enter applies ty's imports and ignore comments.
- Rename can reuse the applier for files that are not open; a closed file in a project open in another window is
  written on disk and that window's editor reloads it as it does for any change on disk. Files outside the project
  (a library in `.venv` is inside it; a stub elsewhere is not) and open editors with unsaved changes refuse the edit
  until P9 decides how to ask.
- A file with mixed line breaks gets new lines in its first kind; its other lines stay as they are.
- Listed in [../index.md](../index.md).
