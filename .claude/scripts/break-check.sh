#!/usr/bin/env bash
# Mutation run over a change (ADR 0178): changes each guard and call that the commits since <base> add to production
# Kotlin, one at a time, runs the tests that cover the file and lists every change no test noticed.
# Mutations, per line added in */src/main/kotlin/**/*.kt (moved files count only their changed lines):
#   if (c), while (c)      -> false; for `if`, also true when the same line has an `else`
#   if (a || b), (a && b)  -> each top-level operand dropped once
#   .filter { ... }        -> removed; also filterNot, takeIf, takeUnless, coerceIn, coerceAtLeast, coerceAtMost
#                             (on one line, brackets balanced)
#   a call or an assignment that is a whole statement on one line -> removed (wiring, stop(), require())
# Not mutated: comments, imports, declarations, logging, lines in multi-line strings, lines that continue or open a
# multi-line expression or follow a brace-less if/else, and presentation (literal values, style classes, sizes,
# padding, spacing, alignment, prompt texts: paint checks cover what the user sees). Guard-like added lines that got
# no mutant (`?: return`, `when`, multi-line conditions, ...) are listed by --list and counted in the summary: they
# keep a hand break check.
# Tests per file: the unit `test` task of its module, the classes `related-tests.sh --names <file>` finds and the
# verify.sh targets after `--` (the classes a brief names). The baseline runs them unmutated with Gradle's cached
# results invalidated, so its time is a real run; a red baseline stops the run. Each mutant runs in stages with
# Gradle's fail-fast on, each stage ending the mutant at its first failing test: the UI class that killed the previous
# mutant of the file, then the unit tests, then the remaining UI classes. It counts as killed (a test failed),
# SURVIVED (all green), timeout (a stage ran past 4x its baseline plus 60 s; not a kill: an endless loop, or load
# that hid a survivor, so read its log and rerun the file) or no compile (ignored). Any other outcome (INCOMPLETE, NOT RUN, a Gradle failure without a failing test)
# stops the run with exit 3.
# Files are replaced atomically and restored after every mutant and on exit, Ctrl-C, SIGTERM and SIGHUP. The originals
# and the mutant in place are recorded in build/break-check/restore/: after a hard kill (SIGKILL) the next start
# restores a file that holds exactly that mutant and refuses one that differs otherwise. Refuses while a file to
# mutate has uncommitted changes, while another run is active in this tree, and when <base> is not an ancestor of HEAD.
# Usage: .claude/scripts/break-check.sh [--display N] [--list] [--detach] <base> [file...] [-- verify-target...]
#        .claude/scripts/break-check.sh --wait [seconds]
#   --list    print each file's tests, the mutants and the guard-like lines left unmutated; run nothing
#   --detach  start the run in the background and return; collect it with --wait
#   --wait    print what the last detached run printed since the last --wait, waiting up to `seconds` (default 100,
#             below the Bash tool's default 2-minute timeout); its exit code once finished, 5 while it still runs
#   file...   only these production files (default: every one the commits since <base> change)
# Output: per mutant `killed|SURVIVED|timeout|no compile  path:line  what was changed [first failing test]`, then
# `break-check: N mutants, K killed, S SURVIVED, T timeout, C no compile; G guard-like lines not mutated`. Each
# verify run's output: build/break-check/logs/. About 1-2 s per unit-test stage with a warm daemon, about 7 s in a
# worktree (`--no-daemon`); a UI stage adds its classes' time, so --list and the estimate after the baseline come
# first.
# Exit: 0 no survivor and no timeout, 1 survivors or timeouts, 2 usage or refusal, 3 a run neither passed nor failed (or a detached run died),
# 4 internal error, 5 (--wait) still running, 128+signal when interrupted.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd -P)"
cd "$root"
exec python3 -I - "$root" "$root/.claude/scripts/break-check.sh" "$@" <<'PY'
import json, os, re, signal, subprocess, sys, time, traceback

root, self_path, args = sys.argv[1], sys.argv[2], sys.argv[3:]
detached = os.environ.pop("BREAK_CHECK_DETACHED", None) == "1"  # not passed on to verify.sh and Gradle
os.chdir(root)
work = os.path.join(root, "build", "break-check")
restore_dir = os.path.join(work, "restore")
logs = os.path.join(work, "logs")
pid_file, status_file = os.path.join(work, "run.pid"), os.path.join(work, "status")
output_file, cursor_file = os.path.join(work, "output.txt"), os.path.join(work, "wait-offset")
USAGE = ("Usage: break-check.sh [--display N] [--list] [--detach] <base> [file...] [-- verify-target...]\n"
         "       break-check.sh --wait [seconds]")

def fail(message, code=2):
    print(message, file=sys.stderr)
    sys.exit(code)

def read(path, default=None):
    try:
        with open(path, encoding="utf-8") as f: return f.read()
    except (OSError, UnicodeDecodeError): return default

def write_atomic(path, data):
    tmp = path + ".break-check-tmp"
    with open(tmp, "wb") as f: f.write(data)
    os.replace(tmp, path)

def active_pid():
    try: pid = int(read(pid_file, "").strip())
    except ValueError: return None
    try: os.kill(pid, 0)
    except ProcessLookupError: return None
    except PermissionError: pass
    return None if pid == os.getpid() else pid

# --- --wait ----------------------------------------------------------------------------------------------------
def wait(seconds):
    deadline = time.time() + seconds
    while True:
        done = read(status_file)
        if done is not None or active_pid() is None or time.time() >= deadline: break
        time.sleep(3)
    text = read(output_file, "")
    try: offset = int(read(cursor_file, "0"))
    except ValueError: offset = 0
    sys.stdout.write(text[offset:] if offset <= len(text) else text)
    with open(cursor_file, "w") as f: f.write(str(len(text)))
    done = read(status_file)
    if done is not None: sys.exit(int(done.strip() or "4"))
    if active_pid() is not None:
        print("break-check: still running (pid %d); run --wait again" % active_pid())
        sys.exit(5)
    if not text: fail("break-check: no detached run to wait for")
    fail("break-check: the detached run ended without a result (killed?); the next run restores its files", 3)

if args[:1] == ["--wait"]:
    if len(args) > 2 or (len(args) == 2 and not args[1].isdigit()): fail(USAGE)
    wait(int(args[1]) if len(args) == 2 else 100)

# --- Arguments -------------------------------------------------------------------------------------------------
display, list_only, detach, base, only, extra = "", False, False, None, [], []
i = 0
while i < len(args):
    a = args[i]
    if a == "--display":
        if i + 1 >= len(args): fail("--display needs a number")
        display = args[i + 1]; i += 1
    elif a == "--list": list_only = True
    elif a == "--detach": detach = True
    elif a == "--":
        extra = args[i + 1:]; break
    elif a.startswith("-"): fail("Unknown option %s.\n%s" % (a, USAGE))
    elif base is None: base = a
    else: only.append(os.path.relpath(os.path.abspath(a), root))
    i += 1
if base is None: fail("No base commit.\n" + USAGE)

def git(*a):
    return subprocess.run(["git", *a], check=True, capture_output=True, text=True).stdout

if subprocess.run(["git", "rev-parse", "--verify", "-q", base + "^{commit}"], capture_output=True).returncode:
    fail("Unknown commit " + base)
if subprocess.run(["git", "merge-base", "--is-ancestor", base, "HEAD"]).returncode:
    fail(base + " is not an ancestor of HEAD")
if not list_only and active_pid() is not None:
    fail("Another break-check run is active in this tree (pid %d); collect it with --wait" % active_pid())

if detach and not list_only:
    os.makedirs(work, exist_ok=True)
    for p in (status_file, cursor_file):
        if os.path.exists(p): os.remove(p)
    child_args = [a for a in args if a != "--detach"]
    with open(output_file, "w") as out:
        child = subprocess.Popen([self_path] + child_args, stdin=subprocess.DEVNULL, stdout=out,
                                 stderr=subprocess.STDOUT, start_new_session=True,
                                 env=dict(os.environ, BREAK_CHECK_DETACHED="1"))
    with open(pid_file, "w") as f: f.write(str(child.pid))  # the same pid after the child's exec into python
    print("break-check: started in the background (pid %d); collect it with .claude/scripts/break-check.sh --wait"
          % child.pid)
    sys.exit(0)

# --- Repair after a hard kill ----------------------------------------------------------------------------------
manifest_file = os.path.join(restore_dir, "manifest.json")

def clear_restore_dir():
    if os.path.exists(manifest_file): os.remove(manifest_file)  # first: without it, leftovers are ignored
    if os.path.isdir(restore_dir):
        for name in os.listdir(restore_dir): os.remove(os.path.join(restore_dir, name))
        os.rmdir(restore_dir)

def with_mutant(original, line, text):
    lines = original.decode("utf-8").split("\n")
    lines[line - 1] = text
    return "\n".join(lines).encode("utf-8")

def repair():
    manifest = read(manifest_file)
    if manifest is None:
        clear_restore_dir()
        return
    manifest = json.loads(manifest)
    applied = manifest.get("applied")
    for path, name in manifest["files"].items():
        if os.path.exists(path + ".break-check-tmp"): os.remove(path + ".break-check-tmp")
        with open(os.path.join(restore_dir, name), "rb") as f: original = f.read()
        try:
            with open(path, "rb") as f: current = f.read()
        except OSError: current = None
        if current == original: continue
        if applied and applied["path"] == path and current == with_mutant(original, applied["line"], applied["text"]):
            write_atomic(path, original)
            print("restored %s, left mutated by a killed break-check run" % path, file=sys.stderr)
            continue
        fail("%s differs from the copy a killed break-check run kept (build/break-check/restore/%s), and not by the "
             "mutant it had in place: compare them by hand, then delete build/break-check/restore/" % (path, name))
    clear_restore_dir()

# --- Added lines -----------------------------------------------------------------------------------------------
def added_lines():
    diff = git("diff", "-U0", "--no-color", "--no-ext-diff", "-M", "--src-prefix=a/", "--dst-prefix=b/",
               base + "...HEAD", "--", "*/src/main/kotlin/*.kt")
    added, current = {}, None
    for line in diff.splitlines():
        if line.startswith("+++ "):
            current = line[6:] if line.startswith("+++ b/") else None
        elif line.startswith("@@") and current:
            m = re.match(r"@@ -\S+ \+(\d+)(?:,(\d+))? @@", line)
            start, count = int(m.group(1)), int(m.group(2) or "1")
            if count: added.setdefault(current, set()).update(range(start, start + count))
    return added

# --- Mutant generation -----------------------------------------------------------------------------------------
SKIP_START = ("return", "val ", "var ", "else", "when", "for ", "for(", "while", "do ", "throw ", "try", "catch",
              "finally", "}", ")", "]", ".", "?.", "?:", "&&", "||", "+", "-", "is ", "in ", "!in ", "->", "get()",
              "set(")
DECL = re.compile(r"\b(fun|class|object|interface|typealias|constructor|init)\b")
LOGGING = re.compile(r"^(log|logger|LOG|LOGGER|println|print)\b")
CONTINUES = ("(", "[", ",", "=", "->", ".", "&&", "||", "+", "-", "?:", "{")
STATEMENT = re.compile(r"^[A-Za-z_][\w.?!]*(\(.*\)|\s*\{.*\})(\s*\{.*\})?$")
ASSIGNMENT = re.compile(r"^([A-Za-z_][\w.?\[\]]*)\s*(=|\+=|-=)\s*(\S.*)$")
LITERAL = re.compile(r'^"[^"$]*"$')
PRESENTATION_NAMES = {"styleClass", "promptText", "prefWidth", "prefHeight", "minWidth", "minHeight",
                      "maxWidth", "maxHeight", "padding", "spacing", "hgap", "vgap", "alignment", "prefColumnCount",
                      "prefRowCount", "isWrapText", "graphicTextGap", "contentDisplay", "textOverrun", "tooltip",
                      "fixedCellSize", "stylesheets"}
PRESENTATION_CALL = re.compile(r"(^|\.)(styleClass|getStyleClass\(\))\.(add|addAll|remove|removeAll|setAll)\(|"
                               r"(^|\.)(setPrefSize|setMinSize|setMaxSize|setHgrow|setVgrow|setMargin)\(")
GUARD_LIKE = re.compile(r"\?:\s*(return|continue|break|throw)\b|&&|\|\||\bif\b|\bwhile\b|\bcoerce|"
                        r"\brequire|\bcheck(NotNull)?\s*\(|\btake(If|Unless)\b|\bfilter(Not)?\b")
FILTERS = re.compile(r"\??\.(filter|filterNot|takeIf|takeUnless)\s*\{|\??\.(coerceIn|coerceAtLeast|coerceAtMost)\s*\(")

def code_mask(s):
    """The line with string literal contents and line comments blanked."""
    out, quote, i = [], None, 0
    while i < len(s):
        c = s[i]
        if quote:
            if c == "\\":
                out.append("  "); i += 2; continue
            out.append(c if c == quote else " ")
            if c == quote: quote = None
        else:
            if s.startswith("//", i):
                out.append(" " * (len(s) - i)); break
            if c in "\"'": quote = c
            out.append(c)
        i += 1
    return "".join(out)

def match_close(mask, open_at):
    pairs = {"(": ")", "{": "}", "[": "]"}
    depth, opener = 0, mask[open_at]
    for i in range(open_at, len(mask)):
        if mask[i] == opener: depth += 1
        elif mask[i] == pairs[opener]:
            depth -= 1
            if depth == 0: return i
    return -1

def balanced(mask):
    return all(mask.count(a) == mask.count(b) for a, b in ("()", "{}", "[]"))

def split_top(mask, cond, op):
    parts, depth, last, i = [], 0, 0, 0
    while i < len(cond):
        c = mask[i]
        if c in "([{": depth += 1
        elif c in ")]}": depth -= 1
        elif depth == 0 and mask.startswith(op, i):
            parts.append(cond[last:i].strip()); last = i + len(op); i += len(op); continue
        i += 1
    parts.append(cond[last:].strip())
    return parts

def braceless(pmask):
    """True if the previous line is an if/while/for head or an `else` without a brace, whose body is the next line."""
    if re.search(r"\belse$", pmask): return True
    for m in re.finditer(r"\b(if|while|for)\s*\(", pmask):
        close = match_close(pmask, m.end() - 1)
        if close >= 0 and not pmask[close + 1:].strip(): return True
    return False

def presentation(smask, stripped):
    if PRESENTATION_CALL.search(smask): return True
    m = ASSIGNMENT.match(stripped)
    if not m: return False
    name = m.group(1).split(".")[-1].split("?")[-1]
    return name in PRESENTATION_NAMES or bool(LITERAL.match(m.group(3).strip()))

def mutants(text, prev, nxt):
    stripped, indent = text.strip(), text[:len(text) - len(text.lstrip())]
    if not stripped or stripped.startswith(("//", "/*", "*", "import ", "package ", "@")): return
    mask = code_mask(text)
    found = False
    for m in re.finditer(r"\b(if|while)\s*\(", mask):
        open_at = m.end() - 1
        close = match_close(mask, open_at)
        if close < 0: continue
        cond, cmask = text[open_at + 1:close], mask[open_at + 1:close]
        head, tail, word = text[:open_at + 1], text[close:], m.group(1)
        found = True
        yield head + "false" + tail, "%s (%s) -> false" % (word, cond.strip())
        if word == "if" and re.search(r"\belse\b", mask[close:]):
            yield head + "true" + tail, "if (%s) -> true" % cond.strip()
        for op in ("||", "&&"):  # || binds weaker, so it is the top-level operator when both appear
            parts = split_top(cmask, cond, op)
            if len(parts) > 1:
                for j, part in enumerate(parts):
                    rest = (" %s " % op).join(p for k, p in enumerate(parts) if k != j)
                    yield head + rest + tail, "operand `%s` of %s dropped" % (part, op)
                break
    for m in FILTERS.finditer(mask):
        close = match_close(mask, m.end() - 1)
        if close < 0: continue
        found = True
        yield text[:m.start()] + text[close + 1:], "%s removed" % text[m.start():close + 1].strip()
    smask = code_mask(stripped).rstrip()
    if found or DECL.search(smask) or LOGGING.match(stripped): return
    if stripped.startswith(SKIP_START) or not balanced(smask) or smask.endswith(CONTINUES): return
    pmask = code_mask(prev).rstrip()
    if pmask.endswith(CONTINUES[:-1]) or braceless(pmask): return
    if nxt.lstrip().startswith((".", "?.", "?:", "&&", "||")): return
    if presentation(smask, stripped): return
    if STATEMENT.match(smask) or (ASSIGNMENT.match(smask) and "==" not in smask):
        yield indent, "statement removed: " + stripped

def in_raw_strings(lines):
    """Per line: True if any part of it lies inside a multi-line raw string."""
    inside, result = False, []
    for line in lines:
        count = line.count('"""')
        result.append(inside or count > 0)
        if count % 2: inside = not inside
    return result

def plan_for(added):
    plan, unmutated = [], []
    for path in sorted(added):
        lines = read(path, "").split("\n")
        raw = in_raw_strings(lines)
        for n in sorted(added[path]):
            if n > len(lines) or raw[n - 1]: continue
            prev = next((l for l in reversed(lines[:n - 1]) if l.strip()), "")
            nxt = next((l for l in lines[n:] if l.strip()), "")
            found = list(mutants(lines[n - 1], prev, nxt))
            plan.extend((path, n, new, what) for new, what in found)
            stripped = lines[n - 1].strip()
            if not found and not stripped.startswith(("//", "*", "/*")) and GUARD_LIKE.search(code_mask(stripped)):
                unmutated.append((path, n, stripped))
    return plan, unmutated

def targets_for(path):
    gradle = ":" + path.split("/src/main/")[0].replace("/", ":")
    r = subprocess.run([".claude/scripts/related-tests.sh", "--names", path], capture_output=True, text=True)
    for line in r.stderr.splitlines(): print("  related-tests (%s): %s" % (path, line), file=sys.stderr)
    names = [] if r.stdout.strip() in ("", "no related tests") else r.stdout.split()
    return [gradle] + names + extra

def stages_for(targets):
    """(unit targets, UI class names): verify.sh resolves each target to `<module> <task> <class or *>`."""
    r = subprocess.run([".claude/scripts/verify.sh", "--list"] + targets, capture_output=True, text=True)
    if r.returncode: fail("verify.sh --list %s failed:\n%s" % (" ".join(targets), r.stdout + r.stderr))
    unit, ui = [], []
    for line in r.stdout.splitlines():
        parts = line.split()
        if len(parts) != 3: continue
        module, task, cls = parts
        name = module if cls == "*" else cls.split(".")[-1]
        group = unit if task == "test" else ui
        if name not in group: group.append(name)
    return unit, ui

# --- Runs ------------------------------------------------------------------------------------------------------
current_proc = None
fail_fast = os.path.join(work, "fail-fast.gradle")

def on_signal(signum, _frame):
    raise SystemExit(128 + signum)

def stop_children():
    global current_proc
    if current_proc is not None and current_proc.poll() is None:
        try: os.killpg(current_proc.pid, signal.SIGKILL)
        except ProcessLookupError: pass
        current_proc.wait()
    current_proc = None

def verify(targets, name, timeout, gradle_args=()):
    global current_proc
    cmd = ([".claude/scripts/verify.sh", "--no-format"] + (["--display", display] if display else []) + targets
           + ["--", "--init-script", fail_fast] + list(gradle_args))
    started = time.time()
    current_proc = subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    text=True, start_new_session=True)
    timed_out = False
    try:
        output, _ = current_proc.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        timed_out = True
        try: os.killpg(current_proc.pid, signal.SIGKILL)
        except ProcessLookupError: pass
        output, _ = current_proc.communicate()
    code, current_proc = current_proc.returncode, None
    elapsed = time.time() - started
    with open(os.path.join(logs, name + ".log"), "w") as f:
        f.write(output + ("\n[break-check: killed after %d s]\n" % timeout if timed_out else ""))
    if timed_out:
        # A hung test can keep a shared daemon busy; stop the daemons only when no other Gradle client runs anywhere.
        if subprocess.run([".claude/scripts/gradle-running.sh", "--any"], capture_output=True).returncode != 0:
            subprocess.run(["./gradlew", "--stop", "-q"], capture_output=True)
        return "timeout", "timeout after %d s" % timeout, elapsed
    out = output.splitlines()
    fails = [l for l in out if l.startswith("FAIL ")]
    if code == 0 and any(l.startswith("verify: OK") and "compiled only" not in l for l in out):
        return "green", "", elapsed
    if fails: return "red", fails[0][5:].split(":")[0], elapsed
    if any(l.startswith("e: ") for l in out): return "nocompile", "", elapsed
    return "error", (out[-1] if out else "no output"), elapsed

def invalidate_results(unit, ui):
    """Removes the results of the test tasks the targets resolve to (uiTest with its uiTestPart1/2), so that Gradle
    runs the baseline's tests (with --no-build-cache) instead of reusing results: its time sets the timeouts."""
    r = subprocess.run([".claude/scripts/verify.sh", "--list"] + unit + ui, capture_output=True, text=True)
    for line in r.stdout.splitlines():
        parts = line.split()
        if len(parts) != 3: continue
        results = os.path.join(root, parts[0].lstrip(":").replace(":", "/"), "build", "test-results")
        if not os.path.isdir(results): continue
        for task in os.listdir(results):
            if task == parts[1] or (parts[1] == "uiTest" and task.startswith("uiTestPart")):
                subprocess.run(["rm", "-rf", os.path.join(results, task)])

def run(plan, unmutated, targets):
    os.makedirs(logs, exist_ok=True)
    for name in os.listdir(logs): os.remove(os.path.join(logs, name))
    with open(fail_fast, "w") as f:
        f.write("allprojects { tasks.withType(Test).configureEach { failFast = true } }\n")
    originals = {}
    for p in targets:
        with open(p, "rb") as f: originals[p] = f.read()
    os.makedirs(restore_dir, exist_ok=True)
    manifest = {"files": {}, "applied": None}
    for k, (p, data) in enumerate(originals.items()):
        manifest["files"][p] = "%03d.orig" % k
        write_atomic(os.path.join(restore_dir, manifest["files"][p]), data)
    def save_manifest(applied):
        manifest["applied"] = applied
        write_atomic(manifest_file, json.dumps(manifest).encode())
    save_manifest(None)

    def restore():
        for p, data in originals.items():
            with open(p, "rb") as f: same = f.read() == data
            if not same: write_atomic(p, data)
        save_manifest(None)
    counts = {"killed": 0, "SURVIVED": 0, "timeout": 0, "no compile": 0}
    stages, base_time, last_killer = {}, {}, {}
    try:
        for path, t in targets.items():
            unit, ui = stages_for(t)
            stages[path] = (unit, ui)
            invalidate_results(unit, ui)
            for kind, group in (("unit", unit), ("ui", ui)):
                if not group: continue
                state, detail, elapsed = verify(group, "baseline-%s-%s" % (kind, path.replace("/", "_")), None,
                                                ["--no-build-cache"])
                if state != "green":
                    print("baseline (%s tests) of %s is not green (%s %s); fix that first. Logs: %s"
                          % (kind, path, state, detail, logs))
                    return 3
                base_time[(path, kind)] = elapsed
        low = sum(base_time.get((p, "unit"), 0) for p, *_ in plan)
        high = low + sum(base_time.get((p, "ui"), 0) for p, *_ in plan)
        print("break-check: %d mutants, between %d and %d min" % (len(plan), round(low / 60 + 0.5),
                                                                  round(high / 60 + 0.5)), flush=True)

        def evaluate(path, k):
            unit, ui = stages[path]
            ui_timeout = int(4 * base_time.get((path, "ui"), 0) + 60)
            runs = []
            killer = last_killer.get(path)
            if killer: runs.append(("first", [killer], ui_timeout))
            if unit: runs.append(("unit", unit, int(4 * base_time[(path, "unit")] + 60)))
            rest = [c for c in ui if c != killer]
            if rest: runs.append(("ui", rest, ui_timeout))
            for kind, group, timeout in runs:
                state, detail, _ = verify(group, "mutant-%03d-%s" % (k, kind), timeout)
                if state == "red":
                    cls = detail.split(".")[0]
                    if cls in ui: last_killer[path] = cls
                    return state, detail
                if state != "green": return state, detail
            return "green", ""

        for k, (path, n, new, what) in enumerate(plan, 1):
            save_manifest({"path": path, "line": n, "text": new})
            write_atomic(path, with_mutant(originals[path], n, new))
            try:
                state, detail = evaluate(path, k)
            finally:
                restore()
            if state == "error":
                print("mutant %d (%s:%d) neither passed nor failed: %s. Logs: %s/mutant-%03d-*.log"
                      % (k, path, n, detail, logs, k))
                return 3
            label = {"red": "killed", "timeout": "timeout", "green": "SURVIVED", "nocompile": "no compile"}[state]
            counts[label] += 1
            print("%-10s  %s:%d  %s%s" % (label, path, n, what, "  [%s]" % detail if detail else ""), flush=True)
    finally:
        stop_children()
        restore()
        clear_restore_dir()
    print("break-check: %d mutants, %d killed, %d SURVIVED, %d timeout, %d no compile; %d guard-like lines not "
          "mutated%s" % (len(plan), counts["killed"], counts["SURVIVED"], counts["timeout"], counts["no compile"],
                         len(unmutated), " (--list shows them)" if unmutated else ""))
    return 1 if counts["SURVIVED"] or counts["timeout"] else 0

def main():
    if not list_only:
        os.makedirs(work, exist_ok=True)
        with open(pid_file, "w") as f: f.write(str(os.getpid()))
        repair()
    added = added_lines()
    if only:
        missing = [f for f in only if f not in added]
        if missing: fail("No added production lines in: " + ", ".join(missing))
        added = {f: added[f] for f in only}
    if not added:
        print("break-check: no production Kotlin lines added since " + base)
        return 0
    if not list_only:
        dirty = git("status", "--porcelain", "--", *added).strip()
        if dirty: fail("Uncommitted changes in files to mutate; commit them (a fixup! commit) first:\n" + dirty)
    plan, unmutated = plan_for(added)
    targets = {path: targets_for(path) for path in sorted({p for p, *_ in plan})}
    if list_only:
        for path, t in targets.items(): print("%s  tests: %s" % (path, " ".join(t)))
        for path, n, _, what in plan: print("  %s:%d  %s" % (path, n, what))
        for path, n, text in unmutated: print("  not mutated (hand break check)  %s:%d  %s" % (path, n, text))
        print("break-check: %d mutants, %d guard-like lines not mutated (list only)" % (len(plan), len(unmutated)))
        return 0
    if not plan:
        print("break-check: 0 mutants; %d guard-like lines not mutated" % len(unmutated))
        return 0
    return run(plan, unmutated, targets)

for s in (signal.SIGTERM, signal.SIGHUP): signal.signal(s, on_signal)
code = 4
try:
    code = main()
except SystemExit as e:
    code = e.code if isinstance(e.code, int) else (0 if e.code is None else 2)
except KeyboardInterrupt:
    code = 128 + signal.SIGINT
except Exception:
    traceback.print_exc()
    code = 4
finally:
    if not list_only:
        if read(pid_file, "").strip() == str(os.getpid()):
            os.remove(pid_file)
            if detached:
                with open(status_file, "w") as f: f.write(str(code))
sys.exit(code)
PY
