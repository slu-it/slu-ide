# 0188. Paste clipboard text in the explorer

- Status: accepted
- Date: 2026-11-19

## Context
[ADR 0092](0092-explorer-copy-cut-paste.md) made Paste take files, [ADR 0167](0167-paste-clipboard-image-in-the-explorer.md)
also a raw image. Text on the clipboard (a JSON reply, a snippet, a table copied from a spreadsheet) did nothing.
[ADR 0187](0187-text-type-detection.md) guesses the type of a text.

## Decision
Amends 0167 and 0092: Paste (Shortcut+V and the context menu item) also works for text, creating a file from it.
- **Precedence: files > text > image.** Text counts only if it is not blank (`Clipboard.getString()`, plain text; an
  HTML-only clipboard has none). The explorer's own Copy (files and their paths as text) stays a file paste; a
  spreadsheet range (text and a rendered image) is pasted as text (TSV); a screenshot (image only) stays a PNG. A pending
  cut is dropped as for any other content (`checkCut`). No new key; target folder as before (`ExplorerCopies.pasteTarget`).
- **One read per menu show** (`ExplorerClipboard.pasteContent`): the files first, the text only without files, the image
  only without non-blank text. `PasteContent` carries the text itself (`text`, `pastesText`), so a paste does not read
  it again; the clipboard may hold megabytes and each read is a query of its owner.
- **Menu item** (`ExplorerCopies.pasteItem`): "Paste" for files, "Paste Text" for text, "Paste Image" for an image;
  enabled for any of them when there is a target. The detected type is not in the label (it would need the detection
  on every menu show).
- **Flow** (`actions/ExplorerTextPaste.kt`, like the image paste): the text and the target folder's path are read on the
  JavaFX thread; the detector (`FileExplorerView.textTypes`, read when the paste runs, since `ide` sets it later) runs
  in `host.runInBackground`; the dialog follows in `host.dispatch`; on OK the write runs in the background and the end
  (an error, or select and open if the folder is still shown) in `dispatch`. The write is shared with the image paste
  (`writePastedFile` in `actions/PastedFiles.kt`): `FileOperations.createParents`, a `CREATE_NEW` stream, a failed write
  deletes the file it created, a taken name is an error ("“x” already exists in this folder."), IOExceptions are logged
  and shown as "Could not save “x”: …", anything unexpected as "Could not save “x”." and never silent.
- **Written as it is:** UTF-8, no change to line endings.
- **Dialog "Paste Text"** (`actions/PasteTextDialog.kt`, beside `NameDialog`): "Name:" holds the base name, prefilled
  `pasted-yyyy-MM-dd-HHmmss` (local time, injectable `Clock`), all selected and focused; "Type:" is a combo box over
  `TextTypes.all` with the file-type icon and "JSON (.json)" per entry, the detected type marked " · detected" and
  preselected, "No extension" last (for Dockerfile, Makefile). The final name is name + "." + extension, always appended
  ("config.yaml" and JSON make `config.yaml.json`); "No extension" keeps the name as typed. The note `Saved as “…”.`
  shows while the name is valid and not blank. Validation (`FileOperations.pathProblem` on the final name, like the image
  dialog) re-runs when the type changes, as the final name may now be taken: a `/` path with missing folders is fine
  ([ADR 0094](0094-paths-in-new-file-and-folder.md)), a taken name and a name ending with `/` are refused. The logic is
  in FX-free functions (`actions/PasteTextName.kt`). Enter is OK, Escape and Cancel write nothing.
- **Host hook:** `ExplorerEditHost.askTextName(initialName, detected, folder): String?` returns the final name; on
  `FileExplorerView` it is a test seam like `askImageName`.
- **Image name:** `pasted-yyyy-MM-dd-HHmmss.png` instead of `image-…` (one timestamp name, `pastedBaseName`, for both).
- **Module:** `file-explorer` depends on `text-types` with `api`, since `FileExplorerView.textTypes: TextTypeDetector` is
  public. `ide` sets it to the one detector that all windows share (the `ClipboardTextTypes` singleton): the built-in
  rules plus Converting's data formats (JSON, YAML, TOML, Properties, CSV, TSV, cURL, HTTP requests, through
  `ConvertingTextTypes`), held to the guards of ADR 0187. Without it the built-in rules alone detect (no JSON or YAML).
- `FileExplorerView.kt` was at 593 lines: the default alert building moved to `ExplorerAlerts.kt` (ADR 0054).
- **File → New Scratch from Clipboard** (`ide`, `WindowScratches`, logic in `ClipboardScratch`), right after New
  Generated Scratch File…, no key binding (Shortcut+Shift+V is the terminal's Paste), also from a terminal tab. The
  clipboard text is read on the JavaFX thread; none or blank shows "The clipboard holds no text." and creates nothing;
  else the same detector as the explorer's runs on a virtual thread and `openText(stage, "pasted", extension, text)`
  creates `pasted.json`, `pasted_1.json`, … (Plain Text gives `.txt`) and opens it. No dialog, no choice of type.

Rejected: the type in the menu label (detection per menu show); a name field with the extension (the combo is the one
place that says the type, so the name never fights it); changing line endings.

## Consequences
Text is two keystrokes from a file. A spreadsheet range now pastes as text where it pasted as an image; a clipboard
with a screenshot and some text pastes the text. `ClipboardFiles` gained `text()`. Not checkable in the sandbox: macOS
clipboard mixes (Excel, Numbers, Finder copies). Ideas left out: [clipboard-text](../ideas/clipboard-text.md).
