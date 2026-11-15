# Find in Files: ideas left for later

Follow-ups of [ADR 0177](../adr/0177-find-in-files.md).

- **Replace in Files.** Replacing across files with a preview; Shortcut+Shift+R is taken by Git → Review Branch, so it
  needs another key.
- **Pinning results to a panel.** Keeping a result list open beside the editor instead of the popup that hides.
- **Live re-run on file changes.** Searching again when files change while the popup is open; today it is a snapshot.
- **Editable preview.** Editing the file in the preview instead of opening it.
- **Resizable popup.** A popup the user can resize, remembering its size.
- **Toggle keys.** Keys for match case, whole words and regex while the query field has the focus.
- **Parallel reads.** Reading files on several threads, if a warm search of a large repository takes over a second.
- **Query history per project.** Remembering earlier queries in the project data instead of for the window's lifetime.
