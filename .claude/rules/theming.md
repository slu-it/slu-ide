---
paths:
  - "**/*.css"
---

# Stylesheets and the dark theme

Details: ADR 0009, `docs/ui-conventions.md` ("Theming"). Dark is the default theme, so most users see it first.

This is about JavaFX stylesheets. The HTML pages in WebViews (`previews`: `preview.css`, `pem.css`, `fold-tree.css`,
`image-page.css`) are web CSS: they follow the theme through a `body.dark` class (ADR 0016), not through media blocks
or Modena colours. Vendored CSS (`previews/.../vendor/`) is left as shipped. `preview-view.css` is the previews'
JavaFX stylesheet and follows this rule.

- Prefer Modena's looked-up colours (`-fx-background`, `-fx-text-base-color`, `-fx-accent`, `derive(...)`): they
  follow the theme without dark rules. A stylesheet without colours of its own says so in its first comment.
- Every hard-coded colour gets its dark counterpart in an `@media (prefers-color-scheme: dark)` block. JavaFX applies
  descendant selectors inside a media block whatever the scheme, so dark rules use single-class selectors, and the
  light rules they override use the same specificity.
- Icons take their colour from the stylesheet (`-fx-icon-color: -fx-text-base-color`), not from Kotlin. The property
  belongs to the `FontIcon`: select `.my-button .ikonli-font-icon`, not the button, or the icon stays black in dark.
- Muted text uses `-fx-opacity` (0.6-0.8), not `-fx-text-fill: derive(-fx-text-base-color, N%)`: a positive N
  lightens, so the text stays white in dark. An author `-fx-opacity` also overrides Modena's disabled-label opacity
  (0.4): inside a disabled container the muted label stays brighter than its disabled neighbours; add a `:disabled`
  rule where that matters.
- Text inputs: `theme.css`'s `.text-input` fill and border; a control drawing its own input box copies the values
  with a "keep in sync" comment, as `git-log.css` does. Change both together.
- Check the result in the real app in the dark theme (`.claude/scripts/run-app.sh`), and in light when colours
  changed.
