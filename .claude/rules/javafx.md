---
paths:
  - "modules/*/src/main/**/*.kt"
  - "ide/src/main/**/*.kt"
---

# JavaFX listeners and observable values

- JavaFX tells invalidation listeners before change listeners (`subscribe { }` is a change listener): a value other
  code reads when a binding fires (a `@Volatile` mirror for another thread) is updated in an `InvalidationListener`
  registered before that binding, or a listener of the binding can still read the old value.
- A default `ObservableValue` that all implementations of an interface share is a `ConstantValue`/`ConstantBoolean`
  (file-viewers), never a top-level `ReadOnly*Wrapper(...).readOnlyProperty`: a shared property keeps every listener,
  and with it every window that subscribed (ADR 0180).
- Pages go into a WebView through `WebEngine.loadPage` (`previews`), never `loadContent`, except `loadContent("")`
  resets: `loadContent` shows every emoji as six U+FFFD (ADR 0190).
