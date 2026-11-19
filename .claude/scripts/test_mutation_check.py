#!/usr/bin/env python3
"""Tests of the pure parts of mutation_check.py. Run: python3 -I .claude/scripts/test_mutation_check.py"""
import os
import sys
import unittest

sys.dont_write_bytecode = True  # no __pycache__ in the scripts folder
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mutation_check as mc  # noqa: E402

GIT = "modules/git/src/main/kotlin/dev/sluide/git"

DIFF = f"""diff --git a/{GIT}/A.kt b/{GIT}/A.kt
index 1111111..2222222 100644
--- a/{GIT}/A.kt
+++ b/{GIT}/A.kt
@@ -3,0 +4,2 @@ class A
+val x = 1
+val y = 2
@@ -10 +12 @@ class A
-old
+new
@@ -20,2 +22,0 @@ class A
-gone
-gone
diff --git a/{GIT}/Old.kt b/{GIT}/Moved.kt
similarity index 90%
rename from {GIT}/Old.kt
rename to {GIT}/Moved.kt
index 3333333..4444444 100644
--- a/{GIT}/Old.kt
+++ b/{GIT}/Moved.kt
@@ -7,0 +8 @@ class Moved
+added in the moved file
diff --git a/{GIT}/Deleted.kt b/{GIT}/Deleted.kt
deleted file mode 100644
index 5555555..0000000
--- a/{GIT}/Deleted.kt
+++ /dev/null
@@ -1,3 +0,0 @@
-a
-b
-c
"""


def mutant(**kw):
    base = dict(
        status="SURVIVED",
        source_file="GitPush.kt",
        mutated_class="dev.sluide.git.GitPush",
        mutated_method="push",
        method_description="()V",
        line=10,
        mutator="NegateConditionalsMutator",
        description="negated conditional",
        indexes=(3,),
        killing_test="",
    )
    base.update(kw)
    return mc.Mutant(**base)


def element(status, source="GitPush.kt", line=328, killing="<killingTest/>", index=42,
            mutator="NonVoidMethodCallMutator", description="removed call to java/lang/Object::toString",
            cls="dev.sluide.git.GitPush"):
    detected = "true" if status == "KILLED" else "false"
    return (
        f"<mutation detected='{detected}' status='{status}' numberOfTestsRun='0'><sourceFile>{source}</sourceFile>"
        f"<mutatedClass>{cls}</mutatedClass><mutatedMethod>deleteRemoteRefs-9VgGkz4</mutatedMethod>"
        "<methodDescription>(Ljava/nio/file/Path;Ljava/lang/String;)Ldev/sluide/git/GitRemoteDeletionResult;"
        f"</methodDescription><lineNumber>{line}</lineNumber>"
        f"<mutator>org.pitest.mutationtest.engine.gregor.mutators.{mutator}</mutator>"
        f"<indexes><index>{index}</index></indexes><blocks><block>9</block></blocks>{killing}"
        f"<description>{description}</description></mutation>"
    )


def report(*elements):
    head = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mutations partial=\"true\">\n"
    return head + "\n".join(elements) + "\n</mutations>\n"


KILLING = (
    "<killingTest>dev.sluide.git.GitPushTest.[engine:junit-jupiter]/[class:dev.sluide.git.GitPushTest]/"
    "[method:the deletion&apos;s command line()]</killingTest>"
)


class DiffTest(unittest.TestCase):
    def test_added_and_changed_lines_per_file_with_renames_at_their_new_path_and_without_deletions(self):
        self.assertEqual(
            {f"{GIT}/A.kt": {4, 5, 12}, f"{GIT}/Moved.kt": {8}},
            mc.parse_diff(DIFF),
        )

    def test_a_line_starting_with_elvis_or_safe_call_allows_the_line_before_it(self):
        added = {"a.kt": {3, 6, 9, 1}, "b.kt": {2}}
        texts = {
            "a.kt": ["x", "y", "    ?: return", "p", "q", "    ?.let { }", "r", "s", "    foo()"],
            "b.kt": ["u", "v"],
        }
        self.assertEqual({"a.kt": {2, 3, 5, 6, 9, 1}, "b.kt": {2}}, mc.with_previous_lines(added, texts))

    def test_the_first_line_has_no_line_before_it_and_a_line_past_the_end_of_the_file_is_ignored(self):
        self.assertEqual(
            {"a.kt": {1}, "b.kt": {5}},
            mc.with_previous_lines({"a.kt": {1}, "b.kt": {5}}, {"a.kt": ["    ?: return"], "b.kt": ["x", "    ?.y"]}),
        )

    def test_a_line_that_merely_contains_elvis_allows_no_other_line(self):
        self.assertEqual({"a.kt": {2}}, mc.with_previous_lines({"a.kt": {2}}, {"a.kt": ["x", "val a = b ?: c"]}))

    def test_every_line_of_a_file_counts_in_whole_mode(self):
        self.assertEqual({1, 2, 3}, mc.whole_file_lines("a\nb\nc\n"))
        self.assertEqual({1, 2, 3}, mc.whole_file_lines("a\nb\nc"))
        self.assertEqual(set(), mc.whole_file_lines(""))


class ModuleTest(unittest.TestCase):
    def test_the_module_is_the_folder_above_src_main_kotlin(self):
        self.assertEqual("modules/git", mc.module_of(f"{GIT}/A.kt"))
        self.assertEqual("ide", mc.module_of("ide/src/main/kotlin/dev/sluide/ide/Main.kt"))
        self.assertEqual("tools/pitest-kotlin", mc.module_of("tools/pitest-kotlin/src/main/kotlin/x/Y.kt"))
        self.assertIsNone(mc.module_of("modules/git/src/test/kotlin/dev/sluide/git/ATest.kt"))

    def test_the_gradle_task_is_the_modules_pitest_task(self):
        self.assertEqual(":modules:git-log:pitest", mc.gradle_task("modules/git-log"))
        self.assertEqual(":ide:pitest", mc.gradle_task("ide"))

    def test_the_package_is_read_from_the_package_line(self):
        self.assertEqual("dev.sluide.git", mc.package_of("// c\n\npackage dev.sluide.git\n\nimport a.b\n"))
        self.assertEqual("dev.sluide.git.cli", mc.package_of("package dev.sluide.git.cli // x\nclass A"))
        self.assertIsNone(mc.package_of("class A"))

    def test_the_default_package_is_targeted_with_a_bare_star(self):
        self.assertEqual(["*", "dev.sluide.git.*"], mc.target_classes([None, "dev.sluide.git"]))

    def test_package_problems_list_every_file_of_the_run_whose_package_line_does_not_match_its_folder(self):
        texts = {
            f"{GIT}/A.kt": "package dev.sluide.git\n",
            f"{GIT}/B.kt": "package dev.sluide.wrong\n",
            "modules/x/src/main/kotlin/dev/sluide/x/C.kt": "class C\n",
        }
        problems = mc.package_problems(texts)
        self.assertEqual(2, len(problems))
        self.assertIn("B.kt", problems[0])
        self.assertIn("C.kt", problems[1])
        self.assertEqual([], mc.package_problems({f"{GIT}/A.kt": "package dev.sluide.git\n"}))

    def test_a_package_line_that_does_not_match_the_folder_is_named_as_a_refusal(self):
        path = f"{GIT}/A.kt"
        self.assertIsNone(mc.package_mismatch(path, "package dev.sluide.git\n"))
        self.assertIn("dev.sluide.git.other", mc.package_mismatch(path, "package dev.sluide.git.other\n") or "")
        self.assertIn("no package line", mc.package_mismatch(path, "class A\n") or "")
        self.assertIsNone(mc.package_mismatch("ide/src/main/kotlin/Main.kt", "class A\n"))

    def test_target_classes_are_the_distinct_packages_with_a_star_never_class_names(self):
        self.assertEqual(
            ["dev.sluide.git.*", "dev.sluide.git.cli.*"],
            mc.target_classes(["dev.sluide.git.cli", "dev.sluide.git", "dev.sluide.git"]),
        )

    def test_allow_list_entries_are_repo_paths_with_lines_sorted(self):
        self.assertEqual(
            ["a/B.kt:2", "a/B.kt:10", "a/C.kt:1"],
            mc.allow_list({"a/C.kt": {1}, "a/B.kt": {10, 2}}),
        )


class ReportTest(unittest.TestCase):
    def test_a_report_gives_one_record_per_mutation_with_its_identity_and_short_names(self):
        xml = report(
            element("KILLED", killing=KILLING, index=18, line=328),
            element("SURVIVED", index=42, line=329, mutator="NegateConditionalsMutator", description="negated"),
        )
        parsed = mc.parse_report(xml)
        self.assertEqual(2, len(parsed))
        killed, survived = parsed
        self.assertEqual(
            mc.Mutant(
                status="KILLED",
                source_file="GitPush.kt",
                mutated_class="dev.sluide.git.GitPush",
                mutated_method="deleteRemoteRefs-9VgGkz4",
                method_description="(Ljava/nio/file/Path;Ljava/lang/String;)Ldev/sluide/git/GitRemoteDeletionResult;",
                line=328,
                mutator="NonVoidMethodCallMutator",
                description="removed call to java/lang/Object::toString",
                indexes=(18,),
                killing_test="GitPushTest.the deletion's command line",
            ),
            killed,
        )
        self.assertEqual(
            ("SURVIVED", 329, "NegateConditionalsMutator", "negated", ""),
            (survived.status, survived.line, survived.mutator, survived.description, survived.killing_test),
        )

    def test_not_viable_mutants_are_dropped_and_the_other_statuses_kept(self):
        xml = report(
            element("NON_VIABLE", index=1),
            element("NO_COVERAGE", index=2),
            element("TIMED_OUT", index=3),
            element("MEMORY_ERROR", index=4),
            element("RUN_ERROR", index=5),
        )
        self.assertEqual(
            ["NO_COVERAGE", "TIMED_OUT", "MEMORY_ERROR", "RUN_ERROR"], [m.status for m in mc.parse_report(xml)]
        )

    def test_a_parameterized_test_names_its_template_and_a_mutation_has_several_indexes(self):
        killing = (
            "<killingTest>dev.sluide.x.T.[engine:junit-jupiter]/[class:dev.sluide.x.T]/"
            "[test-template:cases(int)]/[test-template-invocation:#2]</killingTest>"
        )
        two = element("KILLED", killing=killing).replace("<index>42</index>", "<index>4</index><index>9</index>")
        xml = report(two)
        parsed = mc.parse_report(xml)
        self.assertEqual(1, len(parsed))
        (m,) = parsed
        self.assertEqual("T.cases", m.killing_test)
        self.assertEqual((4, 9), m.indexes)


    def test_only_the_trailing_parenthesis_of_a_test_name_is_dropped(self):
        killing = (
            "<killingTest>dev.sluide.x.T.[engine:junit-jupiter]/[class:dev.sluide.x.T]/"
            "[method:moves (with undo) the entry()]</killingTest>"
        )
        (m,) = mc.parse_report(report(element("KILLED", killing=killing)))
        self.assertEqual("T.moves (with undo) the entry", m.killing_test)


class MergeTest(unittest.TestCase):
    def test_a_mutant_the_ui_stage_killed_is_killed_and_names_the_ui_test_the_others_keep_their_unit_status(self):
        survived = mutant(indexes=(1,))
        timeout = mutant(status="TIMED_OUT", indexes=(2,))
        stays = mutant(indexes=(3,))
        killed_before = mutant(status="KILLED", indexes=(4,), killing_test="UnitTest.a")
        ui = [
            mutant(status="KILLED", indexes=(1,), line=99, killing_test="PaneUiTest.b"),
            mutant(status="TIMED_OUT", indexes=(3,)),
            # The UI stage reruns the unit-killed mutants that share a line with a survivor.
            mutant(status="KILLED", indexes=(4,), killing_test="PaneUiTest.other"),
        ]
        merged = mc.merge_stages([survived, timeout, stays, killed_before], ui)
        self.assertEqual(
            [("KILLED", True, "PaneUiTest.b", 10), ("TIMED_OUT", False, "", 10), ("SURVIVED", False, "", 10),
             ("KILLED", False, "UnitTest.a", 10)],
            [(m.status, m.by_ui, m.killing_test, m.line) for m in merged],
        )

    def test_mutants_are_matched_by_class_method_descriptor_mutator_and_indexes_not_by_line(self):
        base = mutant()
        others = [
            mutant(mutated_class="dev.sluide.git.Other"),
            mutant(mutated_method="pull"),
            mutant(method_description="(I)V"),
            mutant(mutator="MathMutator"),
            mutant(indexes=(4,)),
        ]
        keys = {mc.mutant_key(m) for m in [base] + others}
        self.assertEqual(6, len(keys))
        self.assertEqual(mc.mutant_key(base), mc.mutant_key(mutant(line=77, description="x", status="KILLED")))


class UiStageTest(unittest.TestCase):
    MUTANTS = [
        mutant(status="SURVIVED", line=5, indexes=(1,), mutated_class="dev.sluide.x.A"),
        mutant(status="KILLED", line=5, indexes=(2,), mutated_class="dev.sluide.x.A"),
        mutant(status="NO_COVERAGE", line=7, indexes=(3,), mutated_class="dev.sluide.x.A$run$1"),
        mutant(status="KILLED", line=3, indexes=(4,), mutated_class="dev.sluide.x.B"),
        mutant(status="TIMED_OUT", line=4, indexes=(5,), mutated_class="dev.sluide.x.B"),
        mutant(status="MEMORY_ERROR", line=6, indexes=(6,), mutated_class="dev.sluide.x.B"),
    ]
    PATHS = ["a.kt", "a.kt", "a.kt", "b.kt", "b.kt", "b.kt"]

    def test_the_lines_left_are_those_of_survivors_and_no_coverage_mutants_and_a_file_with_none_stays_out(self):
        self.assertEqual({"a.kt": {5, 7}}, mc.left_lines(self.MUTANTS, self.PATHS))

    def test_the_classes_left_are_those_that_hold_a_remaining_mutant_whatever_their_package(self):
        moved = mutant(status="SURVIVED", mutated_class="dev.sluide.other.Caller", source_file="dev/sluide/x/Inl.kt")
        self.assertEqual(
            ["dev.sluide.other.Caller", "dev.sluide.x.A", "dev.sluide.x.A$run$1"],
            mc.left_classes(self.MUTANTS + [moved]),
        )

    def test_the_mutants_the_ui_stage_is_asked_about_are_the_survivors_and_no_coverage_ones(self):
        self.assertEqual(2, len(mc.left_mutants(self.MUTANTS)))

    def test_the_ui_stage_runs_on_linux_only_and_a_skip_says_why(self):
        self.assertEqual((False, None), mc.effective_unit_only(False, "linux"))
        self.assertEqual((True, None), mc.effective_unit_only(True, "linux"))
        skipped, note = mc.effective_unit_only(False, "darwin")
        self.assertTrue(skipped)
        self.assertIn("Linux", note or "")
        self.assertEqual((True, None), mc.effective_unit_only(True, "darwin"))


class PathTest(unittest.TestCase):
    ROOTS = ["ide", "modules/a", "modules/git"]

    def test_a_plain_source_file_goes_under_the_run_modules_package_path(self):
        m = mutant(mutated_class="dev.sluide.git.cli.GitRefusalKt$refusal$1", source_file="GitRefusal.kt")
        self.assertEqual(
            "modules/git/src/main/kotlin/dev/sluide/git/cli/GitRefusal.kt",
            mc.mutant_path(m, "modules/git", self.ROOTS, lambda p: False),
        )

    def test_a_source_file_with_a_slash_is_a_path_from_the_source_root_of_whichever_module_owns_it(self):
        m = mutant(source_file="dev/sluide/a/Inl.kt")
        owned = {"modules/a/src/main/kotlin/dev/sluide/a/Inl.kt"}
        self.assertEqual(
            "modules/a/src/main/kotlin/dev/sluide/a/Inl.kt",
            mc.mutant_path(m, "modules/git", self.ROOTS, lambda p: p in owned),
        )

    def test_a_source_file_with_a_slash_goes_to_the_run_module_when_it_owns_it_too(self):
        m = mutant(source_file="dev/sluide/a/Inl.kt")
        owned = {"modules/a/src/main/kotlin/dev/sluide/a/Inl.kt", "modules/git/src/main/kotlin/dev/sluide/a/Inl.kt"}
        self.assertEqual(
            "modules/git/src/main/kotlin/dev/sluide/a/Inl.kt",
            mc.mutant_path(m, "modules/git", self.ROOTS, lambda p: p in owned),
        )

    def test_a_source_file_with_a_slash_that_no_module_owns_stays_under_the_run_module(self):
        m = mutant(source_file="dev/sluide/a/Inl.kt")
        self.assertEqual(
            "modules/git/src/main/kotlin/dev/sluide/a/Inl.kt",
            mc.mutant_path(m, "modules/git", self.ROOTS, lambda p: False),
        )


class OutputTest(unittest.TestCase):
    def test_a_mutant_left_is_one_line_with_its_label_path_line_mutator_and_description(self):
        path = "modules/git/src/main/kotlin/dev/sluide/git/GitPush.kt"
        labels = {
            "SURVIVED": "SURVIVED",
            "NO_COVERAGE": "no coverage",
            "TIMED_OUT": "timeout",
            "MEMORY_ERROR": "memory error",
            "RUN_ERROR": "run error",
        }
        for status, label in labels.items():
            self.assertEqual(
                f"{label}  {path}:10  NegateConditionalsMutator: negated conditional",
                mc.format_mutant(mutant(status=status), path, False),
            )

    def test_killed_mutants_print_only_with_verbose_and_name_their_test(self):
        killed = mutant(status="KILLED", killing_test="GitPushTest.push")
        self.assertIsNone(mc.format_mutant(killed, "p.kt", False))
        self.assertEqual("killed  p.kt:10  NegateConditionalsMutator: negated conditional [GitPushTest.push]",
                         mc.format_mutant(killed, "p.kt", True))

    def test_the_summary_counts_each_status_and_the_ui_stage(self):
        mutants = [mutant(status="KILLED")] * 3 + [mutant(status="SURVIVED"), mutant(status="NO_COVERAGE"),
                                                   mutant(status="NO_COVERAGE"), mutant(status="TIMED_OUT")]
        self.assertEqual(
            "mutation-check: 7 mutants, 3 killed, 1 SURVIVED, 2 no coverage, 1 timeout; "
            "UI stage: 4 mutants on 2 files, 1 killed in the UI stage",
            mc.summary(mutants, (4, 2, 1), False),
        )

    def test_the_unit_only_summary_is_preliminary_and_errors_get_their_own_count(self):
        mutants = [mutant(status="KILLED"), mutant(status="MEMORY_ERROR"), mutant(status="RUN_ERROR")]
        self.assertEqual(
            "mutation-check: 3 mutants, 1 killed, 0 SURVIVED, 0 no coverage, 0 timeout, 2 errors; "
            "unit tests only; preliminary",
            mc.summary(mutants, None, True),
        )

    def test_the_exit_code_is_1_when_anything_is_left_else_0(self):
        self.assertEqual(0, mc.exit_code([mutant(status="KILLED")]))
        self.assertEqual(0, mc.exit_code([]))
        for status in ("SURVIVED", "NO_COVERAGE", "TIMED_OUT", "MEMORY_ERROR", "RUN_ERROR"):
            self.assertEqual(1, mc.exit_code([mutant(status="KILLED"), mutant(status=status)]), status)


class UiTestsTest(unittest.TestCase):
    FILES = {
        "ide/src/test/kotlin/dev/sluide/ide/MainWindowScratchesUiTest.kt":
            "package dev.sluide.ide\n\n@Tag(\"ui\")\nclass MainWindowScratchesUiTest : Fixture() {\n"
            "    private class Fake\n}\n",
        "ide/src/test/kotlin/dev/sluide/ide/scratches/Shots.kt":
            "package dev.sluide.ide.scratches\n\nclass Shots\n\ninternal class ShotsUiTest {\n}\n",
        "ide/src/test/kotlin/Bare.kt": "class BareUiTest\n",
    }

    def test_ui_test_names_become_the_qualified_classes_the_modules_test_files_declare(self):
        self.assertEqual(
            ["BareUiTest", "dev.sluide.ide.MainWindowScratchesUiTest", "dev.sluide.ide.scratches.ShotsUiTest"],
            mc.ui_test_classes(["ShotsUiTest", "MainWindowScratchesUiTest", "BareUiTest", "ShotsUiTest"], self.FILES),
        )

    def test_a_name_no_test_file_declares_gives_nothing_and_a_longer_class_name_is_no_match(self):
        self.assertEqual([], mc.ui_test_classes(["MainWindowScratches", "Shot", "ElsewhereUiTest"], self.FILES))

    def test_a_nested_class_is_no_test_class_of_that_name(self):
        self.assertEqual([], mc.ui_test_classes(["Fake"], self.FILES))

    def test_the_unknown_names_are_those_no_module_of_the_run_declares(self):
        other = {"m/src/test/kotlin/OtherUiTest.kt": "package o\n\nclass OtherUiTest\n"}
        self.assertEqual(
            ["Fake", "NoSuchUiTest"],
            mc.unknown_ui_tests(["ShotsUiTest", "NoSuchUiTest", "OtherUiTest", "Fake"], [self.FILES, other]),
        )


class AcceptedTest(unittest.TestCase):
    PATH = "ide/src/main/kotlin/dev/sluide/ide/Guards.kt"
    CLS = "dev.sluide.ide.Guards"
    LEFT = (
        f"{PATH}\t66\t{CLS}\tscan\tMathMutator\tReplaced integer addition with subtraction\n"
        f"{PATH}\t66\t{CLS}\tscan\tMathMutator\tReplaced integer addition with subtraction\n"
        f"{PATH}\t66\t{CLS}\tscan\tNonVoidMethodCallMutator\tremoved call to indexOf\n"
        f"{PATH}\t70\t{CLS}\tscan\tNegateConditionalsMutator\tnegated conditional\n"
    )
    MATH = dict(mutated_class=CLS, mutated_method="scan", mutator="MathMutator",
                description="Replaced integer addition with subtraction")
    ENTRY = (f"{PATH}\t{CLS}\tscan\tMathMutator\tReplaced integer addition with subtraction"
             "\tfrom = indexOf(from + 1)\tendless loop\n")

    def test_accepting_a_line_and_mutator_records_each_such_mutant_the_last_run_left_with_the_lines_text(self):
        entry = (f"{self.PATH}\t{self.CLS}\tscan\tMathMutator\tReplaced integer addition with subtraction"
                 "\tfrom = indexOf(from + 1)\tendless loop: from never grows")
        self.assertEqual(
            [entry, entry],
            mc.new_accepted(self.LEFT, self.PATH, 66, "MathMutator", "endless loop:\tfrom never grows",
                            "    from = indexOf(from + 1)  "),
        )

    def test_accepting_a_line_without_a_mutator_takes_its_mutants_when_they_share_one_mutator(self):
        self.assertEqual(1, len(mc.new_accepted(self.LEFT, self.PATH, 70, None, "why", "x")))

    def test_a_line_whose_mutants_left_have_several_mutators_needs_the_mutator_named(self):
        self.assertEqual(["MathMutator", "NonVoidMethodCallMutator"], mc.mutators_left(self.LEFT, self.PATH, 66))
        with self.assertRaises(mc.Stop) as stop:
            mc.new_accepted(self.LEFT, self.PATH, 66, None, "why", "x")
        self.assertEqual(2, stop.exception.code)
        self.assertIn("MathMutator, NonVoidMethodCallMutator", str(stop.exception))

    def test_accepting_a_line_or_mutator_the_last_run_left_nothing_on_records_nothing(self):
        self.assertEqual([], mc.new_accepted(self.LEFT, self.PATH, 67, None, "why", "x"))
        self.assertEqual([], mc.new_accepted(self.LEFT, "other.kt", 70, None, "why", "x"))
        self.assertEqual([], mc.new_accepted(self.LEFT, self.PATH, 70, "MathMutator", "why", "x"))

    def test_an_accept_target_is_a_path_a_line_and_maybe_a_mutator(self):
        self.assertEqual(("a/B.kt", 66, None), mc.accept_target("a/B.kt:66"))
        self.assertEqual(("a/B.kt", 66, "MathMutator"), mc.accept_target("a/B.kt:66:MathMutator"))
        for bad in ("a/B.kt", "a/B.kt:x", ":66", "a/B.kt:66:"):
            self.assertIsNone(mc.accept_target(bad), bad)

    def test_the_mutants_left_are_listed_for_a_later_accept_and_killed_ones_are_not(self):
        mutants = [mutant(status="TIMED_OUT", line=66, mutator="MathMutator", description="a\tb"),
                   mutant(status="KILLED", line=67)]
        self.assertEqual(f"{self.PATH}\t66\tdev.sluide.git.GitPush\tpush\tMathMutator\ta b\n",
                         mc.left_rows(mutants, [self.PATH, self.PATH]))

    def accepted(self, mutants, paths=None, text="from = indexOf(from + 1)", entries=None):
        return [m.status for m in mc.apply_accepted(mutants, paths or [self.PATH] * len(mutants),
                                                    mc.parse_accepted(entries or self.ENTRY), lambda p, n: text)]

    def test_a_mutant_left_that_matches_an_entry_and_whose_line_still_has_the_entrys_text_is_accepted(self):
        got = mc.apply_accepted([mutant(status="TIMED_OUT", line=66, **self.MATH)], [self.PATH],
                                mc.parse_accepted(self.ENTRY), lambda p, n: "  from = indexOf(from + 1)")
        self.assertEqual(("ACCEPTED", "endless loop"), (got[0].status, got[0].reason))

    def test_a_killed_mutant_and_one_that_differs_in_any_part_of_the_entry_are_not_accepted(self):
        self.assertEqual(["KILLED"], self.accepted([mutant(status="KILLED", **self.MATH)]))
        for part, other in (("mutator", "OtherMutator"), ("description", "another"), ("mutated_class", "x.Other"),
                            ("mutated_method", "save")):
            self.assertEqual(["SURVIVED"], self.accepted([mutant(**{**self.MATH, part: other})]), part)
        self.assertEqual(["SURVIVED"], self.accepted([mutant(**self.MATH)], paths=["other.kt"]))
        self.assertEqual(["SURVIVED"], self.accepted([mutant(**self.MATH)], text="from = indexOf(from + 2)"))

    def test_an_entry_accepts_one_mutant_only_so_a_second_one_like_it_stays_left(self):
        twice = [mutant(indexes=(1,), **self.MATH), mutant(indexes=(2,), **self.MATH)]
        self.assertEqual(["ACCEPTED", "SURVIVED"], self.accepted(twice))
        self.assertEqual(["ACCEPTED", "ACCEPTED"], self.accepted(twice, entries=self.ENTRY * 2))

    def test_blank_and_malformed_lines_of_the_accepted_file_are_no_entries(self):
        self.assertEqual(1, len(mc.parse_accepted("\n" + self.ENTRY + "only\ttwo\n")))

    def test_an_accepted_mutant_is_not_left_is_printed_with_its_reason_and_counted_on_its_own(self):
        accepted = mutant(status="ACCEPTED", reason="endless loop")
        self.assertEqual(0, mc.exit_code([accepted]))
        self.assertEqual([], mc.left_mutants([accepted]))
        self.assertEqual({}, mc.left_lines([accepted], ["a.kt"]))
        self.assertEqual("accepted  p.kt:10  NegateConditionalsMutator: negated conditional  (endless loop)",
                         mc.format_mutant(accepted, "p.kt", False))
        self.assertEqual(
            "mutation-check: 2 mutants, 1 killed, 0 SURVIVED, 0 no coverage, 0 timeout, 1 accepted; "
            "unit tests only; preliminary",
            mc.summary([accepted, mutant(status="KILLED")], None, True),
        )


class ArgsTest(unittest.TestCase):
    def test_ui_tests_takes_a_comma_list_of_class_names(self):
        opts = mc.parse_args(["--ui-tests", "AUiTest, BUiTest,", "base"])
        self.assertEqual(["AUiTest", "BUiTest"], opts["ui_tests"])
        self.assertEqual([], mc.parse_args(["base"])["ui_tests"])

    def test_ui_tests_without_names_is_a_usage_error(self):
        for args in (["base", "--ui-tests"], ["--ui-tests", "--detach", "base"], ["--ui-tests", " , ", "base"]):
            with self.assertRaises(mc.Stop) as stop:
                mc.parse_args(args)
            self.assertEqual(2, stop.exception.code, args)


class FailureHintTest(unittest.TestCase):
    RED = (
        "10:53:38 AM PIT >> SEVERE : Description [testClass=a.ScratchRedTest] did not pass without mutation.\n"
        "* What went wrong:\n"
        "Execution failed for task ':modules:git:pitest'.\n"
        "> PitHelpError: 1 tests did not pass without mutation\n"
    )

    def test_a_red_baseline_is_named_by_its_pit_help_error_line(self):
        self.assertEqual("PitHelpError: 1 tests did not pass without mutation", mc.failure_hint(self.RED, 1))

    def test_a_missing_or_empty_lines_file_and_a_missing_classes_property_are_named(self):
        self.assertEqual("LINES: file x is empty", mc.failure_hint("noise\nLINES: file x is empty\n", 1))
        self.assertEqual(
            "pitest needs -Psluide.pitest.classes=<list>",
            mc.failure_hint("> pitest needs -Psluide.pitest.classes=<list>\n", 1),
        )

    def test_another_failure_gives_the_line_gradle_says_went_wrong_else_the_exit_status(self):
        self.assertEqual(
            "Gradle failed: Could not resolve x",
            mc.failure_hint("* What went wrong:\nExecution failed for task ':a:pitest'.\n> Could not resolve x\n", 1),
        )
        self.assertEqual("Gradle failed (exit 7); see the log", mc.failure_hint("nothing\n", 7))


if __name__ == "__main__":
    unittest.main()
