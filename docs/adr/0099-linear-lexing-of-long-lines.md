# 0099. Near-linear lexing of lines with many tokens or raw strings

- Status: accepted
- Date: 2026-10-26

## Context
`HighlightingPerformanceTest` failed on the GitHub ubuntu and macOS runners for "Handlebars blanks" and "Scala quotes"
(5 to 7.7 s against a limit of 3 s), which took 0.8 and 1.4 s locally. Both were quadratic, one in RSyntaxTextArea,
one in `RSyntaxHighlighter`:
- **RSyntaxTextArea's token pool** (`DefaultTokenFactory`, private to `TokenMakerBase`) starts with 30 tokens, grows
  by 10 and copies all of them each time it grows: quadratic time in the tokens of one line. In a Handlebars mustache
  every blank is a token of its own; a minified JavaScript line of 200,000 tokens took 1.1 s, of 700,000 tokens 13 s.
- **Raw strings** (`rawStringDelimiter`): after each raw string the lexer started again right after it and lexed the
  rest of the line, so a line full of `"""a"""a…` was lexed again to its end per string.

## Decision
- **A token pool that grows in large steps, set by reflection**: `RSyntaxHighlighter` replaces each new lexer's
  `tokenFactory` with a `DefaultTokenFactory` that grows by a sixteenth of the text's length (at most 8,192 tokens).
  Growth stays quadratic, but with a constant over 800 times smaller for long lines, which is enough in practice.
  Both are private to RSyntaxTextArea, so this uses reflection; if a later version renames or changes them, the
  lookup fails quietly and lexers keep their own pool. The test's "minified line" fails then, which shows it.
- **Lexing stops after the next raw string delimiter**: a line is lexed only up to and including the next delimiter;
  if a raw string starts there, lexing continues after it as before. If not (the delimiter is in a comment or an
  ordinary string), the spans are dropped and the whole line is lexed. Tokens before the delimiter do not depend on
  what follows it, and a string prefix (`s"""`) still sees the string token after it.
- **Cheap lookahead before a long lookbehind**: the Handlebars rules for `@data` and `this` check the word first, so
  that their 200-character lookbehind runs only where it is, as `DIGIT_SEPARATOR` already did.

## Consequences
- The two cases take 25 and 17 ms locally, the minified lines 14 ms (200,000 tokens) and about 0.1 s (1 million).
- Upgrading RSyntaxTextArea needs a look at `TokenMakerBase.tokenFactory` and `DefaultTokenFactory(int, int)`; the
  "minified line" case fails if they change.
- A line where delimiters inside comments or ordinary strings alternate with raw strings is still lexed again per
  raw string; real code does not do that.
- Related: [ADR 0005](0005-syntax-highlighting.md) (RSyntaxTextArea lexers), [ADR 0097](0097-second-file-type-round.md)
  (`rawStringDelimiter` for C#, Go and Scala besides Kotlin), [file-viewers.md](../features/file-viewers.md).
