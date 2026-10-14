# 0016. Markdown preview with commonmark-java in a WebView

- Status: accepted
- Date: 2026-10-14

## Context
Markdown files should open as highlighted source beside a rendered preview, with GitHub-like rendering (tables, task
lists, images, inline HTML), synced scrolling and clickable links. Markdown files come from any repository, so the
preview must not run scripts or event handlers that a file contains.

Options considered:
- **commonmark-java to HTML in a JavaFX `WebView`:** full HTML and CSS rendering, images and inline HTML for free,
  scrolling and link handling in a few lines of JavaScript. Needs the `javafx.web` module (WebKit).
- **commonmark-java to JavaFX nodes (`TextFlow`, `GridPane`, `ImageView`):** small and themed by our own CSS, but
  every element needs rendering code, and tables, nested lists and inline HTML would stay behind GitHub's rendering.
- **flexmark-java:** more extensions, but larger and less active than commonmark-java.

## Decision
- `MarkdownRenderer` renders with commonmark-java (`org.commonmark`) and the GitHub extensions for tables,
  strikethrough, task lists, autolinks and heading anchors. Block elements carry `data-line` (their first source
  line) for scroll sync. Fenced code with a known language is highlighted by the code views' highlighters.
- The HTML is sanitised with jsoup: `Safelist.relaxed()` plus `del`, `s`, `hr`, `kbd`, `mark`, `details` (with
  `open`)/`summary`, task-list checkboxes (`input` with `type`, `checked`, `disabled`; every `input` that is no
  checkbox is removed after cleaning, so no text field or file picker reaches the page), `class`, `id`, `align` and
  `data-line` on all elements, `data-source` on `pre` (the source of a diagram), and `file:` links and images.
  Scripts, `on*` handlers, `style`, frames, forms, SVG and `javascript:`/`data:` links in a Markdown file never reach
  the WebView. The page then gets our own stylesheet and script.
- The page has no `<base>`: it is loaded from a string, so WebKit would not load `file:` images through it anyway, and
  with a base an anchor (`#part`) would navigate away. Local images are embedded as `data:` URIs instead; remote
  images load from the web. Links keep their `href` and are resolved in Kotlin when clicked.
- No link navigates the WebView: the page's script handles anchors itself and hands every other link to the bridge.
  Should the WebView still load another page, the view renders the Markdown again; the bridge is only set on its own
  page.
- `PreviewFileView` shows a `CodeView` and a `WebView` in a `SplitPane`, with a toolbar for Source / Split /
  Preview. The preview follows the window's colour scheme by toggling a `dark` class on the page.
- Kotlin calls the page through `window.sluide`; the page calls back through one bridge object for link clicks.

## Consequences
- `javafx.web` is added to `file-viewers` and `ide` like the other JavaFX modules ([ADR 0003](0003-javafx-platform-jars.md)).
  The fat jar grows from 30 MB to 78 MB on linux-aarch64, almost all of it WebKit (`javafx-web`, 45 MB).
- A `WebView` must be created on the JavaFX thread, which holds for views opened by the editor area; tests create it
  there too.
- The preview is only as safe as the sanitiser: new allowed tags or attributes go through the jsoup `Safelist` in
  `MarkdownRenderer`, never around it.
- Rendering runs on the JavaFX thread when a file is opened or changes, like highlighting
  ([ADR 0005](0005-syntax-highlighting.md)).
