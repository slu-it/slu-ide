# Generated folders: ideas left for later

Follow-ups of [ADR 0158](../adr/0158-hide-generated-folders.md) ([generated-folders.md](../features/generated-folders.md)).

- **Per-folder "always show".** A context menu entry that keeps one generated folder visible without turning the whole
  option off; today only revealing a path shows a folder, until the next toggle, Refresh or Collapse All.
- **Own folder names.** A user list of folder names (or patterns) that count as generated, next to the catalogue.
- **Gradle `layout.buildDirectory`.** Read a changed build directory from the Gradle model instead of guessing
  `build`.
- **Git status for nested projects.** A project nested in a larger repository gets no git status snapshot, so the
  rules alone decide there (generated files in a nested repository are already handled, ADR 0163).
- **Other uses of the classifier.** Language-server watch exclusions (`ProjectWatchOptions`) and library code of other
  ecosystems ([library-code-later.md](library-code-later.md)) could reuse the same rules.
- **More generated files.** AppleDouble `._*`, `Thumbs.db`, `desktop.ini`, tool caches such as `*.tsbuildinfo` and
  `.eslintcache`; only `.DS_Store` is in the catalogue for now ([ADR 0163](../adr/0163-hide-generated-files.md)).
