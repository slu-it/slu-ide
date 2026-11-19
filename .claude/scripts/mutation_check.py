#!/usr/bin/env python3
"""Mutation run over a change, or over whole files, with pitest (ADR 0185). Entry: mutation-check.sh (usage, output
and exit codes are in its header). The pure parts are plain functions, tested by test_mutation_check.py; `main` and
what it calls below the "Run" marker start Gradle, git and processes.

How it works:
Mutation run with pitest (ADR 0185, replaces break-check.sh): mutates the production Kotlin that the commits since
<base> add (or, with --whole, every line of the given files), runs the tests and lists every mutant no test killed.
A driver around the pitest Gradle wiring of the root build.gradle.kts (`./gradlew :<module>:pitest
-Psluide.pitest.*`) and the plugin tools/pitest-kotlin (KOTLIN_JUNK drops bytecode the Kotlin compiler adds, LINES
keeps the mutants on an allow-list of lines). pitest never edits a source file: nothing is restored after a kill.
Change mode (default): the lines `git diff -M <base>...HEAD` adds in */src/main/kotlin/**/*.kt (moved files count
only their changed lines). An added line that starts with `?:` or `?.` also allows the line before it, where Kotlin
puts that null check. --whole: every line of the given files (the backfill; <base> then only has to be an ancestor).
Mutators, fixed in build.gradle.kts: DEFAULTS, REMOVE_CONDITIONALS_EQUAL_IF/ELSE, NON_VOID_METHOD_CALLS. Files in
:tools have no pitest wiring and are skipped.
Per module (one at a time, in order), always with LINES: targetClasses are the packages of the files (never class
names: a file holds classes named otherwise, FooKt facades, lambdas and $$inlined$ classes), the allow-list holds
repo-root entries `modules/git/src/main/kotlin/dev/sluide/git/GitPush.kt:53`.
  1. Unit stage: one Gradle call, the module's unit tests (the build's default targetTests, no @Tag("ui")).
  2. UI stage, unless --unit-only (and Linux only: it needs flock and a display per worker; elsewhere it is skipped
     with a note and the summary says "unit tests only; preliminary"): every file that still has SURVIVED or
     NO_COVERAGE mutants. A second call (-Psluide.pitest.ui=true, no tests property) runs all the module's tests,
     unit and UI, with every given display leased per worker by pitest-java.sh (-Psluide.pitest.displays);
     pitest's per-test coverage picks the tests of each mutant. targetClasses are the classes that hold the
     remaining mutants (never packages: a relocated copy sits in a class of another package), LINES the remaining
     lines. A mutant keeps its unit-stage status unless the UI stage KILLED it; the stages' mutants are matched by
     class, method, descriptor, mutator and indexes, not by line. The UI stage's "n mutants" are the ones the unit
     stage left. It costs about one run of the module's UI tests plus seconds per mutant (a kill by a test that
     waits for its deadline costs that wait). --ui-tests A,B limits the call to those test classes (simple names,
     found in the module's src/test/kotlin; a module that declares none of them runs all its tests): on ide's 21
     mutants 765 s became 413 s on one display and 185 s on four (ADR 0189). A kill by a class that is not named
     shows up as a survivor.
Gradle runs on the shared daemon (--no-daemon in a worktree). Each call's output and a copy of its mutations.xml:
build/mutation-check/logs/<module>-<unit|ui>.{log,xml}. Every run starts fresh: pitest's history plugin replayed old
results (timeouts, kills, survivors) whenever the mutated class was unchanged, even after the tests changed.
A mutant's path is <module>/src/main/kotlin/<package path of its class>/<sourceFile>; a sourceFile with a "/" is the
plugin's mapped location of an inlined copy (a path from a source root, maybe another module's) and is reported under
the module that owns that file. A mutant on a file outside the requested ones is an internal error (exit 4).
A timeout is not a kill (read its log, rerun, or break it by hand). pitest's XML does not list the mutants the junk
filter dropped, so the summary has no count of them.
Accepted gap: the copy of an inline function inlined into a class of another package or another module is not
mutated, because targetClasses lists only the files' packages and pitest runs only in the file's module.
"""
import dataclasses
import os
import re
import signal
import subprocess
import sys
import time
import traceback
import xml.etree.ElementTree as ET

# --- Records ---------------------------------------------------------------------------------------------------


@dataclasses.dataclass(frozen=True)
class Mutant:
    status: str  # pitest's: KILLED, SURVIVED, NO_COVERAGE, TIMED_OUT, MEMORY_ERROR, RUN_ERROR
    source_file: str  # a simple name, or a path from a source root for an inlined copy (contains "/")
    mutated_class: str
    mutated_method: str
    method_description: str
    line: int
    mutator: str  # the class's simple name
    description: str
    indexes: tuple
    killing_test: str  # "Class.method" or ""
    by_ui: bool = False  # killed in the UI stage
    reason: str = ""  # of an ACCEPTED one (our status: a mutant left that the accepted file lists)


LEFT = frozenset({"SURVIVED", "NO_COVERAGE", "TIMED_OUT", "MEMORY_ERROR", "RUN_ERROR"})
LABELS = {
    "SURVIVED": "SURVIVED",
    "NO_COVERAGE": "no coverage",
    "TIMED_OUT": "timeout",
    "MEMORY_ERROR": "memory error",
    "RUN_ERROR": "run error",
}

# --- Lines -----------------------------------------------------------------------------------------------------


def parse_diff(diff):
    """Added and changed lines per path (the new path of a rename) from `git diff -U0 -M`; deletions give none."""
    added, current = {}, None
    for line in diff.splitlines():
        if line.startswith("+++ "):
            current = line[6:] if line.startswith("+++ b/") else None
        elif line.startswith("@@") and current:
            m = re.match(r"@@ -\S+ \+(\d+)(?:,(\d+))? @@", line)
            start, count = int(m.group(1)), int(m.group(2) or "1")
            if count:
                added.setdefault(current, set()).update(range(start, start + count))
    return added


def with_previous_lines(added, texts):
    """An added line that starts with `?:` or `?.` also allows the line before it: Kotlin puts that null check there."""
    result = {}
    for path, numbers in added.items():
        lines = texts.get(path, [])
        extra = {
            n - 1
            for n in numbers
            if n > 1 and n <= len(lines) and lines[n - 1].lstrip().startswith(("?:", "?."))
        }
        result[path] = set(numbers) | extra
    return result


def whole_file_lines(text):
    return set(range(1, len(text.splitlines()) + 1))


def module_of(path):
    m = re.match(r"^(.+?)/src/main/kotlin/", path)
    return m.group(1) if m else None


def gradle_task(module):
    return ":" + module.replace("/", ":") + ":pitest"


def package_of(text):
    m = re.search(r"(?m)^package\s+([\w.]+)", text)
    return m.group(1) if m else None


def package_mismatch(path, text):
    """Why LINES would match nothing: pitest matches a mutant's file by its package path, so the package line has to
    equal the folder under src/main/kotlin. None when it does (or the file sits in the root folder)."""
    folder = path.split("/src/main/kotlin/", 1)[1].rpartition("/")[0].replace("/", ".")
    package = package_of(text)
    if not folder and package is None:
        return None
    if package is None:
        return f"no package line in {path}"
    return None if package == folder else f"{path}: package {package} does not match its folder ({folder})"


def target_classes(packages):
    """`pkg.*` per package; a file in the default package (no package line) gives `*`, which LINES narrows."""
    return sorted({p + ".*" if p else "*" for p in packages})


def package_problems(texts):
    """The refusals for files (path -> text) whose package line does not match their folder, in path order."""
    return [p for p in (package_mismatch(path, texts[path]) for path in sorted(texts)) if p]


def allow_list(lines_by_path):
    return [f"{path}:{n}" for path in sorted(lines_by_path) for n in sorted(lines_by_path[path])]


# --- Reports and stages ----------------------------------------------------------------------------------------


def _short_test(killing):
    cls = re.search(r"\[class:([^\]]*)\]", killing)
    if not cls:
        return killing
    name = None
    for key, value in re.findall(r"\[([\w-]+):([^\]]*)\]", killing):
        if key in ("method", "test-template", "test-factory"):
            name = re.sub(r"\([^()]*\)$", "", value)
            break
    short_class = cls.group(1).rsplit(".", 1)[-1]
    return f"{short_class}.{name}" if name else short_class


def parse_report(xml):
    mutants = []
    for e in ET.fromstring(xml).iter("mutation"):
        status = e.get("status")
        if status == "NON_VIABLE":
            continue
        mutants.append(
            Mutant(
                status=status,
                source_file=e.findtext("sourceFile", ""),
                mutated_class=e.findtext("mutatedClass", ""),
                mutated_method=e.findtext("mutatedMethod", ""),
                method_description=e.findtext("methodDescription", ""),
                line=int(e.findtext("lineNumber", "0")),
                mutator=e.findtext("mutator", "").rsplit(".", 1)[-1],
                description=e.findtext("description", ""),
                indexes=tuple(int(i.text) for i in e.iter("index")),
                killing_test=_short_test(e.findtext("killingTest", "") or ""),
            )
        )
    return mutants


def mutant_key(m):
    return (m.mutated_class, m.mutated_method, m.method_description, m.mutator, m.indexes)


def merge_stages(unit, ui):
    """A mutant keeps its unit-stage status unless the UI stage killed it."""
    killed = {mutant_key(m): m for m in ui if m.status == "KILLED"}
    merged = []
    for m in unit:
        k = killed.get(mutant_key(m))
        if m.status != "KILLED" and k is not None:
            m = dataclasses.replace(m, status="KILLED", killing_test=k.killing_test, by_ui=True)
        merged.append(m)
    return merged


def left_mutants(mutants):
    """What the UI stage is asked about: the mutants no unit test killed (survived, or no unit test reaches them)."""
    return [m for m in mutants if m.status in ("SURVIVED", "NO_COVERAGE")]


def left_lines(mutants, paths):
    """The lines still having such a mutant, per path (`paths[i]` is where `mutants[i]` is reported)."""
    left = {}
    for m, path in zip(mutants, paths):
        if m.status in ("SURVIVED", "NO_COVERAGE"):
            left.setdefault(path, set()).add(m.line)
    return left


def left_classes(mutants):
    """The classes that hold those mutants, as pitest names them. Never the packages: a copy relocated from a class
    in another package (an inlined lambda or function) lives in a class of its own package."""
    return sorted({m.mutated_class for m in left_mutants(mutants)})


def effective_unit_only(unit_only, platform):
    """(unit tests only?, a note). The UI stage needs flock and a display per worker: Linux only."""
    if not unit_only and platform != "linux":
        return True, "mutation-check: the UI stage runs on Linux only (flock, one display per worker); skipped"
    return unit_only, None


def ui_test_classes(names, texts):
    """The qualified names of the test classes called `names` that a module's test files (path -> text) declare at
    the top level (a declaration at column 0: a nested class has another binary name, which pitest would not find)."""
    found = set()
    for text in texts.values():
        package = package_of(text)
        for name in names:
            if re.search(rf"(?m)^(?:\w+[ \t]+)*class[ \t]+{re.escape(name)}\b", text):
                found.add(f"{package}.{name}" if package else name)
    return sorted(found)


def unknown_ui_tests(names, module_texts):
    """The names that no module of the run (each: its test files, path -> text) declares, sorted."""
    return sorted(n for n in set(names) if not any(ui_test_classes([n], texts) for texts in module_texts))


# --- Accepted mutants ------------------------------------------------------------------------------------------


def _cell(text):
    return re.sub(r"\s+", " ", text).strip()


def left_rows(mutants, paths):
    """What a run left, for a later --accept: `path, line, class, method, mutator, description` (tabs) per mutant."""
    return "".join(
        "\t".join([path, str(m.line), m.mutated_class, m.mutated_method, m.mutator, _cell(m.description)]) + "\n"
        for m, path in zip(mutants, paths) if m.status in LEFT)


def _left_on(left_text, path, line):
    rows = [row.split("\t") for row in left_text.splitlines()]
    return [c for c in rows if len(c) == 6 and c[0] == path and c[1] == str(line)]


def mutators_left(left_text, path, line):
    return sorted({c[4] for c in _left_on(left_text, path, line)})


def accept_target(target):
    """`path:line` or `path:line:Mutator` as (path, line, mutator or None); None when it is neither."""
    m = re.fullmatch(r"(.+?):(\d+)(?::(\w+))?", target)
    return (m.group(1), int(m.group(2)), m.group(3)) if m else None


def new_accepted(left_text, path, line, mutator, reason, line_text):
    """The accepted file's entries for the mutants the last run left on path:line, one per mutant:
    `path, class, method, mutator, description, the line's text, reason` (tabs). Mutants of several mutators rarely
    share a reason (an endless loop next to a real gap), so such a line needs the mutator named."""
    mutators = mutators_left(left_text, path, line)
    if mutator is None and len(mutators) > 1:
        raise Stop(f"mutation-check: {path}:{line} has mutants of {', '.join(mutators)}; accept each with its own "
                   f"reason: --accept {path}:{line}:<Mutator> <reason>", 2)
    return ["\t".join([path, c[2], c[3], c[4], c[5], _cell(line_text), _cell(reason)])
            for c in _left_on(left_text, path, line) if mutator in (None, c[4])]


def parse_accepted(text):
    return [tuple(cells) for cells in (row.split("\t") for row in text.splitlines()) if len(cells) == 7]


def apply_accepted(mutants, paths, entries, line_text):
    """A mutant left becomes ACCEPTED, with the entry's reason, when an entry names its path, class, method, mutator
    and description and its line still has the entry's text: an edited line has to be argued again. An entry accepts
    one mutant, so a second mutant like it, on the same line or on a line with the same text, stays left.
    `line_text(path, line)` reads."""
    open_entries = {}
    for *key, reason in entries:
        open_entries.setdefault(tuple(key), []).append(reason)
    if not open_entries:
        return mutants
    result = []
    for m, path in zip(mutants, paths):
        if m.status in LEFT:
            key = (path, m.mutated_class, m.mutated_method, m.mutator, _cell(m.description),
                   _cell(line_text(path, m.line)))
            if open_entries.get(key):
                m = dataclasses.replace(m, status="ACCEPTED", reason=open_entries[key].pop(0))
        result.append(m)
    return result


# --- Output ----------------------------------------------------------------------------------------------------


def mutant_path(m, module, roots, exists):
    if "/" in m.source_file:
        for root in [module] + [r for r in roots if r != module]:
            candidate = f"{root}/src/main/kotlin/{m.source_file}"
            if exists(candidate):
                return candidate
        return f"{module}/src/main/kotlin/{m.source_file}"
    package = m.mutated_class.rsplit(".", 1)[0].replace(".", "/") if "." in m.mutated_class else ""
    folder = f"{module}/src/main/kotlin/{package}" if package else f"{module}/src/main/kotlin"
    return f"{folder}/{m.source_file}"


def format_mutant(m, path, verbose):
    what = f"{path}:{m.line}  {m.mutator}: {m.description}"
    if m.status == "KILLED":
        return f"killed  {what} [{m.killing_test}]" if verbose else None
    if m.status == "ACCEPTED":
        return f"accepted  {what}  ({m.reason})"
    return f"{LABELS.get(m.status, 'run error')}  {what}"


def summary(mutants, ui_stats, unit_only):
    count = lambda *s: sum(1 for m in mutants if m.status in s)  # noqa: E731
    errors = count("MEMORY_ERROR", "RUN_ERROR")
    text = (
        f"mutation-check: {len(mutants)} mutants, {count('KILLED')} killed, {count('SURVIVED')} SURVIVED, "
        f"{count('NO_COVERAGE')} no coverage, {count('TIMED_OUT')} timeout"
    )
    if errors:
        text += f", {errors} errors"
    if count("ACCEPTED"):
        text += f", {count('ACCEPTED')} accepted"
    if unit_only:
        return text + "; unit tests only; preliminary"
    n, files, killed = ui_stats or (0, 0, 0)
    return text + f"; UI stage: {n} mutants on {files} files, {killed} killed in the UI stage"


def exit_code(mutants):
    return 1 if any(m.status in LEFT for m in mutants) else 0


def failure_hint(output, status):
    """The line of a failed Gradle call that says why: a red baseline's PitHelpError first, then the LINES or classes
    errors, then what Gradle says went wrong."""
    lines = [ln.strip() for ln in output.splitlines()]
    for wanted in (lambda ln: "PitHelpError" in ln, lambda ln: ln.startswith("LINES:") or "pitest needs" in ln):
        found = next((ln for ln in lines if wanted(ln)), None)
        if found:
            return found.lstrip("> ")
    for i, ln in enumerate(lines):
        if "What went wrong" in ln and i + 2 < len(lines):
            return "Gradle failed: " + lines[i + 2].lstrip("> ")
    return f"Gradle failed (exit {status}); see the log"


# --- Run -------------------------------------------------------------------------------------------------------

USAGE = (
    "Usage: mutation-check.sh [--display N | --displays N,M] [--ui-tests A,B] [--detach] [--unit-only] [--whole]"
    " [--verbose] <base> [file...]\n       mutation-check.sh --wait [seconds]"
    "\n       mutation-check.sh --accept <path>:<line>[:<Mutator>] <reason>"
)


class Stop(Exception):
    def __init__(self, message, code):
        super().__init__(message)
        self.code = code


def read(path, default=None):
    try:
        with open(path, encoding="utf-8") as f:
            return f.read()
    except (OSError, UnicodeDecodeError):
        return default


class Run:
    def __init__(self, root, self_path):
        self.root, self.self_path = root, self_path
        self.work = os.path.join(root, "build", "mutation-check")
        self.logs = os.path.join(self.work, "logs")
        self.pid_file = os.path.join(self.work, "run.pid")
        self.status_file = os.path.join(self.work, "status")
        self.output_file = os.path.join(self.work, "output.txt")
        self.cursor_file = os.path.join(self.work, "wait-offset")
        self.left_file = os.path.join(self.work, "left.tsv")
        self.accepted_file = os.path.join(self.work, "accepted.tsv")
        self.child = None

    # -- detach and wait (break-check's mechanics) --
    def active_pid(self):
        try:
            pid = int(read(self.pid_file, "").strip())
        except ValueError:
            return None
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return None
        except PermissionError:
            pass
        return None if pid == os.getpid() else pid

    def wait(self, seconds):
        deadline = time.time() + seconds
        while True:
            if read(self.status_file) is not None or self.active_pid() is None or time.time() >= deadline:
                break
            time.sleep(3)
        text = read(self.output_file, "")
        try:
            offset = int(read(self.cursor_file, "0"))
        except ValueError:
            offset = 0
        sys.stdout.write(text[offset:] if offset <= len(text) else text)
        with open(self.cursor_file, "w") as f:
            f.write(str(len(text)))
        done = read(self.status_file)
        if done is not None:
            return int(done.strip() or "4")
        pid = self.active_pid()
        if pid is not None:
            print(f"mutation-check: still running (pid {pid}); run --wait again")
            return 5
        if not text:
            raise Stop("mutation-check: no detached run to wait for", 2)
        raise Stop("mutation-check: the detached run ended without a result (killed?)", 3)

    def detach(self, args):
        os.makedirs(self.work, exist_ok=True)
        for p in (self.status_file, self.cursor_file):
            if os.path.exists(p):
                os.remove(p)
        child_args = [a for a in args if a != "--detach"]
        with open(self.output_file, "w") as out:
            child = subprocess.Popen(
                [self.self_path] + child_args,
                stdin=subprocess.DEVNULL,
                stdout=out,
                stderr=subprocess.STDOUT,
                start_new_session=True,
                env=dict(os.environ, MUTATION_CHECK_DETACHED="1"),
            )
        with open(self.pid_file, "w") as f:
            f.write(str(child.pid))  # the same pid after the child's exec into python
        print(f"mutation-check: started in the background (pid {child.pid}); collect it with "
              ".claude/scripts/mutation-check.sh --wait")

    # -- accepted mutants --
    def line_text(self, rel, line):
        lines = self.lines_of(rel)
        return lines[line - 1] if 0 < line <= len(lines) else ""

    def accept(self, target, reason):
        parsed = accept_target(target)
        if parsed is None or not reason.strip():
            raise Stop(USAGE, 2)
        if self.active_pid() is not None:
            raise Stop("mutation-check: a run is active in this tree; accept after it has finished", 2)
        path, line, mutator = parsed
        path = os.path.relpath(os.path.abspath(path), self.root)
        entries = new_accepted(read(self.left_file, ""), path, line, mutator, reason, self.line_text(path, line))
        if not entries:
            raise Stop(f"mutation-check: the last finished run in this tree left no such mutant on {path}:{line}", 2)
        with open(self.accepted_file, "a", encoding="utf-8") as f:
            f.write("".join(e + "\n" for e in entries))
        for e in entries:
            cells = e.split("\t")
            print(f"accepted  {path}:{line}  {cells[3]}: {cells[4]}  ({cells[6]})")
        return 0

    def test_texts(self, module):
        texts = {}
        for folder, _, names in os.walk(self.path(f"{module}/src/test/kotlin")):
            for name in names:
                if name.endswith(".kt"):
                    texts[os.path.join(folder, name)] = read(os.path.join(folder, name), "") or ""
        return texts

    # -- git and files --
    def git(self, *a, check=True):
        return subprocess.run(["git", *a], check=check, capture_output=True, text=True, cwd=self.root).stdout

    def path(self, rel):
        return os.path.join(self.root, rel)

    def lines_of(self, rel):
        return (read(self.path(rel), "") or "").split("\n")

    def changed_lines(self, base):
        diff = self.git("diff", "-U0", "--no-color", "--no-ext-diff", "-M", "--src-prefix=a/", "--dst-prefix=b/",
                        base + "...HEAD", "--", "*/src/main/kotlin/*.kt")
        added = {p: n for p, n in parse_diff(diff).items() if os.path.exists(self.path(p))}
        return with_previous_lines(added, {p: self.lines_of(p) for p in added})

    # -- Gradle --
    def gradlew(self):
        cmd = ["./gradlew", "--console=plain"]
        git_dir = self.git("rev-parse", "--absolute-git-dir").strip()
        common = self.git("rev-parse", "--path-format=absolute", "--git-common-dir").strip()
        if git_dir != common:
            cmd.insert(1, "--no-daemon")
        return cmd

    def pitest(self, module, stage, classes, lines_file, tests=None, displays=None):
        """One Gradle pitest call; returns the report's text. Output and a copy of the report go to the logs."""
        name = module.replace("/", "-")
        os.makedirs(self.logs, exist_ok=True)
        report = self.path(f"{module}/build/reports/pitest/mutations.xml")
        if os.path.exists(report):
            os.remove(report)
        cmd = self.gradlew() + [gradle_task(module), f"-Psluide.pitest.classes={','.join(classes)}",
                                f"-Psluide.pitest.lines={lines_file}"]
        if tests:
            cmd.append(f"-Psluide.pitest.tests={','.join(tests)}")
        if displays:
            cmd += ["-Psluide.pitest.ui=true", f"-Psluide.pitest.displays={','.join(displays)}"]
        log = os.path.join(self.logs, f"{name}-{stage}.log")
        with open(log, "w") as out:
            self.child = subprocess.Popen(cmd, cwd=self.root, stdin=subprocess.DEVNULL, stdout=out,
                                          stderr=subprocess.STDOUT)
            status = self.child.wait()
            self.child = None
        text = read(report)
        if status != 0 or text is None:
            output = read(log, "") or ""
            hint = failure_hint(output, status)
            raise Stop(f"mutation-check: inconclusive: {module} {stage} stage: {hint}\n  log: {log}", 3)
        with open(os.path.join(self.logs, f"{name}-{stage}.xml"), "w") as f:
            f.write(text)
        return text

    def write_lines(self, module, stage, lines_by_path):
        os.makedirs(self.work, exist_ok=True)
        path = os.path.join(self.work, f"lines-{module.replace('/', '-')}-{stage}.txt")
        with open(path, "w") as f:
            f.write("\n".join(allow_list(lines_by_path)) + "\n")
        return path

    def ensure_displays(self, displays):
        for d in displays:
            r = subprocess.run([self.path(".claude/scripts/setup-display.sh"), d], capture_output=True, text=True)
            if r.returncode != 0:
                raise Stop(f"mutation-check: inconclusive: display :{d} could not be started:\n{r.stdout}{r.stderr}", 3)

    # -- stages --
    def module_run(self, module, files, lines_by_path, displays, unit_only, roots, ui_tests=()):
        """Both stages of one module; returns (mutants with their paths, ui stats)."""
        packages = [package_of(read(self.path(f), "")) for f in files]
        classes = target_classes(packages)
        wanted = {f: lines_by_path[f] for f in files}
        t0 = time.time()
        print(f"mutation-check: {module}: unit stage on {len(files)} files ...", flush=True)
        unit = parse_report(self.pitest(module, "unit", classes, self.write_lines(module, "unit", wanted)))
        print(f"mutation-check: {module}: unit stage done in {time.time() - t0:.0f} s", flush=True)
        located = self.locate(unit, module, roots, wanted)
        unit = apply_accepted(unit, located, parse_accepted(read(self.accepted_file, "")), self.line_text)
        ui_stats = (0, 0, 0)
        merged = unit
        left = left_lines(unit, located)
        if not unit_only and left:
            self.ensure_displays(displays)
            t0 = time.time()
            asked = left_mutants(unit)
            tests = ui_test_classes(ui_tests, self.test_texts(module)) if ui_tests else []
            which = f"{len(tests)} test classes" if tests else "all the module's tests"
            print(f"mutation-check: {module}: UI stage on {len(left)} files, {len(asked)} mutants, {which}, "
                  f"displays {','.join(displays)} ...", flush=True)
            ui = parse_report(self.pitest(module, "ui", left_classes(unit), self.write_lines(module, "ui", left),
                                          tests or None, displays))
            merged = merge_stages(unit, ui)
            ui_stats = (len(asked), len(left), sum(1 for m in merged if m.by_ui))
            print(f"mutation-check: {module}: UI stage done in {time.time() - t0:.0f} s", flush=True)
        return list(zip(merged, located)), ui_stats

    def locate(self, mutants, module, roots, wanted):
        paths = [mutant_path(m, module, roots, lambda p: os.path.exists(self.path(p))) for m in mutants]
        stray = sorted({p for p in paths if p not in wanted})
        if stray:
            raise Stop("mutation-check: internal error: mutants outside the requested files: " + ", ".join(stray), 4)
        return paths


def parse_args(args):
    opts = dict(displays=["99"], detach=False, unit_only=False, whole=False, verbose=False, base=None, files=[],
                ui_tests=[])
    i = 0
    while i < len(args):
        a = args[i]
        if a in ("--display", "--displays"):
            if i + 1 >= len(args):
                raise Stop(f"{a} needs a number", 2)
            value = args[i + 1].split(",") if a == "--displays" else [args[i + 1]]
            if not all(v.isdigit() for v in value):
                raise Stop(f"{a} needs {'numbers' if a == '--displays' else 'a number'}, not {args[i + 1]}", 2)
            opts["displays"] = value
            i += 1
        elif a == "--ui-tests":
            if i + 1 >= len(args) or args[i + 1].startswith("-"):
                raise Stop("--ui-tests needs test class names, comma-separated", 2)
            opts["ui_tests"] = [n.strip() for n in args[i + 1].split(",") if n.strip()]
            if not opts["ui_tests"]:
                raise Stop("--ui-tests needs test class names, comma-separated", 2)
            i += 1
        elif a in ("--detach", "--unit-only", "--whole", "--verbose"):
            opts[a[2:].replace("-", "_")] = True
        elif a.startswith("-"):
            raise Stop(f"Unknown option {a}.\n{USAGE}", 2)
        elif opts["base"] is None:
            opts["base"] = a
        else:
            opts["files"].append(a)
        i += 1
    if opts["base"] is None:
        raise Stop("No base commit.\n" + USAGE, 2)
    if opts["whole"] and not opts["files"]:
        raise Stop("--whole needs the files to mutate.\n" + USAGE, 2)
    return opts


def execute(run, args):
    root = run.root
    if args[:1] == ["--wait"]:
        if len(args) > 2 or (len(args) == 2 and not args[1].isdigit()):
            raise Stop(USAGE, 2)
        return run.wait(int(args[1]) if len(args) == 2 else 100)
    if args[:1] == ["--accept"]:
        if len(args) < 3:
            raise Stop(USAGE, 2)
        return run.accept(args[1], " ".join(args[2:]))
    opts = parse_args(args)
    base = opts["base"]
    if subprocess.run(["git", "rev-parse", "--verify", "-q", base + "^{commit}"], capture_output=True,
                      cwd=root).returncode:
        raise Stop("Unknown commit " + base, 2)
    if subprocess.run(["git", "merge-base", "--is-ancestor", base, "HEAD"], cwd=root).returncode:
        raise Stop(base + " is not an ancestor of HEAD", 2)
    if run.active_pid() is not None:
        raise Stop(f"Another mutation-check run is active in this tree (pid {run.active_pid()}); collect it with "
                   "--wait", 2)
    found = subprocess.run([os.path.join(root, ".claude/scripts/gradle-running.sh"), root], capture_output=True,
                           text=True)
    if found.returncode == 0:
        raise Stop(f"Another Gradle build runs in {root} (pid {found.stdout.split()[0]}); wait for it to finish "
                   "first.", 2)

    given = [os.path.relpath(os.path.abspath(f), root) for f in opts["files"]]
    if opts["whole"]:
        lines = {}
        for f in given:
            if module_of(f) is None or not f.endswith(".kt") or not os.path.isfile(run.path(f)):
                raise Stop(f"Not a production Kotlin file (…/src/main/kotlin/….kt): {f}", 2)
            lines[f] = whole_file_lines(read(run.path(f), ""))
    else:
        lines = run.changed_lines(base)
        missing = [f for f in given if f not in lines]
        if missing:
            raise Stop("No added production lines in: " + ", ".join(missing), 2)
        if given:
            lines = {f: lines[f] for f in given}
    skipped = sorted(f for f in lines if module_of(f).startswith("tools/"))
    lines = {f: n for f, n in lines.items() if f not in skipped and n}
    if not lines:
        what = "lines of the given files" if opts["whole"] else f"production Kotlin lines added since {base}"
        print(f"mutation-check: no {what} to mutate"
              + (f" (not run in :tools, which has no pitest: {', '.join(skipped)})" if skipped else ""))
        return 0
    dirty = run.git("status", "--porcelain", "--", *lines).strip()
    if dirty:
        raise Stop("Uncommitted changes in files to mutate; commit them (a fixup! commit) first:\n" + dirty, 2)
    problems = package_problems({f: read(run.path(f), "") for f in lines})
    if problems:
        raise Stop("mutation-check: " + "\n".join(problems), 2)
    unknown = unknown_ui_tests(opts["ui_tests"], [run.test_texts(m) for m in sorted({module_of(f) for f in lines})])
    if unknown:
        raise Stop("--ui-tests: no test class of the mutated modules is called " + ", ".join(unknown), 2)
    if opts["detach"]:
        run.detach(args)
        return 0

    os.makedirs(run.work, exist_ok=True)
    with open(run.pid_file, "w") as f:
        f.write(str(os.getpid()))
    if os.path.exists(run.left_file):
        os.remove(run.left_file)  # --accept must not read the rows of an earlier run after this one failed
    if skipped:
        print("mutation-check: not run in :tools (no pitest there): " + ", ".join(skipped))
    roots = sorted({module_of(p) for p in subprocess.run(
        ["git", "ls-files", "--", "*/src/main/kotlin/*.kt"], capture_output=True, text=True, cwd=root
    ).stdout.split() if module_of(p)})
    by_module = {}
    for f in lines:
        by_module.setdefault(module_of(f), []).append(f)
    unit_only, note = effective_unit_only(opts["unit_only"], sys.platform)
    if note:
        print(note)
    results, ui_n, ui_files, ui_killed = [], 0, 0, 0
    for module in sorted(by_module):
        pairs, (n, files, killed) = run.module_run(module, sorted(by_module[module]), lines, opts["displays"],
                                                   unit_only, roots, opts["ui_tests"])
        results += pairs
        ui_n, ui_files, ui_killed = ui_n + n, ui_files + files, ui_killed + killed
    results.sort(key=lambda r: (r[1], r[0].line, r[0].mutator, r[0].description, r[0].indexes))
    for m, path in results:
        out = format_mutant(m, path, opts["verbose"])
        if out:
            print(out)
    mutants = [m for m, _ in results]
    with open(run.left_file, "w", encoding="utf-8") as f:
        f.write(left_rows(mutants, [path for _, path in results]))
    print(summary(mutants, None if unit_only else (ui_n, ui_files, ui_killed), unit_only))
    return exit_code(mutants)


def main(argv, root, self_path):
    run = Run(root, self_path)
    detached = os.environ.pop("MUTATION_CHECK_DETACHED", None) == "1"
    waiting = argv[:1] == ["--wait"]
    sys.stdout.reconfigure(line_buffering=True)

    def on_signal(number, _frame):
        if run.child is not None:
            run.child.terminate()
        raise KeyboardInterrupt(number)

    for s in (signal.SIGTERM, signal.SIGHUP):
        signal.signal(s, on_signal)
    os.chdir(root)
    code = 4
    try:
        code = execute(run, argv)
    except Stop as e:
        print(str(e), file=sys.stderr)
        code = e.code
    except KeyboardInterrupt as e:
        code = 128 + (e.args[0] if e.args and isinstance(e.args[0], int) else signal.SIGINT)
    except Exception:
        traceback.print_exc()
        code = 4
    finally:
        if not waiting and read(run.pid_file, "").strip() == str(os.getpid()):
            os.remove(run.pid_file)
            if detached:
                with open(run.status_file, "w") as f:
                    f.write(str(code))
    return code


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    sys.exit(main(sys.argv[1:], os.path.dirname(os.path.dirname(here)), os.path.join(here, "mutation-check.sh")))
