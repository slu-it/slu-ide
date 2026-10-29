# 0109. Coloring tool: own color math in the coloring module, saved colors per project

- Status: accepted; amends [0047](0047-module-dependencies-and-shared-modules.md) (`coloring` joins the editor family); amended by [0111](0111-coloring-slots-and-inspector.md)
- Date: 2026-10-29

## Context
Frontend and design work needs quick color help: reading a color in any CSS notation, converting it, picking one,
checking contrast, deriving shades, mixing, gradients and how people with color-vision deficiencies see it. The first
tool of the Tools menu ([ADR 0110](0110-tools-menu.md)) does all of it in one tab, and code editors link to it
([ADR 0108](0108-code-editor-extensions-and-context-menu.md)). JavaFX's `Color.web` reads hex, `rgb()`, `hsl()` and
names only, and knows none of the CSS Color 4 spaces (`lab`, `lch`, `oklab`, `oklch`, `hwb`).

## Decision
- **Module `coloring`** in the editor family: its tab is an `EditorTab` and it extends code editors, so it depends on
  `editor-area` and `file-viewers`, and on `project-data` for saved colors. `Coloring` (window scope) holds the
  window's current color, so a reopened tab or a gutter click shows the right one; `SavedColors` is a singleton.
- **Own color math in pure Kotlin**, no library: the CSS Color 4 conversions with the matrices of its sample code
  (Lab and LCH in D50 with Bradford adaptation, OKLab and OKLCH, HWB, HSL), HSV and naive CMYK, CSS Color 4 gamut
  mapping (OKLCH chroma reduction with deltaEOK 0.02) for colors outside sRGB, WCAG 2 contrast, APCA Lc, the CVD
  simulation matrices of Machado et al. (2009) on linear RGB, and `color-mix()`. The math lives in sub-packages without
  JavaFX (`color`, `analysis`, `detect`); it moves to a foundation module once a second module needs it, not before.
- **Colors keep their notation's space**; each format is converted from it, so a parsed `oklch()` round-trips. Colors
  outside sRGB are shown gamut-mapped and marked.
- **APCA** is shown as informational (WCAG 3 draft), next to the WCAG 2 verdicts that apply today. Only the published
  formula and constants (0.0.98G-4g) are reimplemented; no APCA code is copied.
- **Saved colors** are explicit (a Save button), with no automatic history, and per project: the top-level field
  `colors` in the project's data file ([ADR 0090](0090-per-project-metadata-store.md)), or in `shared.json`
  ([ADR 0101](0101-shared-data-file.md)) for a window without a folder. Entries are CSS texts
  (`cssText`: hex, or `oklch()` outside sRGB); unknown content is kept.
- **Detection in source text** is by file type, for style and markup files only (CSS, SCSS, Sass, Less, HTML, XML, SVG,
  Vue, Svelte, JavaScript and TypeScript with JSX/TSX), with context rules against false hits: hex only where a value
  can be (not in id selectors, entities or private fields; in scripts only in strings), functions only with literal
  arguments, names only in property values, color attributes or whole string literals. `.xml` files ignore 4 and 8
  digit hex, as Android reads `#AARRGGBB` where CSS reads `#RRGGBBAA`.

## Consequences
- No dependency to update, and conversions match browsers to the precision the tests pin; the color science is ours to
  maintain.
- The UI says "color" (American), like CSS.
- Listed in [../index.md](../index.md).
