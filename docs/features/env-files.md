# .env files

**Module:** `file-viewers` — `EnvFileViewer` (a `CodeFileViewer`), `EnvFileView`, `EnvValues`; decision in
[ADR 0030](../adr/0030-masking-env-values.md)

- `.env`, `.env.*` and `*.env` files open as highlighted, editable code; names like `.env.json` go to the viewer of
  their extension ([file-viewers.md](file-viewers.md)). Keys are `property`, values `string`, `export` a keyword. A
  comment starts at a `#` after a blank, as dotenv reads it, so `a#b` is part of the value. Shortcut+/ comments out
  with `#`; ` ```dotenv ` and ` ```env ` fences in Markdown are highlighted the same way.
- **Masked values:** every value is hidden behind a solid bar as long as the value (invisible text, `masked` in
  `syntax.css`, light and dark). A value is the text after the first `=`, without the blanks around it and without a
  trailing ` # comment`; quotes belong to it. A quoted value whose closing quote is on a later line is masked on
  every line up to that quote (`\"` does not close a double-quoted value). Commented-out assignments
  (`# KEY=value`) are masked too, prose comments are not.
- **Revealed:** the line with the caret, while the text has the focus and once the caret was placed (not right after
  opening), so editing is never blind; and the lines of the match the find bar selected. Other matches inside masked
  values stay hidden. Every edit is masked at once, not only after highlighting caught up.
- **Show values** in the tab's toolbar shows all values. It is off whenever the file opens and never stored.
- The text itself is untouched, only its styles change: copy, find, replace, save, undo and the unsaved-changes dot
  work on the real values. Selecting text reveals nothing.
- Not masked: names ending in `.example`, `.sample` or `.template` (`.env.example`, `.env.local.example`), which have
  no toolbar. Renaming a file to or from such a name switches masking.
- The side-by-side diff ([git-diff.md](git-diff.md)) shows the values unmasked.
- Known gaps: continuation lines of multi-line values are highlighted as keys; the bars are square (RichTextFX has no
  background radius).
