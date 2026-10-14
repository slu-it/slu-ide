# 0009. Light and dark themes via CSS media queries

- Status: accepted
- Date: 2026-10-13

## Context
Users want a light or a dark IDE, or one that follows the operating system. JavaFX ships only the light Modena
stylesheet. Several modules have their own stylesheets (`syntax.css`, `editor.css`, `settings.css`), and `file-viewers`
does not depend on `settings`.

## Decision
- `ThemeSettings` (in `modules/settings`, stored as `theme` in `settings.properties`, see [ADR 0008](0008-settings.md))
  holds Light, Dark or System (default) and the resulting `ColorScheme`; System follows
  `Platform.getPreferences().colorSchemeProperty()`, which is read lazily because it may only be used on the FX thread
  and Spring creates the bean on the launcher thread.
- `ThemedWindows` sets `Scene.getPreferences().colorScheme` (JavaFX 25+) on every window in `Window.getWindows()`,
  including dialogs and popups, and adds `theme.css`, which re-tints Modena's looked-up colours (`-fx-base`,
  `-fx-background`, …) inside `@media (prefers-color-scheme: dark)` (JavaFX 24+).
- Stylesheets that set their own colours add `@media (prefers-color-scheme: dark)` rules for them (`syntax.css`,
  `file-explorer.css`); stylesheets that only use Modena's looked-up colours (`editor.css`, `preview-view.css`,
  `settings.css` apart from its always-dark terminal preview) need none, `theme.css` re-tints those. Either way
  modules need no dependency on `settings` to be themed. Pages in a `WebView` (previews, images) are not JavaFX
  stylesheets: they follow the scene's colour scheme through a `dark` class set from Kotlin, see
  [ADR 0016](0016-markdown-preview.md).
- The terminal stays dark in every theme.
- Not chosen: swapping whole stylesheets per theme (every module would have to know the theme), replacing Modena via
  `Application.setUserAgentStylesheet` (we would have to maintain a complete stylesheet), a third-party theme library.

## Consequences
- Dark styling of a new view comes for free with Modena's looked-up colours; colours of its own need a
  `@media (prefers-color-scheme: dark)` block in its stylesheet.
- JavaFX 27 applies rules with descendant selectors (e.g. `.code-view .keyword`) inside `@media` blocks whatever the
  colour scheme. Dark rules must use single-class selectors, and the light rules they override must have the same
  specificity; `syntax.css` therefore uses single classes, which is safe as it is only added to code views.
  `PythonFileViewerUiTest` checks that the token colours switch.
- Theme changes apply live to all open windows; the system theme follows OS changes while the IDE runs.
