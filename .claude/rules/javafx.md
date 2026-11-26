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
- `requestFocus()` on a node that is not in a scene yet, or hidden, is dropped silently. A `SplitPane` (and other
  skinned containers) put their items into the scene only once skinned, a pulse after the container: wait for the
  node itself (`FocusWhenShown` on that node), not for its container or the view's root (ADR 0192).
- Pages go into a WebView through `WebEngine.loadPage` (`previews`), never `loadContent`, except `loadContent("")`
  resets: `loadContent` shows every emoji as six U+FFFD (ADR 0190).
