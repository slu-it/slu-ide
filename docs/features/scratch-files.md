# Scratch Files

**Module:** `modules/scratches` (package `dev.sluide.scratches`), wired in `ide` by `WindowScratches`
([ADR 0066](../adr/0066-scratch-files.md))

Files for notes and experiments outside the project, shared by all project windows, as in IntelliJ.

- **Create:** File → New Scratch File… (Ctrl+Shift+N, Cmd+Shift+N on macOS; also from a terminal tab) shows the type
  picker near the top of the window: a filter field above the types, each with its file-type icon, name and
  extension (Plain Text, Markdown, JSON, YAML, XML, HTML, CSS, JavaScript, TypeScript, Kotlin, Kotlin Script, Java,
  Python, Shell Script, SQL, HTTP Request, TOML, Properties, CSV, Mermaid, KQL, Bicep).
  - Typing filters by name or extension, ignoring case; a leading dot is ignored (`.kt` finds Kotlin). Up and Down
    move the selection while the focus stays in the field; Enter or a click picks a type. Escape or a click elsewhere
    closes the picker without creating anything. At most 12 rows are shown, the rest scroll.
  - The type picked last is selected when the picker opens again (until the app ends).
- **Naming:** no dialog. The first file of a type is `scratch.<ext>`, further ones `scratch_1.<ext>`, `scratch_2.<ext>`
  …, always the lowest free number; another window creating one at the same moment gets the next.
- The new file is shown in the explorer (the Scratches node expanded, the file selected) and opened in a tab with the
  focus in it. If it cannot be created (e.g. the folder is not writable), an error says so and nothing opens.
- **Where:** `~/.sluide/scratches`, one folder for all windows, created when a window opens a project.
- **The Scratches node:** every window's explorer shows the folder as a second top-level node "Scratches" below the
  project, collapsed until opened, with the full path as its tooltip ([file-explorer.md](file-explorer.md)). Files in
  it open, rename, delete and are created like any other (New File…, New Folder… from its context menu); the node
  itself cannot be renamed or deleted. The node shows a folder with a pencil (`folder-edit`), expanded or not; the
  folders in it show the normal closed and open folder icons. Both are teal instead of the folders' amber, so the
  scratches stand out from the project; files in it keep their type icons. If the folder cannot be created, the node is missing and a warning is
  logged.
- **Empty scratches are deleted** when their last tab closes, in any window (closing the tab, Close Others/All,
  closing the window): only if the file is empty and no other window has it open. A scratch with content stays until
  you delete it in the explorer.
- **What does not apply to scratches:** they are normally in no repository, so no git status colours, change markers,
  diffs or history, and changes in the folder do not refresh the project's git status. A scratch `.http` file sees only
  the environment files in the scratches folder; the HTML preview of a scratch serves the scratches folder;
  `.editorconfig` is looked up from the scratches folder upwards, not from the project. Renaming or deleting a scratch
  in one window closes its clean tabs in other windows instead of following the rename; tabs with unsaved changes there
  show the file deleted.
