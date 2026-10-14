# 0008. Settings in their own module, stored in a properties file

- Status: accepted
- Date: 2026-10-13

## Context
Users want to configure SluIde, starting with fonts for the file explorer, the editor and the terminal. Settings are
read by several feature modules, changed in one dialog, and must survive a restart. More settings pages will follow.

## Decision
- A module `modules/settings` (package `dev.sluide.settings`) holds the settings state and the settings dialog.
  Feature modules that use a setting depend on it via `api` (`file-explorer`, `editor`, `terminal`); `settings`
  depends on no other module.
- Each kind of setting is a Spring bean with read-only JavaFX properties, like `ProjectService`; views bind to them,
  so changes apply live. `FontSettings` is the first.
- Values are stored by `SettingsStore` as key/value pairs in `~/.sluide/settings.properties`: plain text, easy to
  inspect, back up or delete. Writes go to a temporary file that is then moved into place. Unusable values fall
  back to defaults; a file that cannot be read at all (e.g. a malformed `\u` escape) counts as no settings, is
  logged, and is never replaced, so a later write cannot drop the settings in it.
- Keys and formats: `theme=LIGHT|DARK|SYSTEM`; per font area (`explorer`, `editor`, `terminal`)
  `font.<area>.family` (a family name), `font.<area>.size` (a decimal number, e.g. `13.0`) and `font.<area>.style`
  (`REGULAR`, `BOLD`, `ITALIC` or `BOLD_ITALIC`). Keys the IDE does not know are kept when it writes the file.
- A setting is changed only after it is stored. When storing fails, the dialog applies the other pages and shows
  one error message for the failed ones; OK keeps the dialog open, so the edits are not lost. The file is written
  synchronously on the FX thread: it is tiny, and a slow or failing home folder shows up right away.
- Not `java.util.prefs`: it stores in the Windows registry or macOS plists, is hard to inspect, and has no single
  file to back up or reset.
- The dialog is built from `SettingsPage` beans: each page names its place in the tree (e.g. Display → Fonts) and
  applies its own edits. New pages need no change to the dialog.

## Consequences
- Adding a setting means adding a property to a settings bean (or a new bean) and a page; the views that use it bind
  to the property.
- Settings are per user, not per project.
- Edits stay drafts in the page until OK or Apply, so pages can show previews without touching the IDE.
