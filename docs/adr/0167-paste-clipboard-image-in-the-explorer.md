# 0167. Paste a clipboard image in the explorer

- Status: accepted
- Date: 2026-11-12

## Context
[ADR 0092](0092-explorer-copy-cut-paste.md) made Paste handle only `DataFormat.FILES`. A screenshot or a copied image
region is raw image data on the clipboard, with no file, so Paste did nothing for it.

## Decision
Amends 0092: Paste (Shortcut+V and the context menu item) also works for an image without files.
- **Files win.** With files on the clipboard they are pasted as before, even if an image is also there (the Finder puts
  an icon image beside copied files). Only no files and an image paste the image. A pending cut is dropped as for any
  other content (`checkCut`). The paste target is `ExplorerCopies.pasteTarget`; no new key binding.
- **Paste item** (`ExplorerCopies.pasteItem`): enabled for files or an image, reading "Paste Image" for an image
  without files, "Paste" otherwise. Both, and the cut check, come from one read of the clipboard per menu show
  (`ExplorerClipboard.pasteContent`; the image is asked for only without files): each read is a synchronous query on
  the JavaFX thread, on X11 a round trip to the clipboard's owner, and separate reads could disagree. With a pending
  cut, a right-click that first focuses the explorer (or its window) checks the cut once more from the focus listener
  (ADR 0092), a separate event.
- **Name dialog** (`NameDialog`, title "Paste Image"): prefilled `image-yyyy-MM-dd-HHmmss.png` in local time (an
  injectable `Clock`), validated with `FileOperations.pathProblem` on the final name like New File, so `/` paths with
  missing folders work ([ADR 0094](0094-paths-in-new-file-and-folder.md)) and a taken name is refused. `.png` is
  appended unless the name ends with it in any case; the dialog shows `Saved as “diagram.png”.` in its own label (new
  optional `note` parameter; a dialog with notes keeps their line while empty, since a shown dialog does not grow and
  its buttons were cut off, one without notes has none). Cancel writes nothing.
- **PNG only**, lossless, transparency kept, the image's own pixel size.
- **Threads:** the pixels are read on the JavaFX thread before the dialog (`ClipboardImage`, FX-free ARGB array, kept
  behind `ClipboardFiles` so unit tests need no toolkit); encoding and writing run in the background
  (`host.runInBackground`), the end in `host.dispatch`. Missing folders are created like New File does
  (`FileOperations.createParents`), the file is written with `CREATE_NEW`, never replacing; a write failure deletes
  the file it created. Anything unexpected (an `Error` from AWT's natives, e.g. on a headless JDK with a display) is
  shown as "Could not save …" too, never silent. A name taken meanwhile is an error. An unreadable image (error, zero
  size, no pixel reader) shows "The clipboard image could not be read." and asks nothing.
- After success the file is selected (`host.select`, which expands and reads the folder again) and opened.

Rejected: other formats now (JPEG, WebP need a quality choice, see [ideas](../ideas/clipboard-images.md)); a name
without a dialog (a silent timestamp name is hard to find and rename); a separate menu item (one Paste is what users
expect for whatever is on the clipboard).

## Consequences
Screenshots land in the project in two keystrokes. `ClipboardFiles` gained `hasImage()` and `image()`. A `CREATE_NEW`
race with another process is shown as an error. The delete-after-failed-write path is not unit tested (it needs a
write that fails after the file was created).
