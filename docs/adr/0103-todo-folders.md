# 0103. TODO folders

- Status: accepted
- Date: 2026-10-28

## Context
A flat list of TODOs per tab ([ADR 0102](0102-todo-panel.md)) gets long in a real project: related TODOs (a release,
an area of the code) need a group. The user wanted folders that only have a name, can be collapsed, take TODOs by
drag and drop and are sorted among the other TODOs.

## Decision
- **One level, mixed with root TODOs.** A folder holds only TODOs; folders and TODOs outside folders form one freely
  ordered list per tab. TODOs stay at the root by default.
- **Kept in the same `todos` array.** A folder is an entry `{ "id", "folder": "<name>", "collapsed": false,
  "todos": [ …TODO entries… ] }`; the string field `folder` marks it and holds its name. As in ADR 0102, fields this
  version does not know are kept, and so are entries it does not list (a folder inside a folder, a folder whose `todos`
  is no array: shown as empty, and writes into it are refused). Names need not be unique; the `id` is the identity.
- **`TodoPlace(folderId, index)`** says where a moved item lands: `folderId` null is the root, `index` counts the
  listed items of that container once the item is removed from its old place. `Todos.move` refuses a folder in a
  folder.
- **Collapsed is stored** in the folder entry, so it lasts across restarts and applies to every window; new folders
  start expanded, and adding a TODO into a collapsed folder expands it. Cards store theirs the same way: a TODO entry
  has `"expanded": true` while its description is shown, and no such field while collapsed (new TODOs), so the
  expanded state also moves with the TODO.
- **A folder changes scope with its dialog** (Shared | Project and the name): it moves with all its TODOs to the top of
  the other tab, target written first as for single TODOs. The TODO dialog has a Folder field (the folders of the
  selected scope), so editing can also move a TODO; one whose scope and folder stay keeps its place, else it goes to
  the top of the target (the root top if that folder is gone).
- **Deleting a folder with TODOs asks** whether to delete them too or to keep them: kept TODOs take the folder's place
  at the root. An empty folder gets the plain question. Cancel is the default.
- **Drag and drop stays a plain mouse drag** (ADR 0073), now `ItemDrag`, with the rules in the pure `dropTarget`
  (`DropTarget.kt`): on a card, the upper half is before it and the lower half after it; on a folder header, the top
  quarter is before the folder, the bottom quarter of a collapsed one after it, and the rest the folder's top; an empty
  folder's hint and a folder's padding below its last card go into that folder. A dragged folder lands only between
  root items. **An insertion line** (or a highlighted folder header or hint) shows where the item lands, instead of the
  cards making room: shifting siblings does not work across containers.

Rejected:
- Nested folders: deeper trees make the drop rules and the indentation much harder for little gain in a side panel.
- Folders in a separate field with the TODOs referring to them: the display order of folders among TODOs would need a
  second ordering.
- Keeping the collapsed or expanded state per window (as cards did before): the user wants the panel as they left it.

## Consequences
- `Todos` reads and writes a tree: `items` gives it, `list` all TODOs flattened (the tab counts them all).
- A version before this one shows a folder entry as a TODO without a summary.
- Every expand or collapse, of a folder or a card, rewrites the data file and refreshes every window of that scope
  (as any change does, ADR 0102). While the data cannot be written, the toggle shows the save error and nothing opens.
- Listed in [../index.md](../index.md).
