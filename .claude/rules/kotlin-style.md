---
paths:
  - "**/*.kt"
  - "**/*.kts"
---

# Kotlin code

- Formatting is Spotless/ktlint's job (`ktlint_official`, max line length 120, `.editorconfig`): `verify.sh` runs
  `spotlessApply`; do not fight the formatter. ktlint keeps imports that a removal left unused, but the full build
  fails on them: `.claude/scripts/check-unused-imports.sh <files>`.
- Prefer immutable `val`, expression bodies for one-liners, constructor injection for Spring beans.
- No Java sources. No wildcard imports. Avoid `!!`; model absence with nullable types.
- Public API gets KDoc when the intent is not obvious from the name; do not comment what the code already says.
- Gradle Kotlin DSL: take versions from the version catalog (`libs.*`), never inline.
- Before working around a library you think is missing, grep `gradle/libs.versions.toml` and the other modules'
  `build.gradle.kts`: it is often already there.
- Decide by an exact rule, not a heuristic: tell the app's own events from the user's by what they are (an expected
  position, a flag), not by a time window; skip a known prefix by its pattern, not by searching for the text; work on
  the parsed document, not with a regex over raw markup. If only a heuristic seems possible, say so in the report.
- Inside `X().apply { ... }`, a name the receiver also has (`width`, `text`, `id`) resolves to the receiver's
  property, not to an outer parameter or variable; name parameters so they cannot be shadowed.
- JavaFX tells invalidation listeners before change listeners (`subscribe { }` is a change listener): a value other
  code reads when a binding fires (a `@Volatile` mirror for another thread) is updated in an `InvalidationListener`
  registered before that binding, or a listener of the binding can still read the old value.
