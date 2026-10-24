# Gradle

Thoreau finds the Gradle of `build.gradle` and `build.gradle.kts` files and asks the project trust prompt about it.
Decisions: [ADR 0085](../adr/0085-gradle-integration.md). The task view is documented here when its UI lands.

## Detection
- A build file uses the **wrapper** of the nearest folder, from its own folder up to and including the project root,
  that has `gradlew` and `gradle/wrapper/gradle-wrapper.properties`. A `gradlew` that is not executable runs as
  `sh gradlew`.
- Without a wrapper it uses the **global** `gradle` if the login shell finds it (`command -v gradle`, off the FX
  thread).
- Files outside the project root (e.g. scratches) get no Gradle. Neither do files in hidden folders, in `build`,
  `node_modules` or `out`, below a symbolic link, or more than 8 folders below the project root: the trust check does
  not look there either.
- Known limit: Gradle finds a `settings.gradle` above the project root itself.
- Tasks are read with a bundled init script, so Gradle 6 or newer is needed.

## Trust
- Gradle is a tool of the project trust prompt (`gradle`). It shows one entry per wrapper, "Gradle wrapper in
  <folder>" (what the wrapper downloads and runs), and one per folder with build scripts and no wrapper in or above it,
  "Gradle build scripts in <folder>" (run by the global gradle).
- A changed `gradlew`, `gradle-wrapper.jar` or `distributionUrl` asks again.
- A project with more than 20,000 folders cannot be checked, and its Gradle stays off.
