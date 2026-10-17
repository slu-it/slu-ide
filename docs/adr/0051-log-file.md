# 0051. Log file in ~/.sluide/logs

- Status: accepted
- Date: 2026-10-17

## Context
There was no logging configuration, so Spring Boot's Logback defaults applied: console only, level INFO. The modules
log through `System.getLogger` (JUL), which Spring Boot bridges into Logback (`jul-to-slf4j`), so their lines went to
stdout as well. Started as `Thoreau.app` ([ADR 0011](0011-native-app-packaging.md)) nobody sees stdout, so the log
was lost exactly when a user would need it.

## Decision
- `ide/src/main/resources/logback-spring.xml` (the `-spring` name lets Spring Boot apply it, with its defaults)
  keeps Spring Boot's console appender and adds a file appender, root level INFO for both.
- The file is `~/.sluide/logs/sluide.log`, next to the settings and remembered projects; technical name, not "Thoreau"
  ([ADR 0032](0032-product-name-thoreau.md)). The folder is chosen by `LogFolder` (a Logback property definer), which
  creates it. When it cannot take the file (read-only `~/.sluide`, `logs` or `sluide.log` of the wrong kind),
  `sluide-logs-<user>` in the temp folder is used instead (a Logback status warning says so): Spring Boot treats a file
  Logback cannot open as a configuration error and does not start, and a read-only `~/.sluide` must not stop the IDE
  ([settings](../features/settings.md)).
- A new file starts at 10 MB; at most 5 files exist (`sluide.log`, `sluide.1.log` … `sluide.4.log`, oldest dropped):
  `FixedWindowRollingPolicy` with a `SizeBasedTriggeringPolicy`. Rejected: Spring Boot's `logging.file.*` and
  `logging.logback.rollingpolicy.*` properties: their policy rolls by date and size into dated, compressed files,
  `max-history` counts days, and `total-size-cap` bounds the size but not the number of files.
- Once the Spring context has started (`SluIdeApplication.init`), a default uncaught exception handler logs the
  exception with its thread (`logUncaught`). It also gets exceptions of JavaFX event handlers, which the toolkit hands
  to the thread's handler, and those of shutdown hooks. Before Spring has configured logging the JVM's own print to
  stderr stays: Logback would suppress the lines or print them in its default format to stdout.
- It logs through SLF4J, not `System.Logger` like the rest of the code: Spring Boot removes its bridge from
  `java.util.logging` when the context closes, before the beans are destroyed, after which `System.Logger` lines are
  dropped (checked: the shutdown hook's exceptions vanished entirely). As the last step of shutting down Spring Boot
  stops Logback (`logging.register-shutdown-hook`); from then on the handler prints to stderr as the JVM would.
- The system property `sluide.log.dir` replaces the folder. The build passes it to every test task (an argument
  provider, so the absolute path stays out of the cache key) as the module's `build/test-logs`, because tests that
  start Spring (`SpringWiringTest`, `LogFileTest`) would otherwise write to the real home.

## Consequences
- Logs of the packaged app survive, about 50 MB of them (Logback checks the size now and then, so a file can grow a
  little past 10 MB).
- Two IDE processes at once (nothing prevents that) write to the same files, and their renames when rolling race.
  Accepted: rare, and at worst lines end up in an unexpected file.
- Spring Boot's `logging.threshold.file` has no effect: the file appender is our own and has no threshold filter.
- Only what goes through the logger reaches the file: the command-line warnings of `startupFolders` and uncaught
  exceptions before the Spring context has started still go to stderr only. `System.Logger` lines from before logging
  is configured or while the beans are destroyed at shutdown (e.g. `ProjectWindows.destroy`, the file watcher's
  warnings racing its close) are dropped.
