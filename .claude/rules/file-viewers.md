---
paths:
  - "modules/file-viewers/src/main/**/*.kt"
---

# The `file-viewers` module

- A new Spring bean in `dev.sluide.fileviewers` is also found by the previews' viewer scan (`AllViewers.kt`), which
  has no beans from other modules: run the `:modules:previews` tests too, and exclude the bean there if it needs one.
