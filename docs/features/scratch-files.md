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
- **Generated:** File → New Generated Scratch File… creates a scratch file with values in it, see
  [Generated scratch files](#generated-scratch-files).
- **Where:** `~/.sluide/scratches`, one folder for all windows, created when a window opens a project.
- **The Scratches node:** every window's explorer shows the folder as a second top-level node "Scratches" below the
  project, collapsed until opened, with the full path as its tooltip ([file-explorer.md](file-explorer.md)). Files in
  it open, rename, delete and are created like any other (New File…, New Folder… from its context menu); the node
  itself cannot be renamed or deleted. The node shows a folder with a pencil (`folder-edit`), expanded or not; the
  folders in it show the normal closed and open folder icons. Both are teal instead of the folders' amber, so the
  scratches stand out from the project; files in it keep their type icons. If the folder cannot be created, the node is missing and a warning is
  logged.
- **Moving:** entries can be dragged from the project into Scratches (onto the node or a folder in it) and out of it,
  after a confirmation ([file-explorer.md](file-explorer.md)); open tabs follow. A file moved in counts as a scratch
  from then on: if it is empty when its last tab closes (in any window), it is deleted like any empty scratch. A file
  moved out is no scratch any more and stays when empty.
- **Empty scratches are deleted** when their last tab closes, in any window (closing the tab, Close Others/All,
  closing the window): only if the file is empty and no other window has it open. A scratch with content stays until
  you delete it in the explorer.
- **What does not apply to scratches:** they are normally in no repository, so no git status colours, change markers,
  diffs or history, and changes in the folder do not refresh the project's git status. A scratch `.http` file sees only
  the environment files in the scratches folder; the HTML preview of a scratch serves the scratches folder;
  `.editorconfig` is looked up from the scratches folder upwards, not from the project. Renaming or deleting a scratch
  in one window closes its clean tabs in other windows instead of following the rename; tabs with unsaved changes there
  show the file deleted.

## Generated scratch files
[ADR 0114](../adr/0114-generated-scratch-files.md)

- **Create:** File → New Generated Scratch File… (Ctrl+Shift+G, Cmd+Shift+G on macOS; also from a terminal tab) shows
  the generator picker, a popup like the type picker: a filter field above one list of generators under the headers
  UUIDs, IDs, Secrets and Time, each row with the generator's name and the dimmed name of the file it creates
  (`uuid-v7.txt`).
  - Typing filters by name, group title or file name, ignoring case; a header shows only while its group has
    matches. Up and Down move the selection and skip the headers; Enter or a click on an entry picks it, a click on a
    header does nothing. Escape or a click elsewhere closes the picker without creating anything.
  - The generator picked last is selected when the picker opens again (until the app ends), else the first entry.
- **The file:** a new scratch file named `<name>.<ext>`, then `<name>_1.<ext>`, `<name>_2.<ext>` …, the lowest free
  number as for other scratches, e.g. `uuid-v7.txt`, `password-20-alphanumeric.txt`, `bytes-32-base64url.txt`,
  `time.txt`. It holds the generated text, is shown in the explorer and opened in a tab with the focus; errors as for
  New Scratch File. Since it has content, closing its tab keeps it. There are no count or format options, and nothing
  is inserted at the editor's caret.
- **Values:** every list holds 50 values, one per line. UUID v1, v6 and v7, ULID, ObjectId and Snowflake IDs strictly
  increase within a file. Random values come from `SecureRandom`.
- **Catalogue:**
  - **UUIDs:** UUID v1 (time, random node: a random multicast node, never the MAC address), v4 (random), v6 (time,
    sortable), v7 (Unix time, sortable).
  - **IDs:** ULID, NanoID (21 characters), KSUID, MongoDB ObjectId, Snowflake ID (Twitter's layout).
  - **Secrets:** Password with 12, 16, 20, 32 or 64 characters, each from letters, digits and the symbols
    `!#$%&*+-.:;=?@^_~`, from letters and digits, or digits only; every class of the set occurs at least once. Random
    bytes, 16, 24, 32 or 64 of them, as hex, Base64 or Base64URL without padding.
  - **Time:** Current Time (all formats), one file with labelled lines: epoch seconds and milliseconds, ISO-8601 in
    UTC, local and with the zone, RFC 1123, local date-time, SQL timestamp in UTC, date, ISO week date, ordinal date.
- Not offered (yet): UUID v3/v5, key pairs and certificates, test data such as names or IBANs
  ([scratch-generators.md](../ideas/scratch-generators.md)).
