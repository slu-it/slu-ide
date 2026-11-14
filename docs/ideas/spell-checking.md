# Spell checking

Explored, not decided or built. Spell checking for file editors (plain text, Markdown, comments and strings in code)
and prose inputs such as the commit message. Researched on 2026-11-14; versions and sizes are from that day.

## Leaning: Lucene Hunspell with LibreOffice dictionaries
`org.apache.lucene.analysis.hunspell.Hunspell` in `lucene-analysis-common` (10.6.0, Apache-2.0, Java 21+) is a pure-Java
Hunspell: `spell(word)` and `suggest(word)` on the standard `.aff`/`.dic` files, thread-safe, about 6.6 MB of jars with
`lucene-core`. `suggest` has a 250 ms default limit and returns partial results by default (`TimeoutPolicy`).
Not supported: Hungarian, characters outside the Basic Multilingual Plane, the `PHONE` affix option. No native
libraries, no per-platform builds, every language LibreOffice has a dictionary for.

Open: load time, heap and suggestion speed for the German dictionary (4.4 MB `.dic`, many compounds); no published
numbers, a small benchmark should come before the plan.

## Dictionaries and their licences
From the [LibreOffice dictionaries](https://github.com/LibreOffice/dictionaries) repository (maintained):

| Dictionary | Licence | Size | Shipping |
|---|---|---|---|
| en_US | permissive (SCOWL, BSD affix file) | 0.5 MB | could be bundled |
| en_GB | LibreOffice README says LGPL, wooorm says MIT and BSD | 1.2 MB | check before bundling |
| de_DE, de_AT, de_CH (frami) | GPL-2.0 or GPL-3.0 | 4.4 MB each | download on demand |

Downloading on demand fits `local-models` ([ADR 0125](../adr/0125-local-models-module.md)): a `.dic` and `.aff` per
language as `ModelFile`s pinned by size and sha256 in a catalog resource, `ModelSpecs` keyed by language, install states
in a Settings page, nothing downloaded at startup. Each dictionary gets a row in
[external-artifacts.md](../external-artifacts.md). The Word Guess word lists hold five-letter words only and are no
dictionary.

## Alternatives
- **LanguageTool** (6.8, LGPL-2.1+): spelling and grammar, the only real German grammar checker; can be restricted to
  spelling rules. Heavy (`language-de` 21 MB plus dependencies) and its unreleased 6.9 states "Java 17 up to 21",
  a risk on JDK 25. Candidate for a later grammar step, in process or as its local HTTP server.
- **harper-ls** (Automattic Harper, Apache-2.0, Rust, binaries about 14 MB per platform): fast grammar and spelling
  language server on our LSP client ([ADR 0142](../adr/0142-language-server-foundation.md)). English only, and it works
  only on documents a language server serves, which `.txt` and `.md` files are not today.
- **ltex-ls-plus** (LanguageTool as a language server, 20+ languages): 265 to 319 MB per platform. Too big.
- **typos-lsp**: flags a list of known misspellings only, few false positives; suits identifiers, not prose.
- **OS spell checkers** (`NSSpellChecker`, `ISpellChecker`, Enchant): no Java bindings exist; three hand-written FFM
  bindings with platform-dependent results. Not worth it.
- **morfologik-speller, SymSpell ports, JOrtho, native Hunspell over JNA**: low activity or unmaintained, or native
  libraries; SymSpell has no affix handling, poor for German compounds.

## Fit with the editors
- **Underlines**: an `ExtensibleCodeEditor.overlay(layer, ranges, followEdits = true)` layer with `spell-*` CSS classes
  using `-rtfx-underline-wave-radius`, like the diagnostics of
  [ADR 0148](../adr/0148-diagnostics-quick-fixes-semantic-highlighting.md). Edits drop the ranges they touch, so changed
  words or lines are checked again; lookups run off the FX thread.
- **Actions**: context-menu items for suggestions, Add to Dictionary and Ignore
  ([ADR 0108](../adr/0108-code-editor-extensions-and-context-menu.md)); replacements through `applyEdits`.
- **What to check**: in code only `COMMENT` and `STRING` spans from the `SyntaxHighlighter`, identifiers split at
  camelCase and snake_case; in Markdown everything outside fenced code and front matter.
- **Language**: Lingua per paragraph, but `LanguageDetector` lives in `translation` and would move to a lower module.
  Detection returns nothing when two languages score close, so a fallback is needed (per file or per project).
- **User dictionary**: a `spellcheck` field in `shared.json` ([ADR 0101](../adr/0101-shared-data-file.md)), written
  delayed like the games' `StoredField`, since a large field is rewritten with every change; ignored words of one
  project in the project file.

## Gaps
- Plain `TextArea`s have no style ranges: commit, reword, merge and tag messages, the TODO dialog fallback. They would
  become RichTextFX areas, the commit message first.
- Editors from `CodeEditors.text` in dialogs and tool tabs (Translate, Read Aloud input) get no extensions and would be
  wired directly.
- Alt+Enter is the language-server quick fix key and only the first handler wins: merge spell suggestions into that
  popup or offer them in the context menu only. `CaretPopup` is internal to `code-intelligence`.
- The extension API exposes neither the editor's language nor the token type at a position.

## Open questions
- Spelling only, or grammar too (LanguageTool with the JDK 25 risk, or harper-ls for English)?
- Which inputs first: Markdown and text files, code comments, the commit message?
- One language per document or project, or detection per paragraph?
