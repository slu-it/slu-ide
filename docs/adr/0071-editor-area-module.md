# 0071. The editor area module

- Status: accepted
- Date: 2026-10-21

## Context
The module `editor` (package `dev.sluide.editor`) holds the editor area: the tab strip, the open tabs and their
`EditorTab` contract, saving and close confirmations ([ADR 0004](0004-file-viewer-selection.md)). The editor a user
types in, the code view with highlighting, keys and find, lives in `file-viewers` (`CodeEditors`). The name sent
readers, people and agents alike, to the wrong module, and it did not match its main class `EditorArea`.

## Decision
The module is `editor-area` (`modules/editor-area`, `:modules:editor-area`), its package `dev.sluide.editorarea`.
Classes, CSS style classes (`editor.css`) and behaviour are unchanged; only the module, package and resource folder
move.

## Consequences
- The editor family of [ADR 0047](0047-module-dependencies-and-shared-modules.md) names `editor-area`; its edges stay
  the same (`editor-area` -> `file-viewers`, `terminal` -> `editor-area`).
- Older ADRs keep the name `editor` as a record of their time; current documents use `editor-area`.
- Listed in [../index.md](../index.md).
