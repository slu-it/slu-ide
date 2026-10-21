# 0067. Feature settings live in their module

- Status: accepted
- Date: 2026-10-21

## Context
`settings` is a foundation module that other modules depend on ([ADR 0047](0047-module-dependencies-and-shared-modules.md)).
It still held the terminal scroll speeds and their page, so a foundation module knew about one feature, and a new
feature setting would have grown it further.

## Decision
Settings that belong to one feature, and their pages, live in that feature's module: a settings bean on
`SettingsStore` and a `SettingsPage` bean. `SettingsPages` collects all page beans and sorts them by path, so the
module needs no registration. `settings` keeps the store, the dialog, the `SettingsPage` API and the settings several
modules share (fonts, theme). The terminal scroll settings (`TerminalScrollSettings`, `TerminalScrollPage`) moved to
`terminal`, package `dev.sluide.terminal.settings`. Storage keys (`terminal.scroll.trackpad`, `terminal.scroll.wheel`)
and the order of the pages in the dialog are unchanged.

## Consequences
- Amends the page part of [ADR 0008](0008-settings.md). Listed in [../index.md](../index.md).
- `settings` no longer knows features; a feature adds settings without touching it.
- Tests of the dialog use test-local pages; a feature tests its own page.
- Settings used by several modules still belong in `settings`; moving one there needs a new decision.
