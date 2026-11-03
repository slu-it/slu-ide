# Databases tab

**Modules:** `modules/databases` (package `dev.sluide.databases`: the window bean `Databases`; connections, the
store, JDBC URLs and the password cache in `connect`; sessions, cursors and transactions in `session`; statement
splitting, completion and the query history in `sql`; catalog introspection and DDL in `meta`; the pure result models
(cell text, paging, edits, export) in `result`; the tab, cards, consoles, results and tree in `ui`; Settings →
Databases in `settings`); menu in `ide` (`WindowTools`); decisions in
[ADR 0121](../adr/0121-databases-tab.md) and [ADR 0118](../adr/0118-services-menu.md)

The Databases tab connects to PostgreSQL and MariaDB databases over JDBC (both drivers are bundled), runs SQL in
consoles, shows and edits the results, and browses each connection's schemas. It is modelled on IntelliJ's database
tool window and query consoles, but shows one connection at a time.

## Opening
- **Services → Databases** (Alt+7, Cmd+7 on macOS) opens the tab, one per window; choosing it again selects it.
  Shortcut+W closes it without a question; it is not restored after a restart.
- **Closing the tab keeps everything:** connections stay open, consoles keep their text and results, and reopening the
  tab shows them again. Closing the window disconnects all connections; when one has an uncommitted transaction, a
  running statement or unsubmitted edits, the window's close question lists it ("orders-dev: uncommitted transaction
  (rolled back)"). Opening a folder in a window without one asks the same way, since the connections then change.

## Layout
Three parts side by side: the connection cards (left), the selected connection's consoles over their results
(middle), and its object tree (right). The middle and the right only show something while the selected connection is
connected; otherwise the middle says "Not connected" with a Connect button and the connection's last error.

## Connections (left)
- **+** adds a PostgreSQL or MariaDB connection through the connection dialog.
- **Cards** show the database's logo, a status dot (grey: not connected; a spinner while connecting; green: connected;
  orange: an uncommitted transaction; red: the last connect failed, with the error in a third line), the name in bold,
  `user@host:port/database` and three buttons: Connect, Disconnect and Edit. A colour chosen in the dialog shows as a
  stripe on the left.
- **Context menu:** Connect or Disconnect, Edit…, Duplicate ("name (copy)"), Delete… (asks; Cancel is the default; the
  database itself is not touched).
- **Keys:** Up/Down select, Enter connects, Delete or Shortcut+Backspace deletes; a double click on a disconnected card
  connects it.
- **Reorder** by dragging a card (an insertion line shows where it goes; Escape cancels). The order is stored.
- Several connections can be connected at once.

## The connection dialog
- **General:** Name (unique in the project), Type, Host, Port (the type's default), Database, User and Password. The
  password field is never filled in; a password typed there is used for Test Connection and the next connect.
- **Options:** Read-only (the server refuses changes; results cannot be edited), the commit mode the consoles start in
  (Auto or Manual), the SSL mode (Disable, Prefer, Require, Verify CA, Verify Full; on MariaDB Prefer connects without
  TLS and Require encrypts without checking the certificate), an optional CA file, and the card colour.
- **Advanced:** the JDBC URL built from the fields; "Custom URL" makes it editable and uses it instead. Driver
  properties as a key/value table.
- **Test Connection** connects once and shows the server's product and version, or the error.

## Passwords and storage
- **Passwords are never stored.** The first connect asks for the password (an empty one is allowed); it is then kept
  in memory until Thoreau quits. A failed login forgets it, so the next connect asks again.
- The connections themselves are stored per project in `~/.sluide/projects/<uuid>.json` (field `databases`, only the
  values that differ from the defaults), or in `~/.sluide/shared.json` for a window without a project. They are plain
  text: host, port, database, user name, options and driver properties.
- Console texts, results and the query history are not stored.

## Consoles (middle, top)
- Each connection has console tabs: "Console", and more with **New Console** (+). All consoles of a connection share
  its session and transaction. Each console has its own results below it; switching consoles switches the results.
  Closing a console asks when it still runs statements or has unsubmitted edits.
- **Toolbar:** Run (Shortcut+Enter), Cancel (Shortcut+F2), the commit mode **Auto | Manual**, Commit
  (Shortcut+Alt+Shift+Enter) and Roll Back (enabled while a transaction is open), History (Shortcut+Alt+E) and the
  current schema.
- **What Run executes:** a selection runs as selected (several statements in it run one after the other); without one,
  a single statement runs; with several, a popup offers the statement at the caret or all statements. Statements are
  split at `;` outside strings, quoted names, comments and PostgreSQL dollar quotes; MariaDB's `/*! … */` comments
  count as code. `DELIMITER` is not supported: select a procedure body to run it as one statement.
- Statements run one at a time per connection, in order; a run stops at the first error. A PostgreSQL error with a
  position underlines it in the console. Settings → Databases can set a statement timeout.
- **Completion** of keywords, schemas, tables and views, and the columns of the tables in the statement (also through
  aliases), from the tree's catalog cache; it opens by itself after `.` or two letters and with Ctrl+Space (on macOS
  the input-source switch may take Ctrl+Space first). Prefix matches only, at most 200.
- **History:** the statements run on the connection, newest first, at most 200, kept until Thoreau quits; a filter
  narrows them and Enter inserts the statement at the caret.

## Transactions
- **Auto:** each statement is committed by itself. A `BEGIN` typed in the console is respected: the transaction stays
  open (the dot turns orange) until a typed or toolbar Commit or Roll Back.
- **Manual:** everything runs in one transaction until Commit or Roll Back. PostgreSQL is asked after each statement
  whether the transaction wrote something, so writes inside functions count; on MariaDB every statement that is not a
  plain query does.
- Switching from Manual to Auto, disconnecting and deleting a connection with an open transaction ask: Commit, Roll
  Back or Cancel.
- On PostgreSQL a query in Auto mode runs in a short transaction that ends when its result is closed (so large results
  can be read page by page). While a result is open, it holds its locks; `SELECT … FOR UPDATE` locks stay until the
  next statement.

## Results (middle, bottom)
- **Output** lists every statement with its time, duration, row or update count, or its error.
- **Result tabs:** a query's rows open in a result tab titled with its table, else "Result". The next run replaces the
  unpinned tab; **Pin Tab** in the tab's context menu keeps it. "Close Other Result Tabs" keeps pinned tabs and tabs
  with unsubmitted edits.
- **Paging:** rows are read page by page (500 by default) with First, Previous, Next and Last, a page size field and
  "1–500 of 500+" until the end is known. Last and export stop at the fetch-all limit (100,000 by default). Reload runs
  the query again; it is off for statements that change data.
- **Grid:** a row number column, `<null>` in grey, long text cut with "…" and line breaks shown as "⏎", binary values
  as hex. Clicking a header sorts the page (console results) or adds an ORDER BY and reloads (table data).
  Shortcut+C copies the selected cells as tab-separated text; the context menu also copies with the header.
- **Table data tabs** (from the tree) have WHERE and ORDER BY fields; Enter reloads with them.
- **Export:** copy the selection or all rows, or open all rows as a scratch file, as CSV, TSV, JSON, SQL INSERTs or a
  Markdown table. SQL INSERTs for MariaDB assume backslash escapes (not `NO_BACKSLASH_ESCAPES`).
- **Value viewer** (Shift+Enter): the whole cell in an editor, highlighted as JSON or XML when it is one; editable when
  the result is.
- **Transpose** shows one row per column of the current page; paging and editing are off while it is on.

## Editing results
- A result can be edited when it comes from one table whose primary key columns are all in it, the connection is not
  read-only, and the statement was a query. Otherwise a lock icon names the reason.
- Enter, F2 or a double click edits a cell (Enter keeps, Escape drops); typed text is read by the column's type.
  Add Row, Delete Rows, Set NULL and Revert are in the toolbar and the context menu. Changed cells, added and deleted
  rows are tinted until submitted.
- **Submit** (Shortcut+Enter in the grid) sends the changes as UPDATE, INSERT and DELETE statements keyed by the
  primary key: in Auto mode as one transaction (asking first when rows are deleted, since that is permanent); in Manual
  mode or inside a `BEGIN` as part of the open transaction, through a savepoint, so a failed submit leaves it as it
  was. Every UPDATE and DELETE must hit exactly one row, else nothing is changed and the error says the row was changed
  by someone else. The result is reloaded afterwards, on the page it showed.
- While edits are pending, paging, Reload, sorting and the filters are off; closing the tab asks.

## Object tree (right)
- The selected connection's schemas (MariaDB: databases); PostgreSQL shows the connected database only. System
  schemas are hidden unless **Show System Schemas** (stored per connection) is on.
- Under a schema, loaded on expand: Tables (columns with type, nullability and default; keys, foreign keys, indexes,
  checks and triggers), Views and Materialized Views (PostgreSQL) with their columns, Routines (functions and
  procedures), Sequences, Types (PostgreSQL enums, domains and composite types) and Events (MariaDB). A partitioned
  table lists its partitions under "Partitions".
- **Refresh** (F5) reloads; expansion and selection stay. Collapse All folds everything.
- **Enter or double-click** on a table or view opens its data in a table data tab.
- **Context menu:** Open Data, Show DDL (opens the definition as a `.sql` scratch file: PostgreSQL's is built from the
  catalog, including partitioning, foreign tables and owned sequences; MariaDB's comes from `SHOW CREATE`), Copy Name,
  Copy Qualified Name, Generate SELECT (inserts a SELECT of the columns into the console) and Refresh.

## Settings
Settings → Databases: rows per result page (500), the most rows Last and export fetch (100,000) and the statement
timeout in seconds (0: none). They apply at once.

## Limits
- Only PostgreSQL and MariaDB. More is collected in [databases-later.md](../ideas/databases-later.md).
- A submit that waits for another session's row lock ends only by the statement timeout or a disconnect.
- On PostgreSQL inside a `BEGIN` in Auto mode, a query's result is read at once (up to the fetch-all limit), since
  the driver streams only outside it.

## Keys
See [keyboard-shortcuts.md](keyboard-shortcuts.md), "Databases tab".
