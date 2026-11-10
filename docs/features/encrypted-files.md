# Encrypted files

**Module:** `modules/encrypted-files` (package `dev.sluide.encryptedfiles`: the format, the crypto and the registry
`EncryptedFiles`; the tab in `dev.sluide.encryptedfiles.ui`), decision in
[ADR 0162](../adr/0162-encrypted-files.md)

Text files that are stored encrypted on disk (`.sluenc`) and decrypted only in memory while their tab is unlocked.
The password is stored nowhere: **if it is forgotten, the content is lost**; nothing recovers it.

## Files and types
- The name before `.sluenc` gives the inner type: `notes.md.sluenc` is Markdown, `notes.txt.sluenc` plain text,
  `notes.json.sluenc` JSON and `notes.yaml.sluenc` YAML. A `.sluenc` file opens the same way anywhere (renamed, or
  moved into a project); the extension picks the viewer, and opening a tab neither reads nor decrypts the file.
- Renaming or moving the file keeps its tab, unlocked or not; a new inner type (`notes.md.sluenc` renamed to
  `notes.txt.sluenc`) shows at once in a locked tab, and in an unlocked one after it was locked and unlocked again.
- An inner type without a viewer for decrypted content (e.g. `photo.png.sluenc`) shows "Cannot show .png files."
  instead of a password field.
- The content is AES-256-GCM encrypted with a random key per file, wrapped by a key Argon2id derives from the password;
  the format and the reasons are in ADR 0162.

## Locked tab
- A tab starts locked, with a lock icon on its header ("Encrypted, locked"). It shows a large lock,
  "`<name>` is encrypted.", a password field (it has the focus) and Unlock; Enter unlocks.
- Unlock takes the password out of the field at once, disables field and button and shows "Unlocking…" while the key
  is derived off the FX thread (about 0.2 to 1 s).
- What can go wrong is shown below the button: "Wrong password." (the field is empty and has the focus again),
  "Not a Thoreau encrypted file.", "Written by a newer Thoreau (format N).", "Unsupported encryption parameters.",
  "The file is damaged.", "Not enough memory to unlock." or "Cannot read the file.".
- A locked tab is never dirty; Save and a change on disk do nothing.

## Unlocked tab
- A strip above the content: the open lock, "Encrypted · unlocked", Change Password… and Lock at the right
  (Lock's tooltip "Lock (Shortcut+Shift+L)"). Below it the inner type's normal view: the plain-text code view, or the Markdown, JSON or
  YAML preview with its modes. The header shows no lock icon.
- The dirty marker and Save work as usual; Save encrypts and writes the file atomically (a temp file moved over it),
  so a crash never leaves a half-written file.
- The tab's context menu has Lock and Change Password…, both disabled while the tab is locked.
- **Blocked while unlocked:** the previews' Export menu; every code-editor extension (Read Aloud, Translate, Open in
  Codecs or Converting, code intelligence), which do not attach to confidential content; and the text is not offered
  to other views or windows (the tab is no `TextEditableView`), nor shown as a diff.
- **Not blocked:** Copy to the clipboard (whatever is copied stays in the clipboard), and remote images in the
  Markdown preview: an image from the web is loaded as in any Markdown file, which tells its server that the note was
  opened (not its text). Both are listed on the [ideas page](../ideas/encrypted-files.md).

## Locking
- **Lock** (strip or tab menu) locks at once when nothing is unsaved. With unsaved changes it asks
  "`<name>` has unsaved changes." with Save, Don't Save and Cancel (the default): Save saves, then locks (if the save
  fails, the tab stays unlocked and the strip says why); Don't Save drops the changes and locks; Cancel keeps the tab
  unlocked.
- Locking wipes the view (see below), zeroes the key and shows the locked pane; the field gets the focus if the tab had
  it.
- Closing the tab or the window asks the usual Save / Don't Save / Cancel question first; then the content is wiped and
  the key zeroed. Moving a tab to the other side of the split is not a close and keeps it unlocked.
- **Idle lock:** after N minutes without a key press, mouse press or scroll inside the tab, unsaved changes are saved and
  the tab locks. N is Settings → Editor → Encrypted Files, "Lock after N minutes idle (0 = never)" (0 to 240, default
  10, stored as `encryptedFiles.idleLockMinutes`); it applies on OK or Apply, also to tabs that are open.
  - If the save fails, the tab stays unlocked, the strip says why, and the next try comes one period later.
  - If the file changed elsewhere with another password and the tab has unsaved changes, the idle lock does not save
    (that would ask Replace): the tab stays unlocked and the next try comes one period later. A clean tab locks even
    with the warning.
  - Time spent in another tab or window counts as idle; the Change Password dialog does too, so an idle lock can end
    it ("The file was locked meanwhile.").
- Lock All and the File menu items with their keys follow with the next steps of this work.

## Several windows or tabs on one file
- Each tab locks and unlocks on its own.
- When the file changes on disk (another tab saved it), an unlocked tab decrypts it with its own key first:
  - the key fits: the usual reload, or the conflict banner if the tab has unsaved changes;
  - the key no longer fits (the password was changed elsewhere) and the tab is clean: it locks with
    "Changed elsewhere with another password. Unlock again.";
  - the key no longer fits and the tab has unsaved changes: it stays unlocked and the strip warns. Save then asks
    "Replace `<name>`? It was changed elsewhere with another password; saving writes your version under the password
    you unlocked it with." with Replace and Cancel (the default).
  - A damaged file is handled the same way, with "The file is damaged.".

## Empty encrypted scratches
- A tab closed unlocked whose saved content is empty is recorded (`EncryptedFiles.takeClosedEmpty`, with the file's
  bytes as last read or written), so an empty encrypted scratch can be deleted like an empty plain one. A tab closed
  locked never is: its content is unknown.

## Wiping, and its limits
Best effort, as far as the JVM allows:
- The password is taken from the field as a `char[]`, the field is cleared at once and the array is zeroed after the
  key derivation, also when it fails.
- On Lock and close the inner view is wiped (editor text, undo history, saved text, find state, rendered preview; the
  preview's page loads an empty one), disposed and dropped, and the key's bytes are zeroed.
- Plain bytes are zeroed once used: the encoded text Save encrypts, the plain text decrypted to check a change on
  disk, and the decrypted bytes the view reads (on its next read, on Lock and on close). The text decoded from them is
  a String and stays in memory until the garbage collector reclaims it.
- **Not achievable on the JVM:** Strings cannot be wiped (the editor's text, the password field's text, the preview
  page's DOM), the garbage collector may leave copies, memory may be swapped to disk, and a crash or core dump holds
  what was in memory. Thoreau keeps no heap content in its diagnostics and offers no heap dump.

## Change Password
- **Change Password…** (strip, before Lock, or the tab menu) opens a dialog with Current password, New password and
  Repeat, the strength hint (Weak, OK, Strong), the reason the button is disabled ("At least 12 characters", "The
  passwords differ", "Enter the current password") and the note "The current text, including unsaved changes, is saved
  under the new password.".
- If the file changed elsewhere with another password and the tab has unsaved changes (the strip's warning), Change
  Password first asks the Replace question of Save (Replace or Cancel, Cancel the default); Cancel opens no dialog and
  changes nothing.
- Change Password checks the current password against the file on disk and derives the new key off the FX thread
  ("Changing…", the buttons disabled). A wrong current password shows "The current password is wrong." and the dialog
  stays, with only the current password field cleared.
- Then the tab's text, unsaved changes included, is saved through the inner view (its encoding and line endings stay)
  under a new data key and salt; the tab stays unlocked and is clean, the old key is zeroed, and a warning about a
  change elsewhere goes. If that save fails before the file is written, the dialog shows why and nothing changes (the
  old key stays); an error after the file was written (the view's reload) shows in the strip, as the file is under the
  new password then.
- A tab locked meanwhile (idle lock) closes the dialog on confirm.
- The new file opens only with the new password; a copy of the old file still opens with the old one.

## New Password dialog
`newPasswordDialog(owner, title, work)` (root package `dev.sluide.encryptedfiles`) asks for a password and repeats it (minimum 12 characters, the hint, the warning
"The password is stored nowhere. If you forget it, the content is lost."). Create is disabled until it is long enough
and both fields match; then the fields are cleared, the dialog says "Creating…" and runs `work(password)` on a logged
thread, zeroing the array afterwards. An `IOException` of `work` shows its message and the dialog stays; Cancel
returns `false`.

## Creating encrypted scratch files
Follows with the next steps of this work: four encrypted types in the scratch type picker that use the New Password dialog (above), and
the new file opening unlocked without deriving the key twice (`EncryptedFiles.handOver`).
