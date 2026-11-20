# Emoji in the HTML preview: ideas left for later

[ADR 0190](../adr/0190-pages-reach-webkit-with-astral-chars-escaped.md) repairs emoji in every page Thoreau builds
itself (Markdown, notebooks, JSON, ebooks, …): `loadPage` escapes characters outside the BMP and puts each emoji in a
span of its own, because JavaFX's WebKit on macOS lays out a text node that holds an emoji and other text wrongly (the
text after it vanishes in headings, the emoji smears over the next word in paragraphs).

The HTML preview does not go through `loadPage`: `PreviewServer` serves the user's file over HTTP as it is on disk, so
its emoji stay broken on macOS. For now the preview only shows a banner that names the known limitation. Left out:
- **Rewriting the served HTML:** `PreviewServer` could run the page through the same jsoup pass (emoji spans) before
  serving it. Open questions: whether the preview may show anything but the author's markup (a span changes selectors
  such as `h1 > span`, `:first-child`, `:empty`, and scripts that read `childNodes`); whether to do it only for
  `text/html` responses of the previewed file or also for pages it links to; whether a toolbar toggle should switch it
  off; and that a re-serialised page can differ from the file in View Source of WebKit's inspector.
- **Script-made content:** text that a page's scripts insert after load (also Mermaid labels and KaTeX output in our
  own pages) is never wrapped; a `MutationObserver` in an injected script could wrap it, but HTML previews run no
  script unless allowed, and an injected one would run in the author's page.
- **A JavaFX fix:** JDK-8304041 tracks the WebView layout bug; once a JavaFX release fixes it, the spans and the banner
  can go.
