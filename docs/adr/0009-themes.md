# 0009. Light and dark themes via CSS media queries

- Status: accepted
- Date: 2026-10-13

## Context
Users want a light or a dark IDE, or one that follows the operating system. JavaFX ships only the light Modena
stylesheet. Several modules have their own stylesheets (`syntax.css`, `editor.css`, `settings.css`), and `file-viewers`
does not depend on `settings`.

## Decision
- `ThemeSettings` (in `modules/settings`, stored as `theme` in `settings.properties`, see [ADR 0008](0008-settings.md))
  holds Light, Dark (default) or System and the resulting `ColorScheme`; System follows
  `Platform.getPreferences().colorSchemeProperty()`, which is read lazily because it may only be used on the FX thread
  and Spring creates the bean on the launcher thread.
- `ThemedWindows` sets `Scene.getPreferences().colorScheme` (JavaFX 25+) on every window in `Window.getWindows()`,
  including dialogs and popups, and adds `theme.css`, which re-tints Modena's looked-up colours (`-fx-base`,
  `-fx-background`, …) inside `@media (prefers-color-scheme: dark)` (JavaFX 24+).
- Stylesheets that set their own colours add `@media (prefers-color-scheme: dark)` rules for them (`syntax.css`,
  `file-explorer.css`); stylesheets that only use Modena's looked-up colours (`editor.css`, `preview-view.css`,
  `view-toolbar.css`, `settings.css` apart from its always-dark terminal preview) need none, `theme.css` re-tints those. Either way
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
- In dark, text inputs (`.text-input`: text fields, text areas, editable combo boxes, spinners) get their own fill
  (`#36393b`) and a border lighter than it, since a field in the pane colour with Modena's darker border is hard to
  see as one. Code editors (`.code-area`) and the Fonts page's editor preview keep the pane colour; `ThemeUiTest`
  checks both.

## Amendment 2026-11-02: Dark is the default
A missing or unknown stored theme now means Dark, not System. The user works mostly in Dark, and Dark is where visual
problems hide, so a fresh install and every sandbox check should see it. System stays selectable and still follows the
operating system, falling back to Light when the OS reports nothing. A stored value is always honoured.

## Amendment 2026-11-13: Icons take the text colour by default
Ikonli's `FontIcon` draws black unless a stylesheet sets `-fx-icon-color`, so every module had to add an
`.x .ikonli-font-icon` rule, and each icon outside one stayed black in dark (the review window's file filter, the ebook
reader's zoom buttons, …). `theme.css` therefore gives every `.ikonli-font-icon` `-fx-text-base-color` by default.
JavaFX ranks rules of scene and node stylesheets by specificity first and lets a node stylesheet win only a tie. The
default is one class, the lowest specificity, so any class rule in a module's node stylesheet ties or beats it; a
stylesheet on a scene, which `ThemedWindows` puts before `theme.css`, loses a tie and needs a more specific selector.
`ThemeUiTest` checks the default and a module colour. Module rules that only repeat the default are redundant but
harmless. Not chosen: `-fx-text-background-color` as the default, which would follow selected cells and menu items
but turn icons on default buttons dark in light; icons on a selection keep their own rule.
