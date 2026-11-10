# 0157. Markdown front matter as a code block

- Status: accepted
- Date: 2026-11-10

## Context
commonmark-java has no front matter support. For a `SKILL.md` that starts with `---`, `name: …`, `---`, the preview
showed an `<hr>`, and the closing `---` turned the line above it into a setext `<h2>`, which also appeared in the table
of contents. The HTML export (ADR 0058) had the same defect.

## Decision
- Front matter is shown as written, in a highlighted YAML or TOML code block. Rejected: a key/value table (values are
  free-form YAML), GitHub's horizontal table, hiding it.
- The detection rule lives in `Highlighters.frontMatter` in `file-viewers` and is shared with the source highlighting.
  It is the rule of translation's `MarkdownBlocks`; speech's `MarkdownSpeech` skips YAML only, so TOML front matter is
  still read aloud (left as is).
- The preview replaces the front matter lines by empty lines before commonmark parses the text, so every later block
  keeps its source line (`data-line`, the outline), and prepends a `FrontMatterBlock` node on line 0. The renderer
  writes it like fenced code, as `<pre class="front-matter"><code class="language-yaml">`. The export goes through the
  same `MarkdownRenderer.body`; a `<name>.css` can hide the block.
- Rejected: `commonmark-ext-yaml-front-matter` (YAML only, a simplistic parser, drops the block) and a commonmark block
  parser (must know it is on line 0; more code for the same result).

## Consequences
- Known limit: a document that opens with a `---` thematic break directly followed by text and later has another `---`
  line reads as front matter, as in Jekyll, Hugo and GitHub.
- Notebooks are excluded: their Markdown cells and `text/markdown` outputs are not files, so `---` there stays a
  thematic break. Front matter is opt-in (`frontMatter = true` in `MarkdownRenderer.parse` and `body`), set by the
  Markdown file preview, the export and the outline.
- The outline and the export only pass `frontMatter = true`; scroll sync needs nothing.
