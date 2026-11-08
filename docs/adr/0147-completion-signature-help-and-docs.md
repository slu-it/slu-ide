# 0147. Completion, signature help and hover docs from the language server

- Status: accepted; amends [0145](0145-code-intelligence-module.md) (`CaretPopup` Tab callback, popups above their
  anchor); see [0141](0141-editor-api-for-language-features.md), [0142](0142-language-server-foundation.md),
  [0144](0144-python-via-ty.md), [0084](0084-testability-logic-out-of-controls.md), [0012](0012-file-icons.md)
- Date: 2026-11-08

## Context
The code intelligence module ([ADR 0145](0145-code-intelligence-module.md)) binds Python editors to ty and offers a
caret popup and a hover popup, but nothing fills them yet. A language server offers completion, signature help and
hovers; what it sends needs decisions on the client side: when to ask (every keystroke costs a request, and ty sends a
thousand items for one letter, always marked incomplete), how to filter and rank between answers, how to apply an item
whose ranges were computed for an older text (typing goes on while the answer travels), what to do with the
auto-import edits ty attaches, and how to show documentation that arrives as Markdown with HTML entities and escaped
underscores in popups that must not take the focus.

## Decision
Three features in `code-intelligence`, each a `BindingFeature` in `builtInFeatures` before `HoverFeature`
(completion, signature help, server hover; so the server's hover section comes after the diagnostics'). The logic is
in plain classes and functions, tested without a toolkit ([ADR 0084](0084-testability-logic-out-of-controls.md)):
`CompletionModel`, `rankCompletions`, `acceptEdits`, `snippetText`, `autoTrigger`, `SignatureModel`,
`signatureShown`, `parameterRange`, `markdownBlocks`, `popupPosition`.

- **Documentation** (`MarkupDocument.kt`, `MarkupNodes.kt`): commonmark-java (already used by the previews) parses
  `MarkupContent` into a small block model (`DocBlock`: paragraph, heading, code, list, quote, rule; spans with strong,
  emphasis, code), which `MarkupView` turns into JavaFX `TextFlow`s: headings bold, code in monospace on the panel
  background, lists with bullets or numbers, rules as lines. commonmark decodes entities (`&nbsp;`, `&gt;`, numeric)
  and backslash escapes (`\_`); links show their text only (a `Hyperlink` would be focusable and act on Enter), images
  their alt text, raw HTML is dropped. Plain text keeps its lines; a bare string is plain text, the old `MarkedString`
  with a language is code. `MarkupView` is at most 600 px wide (wrapping) and reports the height of the text wrapped
  at its width, so popups size to it; long docs scroll in a capped scroll pane. Quotes and lists nest at most 8 deep;
  deeper ones are flattened into the deepest level without recursion, so hostile docs cannot overflow the stack in
  parsing or layout. Colours are looked-up ones.
- **Hover docs** (`ServerHoverFeature`): a `HoverProvider` asking `textDocument/hover` at the offset through a
  `RequestSlot` (latest wins; closing a question cancels its request) and answering one `MarkupView` section; nothing
  while the server does not run or does not offer hovers, and nothing for an empty answer.
- **Completion triggers:** a typed server trigger character asks at once (`TriggerCharacter`; ty: `.`, `"`, `'`); a
  typed word char (`[\p{L}\p{N}\p{Mn}\p{Mc}_]`, by code point) at the end of a word that does not start with a digit asks after 100 ms
  (`Invoked`, debounced; already the first letter, as editors do); Ctrl+Space asks at once and shows "Searching…"
  (literal Ctrl on every platform, as the databases console). "Typed" means the change inserted exactly the character
  of the last `KEY_TYPED` since the last key press, without replacing a selection, with the caret behind it: undo,
  redo, paste and applied edits never open it. A typed char is known from event filters on the text area
  (`TypedKeys`, shared with signature help), as `TextChange` does not tell its source. Keys typed with Ctrl or Meta
  are left out, so characters typed with AltGr on Windows (Ctrl+Alt) do not open it, and neither do input method
  commits (no key typed); Ctrl+Space still works there. Not while text is selected; nothing without a running server offering
  completion. Trigger characters come from the static capabilities only.
- **While open:** each change asks again after the debounce (`TriggerForIncompleteCompletions`) if the last answer
  was incomplete (ty's always are), then filters and ranks that answer's items at once, so the list never empties
  while a request travels (an incomplete list matching nothing for now waits for the answer instead of closing). A `RequestSlot` lets the latest request win. The list changes only when something
  matches; a fresh answer with no match closes it, as do the caret leaving the word (before its start, or the word's
  start moving: a space, a `.`), Escape, focus loss and taking an item.
- **Ranking** (`rankCompletions`): the prefix is the word chars before the caret; `filterText` (else `label`) is
  matched ignoring case: prefix matches first, then matches through part starts (after `_`, at an upper case letter
  after a lower one, from any part: `gcw` in `get_cwd`, `cwd` in `get_cwd`), then subsequences. Within a group
  `preselect` items first, then `sortText` (else `label`). Only items that look and act the same show once (same
  label, label details, detail, inserted text and additional edits): ty sends a name once per module it can import it
  from (`panel` with " (import rich.align)", " (import rich.styled)", each with its import edit), and each shows.
  At most 200. The part-start match remembers what it decided per position pair, so a name of many parts costs at
  most prefix × name steps on the FX thread.
- **Popup:** `CaretPopup` anchored at the word start, the row the label with `labelDetails.detail`, at the right the
  description or detail on one line shortened to 40 characters (ty repeats whole signatures there) or the kind's name,
  and a Material Design icon per kind from one table (`CompletionKinds.kt`), coloured by group (callable, type, value,
  module) through looked-up colours with dark counterparts. ty sends attributes as `Struct`, so `Struct` looks like a
  field and is called "attribute". Beside the list the selected item's detail (as code) and documentation; an item
  without documentation is resolved (`completionItem/resolve`, its own slot) if the server offers it, and the pane
  shows again with the answer.
- **Taking an item** (`acceptEdits`): Enter and Tab (`CaretPopup` got an `onTab` callback). The text is the
  `textEdit`'s, else `insertText`, else the label; an `InsertReplaceEdit` uses its insert range for Enter and its
  replace range for Tab. Without a `textEdit` the text replaces the typed prefix (Enter) or the whole word (Tab).
  `additionalTextEdits` (ty's auto-import) go into the same `applyEdits` call: one undo step. Ranges belong to the text
  of the request; the model tracks the lowest offset changed since (the text before it is still the request's).
  Text and caret unchanged: the server's ranges as sent. Otherwise the item's own edit starts at the server's start if
  that lies before the first change (untouched text: `import os.pa` with the new text `os.path` keeps its start at
  `os`), else at the word's start, and ends at the caret (Enter) or the word's end (Tab); an additional edit is kept only if it ends at or before the first change, where
  its positions still hold; the others are left out with a log line (an auto-import above the typed line, the usual
  case, stays). Additional edits that overlap the item's own are left out too; the others come first in the list, so
  an import inserted where the item's text starts goes before it. Thoreau declares no snippet support;
  a snippet anyway is inserted as its plain text (`snippetText`: tab stops dropped, placeholders' and the first
  choice's text kept). `commitCharacters` are not supported: only Enter, Tab and a double-click take an item.
- **Signature help** (`SignatureFeature`): Shortcut+P asks (`Invoked`); a typed server trigger character (ty: `(`,
  `,`) asks at once, a retrigger character (ty: `)`) while open too; while open, every other change and caret move
  asks again after 100 ms (`ContentChange`, `isRetrigger`, with the shown answer). An answer without signatures (the
  caret left the call) closes it; Escape and focus loss too. The popup (`SignaturePopup` on `EditorPopupWindow`,
  which got `preferAbove`) stays where it was first asked (moved with edits before that place), above that line (below only if it fits there and not
  above), leaving the lines below to the completion popup. It shows the active signature (`activeSignature`, clamped)
  in monospace with the active parameter in bold (the signature's `activeParameter`, else the answer's; its label's
  offsets, or its label text found after the `(` as a whole word), "2 of 3" when there are several, and the
  parameter's and the signature's documentation below.
- **Escape with both popups:** each popup's Escape binding acts only while it shows, so the first Escape closes the
  one whose binding came first (the signature popup's, added with its feature), the next the other.

Rejected:
- **A trigger on any one-character insertion** (the databases console's rule): an undo of a typed `.` would open the
  completion; the key filters cost a few lines.
- **Waiting for the fresh answer before filtering:** the list would empty or jump on every keystroke while ty answers.
- **Dropping every additional edit of a stale answer:** ty's auto-imports would vanish whenever typing outran the
  server, the common case.
- **A WebView for the docs:** heavy for a popup, takes the focus, and its links would navigate.

## Consequences
- Python editors complete, show signatures and hover docs from ty; other servers get the same once their spec exists.
- Two requests per pause in typing at most (debounced), plus one per trigger character; a resolve per selected item
  without docs.
- An item taken after the text changed above the request's place loses its additional edits (logged).
- Trigger characters a server registers dynamically are not followed.
- Listed in [../index.md](../index.md).
