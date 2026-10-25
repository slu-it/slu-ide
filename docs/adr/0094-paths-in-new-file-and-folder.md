# 0094. Paths in New File… and New Folder…

- Status: accepted
- Date: 2026-10-25

## Context
[ADR 0024](0024-file-operations-in-the-explorer.md) allowed single names only, so `src/main/kotlin/Foo.kt` took one
dialog per folder. Other IDEs take a path and create the missing folders.

## Decision
- **New File…** and **New Folder…** take a path: `a/b/c.txt` creates the folders `a` and `a/b` that are missing, then
  the empty file; New Folder… with `a/b/c` creates the whole chain. A trailing `/` is ignored in New Folder…; in New
  File… (`docs/notes/`) it creates folders only, opens nothing and selects the deepest folder. A single name behaves
  as before (`FileOperations.nameProblem`).
- **Rename… still takes single names** and refuses `/` and `\` as before; `nameProblem` is unchanged. The path-aware
  sibling is `FileOperations.pathProblem(path, folder, windows)`, with `createPath(folder, path, isFolder, windows)`.
- **Separators:** `/` everywhere; `\` also on Windows only (the flag is a parameter, the real OS the default). Elsewhere
  a `\` stays refused as part of a name.
- **Checks**, live in the dialog (OK disabled while a problem shows): every part is non-empty (so no `a//b`, and no
  leading `/`, which would be an absolute path; on Windows also no `:`, so no drive such as `D:`), not `.` or `..`,
  without NUL and leading or trailing whitespace, and storable by the file system. Folders on the way may exist, also
  as links to folders; one that exists and is not a folder is a problem. The last part must be free (a dangling link
  or a name differing only in case on a case-insensitive file system counts as taken).
- **Creation is top-down**, one folder after the other, taking an existing folder on the way with its name on disk (so
  `Src/Foo.kt` leads to the entry in `src` on a case-insensitive file system, and the selection and the opened tab use
  the real path). If one step fails (e.g. permissions), it stops, the error is shown as for other failures and the
  folders already created stay.
- **Afterwards** the tree expands the folders down to the result, loading the new ones, and selects it
  (`tree/expandTo`, built on `findForReveal`: segment by segment, a folder is read again once if the entry is not there
  yet). A new file is opened.

## Known limits
- On Windows, `nameProblem` (Rename, and single names elsewhere) still accepts `:`, so `file.txt:stream` or `D:foo` can be
  typed in Rename; New File… / New Folder… refuse `:` in every part. This gap predates this ADR.

## Consequences
- ADR 0024's "no nested paths" no longer holds for New File… / New Folder…; it holds for Rename.
- The same name checks run per part, so messages name the part when it is whitespace-only or at the edges.
