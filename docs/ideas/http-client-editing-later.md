# HTTP client editing: ideas left for later

Follow-ups of the completion in `.http` files ([ADR 0195](../adr/0195-http-file-completion.md),
[http-client.md](../features/http-client.md)) that the first round left out. Each would build on the context detection
of `HttpCompletion` (it already knows when the caret is inside `{{ }}`) and on code-intelligence's `HoverProvider` and
navigation.

- **Underlining unresolved `{{variables}}` while typing.** A `{{name}}` that no `@name` line of the file, no `$`
  variable and no environment of the chosen set defines would get a squiggle, as a typo in a name shows only on Send
  today. Useful because the failure otherwise comes after the request left with a literal `{{name}}`. It would reuse
  the variable lookup of the completion (file `@` lines, environments, `$` names). Open questions: whether a name
  defined in only some environments counts as resolved (probably yes, with a hint naming the others), how a
  `{{obj.key}}` into an object is checked, and how often to re-read the environment files while typing.
- **A hover on `{{name}}`.** Shows where the variable is defined (the `@` line, or the environment file) and its value
  in the chosen environment. A value that comes from the private env file is masked, as the visual environment editor
  does; values are never put in a tooltip unmasked. It would be a `HoverProvider` for `.http` editors in `ide`, asking
  http-client for the resolution of the name at the offset. Open questions: whether a dynamic variable (`$uuid`)
  shows its description instead of a value, and whether the hover may reveal a masked value, which the environment
  editor does only with its **Show values** toggle ([http-client-env-editor.md](../features/http-client-env-editor.md)).
- **Going to a variable's definition.** Go to Definition (Shortcut+B) on `{{name}}` jumps to its `@` line in the file,
  or to its key in the `http-client.env.json` that defines it. It builds on the same resolution as the hover and on
  the navigation of code-intelligence (history entry, opening the env file in its editor). Open questions: which
  environment's file to choose when several define the name (a list, as for several definitions in code), and the
  private file's key (opens it, which shows the value in its editor).

## Completion follow-ups

Found in the review of the completion.

- **The popup does not reopen after taking `$env.`, `$random.alphabetic(` or `Name: `.** The accepted text reaches
  `AutoTrigger` with `typed == null`, so no trigger applies, and the user presses Ctrl+Space for the next step. Idea:
  ask again after an accepted item whose inserted text ends in one of the source's trigger characters, or in `(` or
  `: `.
- **Tab right after an answer acts like Enter for source items.** `sourceItems` builds a plain `TextEdit`, so the
  rest of the word is not replaced. Idea: an `InsertReplaceEdit` (insert `[anchor, caret)`, replace
  `[anchor, wordEnd)`), so that the Tab rule of [code-intelligence.md](../features/code-intelligence.md) holds for
  sources too.
