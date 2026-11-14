# 0172. The commit table's menu shows only what applies, in sections, with icons; Go to is Shortcut+G only

- Status: accepted; amends [0045](0045-git-log-navigation.md) (Go to is no longer in the menu),
  [0041](0041-git-write-operations.md), [0042](0042-git-log-commit-operations.md) and
  [0100](0100-editing-commit-messages.md) (where they describe the table's menu), and follows
  [0171](0171-context-sensitive-branch-menus.md); amended by [0173](0173-pushing-tags-and-tag-submenus.md) (tags at the commit get submenus)
- Date: 2026-11-13

## Context
The commit table's context menu listed every item for every selection and greyed out most of them: with several
commits Checkout, New Branch, Cherry-Pick and the rest were all disabled, without a `HEAD` commit Reset and Edit
Commit Message stayed, and a detached `HEAD` offered Edit Commit Message that only answered with a message. The items
had no icons, the branch submenus of a commit with many branches filled the top of the menu, and Go to
Hash/Branch/Tag… sat at the end although Shortcut+G opens it from the whole log body. The branch menus were made
context-sensitive just before ([ADR 0171](0171-context-sensitive-branch-menus.md)).

## Decision
- **Hide what never applies, grey out what is blocked**, the rule of ADR 0171. The pure model is
  `commitMenuEntries` (`CommitMenuEntries.kt`), a list of non-empty sections of `ShownCommitEntry`; the table builds the
  items from it when the menu is asked for (`CONTEXT_MENU_REQUESTED`, since JavaFX shows no menu without items) and
  again when it shows; `HEAD` and the current branch are read from the refs then.
  - Several commits: only Review Commits and Copy Revision Numbers.
  - No `HEAD` commit: no Reset, no Edit Commit Message. Detached `HEAD`: no Edit Commit Message
    (`RefActions.editMessage` keeps its own guard).
  - No selection: no items; JavaFX opens no popup without items, so the table refreshes the items before the request
    reaches it (a menu that stayed empty would never fire "showing" again).
  - Greyed out while an operation runs: Checkout Revision, New Branch, New Tag and Reset; also while git is busy
    (a merge, rebase or pick in progress): Cherry-Pick, Revert and Edit Commit Message. Review, Copy and Compare
    only read and stay enabled.
- **Order**, with a separator between non-empty sections: the branch submenus at the commit; Review Commit(s),
  Checkout Revision, Copy Revision Number(s); Cherry-Pick, Revert Commit, Reset Current Branch to Here…; New
  Branch…, New Tag…; Compare with Local, Edit Commit Message…. The texts are singular or plural by the count.
- **Branches at the commit**: one branch gets its submenu at the top, named after the branch, as before; several
  are grouped in one "Branches" menu with one submenu each. Each submenu is the `branchMenu` of ADR 0171, unchanged
  inside, with the icon of its ref kind as in the branch tree.
- **Icons**: every action has one in `GitLogIcons` (Review, Checkout, New Branch and the working-tree diff are the
  existing ones; Copy, New Tag, Cherry-Pick, Revert, Reset, Edit Commit Message and "Branches" are new). They use the
  context-menu style of `docs/ui-conventions.md` through the shared `contextMenuIcon` (`ui/ContextMenus.kt`): the
  colour is appended to the icon's own style, which holds its glyph font.
- **Ref icon colours in a context menu**: the colours are looked-up colours of `git-log.css` on `.git-log`, with a
  dark set in a media query, and a context menu does not get the module's stylesheet (it only gets the scene root's).
  The existing route of the ref tooltip applies: `refMenuIcon` wraps the ref icon in a small `StackPane` that carries
  `GIT_LOG_CSS` on the node and the class `git-ref-menu-icon`, which the `.git-log` rules (light and dark block) list,
  so `-git-ref-*` resolve there and follow the popup scene's colour scheme, which `ThemedWindows` sets on every
  window's scene. No colour is written in Kotlin. On the highlighted item the icon gets the outline of a selected
  row (`.menu-item:focused .git-ref-icon`), so that green and purple stand out on the selection bar.
- **Go to Hash/Branch/Tag…** leaves the menu; Shortcut+G (handled by the view and the area's tabs) is the only way.

## Consequences
- A menu is as long as the selection allows; tests look the items up by `CommitMenuEntry` (`CommitTable.item`) and
  check absence where an entry is left out. Tests that read the state of an item refresh the menu first
  (`refreshMenu`). While the menu is open, a change of `running` or `busy` enables and disables the shown items
  without rebuilding the popup, as the branches pane does; `RefActions.pick` and `editMessage` check only `running`,
  so the menu is what keeps a pick from starting while git is busy.
- The menu's discoverability of Go to is gone; Shortcut+G stays in
  [keyboard-shortcuts.md](../features/keyboard-shortcuts.md).
- A new commit action is one `CommitMenuEntry` (id, icon, section) plus its cases in `appliesTo`, `isEnabled`,
  `commitEntryText` and `CommitTable.run`.
