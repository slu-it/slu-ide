# 0129. Translate opens a numbered tab each time, with a model of its own

- Status: accepted; amends [0126](0126-translate-tool.md), [0110](0110-tools-menu.md)
- Date: 2026-11-05

## Context
[ADR 0126](0126-translate-tool.md) gave each window one Translate tab and one window-wide model that outlived the tab,
as [ADR 0110](0110-tools-menu.md) does for window tools. Right after the first tests this blocked having two
translations side by side: a new text from an editor replaced the one in the tab.

## Decision
- Every way of opening Translate (Tools → Translate, the code editors' Translate and Translate Selection, the
  explorer's Translate) opens a new tab "Translate N". N counts per window, only increases and is never reused, like
  terminals and sandbox sessions ([ADR 0014](0014-project-windows.md)).
- Each tab has its own `TranslateModel`: text, source, target, quality, Markdown toggle, translation and sentence cache.
  Closing a tab ends its work and drops its text; closing the window ends all.
- A new tab still starts with the source, target and quality last chosen (`TranslationPreferences`).
- `WindowTool.newTabEachTime` lets a Tools-menu tool skip the one-tab rule of ADR 0110: every choice creates and opens
  a tab, and none is remembered or selected.

## Consequences
- Tabs pile up when the user translates often and are closed by hand.
- The sentence cache is not shared between tabs: the same sentence in two tabs is translated twice.
- Each tab that has translated holds a timer thread until it is closed; its worker thread ends after a minute idle.
