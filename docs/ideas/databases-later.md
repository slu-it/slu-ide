# Idea: Database features left for later

**Status:** idea, collected 2026-11-02 while planning the Databases tab ([ADR 0121](../adr/0121-databases-tab.md));
nothing decided or built. Each needs a decision of its own before it is built.

## Connections
- **Safe password storage:** the macOS Keychain (`security`), the Linux Secret Service (`secret-tool`) or an own
  encrypted store, per connection opt-in ("Save password"). Today passwords live in memory until the app quits.
- **SSH tunnels** to reach databases behind a bastion host.
- **Add from Docker:** offer running postgres, mariadb and mysql containers from the Docker tab as new connections, with
  host, port, user and database prefilled from their ports and environment.
- **More database systems:** MySQL (Connector/J or the MariaDB driver), SQLite, H2, SQL Server, Oracle; drivers
  downloaded on demand instead of bundled.
- **Several databases per PostgreSQL connection** in the tree (IntelliJ's "show all databases").

## Consoles
- **Explain plan** and Explain Analyze for the statement at the caret, as a tree (PostgreSQL) or a table (MariaDB).
- **Query parameters** (`?`, `:name`) with a dialog for their values.
- **`DELIMITER`** for MariaDB scripts with procedure bodies.
- **Separate sessions per console** instead of one shared session per connection.
- **Storing console texts and the query history** per project (both are memory-only today).
- **SQL formatting.**

## Results
- **Several result sets per statement** (procedures returning more than one).
- **A cancellable submit**, and a lock timeout for submits.
- **Keeping the page and scroll position** across a console re-run.

## Schema
- **DDL editing:** create, rename, drop and truncate from the tree.
- **Schema diagrams** and **schema or data compare**.
- **Dump and restore** through `pg_dump`/`mysqldump` in a terminal.
