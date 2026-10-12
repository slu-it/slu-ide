---
paths:
  - "**/*.kt"
  - "**/*.kts"
---

# Kotlin style

- Official Kotlin code style; 4-space indent, trailing commas in multi-line parameter/argument lists.
- Prefer immutable `val`, expression bodies for one-liners, constructor injection for Spring beans.
- No Java sources. No wildcard imports. Avoid `!!`; model absence with nullable types.
- Public API gets KDoc when the intent is not obvious from the name; do not comment what the code already says.
- Gradle Kotlin DSL: take versions from the version catalog (`libs.*`), never inline.
