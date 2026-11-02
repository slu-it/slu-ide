# 0121. Databases tab: JDBC connections, consoles, results and object tree in the Services menu

- Status: accepted; see [0118](0118-services-menu.md), [0110](0110-tools-menu.md), [0103](0103-todo-folders.md)
- Date: 2026-11-02

## Context
Thoreau should query and browse SQL databases of a project, starting with PostgreSQL and MariaDB, the way IntelliJ's
database tool window and query consoles do, but scoped to one connection at a time. ADR 0118 foresaw database tools in
the Services menu. Database work brings concerns no other feature had: passwords, long-lived server connections,
statements that run for minutes, transactions that must not be lost silently, and result sets too large to load.

## Decision
**Module and entry point**
- New module `databases` (package `dev.sluide.databases`), a member of the editor family like `docker`: it shows an
  `EditorTab` and embeds code editors. Its window bean `Databases` is the second item of the Services menu,
  "Databases", on **Alt+7** (Cmd+7 on macOS). The tab follows ADR 0110/0118: one per window, Shortcut+W closes it
  without a question, not restored after a restart.
- All state (connections, sessions, consoles, results) lives in the window, so closing the tab keeps the connections
  and reopening it shows them again. Closing the window disconnects everything and asks once, through a
  `closeWarnings` hook next to the editor tabs' close warnings, when a Manual transaction is pending, a statement runs
  or grid edits are unsubmitted.
- The tab is a three-way split: connection cards (left), the selected card's consoles over their results (middle,
  only while the card is connected), the selected card's object tree (right).

**Storage and secrets**
- Only the project's connection list is stored, in its order, in the project data's top-level field `databases`
  ([ADR 0090](0090-per-project-metadata-store.md)); without a project in the shared data file. Console texts, results
  and query history live until the window closes (history: until the app restarts) and are never written.
- Passwords are never stored. They are asked on the first connect (or taken from the connection dialog) and kept in
  memory by a singleton `PasswordCache` keyed by project folder and connection id until the app restarts; a failed
  login forgets the password. Safe storage (keychain) is an idea for later (`docs/ideas/databases-later.md`).
- Settings → Databases: default page size (500), fetch-all cap (100,000), statement timeout (none).

**Drivers**
- The PostgreSQL JDBC driver (BSD-2) and MariaDB Connector/J (LGPL-2.1) are bundled, versions from the Spring Boot
  BOM. The MariaDB connector stays an unmodified jar in the fat jar's `BOOT-INF/lib`, which LGPL permits. Connections
  use `DriverManager` with the URL and properties `JdbcUrls` builds (or the user's custom URL); no pool.

**Sessions** (`session`)
- Connecting opens two JDBC connections: the *session* connection runs the consoles' statements, the data tabs and
  edit submits, and holds the transaction; the *metadata* connection serves the tree, completion, DDL and primary-key
  lookups, so browsing never waits for a running statement or joins its transaction. Each has its own single-thread
  executor; nothing touches JDBC on the FX thread. Connecting is a cancellable background task (ADR 0061).
- One statement at a time per connection. Cancel calls `Statement.cancel()`; the statement timeout is
  `setQueryTimeout`.
- Commit mode Auto or Manual per console session (starting from the connection's setting). In Manual mode the
  transaction is pending until Commit or Roll Back once it wrote something: PostgreSQL is asked after each statement
  (`txid_current_if_assigned()`), so writes hidden in functions count too; on MariaDB every statement that is not a
  plain query (`isQuery`: no DML in a `WITH`, no `SELECT … INTO`, no `FOR UPDATE`, no `EXPLAIN ANALYZE`) counts. Leaving
  Manual mode or disconnecting with a pending transaction asks Commit / Roll Back / Cancel; closing the window (or
  opening a folder in a window without one) lists the connections whose transactions would be rolled back and asks
  once.
- Read-only connections set JDBC `readOnly`: the server refuses changes; the grid is not editable.

**Results and paging**
- A query's rows are read page by page (default 500) from an open cursor. PostgreSQL only streams with auto-commit
  off, so in Auto mode a query runs in a transaction that is committed when its cursor closes (not read-only and not
  rolled back: `SELECT nextval(…)` or a writing function keep their effect as under auto-commit; `FOR UPDATE` locks
  last until the cursor closes). A cursor closes when
  the session runs its next statement or the result closes; pages already fetched stay viewable, and moving past
  them re-executes the query and skips the rows already shown (said in the status line). Last and exports stop at the
  fetch-all cap. Values are copied into plain JDK types right away.
- Editing is offered when a result comes from one table whose primary-key columns are all present. Submit sends the
  UPDATE/INSERT/DELETE statements with bind parameters in one batch on the session connection: committed together in
  Auto mode, part of the transaction in Manual mode. Generated SQL quotes identifiers (`SqlNames`).

**Consoles**
- Console tabs per connection sharing the session; each has its own result tabs (unpinned ones are replaced by the
  next run). Run (Shortcut+Enter) runs the selection, else the only statement, else offers a chooser popup (statement
  at the caret, "All statements"). Splitting is our own (`SqlSplitter`: quotes, comments, dollar quotes); `DELIMITER`
  is not supported. Completion of keywords, schemas, tables and columns comes from the metadata cache.

**Tree**
- PostgreSQL partitions are not listed among the tables; a partitioned table shows them in its own "partitions"
  group (a partition in another schema than its parent stays among its own schema's tables, since a schema loads
  only its own relations). Table DDL includes what replaying it needs: `PARTITION BY`/`PARTITION OF`, `CREATE FOREIGN TABLE`, and the
  sequences the table's columns own (as pg_dump does).
- Built from catalog queries per kind (`Introspector`), loaded per schema on expand, synced by key so expansion and
  selection survive a refresh. System schemas are hidden unless Show System Schemas (stored per connection). Show
  DDL opens a `.sql` scratch file ([ADR 0066](0066-scratch-files.md)): PostgreSQL's DDL is assembled from the
  catalog functions (`pg_get_viewdef`, `pg_get_functiondef`, `pg_get_indexdef`, `pg_get_constraintdef`), MariaDB's
  comes from `SHOW CREATE`.

**Network and trust**
- Nothing connects without a click on Connect or Test Connection; opening the tab or the project does not. Connecting
  is not running project code, so there is no trust check.

## Consequences
- The UI logic lives outside the controls (ADR 0084): FX-free session, SQL text, introspection and result models with
  unit tests; real servers are covered by an opt-in smoke test (`SLUIDE_DB_SMOKE=1`) that starts PostgreSQL and
  MariaDB containers.
- The embeddable `TextEditor` of `file-viewers` gains caret, selection and range replacement for the consoles.
- The fat jar grows by about 2 MB of drivers.
- Holding a cursor open keeps a transaction open on PostgreSQL in Auto mode until the next statement ("idle in
  transaction"): it holds `AccessShareLock`s that block other sessions' DDL, holds back vacuum, and a server with
  `idle_in_transaction_session_timeout` ends the connection, which closes the session with an error. MariaDB cancels
  a streamed result's unread rest before the next statement.
- A transaction the user opens in Auto mode (`BEGIN` …) is respected: a query then runs inside it without a cursor
  transaction of its own, and the open transaction counts as pending for disconnect and window close. PostgreSQL
  then loads the query's result at once, so it is limited to the fetch-all cap.
- The idle metadata connection is the first one network or server idle timeouts end; it reconnects by itself (the
  session keeps the password in memory while connected), so a broken tree refresh never closes the session and its
  transaction.
- Out of scope, kept in `docs/ideas/databases-later.md`: keychain storage, SSH tunnels, connections from Docker
  containers, Explain plan, query parameters, other DBMSs, `DELIMITER`, diagrams, DDL editing, dumps, multiple
  result sets per statement, per-console sessions.
- Feature page: [../features/databases.md](../features/databases.md); listed in [../index.md](../index.md).
