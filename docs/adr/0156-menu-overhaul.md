# 0156. Menu overhaul: File, Docker Sandbox, Services, Games

- Status: accepted; amended by [0170](0170-sbx-management-console.md) (a Management Console item; tab titles)
- Date: 2026-11-10

## Context
The menus grew item by item. Several gaps and inconsistencies showed up:
- Another remembered project could only be opened through the picker window, which has no key.
- A project window could only be closed with the window's own close button.
- The Docker Sandbox menu offered Claude Code and Environment side by side. Only one of them makes sense for a given
  folder, so one item was always useless or disabled.
- Services and Games listed their entries in the order they were added, not in an order a user can scan.
- The game entries were plain text.

## Decision
- **File menu**, top to bottom: scratch items | Open Folder… (Shortcut+O), Open Project… (now on Shortcut+Shift+O), Open
  Recent Project ▸ | Save, Save All | Close Tab | Settings… | Close Project.
  - **Open Recent Project ▸** lists the 10 newest remembered projects (ADR 0034) as `name — path`, without the
    window's own project. Missing folders are disabled, and the submenu is rebuilt each time it shows. Choosing one
    opens it like the picker does: in a new window, or its window comes to the front. With nothing to show, a disabled
    "No Recent Projects" item appears. There is no Clear List: the picker's Remove already forgets a project.
  - **Close Project** fires the window's close request, so it asks exactly what the close button asks (unsaved files,
    running tabs, database work), and Cancel keeps the window. A window holds one project for its whole life (ADR
    0014), so closing the project means closing the window. The last window closing shows the picker, as before.
- **Docker Sandbox** (amends ADR 0010): the menu has one item, "New Docker Sandbox Session (<action>)", on
  Shortcut+Shift+B. The action is Environment when the folder has `sbxenv.yaml` and Claude Code otherwise
  (`SandboxAction.forFolder`).
  - The label and enabled state are refreshed when the menu shows and when the folder changes. The action is chosen
    again when the item fires, because the accelerator works without the menu ever showing.
  - Shortcut+Shift+D, the first wish, stays with View → Show Changes.
- **Services and Games are sorted by title** (case-insensitive) where `WindowTools` builds them, so new entries land in
  place. The Tools menu keeps its order.
- **Game logos:** every game draws its own coloured 16×16 vector logo (`<Game>Logo.kt` in its `ui` package, exposed
  as `logo()` on the window bean). Each logo is original and not modelled on a classic title's sprites. The logo shows
  on the Games menu item (`WindowTool.graphic`) and on the game's tab header and overflow item (`EditorTab.icon()`, a
  fresh node per call, `null` by default).

## Consequences
- A new game adds a logo and passes it to its `WindowTool` and its tab. A new service lands in its sorted place.
- `EditorTab.icon()` is open to other tabs (tools, services). None uses it yet.
- Shortcut+Shift+O and Shortcut+Shift+B are taken. Ctrl+Shift+letter reaches the menu also from a Linux terminal.
- The logos use fixed colours chosen to read on both themes, instead of theme lookups.
