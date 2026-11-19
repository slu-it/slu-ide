# Stories behind the agent workflow rules

The rules in `.claude/` (agents, rules, skills) state what to do. This page keeps the incident behind a rule, where
one exists, so that the rule files stay short. Nothing here is a rule of its own; agents do not need to read it.

| Rule (where) | What happened |
|---|---|
| A design that rests on an external tool states its measured behavior (delegate, "Implementer brief") | Two implementer rounds failed on assumptions read from jdtls' code: a lock file it never takes, and a job order that its own race decides. |
| A new module dependency is checked against "Module rules" first (delegate, "Implementer brief") | A brief cited editor-family modules as precedent for a dependency of a language module. |
| The brief names each agent's scratchpad subfolder (delegate, "Implementer brief") | Parallel agents told to use "your own subfolder" picked the same one and overwrote each other's scripts. |
| A spike gets a time box (delegate, "Spiker brief") | A cold Gradle import through jdtls alone took 9 minutes. |
| One owner declares what two parallel packages both need (delegate, "Parallel packages") | In the remotes round (2026-11-17) two packages declared the same callback; the merge had no textual conflict and failed to compile on a redeclaration. |
| The full mutation run uses four displays and only the package's UI tests (delegate, "Review loop") | ADR 0189: in `ide`, 21 mutants took 13 minutes on one display with all tests and 3 minutes this way. |
| A hand break check's test runs under `timeout 600` (`rules/testing.md`) | A check that removed a clipboard read ended in an error dialog and hung for 34 minutes (ADR 0189). |
| Try the usual killing inputs before calling a mutant equivalent (test-audit, `backfill-brief.md`) | Claims that failed review in the first backfill packages: a readable entry where only an unreadable one tells; `maxByOrNull` calling its selector twice; a store that "never throws" with a throwing getter; a folder with `chmod 000` that makes a walk fail; `/dev/null` and `/dev/zero` for size and read limits. |
| A decided fix checks every place that does the same thing (test-audit, `backfill-brief.md`) | A fix of one `toInt()` on engine numbers and one `Instant` conversion took three review rounds to reach their siblings. |
| Gap backfill scans run two trees at once and need a watchdog (test-audit, "Gap backfill") | The 2026-11-18 scan took about 4 hours for 1690 files; two uncapped trees filled 17.3 of 17.8 GB before the worker heap cap. |
