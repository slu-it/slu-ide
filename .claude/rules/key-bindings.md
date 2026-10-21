---
paths:
  - "modules/*/src/main/**/*.kt"
  - "ide/src/main/**/*.kt"
---

# Key bindings

- `docs/features/keyboard-shortcuts.md` lists every key binding. Adding, changing or removing one means updating
  that page in the same change, along with the feature page that describes the binding.
- Counts as a key binding: a menu `accelerator`, a `KeyCodeCombination`/`KeyCharacterCombination`, an `InputMap`
  entry, a key handler that reacts to a `KeyCode` (e.g. Enter, F2, Delete, Escape in a list or popup), or a mouse
  binding beyond a plain click (e.g. middle-click, Shortcut+wheel).
- Before adding one, look it up on that page so it is not taken in the same context. Menu accelerators apply
  everywhere; in a focused terminal only those the shell does not take (the page's Terminal section).
- Write keys as the page does: `Shortcut+X` for Ctrl/Cmd, with the macOS variant in parentheses if it differs.
