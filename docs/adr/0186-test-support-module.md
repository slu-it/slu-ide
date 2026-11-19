# 0186. A test-only module for generic test helpers

- Status: accepted
- Date: 2026-11-19

## Context
Test sources of several modules held copies of the same helpers: `LogCapture` (the records of a JDK platform logger) in
five modules, six inline logging handlers (http-client, command-runs, docker, file-explorer, file-viewers), and three
helpers that build a string with the hash code of another one (databases, docker, http-client). The copies had drifted:
one was `internal`, one lacked `messages()`, one formatted parameters and the others did not, and the collision helpers
differed in which characters they changed. No foundation module fits: they are production code, and a test helper there
would reach the fat jar's dependencies. `testFixtures` source sets belong to one module and carry its domain fakes ([ADR
0047](0047-module-dependencies-and-shared-modules.md)).

## Decision
- A module `modules/test-support`: a plain Kotlin library in package `dev.sluide.testsupport`, without Spring, JavaFX,
  test frameworks or module dependencies.
- Other modules use it only as `testImplementation(project(":modules:test-support"))`, never from main code or
  `ide`'s runtime; each such edge has its row in the "Module dependencies" table of `docs/architecture.md`. The root
  `build.gradle.kts` enforces it: while a module's configurations are set up, a dependency on `:modules:test-support`
  in a configuration whose name does not start with `test` fails the build.
- It holds generic helpers without domain knowledge: `LogCapture` (level, formatted message and throwable per
  record; the logger's level and handler restored on `close()`; a pattern that cannot be formatted stays raw),
  `collidingWith` and `collidingLettersWith`. Domain fakes (fake servers, builders of a module's types) stay in the
  module's `testFixtures`.
- `collidingWith(word, normalized)` changes the last two characters (first -d, second +31 d, d from 1 to 4) and takes
  the first candidate that `normalized` leaves as it is; callers pass the normalization the code under test applies
  (`{ it.trim().lowercase() }`), so the string still reaches the `when`. It fails with a message if every candidate
  leaves the range of a `Char` or is changed.
- `language-servers`' `LogRecords` stays: it takes a `Logger` object, leaves the logger's level alone and filters by
  level. The language servers log at DEBUG, and `LogCapture`'s `Level.ALL` would change what the `messages()`
  equality assertions of its 14 test classes see.

## Consequences
- One copy to fix; a helper change is tested once, in the module itself.
- Every new helper is checked against the "generic, no domain" rule before it goes in.
