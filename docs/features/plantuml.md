# PlantUML

**Modules:** `previews` (`dev.sluide.previews.plantuml`: `PlantUmlFileViewer`, `PlantUmlPreview`, `PlantUmlPage`,
`PlantUmlHtml`, `DiagramRenders`) and `plantuml` (the engine: `PlantUml`, `GraphvizLocator`); `file-viewers`
(`MarkupLanguages.plantuml`, `PlantUmlHighlighter`) and `text-editing` (`CommentSyntax.PLANTUML`). Decisions in
[ADR 0193](../adr/0193-plantuml-engine.md) (the engine), [ADR 0033](../adr/0033-zooming-images-and-diagrams.md) (zoom)
and [ADR 0050](../adr/0050-focus-when-a-file-tab-is-shown.md) (focus). What is left for later:
[plantuml-later.md](../ideas/plantuml-later.md).

## Which files
`.puml`, `.plantuml`, `.pu` and `.iuml` files (any case) that are text.

## The view
- Opens in **Split**: the highlighted source beside the saved file's diagrams, which the bundled PlantUML (the MIT
  build, in Thoreau's own process, nothing installed or downloaded) draws in the background. No scroll sync. As a
  zooming preview it takes the focus when its tab is shown, like a Mermaid file
  ([previews.md](previews.md#zooming-zoomcontrols)).
- Before the first drawing of a view the page says "Drawing the diagram…". When the file is saved or changes on disk,
  the diagrams shown stay, with their zoom and scroll position, until the new ones are drawn. Each view keeps the
  drawings of its 4 most recent texts, so saving an earlier text again (after an undo, say) shows it at once. One
  drawing runs at a time in the whole app; the others wait.
- An empty file shows nothing. A file without a line starting with `@start` says "No @start…/@end… block in this
  file." and is not drawn.
- **Several diagrams:** every `@start…`/`@end…` block, and every page of a block (`newpage`), is drawn, top to bottom
  with a gap. They zoom as one ([previews.md](previews.md#zooming-zoomcontrols)): **Fit** fits all of them into the
  view together, a click on a diagram goes from fitted to 100% and back. Error boxes keep their size and a click on
  one does not zoom (their text can be selected).
- **Both themes:** each diagram is drawn once in PlantUML's light colours and once in its dark mode; the page shows the
  one of the theme, so a theme change draws nothing and keeps the zoom and scroll position. A `!theme` or `skinparam`
  in the source still wins. Known gap: PlantUML's dark mode turns default text white also on fills the source makes
  light.
- Diagrams are images (`<img>` with the SVG in a `data:` URI): nothing in them runs, and `[[links]]` in a diagram are
  not clickable.

## In Markdown and notebooks
- ` ```plantuml ` and ` ```puml ` fences (the info string's first word, any case, as for ` ```mermaid `) are drawn in
  place in the Markdown preview ([markdown-preview.md](markdown-preview.md)) and in a notebook's Markdown cells, by the
  same engine and with the same look as a PlantUML file: both theme variants, so a theme change draws nothing, an error
  box in place of a block PlantUML cannot read. Diagrams in a page do not zoom on their own (the page zooms as a
  document). `PlantUmlFences` (package `markdown`) inserts them after the page is sanitised; each Markdown and notebook
  view has its own `DiagramRenders` (`MarkdownPreview`, `NotebookPreview`).
- A fence without a line starting with `@start` is wrapped in `@startuml`/`@enduml` (as GitLab does); a fence with one
  is drawn as written, every block of it. An empty fence stays an empty code block.
- Until its drawing is there, a fence says "Drawing the diagram…". Each drawing that arrives loads the page again,
  which keeps its place as after a save: side by side it lines up with the source, shown alone it keeps its scroll
  position. The diagram is a block with the fence's source line, so the scroll sync and the table of contents work
  around it; it is no text for Read Aloud. A fence is drawn per text: an edit above it, which moves it, draws nothing
  again, and the same fence twice is drawn once.
- An edited fence keeps showing its previous drawing until the new one is there, so the text below it does not move.
  This holds when the page has as many fences as before and the fence's previous text is nowhere on the page any
  more; a fence that was added, removed or moved to another place says "Drawing the diagram…" meanwhile. Fences are
  counted across the whole page, in a notebook across its cells.
- Error lines are the Markdown file's lines, also for a wrapped fence. In a notebook they are the cell's: "Cell 3,
  line 2: …", the cell counted among all cells from 1.
- The HTML export ([markdown-export.md](markdown-export.md)) draws every fence before it writes the page (each
  drawing within the engine's 20 s), with both variants as images that the page's theme switch shows; an error is
  exported as its error box and listed after the export ("PlantUML diagram (line N) could not be drawn: …", N being
  the error's line in the file, or the fence's first line if PlantUML named none, as when a drawing took too long).
  The page stays self-contained. Without a page renderer's opt-in (other pages that use the
  Markdown renderer) a PlantUML fence stays highlighted code.

## Errors
- A block PlantUML cannot read shows an error box in its place, under "Syntax error": "Line N: " and PlantUML's
  message, N being the line in the file (also for the second block of a file). Other blocks of the file draw as usual.
- Other failures show only the message, without a line: an error inside a stdlib include (named in the message),
  "Drawing took longer than 20 s", "Too large to draw: N KiB, at most 512 KiB", "PlantUML failed: …".
- The text is selectable, red on a light red box (light red on dark red in the dark theme), like a Mermaid file's
  error.

## Graphviz
- Class, component, state, use case and similar diagrams need a layout engine. Thoreau uses Graphviz's `dot` when it
  finds one: first what the login shell's `command -v dot` names, then `/opt/homebrew/bin`, `/usr/local/bin`,
  `/opt/local/bin` and `/usr/bin`; the one found must answer `dot -V` with a Graphviz version within 5 s. There is no
  setting.
- `dot` is looked up once per app run, before the first drawing: after installing Graphviz, restart Thoreau.
- Without `dot`, PlantUML's built-in Java layout (Smetana) draws such diagrams, and a muted line above them says
  "Graphviz not found, using the built-in layout". Sequence diagrams, mind maps, JSON and the like need no layout
  engine and get no line.

## What a source can use
PlantUML runs in its SANDBOX profile, so a diagram cannot read the user's files or reach the network:
- **Works:** the stdlib bundled with PlantUML: `!include <C4/C4_Container>`, icon and sprite libraries such as
  `<tupadr3/…>`, `!theme cerulean`, images as `<img:data:…>`.
- **Refused:** every local file and every URL: `!include`, `!includesub`, `!import`, `!includeurl` and a `!theme`
  from a file or URL show PlantUML's error ("cannot include …", "Cannot import", "Cannot open URL", "Cannot load
  theme …"), `%load_json` of a file gives an empty value, `<img:file.png>` and sprites or styles from files show
  nothing.
- **Limits:** a file over 512 KiB is not drawn; a drawing that takes longer than 20 s is given up (its `dot` is
  killed), and the next one starts.
- **Not in this build:** `!pragma layout elk`, `<latex>` and charts of the GPL build; `@startdot` blocks fail and
  `@startditaa` shows PlantUML's notice that this build has no ditaa.

## Editing
- Highlighting: `'` comments when `'` starts the line, and `/' … '/` block comments over lines; `@startuml`,
  `@enduml` and every `@start…`/`@end…` marker as keywords; preprocessor directives (`!include`, `!define`, `!theme`,
  …) as annotations; strings in double quotes; statements (`participant`, `class`, `skinparam`, …) as keywords when
  they start a line; arrows as operators (`->`, `-->`, `<|--`, `*--`, `o--`, `..>`, `-up->`, `-[#red]->`, …), while
  hyphens in names stay plain. Known gaps: a statement word used as a name at the start of a line is a keyword, a
  `/'` inside a string starts a block comment, some arrows with a colour or direction but no head are not marked, and
  the content of JSON, YAML, salt, gantt and mind map diagrams is plain.
- **Shortcut+/** comments lines out and in with `'` (block comments are not toggled).

## Export
The toolbar's Export menu of a PlantUML file lists SVG, PNG 100%, PNG 200%, PNG 300% and PNG Custom…, written beside
the file from its saved text (the editor's unsaved changes are not exported). The file is drawn again for the export,
so the files match what the preview shows for the saved text.
- **One file per diagram:** a file can hold several diagrams (blocks and `newpage` pages). The first is `name.svg`
  (`name.png`, `name@2x.png`, …), the others `name-2.svg`, `name-3.svg`, … numbered over all diagrams, so that a
  diagram that fails to draw keeps its number. PNG Custom… names each with its own size: `name-2-640x480.png`.
- **SVG:** the light variant of the diagram as UTF-8, with PlantUML's own `<?plantuml-src …?>` instruction, so that
  PlantUML tools can reopen the source.
- **PNG:** the light SVG drawn as in [svg-png-export.md](svg-png-export.md), on a transparent background. PNG
  Custom… asks for the size of the first drawn diagram (ratio locked); every other diagram is scaled by the same factor.
- **Existing files:** one question names all that exist ("diagram.svg and diagram-2.svg already exist in this
  folder. Replace them with the export?"); Cancel is the default and writes nothing.
- **Errors:** a diagram that failed is not written and reported ("Diagram 2 not exported: Line 7: …"); so is one
  whose PNG cannot be drawn (e.g. "Too large"), and the others are still written. If none could be written, the
  dialog shows the first failure; a file without a diagram says "No diagram to export".
