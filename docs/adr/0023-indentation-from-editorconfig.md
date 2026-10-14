# 0023. Indentation from .editorconfig

- Status: accepted
- Date: 2026-10-14

## Context
Enter, Tab and Shift+Tab ([ADR 0022](0022-editor-key-bindings.md)) indent: with tabs or spaces, and how many. Many
projects state this in `.editorconfig` files, which other editors and formatters (ktlint for this repository) follow.
Files of projects without one have an indentation of their own, which edits should keep. EditorConfig's glob and
section rules (`[*.{kt,kts}]`, `**`, precedence of nearer files, `root = true`) are easy to get subtly wrong.

## Decision
- **ec4j-core 1.1.1** reads `.editorconfig` (`EditorConfigIndent` in `text-editing`). It is Apache-2.0 licensed, has
  no runtime dependencies (only JUnit for its tests) and implements the EditorConfig specification; ktlint, which this
  build already runs through Spotless, uses it for the same purpose. The version is in `gradle/libs.versions.toml`
  (`ec4j-core`); `text-editing` depends on it with `implementation`.
- **Only indentation is read:** `indent_style`, `indent_size` (also `tab`) and `tab_width`, with missing values
  completed as the specification says (`indent_size = tab` and `indent_style = tab` without a size take the tab
  width; the tab width defaults to the indent size). Unreadable or invalid `.editorconfig` files count as setting
  nothing.
- **Fallbacks per value:** each value not set in `.editorconfig` is detected from the file's text
  (`Indentation.detect`), else taken from the default of 4 spaces. Tabs or spaces is decided by the first character of
  most indented lines (a tie decides nothing); the size of space indentation is the most common increase in
  indentation between consecutive non-blank lines, from 2 to 8 (the smaller one on ties); lines indented by a single
  space (e.g. ` *` in block comments) are not counted.
- **Once per opened file:** `IndentStyle.forFile(file, text)` runs when a `CodeView` is created, since it reads files
  from disk. The style stays for the life of the view.
- **No rewrites on save:** `trim_trailing_whitespace`, `insert_final_newline`, `end_of_line` and `charset` are not
  applied. Saving writes the text as it is, keeping the file's line endings
  ([ADR 0020](0020-editing-and-saving-files.md)); a save must not change lines the user did not touch.

Rejected:
- **Our own `.editorconfig` parser:** the glob semantics and precedence rules are the part most likely to be wrong.
- **The EditorConfig project's Java core library:** an alternative of the same kind; ec4j is the one already used in
  this build's tooling.
- **Detection only, or a setting:** ignores what the project states; a setting is global, while indentation is per
  project and language.
- **Applying the other properties on save:** rewriting whitespace or final newlines changes lines that were not
  edited and shows up in diffs.

## Consequences
- A changed `.editorconfig` affects views opened after the change; open ones keep their style until reopened.
- A file whose indentation cannot be told and that no `.editorconfig` covers is indented with 4 spaces.
- `text-editing` has its first runtime dependency besides the Kotlin standard library.
