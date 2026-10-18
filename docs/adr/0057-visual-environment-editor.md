# 0057. Visual editor for the HTTP client's environment files

- Status: accepted
- Date: 2026-10-18

## Context
`http-client.env.json` and `http-client.private.env.json` ([ADR 0025](0025-http-client.md),
[ADR 0028](0028-http-client-secrets.md)) were edited as plain JSON. Variables can be nested objects and lists, OAuth2
configurations (`Security.Auth.<id>`) have a fixed set of keys whose secrets belong in the private file, and client
certificates (`SSLConfiguration`) are read from the private file only. Writing them means knowing the format by heart
and which file each value belongs in. The two files form one set of environments: the private one is deep-merged over
the public one, key by key.

The file viewers' public API ([ADR 0026](0026-http-client-window-session.md)) had no way to change an editor's text, to
claim exact file names over a viewer for their extension ([ADR 0029](0029-file-name-and-shebang-matching.md): only
code viewers could match names), or to reach another file's editor.

## Decision
- **A split view for both file names:** the JSON source of the opened file and a visual editor beside it (Source,
  Split, Visual; Split first). The visual half shows both files merged, so opening either file shows the same form.
- **A tree table with one column per environment** (a matrix, so environments are compared side by side): rows are
  the merged key tree, objects and lists expandable, a type per row (Text, Number, Yes/No, Object, List; Mixed when the
  environments disagree, then read-only in the form) choosing the value editor; "—" marks a value not defined in that
  environment. Sections: Variables, one Auth section per OAuth2 configuration with its fixed fields (those not used by
  a column's grant type greyed), Client certificate. Rejected: one environment at a time (no comparison), dotted flat
  paths (keys with dots, lists), raw JSON fields for objects (not visual).
- **A lock per value says which file holds it.** Toggling it moves the value. Client Secret and Password start
  private, certificate settings are private only, everything else starts public. Private values are masked unless
  Show values is on (off on every open, like `.env` files).
- **The model works on the two texts, not on the disk:** a pure `envedit` model parses both and turns every operation
  into new texts. Form edits re-serialize the changed file: 2-space indentation, key order and number spelling kept,
  new keys appended; a file formatted differently is reformatted by its first form edit. Invalid JSON in either file
  makes the form read-only until it is fixed. Top-level `$` keys and what the form cannot show stay untouched.
- **Nothing is written behind the user's back.** The opened file changes through its code editor (one undo step,
  dirty marker). Changes of the other file go into that file's tab, opened unselected if needed, and stay unsaved. The
  other file's text is read from its tab (with unsaved changes) or from disk, again whenever the view is shown. A
  missing other file is created as `{}` when the first value for it is written.
- **File viewer API extended:** `TextEditableView` (`text`, `replaceText`, a minimal replacement that is one undo
  step), implemented by `CodeEditor` and plain code views; `NamedFileViewer` (exact file names, matched like a code
  viewer's names, so it wins over `JsonFileViewer`); `FileViewHost.currentText` and `FileViewHost.editText` for
  another file's tab.

## Consequences
- Secrets end up in the private file without the user knowing the format; the view warns, as `.http` files do, when
  git would commit the private file.
- A form edit reformats a hand-formatted file; whitespace-only differences show up in its diff once.
- Edits for the other file appear as an unsaved tab, which must be saved separately.
- Added to [../index.md](../index.md).
