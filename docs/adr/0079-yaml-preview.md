# 0079. YAML preview

- Status: accepted
- Date: 2026-10-23

## Context
YAML files (`.yml`, `.yaml`) only had a highlighted source (a `CodeFileViewer` in `file-viewers`). Kubernetes
manifests, Compose files and CI configurations nest deeply and often mix block and flow style; the formatted, foldable
view JSON ([ADR 0064](0064-json-preview.md)) and XML ([ADR 0065](0065-xml-preview.md)) got helps here as well. ADR 0065
left the shared fold parts ready for a third preview that needs only a renderer.

## Decision
- **The third foldable preview, on the shared parts.** `YamlFileViewer` (now in `previews`, package
  `dev.sluide.previews.yaml`, name kept) is a `PreviewFileViewer` with `YamlPreview` (Split, source focused, scroll
  sync, `Preview.folds`, `Preview.copiesFormatted`), on `fold-tree.js`/`.css` and `FoldState` like JSON and XML.
  `Highlighters.yaml` names the highlighter for it and the workflow viewer. GitHub Actions workflows keep their graph
  viewer: its path match wins over the extension.
- **SnakeYAML's event stream** (`Yaml(LoaderOptions).parse(reader)`), already in the version catalog (Spring Boot BOM)
  and used by `github-actions`. The events are turned into a small tree (`YamlTree.kt`): mappings with key and value
  nodes, sequences, scalars, aliases, documents with their directives. Rejected: SnakeYAML's Composer/Constructor
  (`load`, `compose`) builds node graphs or Java objects, expands aliases and loses how scalars were written; Jackson
  YAML (`jackson-dataformat-yaml`) is a new dependency on top of SnakeYAML that also reads values, not their spelling;
  snakeyaml-engine (YAML 1.2) is another new dependency for little gain in a view that shows the text as written.
- **Security:** only events are read, so no tag ever constructs a type (no gadget classes) and aliases are never
  expanded (no billion laughs: `*a` is shown as `*a`). Comments are not processed. `codePointLimit` is raised to
  `Int.MAX_VALUE`, because the default 3 MB would reject files the editor shows; the page is bounded by `MAX_NODES`
  (100,000, with a note) and nesting deeper than 200 levels is an error (the layout recurses, about three calls per
  level; 1000 levels overflowed the default 1 MB thread stack on the Linux CI runner).
- **Scalars as written:** a scalar's text is the source between its marks (SnakeYAML's marks count code points; they
  are converted to char offsets), after its properties, so quotes, escapes and numbers show as spelled. Anchors and
  tags are taken from the source too (`!!str`, `!Ref`, `!e!foo` as written). Multi-line scalars are indented anew:
  continuation lines of plain and quoted scalars lose their leading space (not part of the value), block scalar lines
  (`|`, `>`) lose the block's indentation and keep what they have beyond it; an explicit indentation indicator is
  rewritten to `2`, the step of the formatted text; chomping is kept (`|+` keeps its trailing empty lines), except that
  a block scalar whose value has no final line break (it ended the file without one) gets `-`, since the formatted
  text ends with a line break. Scalar lines end where SnakeYAML sees a line break, also at NEL, LS and PS (YAML 1.1),
  so block indentation after them is re-indented too: NEL is written as a line feed (SnakeYAML reads it as one), LS
  and PS stay as written. An LS or PS after a block scalar's last line is part of its value: clipped, an empty line
  follows it; kept (`|+`), it ends the line, so the next line follows it directly (at the end, the copy then has no
  trailing newline).
- **Layout** (`YamlLayout`, one for the page and the copied text): block style with a 2-space indent; flow collections
  are expanded, empty ones stay `{}`/`[]`; sequences under a key are indented. A non-empty mapping or sequence is a
  `<details>` (summary `key:` with its anchor and tag; closed: `… 3 keys` / `… 1 item`). A sequence item that is a
  mapping starting with a plain entry is written compactly (`- name: web`, the other entries aligned under it);
  otherwise the item is `-` alone with its content under it. Keys that span lines, are empty or are collections become
  explicit `? `/`: ` entries; keys do not fold. Duplicate keys are kept. Colours as the source highlighting: keys
  `property`, quoted and block scalars `string`, untagged plain booleans/null `keyword`, numbers `number`, anchors,
  aliases, tags and directives `annotation`.
- **Comments are dropped**, like JSONC comments in the JSON preview: SnakeYAML's comment events do not attach to the
  nodes in a way the reformatted layout could keep.
- **Multi-document files:** documents are separated by plain `---` lines and do not fold themselves, only their
  contents. A `---` is also written before a document with directives (shown as written, without comments), with root
  properties (`--- &r`) or without content; `...` only where a document with directives follows another one; a single
  document's leading `---` and `...` are dropped.
- **Paths:** `data-path` is a JSON Pointer with the document's index first (`/0/spec/containers/1`; `~` and `/`
  escaped), the key's value as the step, an alias key as written (`*a`), a collection key as `?<entry index>`.
  `data-line` is the key's line for entries, the node's start line otherwise; lines are counted as in the editor.
- **Errors:** SnakeYAML's `MarkedYAMLException` becomes `Line N, column M: <problem>` plus, where the context started
  on another line, `; the block mapping started on line 3`; a forbidden character names its code point. The notice
  stands above the part that parsed, with open containers closed. A byte order mark is removed.

## Copy Formatted
As for JSON and XML, the toolbar button puts the formatted text on the clipboard. `YamlText` writes the same tree
through the same `YamlLayout` as plain text, without the page's node cap, with a trailing newline. It copies the saved
text, which is what the view shows. On a parse error or an empty file (also one with only comments) nothing is copied
and "Not copied: <reason>" shows. A unit test guards that the formatted text reads back as the same YAML: its event
stream equals the original's (types, anchors, tags, implicit flags, scalar values and styles; marks, flow style and
document markers ignored).

## Consequences
- YAML opens in Split with the source focused, like JSON and XML; workflows keep their graph viewer.
- JSON, XML and YAML share the fold script, stylesheet and state.
- `YamlFileViewer` moved from `file-viewers` to `previews` and is no longer a `CodeFileViewer`; the `previews` module
  depends on SnakeYAML.
- Comments in the source are not in the view or the copied text; the copy reformats flow collections into block style.
- NEL, LS and PS inside a scalar start a new line in the view and the copy, though the editor does not count them as
  line breaks; `data-line` follows the editor's lines. A NEL in a scalar becomes a line feed in the copy.
- Added to [../index.md](../index.md).
