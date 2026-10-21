# 0072. CSV table: header guess and state across renders

- Status: accepted
- Date: 2026-10-21

## Context
The CSV/TSV preview ([ADR 0017](0017-webview-previews.md)) took the first row as the header and sorted by a clicked
header. Many files have no header, so their first row of data turned into labels and was left out of sorting. Users
also want to search per column. Every save renders the page again (`loadContent`), which lost the sort, and would
lose a header choice and searches too.

## Decision
- **Header guess with an override:** `hasHeader` (`HeaderGuess.kt`) gives each column below the first row (up to 200
  rows, empty cells ignored) a type: number (read as `csv-table.js` reads it), date (ISO, `dd.mm.yyyy`,
  `mm/dd/yyyy`) or text. A number or date column votes for a header if its first cell is non-empty and of another
  type, against one if of the same type; text columns don't vote. More votes for, a tie, no votes or fewer than two
  rows mean a header. A "First row is header" checkbox starts with the guess and overrides it. Without a header the
  labels are spreadsheet letters (A, B, …, AA). Cookie jars always have a header and no checkbox.
- **All records in the body:** the first record is always a body row (`tr.csv-first`); with a header, CSS hides it
  and the label row carries its `data-line`, so the checkbox needs no new page and scroll sync skips the hidden row.
- **State across renders, like folds:** `TableState` (header choice, sort column and direction, filter texts) is read
  from the current page through `sluide.table.state()` before the next `loadContent`, as `FoldState` does for the
  JSON and XML folds. The renderer writes it into `data-state` (and the filter values) and `csv-table.js` restores
  sort and filters. The header choice is kept only once the user clicked the checkbox; otherwise each render guesses
  again, so the guess follows edits.
- **Simple search syntax:** a field matches cells that contain its text, ignoring case, or, if its operands are
  numbers, compares: `>N`, `>=N`, `<N`, `<=N`, `=N`, `N..M`. All filled fields must match. No regular expressions,
  no OR.

## Consequences
- A third click on a label returns to file order (by `data-line`), so the sort can be undone without a save.
- The guess can be wrong for text-only files (they count as having a header); the checkbox fixes that and the choice
  survives saves.
- Search covers only the rows shown (at most 10,000 below the first record).
- Listed in [../index.md](../index.md).
