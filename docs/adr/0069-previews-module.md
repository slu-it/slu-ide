# 0069. Previews module

- Status: accepted
- Date: 2026-10-21

## Context
`file-viewers` had reached 13k lines. It mixed the viewer framework and the code editor with all WebView previews and
their libraries: `javafx.web`, commonmark, jsoup, jackson and 4 MB of vendored Mermaid and KaTeX. Its root package
depended on preview and image internals, so nothing could be used without the rest. The preview packages had
first been changed to use only the public root-package API of `file-viewers` (`ExtensionFileViewer`, the
`CodeEditor` members, ...), which makes the split a move.

## Decision
`previews` (`dev.sluide.previews`) is a module of the editor family
([ADR 0047](0047-module-dependencies-and-shared-modules.md)). It holds the WebView previews of Markdown, HTML, SVG and
images, JSON, XML, CSV and cookie jars, notebooks, Mermaid, PEM and Bicep, in the sub-packages `preview`,
`markdown`, `html`, `image`, `json`, `xml`, `table`, `notebook`, `pem` and `bicep`
([ADR 0055](0055-sub-packages-within-modules.md)), with their scripts, styles and the vendored libraries.

`previews` depends on `file-viewers` (`api`) through its public root API only: `ExtensionFileViewer` and
`SourceFileViewer` for the viewers, `CodeEditors` and `CodeEditor` (`savedText`, `onTextChanged`,
`onShownLinesChanged`, ...) for the source side, `Highlighters` for highlighting, `BicepResourceGraph`,
`FocusWhenShown` and `readTextSample`. It also depends on `text-editing` (`CommentSyntax`) and `background-tasks`
(exports and image downloads, [ADRs 0060, 0061](0061-background-tasks.md)).

`file-viewers` keeps the code views, the highlighters (RSyntaxTextArea stays there), the diffs, the language viewers,
`.env`, and the Bicep graph and parameter file viewer. It drops `javafx.web`, commonmark, jsoup and jackson.

Viewers are found by Spring component scan, so `ide` must depend on `previews`, though it imports nothing from it.
`SpringWiringTest` checks that the Markdown viewer bean exists, so the previews cannot vanish silently. Cookie jars are
a generic preview, not part of `http-client`.

## Consequences
- `file-viewers` is a smaller module without a web engine; a change to a preview no longer rebuilds the code views.
- Shared styles are duplicated with keep-in-sync comments: `.preview-toolbar` in `preview-view.css` and
  `view-toolbar.css`, and the token colours in `syntax.css` and `preview.css`.
- Test helpers are copied, because the tests of `previews` cannot see the tests of `file-viewers`: `press` and
  `keyPress` (`KeyPresses.kt`) and `MAIN_BICEP` (`BicepSamples.kt`).
- `editor-area` uses `previews` in its UI tests, which open files in real previews.
- Listed in [../index.md](../index.md).
