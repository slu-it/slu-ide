# Soft wrap

**Module:** `file-viewers` (`SoftWrap`, `code/SoftWrapToggle.kt`), registered as a window service in `MainWindow`;
decision in [ADR 0130](../adr/0130-soft-wrap.md).

Long lines in the file editors wrap at the visible edge of the editor while soft wrap is on. The text itself does not
change: no line breaks are inserted, saving writes the lines as they are.

- **One switch for all editors:** turning it on or off in any editor wraps or unwraps every open editor in every
  window at once. It is off by default and remembered user-wide in the field `editor` of `~/.sluide/shared.json`
  (`{"softWrap": true}`; off removes the entry).
- **Toggle it** with the wrap button in the editor's toolbar (pressed while wrapping; a click leaves the focus in the
  text), the **Soft Wrap** check item in the editor's context menu, or **Shortcut+Alt+Z** while the text has the
  focus ([keyboard-shortcuts.md](keyboard-shortcuts.md)).
- **Where the button is:** first in the toolbar row of code and plain-text views and of the `.gitignore` view, which
  always shows now; on the far left of the `.env` view's toolbar, before Show values on the right. Every view that
  embeds a code editor (previews, shell scripts, Gradle, Docker, HTTP client) places it first in its own toolbar and
  hides it while its source side is hidden.
- **Which editors:** the file editors, code and plain text, and the sources of previews. Not the side-by-side diffs,
  read-only outputs (HTTP responses, Docker inspect) or terminals. The text areas of tool tabs (Read Aloud,
  Translate) always wrap.
- In a wrapped line, the line number and the gutter's icons (run, colors, ignore-rule dots) stay at the top, beside
  its first row. Find, go to line, Read Aloud's highlight and scrolling to it work on wrapped lines.

## Known limits
- The Markdown preview follows the source line by line: inside one long wrapped paragraph it does not follow row by
  row.
