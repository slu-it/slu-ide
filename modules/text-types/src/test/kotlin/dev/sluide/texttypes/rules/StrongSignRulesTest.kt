package dev.sluide.texttypes.rules

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals

/** The rules for strong signs, each with the cases at its edges. */
class StrongSignRulesTest {
    @ParameterizedTest
    @MethodSource("shebangs")
    fun `a shebang names its interpreter's type`(
        text: String,
        expected: String,
    ) {
        assertEquals(expected, ShebangRule.scored(text))
    }

    @ParameterizedTest
    @MethodSource("markup")
    fun `markup is told by its prolog and root element`(
        text: String,
        expected: String,
    ) {
        assertEquals(expected, MarkupRule.scored(text))
    }

    @ParameterizedTest
    @MethodSource("mermaid")
    fun `Mermaid needs its keyword as the first line and one more line`(
        text: String,
        expected: String,
    ) {
        assertEquals(expected, MermaidRule.scored(text))
    }

    @ParameterizedTest
    @MethodSource("diffs")
    fun `a diff needs a git header or all three unified lines`(
        text: String,
        expected: String,
    ) {
        assertEquals(expected, DiffRule.scored(text))
    }

    @ParameterizedTest
    @MethodSource("bicep")
    fun `Bicep needs a resource, a target scope or two typed params`(
        text: String,
        expected: String,
    ) {
        assertEquals(expected, BicepRule.scored(text))
    }

    companion object {
        @JvmStatic
        fun shebangs(): List<Arguments> =
            cases(
                "#!/bin/bash" to "sh 100",
                "#!/bin/sh\necho python3" to "sh 100",
                "#!/usr/bin/env bash" to "sh 100",
                "#! /bin/zsh" to "sh 100",
                "#!/usr/bin/env  -S  dash -e" to "sh 100",
                "#!/usr/bin/env PATH=/opt/bin ksh" to "sh 100",
                "#!/usr/local/bin/python3.12" to "py 100",
                "#!/usr/bin/env python" to "py 100",
                "#!/usr/bin/env /usr/bin/python3" to "py 100",
                "#!/usr/bin/env -S deno run --allow-read" to "js 100",
                "#!/usr/bin/node" to "js 100",
                "#!/usr/bin/env bun" to "js 100",
                "#!/usr/bin/perl" to "",
                "#!/usr/bin/env" to "",
                "#!/usr/bin/env -i" to "",
                "#!" to "",
                "#!\n#!/bin/sh" to "",
                " #!/bin/sh" to "",
                "# python3 is great" to "",
            )

        @JvmStatic
        fun markup(): List<Arguments> =
            cases(
                "<svg/>" to "svg 98",
                "<svg:svg xmlns:svg=\"http://www.w3.org/2000/svg\"/>" to "svg 98",
                "<?xml version=\"1.0\"?>\n<svg/>" to "svg 98",
                "<!DOCTYPE html>\n<p>Hi" to "html 98",
                "<!doctype HTML PUBLIC \"-//W3C//DTD HTML 4.01//EN\">\n<p>Hi" to "html 98",
                "<!DOCTYPE htmlx>\n<htmlx>" to "",
                "<HTML><body></body></HTML>" to "html 95",
                "<?xml version=\"1.0\"?>\n<note/>" to "xml 95",
                "  \n<?xml version=\"1.0\"?>   " to "xml 95",
                "<?xml version=\"1.0\"?>\n<?mso-application progid=\"Excel.Sheet\"?>\n<Workbook>" to "xml 95",
                "<?xml version=\"1.0\"" to "xml 95",
                "<?php echo 1; ?>\n<p>" to "",
                "<!-- not closed <svg>" to "",
                "<!DOCTYPE html" to "",
                "<!-- a > b -->\n<note>x</note>" to "xml 70",
                "<!-- a -->\n<!-- b -->\n<note>x</note>\n" to "xml 70",
                "<!DOCTYPE note>\n<note>x</note>" to "xml 70",
                "<note>x</note>" to "xml 70",
                "<p>x</p>" to "html 70",
                "<P>x</P>" to "html 70",
                "<note>x</other>" to "",
                "xnote>x</note>" to "",
                "<!-- c -->\ntext </null>" to "",
                "<1>x</1>" to "",
                "hello" to "",
            )

        @JvmStatic
        fun mermaid(): List<Arguments> =
            cases(
                "graph TD\nA-->B" to "mmd 90",
                "graph\nA-->B" to "mmd 90",
                "flowchart LR;\nA-->B" to "mmd 90",
                "sequenceDiagram\nA->>B: hi" to "mmd 90",
                "classDiagram-v2\nclass A" to "mmd 90",
                "stateDiagram\n[*] --> A" to "mmd 90",
                "erDiagram\nA ||--o{ B : has" to "mmd 90",
                "gantt\ntitle Plan" to "mmd 90",
                "journey\ntitle Day" to "mmd 90",
                "gitGraph LR:\ncommit" to "mmd 90",
                "mindmap\nroot" to "mmd 90",
                "timeline\n2026 : start" to "mmd 90",
                "pie showData title Pets\n\"Dogs\" : 3" to "mmd 90",
                "\n  \n%% a comment\n  graph TD\nA-->B" to "mmd 90",
                "graph TD" to "",
                "graph TD\n\n  \n" to "",
                "graph TD\n%% only a comment" to "",
                "Timeline of the project\nwent well" to "",
                "pie is tasty\nyes" to "",
                "graph TD and more\nA-->B" to "",
            )

        @JvmStatic
        fun diffs(): List<Arguments> =
            cases(
                "diff --git a/x b/x\nindex 1..2" to "diff 95",
                "--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b" to "diff 92",
                "--- a/x\n+++ b/x\n@@ -1,3 +1,4 @@" to "diff 92",
                "+++ b/x\n@@ -1 +1 @@\n-a\n+b" to "",
                "--- a/x\n@@ -1 +1 @@\n-a\n+b" to "",
                "--- a/x\n+++ b/x\n-a\n+b" to "",
                "see diff --git in the docs" to "",
            )

        @JvmStatic
        fun bicep(): List<Arguments> =
            cases(
                "resource sa 'Microsoft.Storage/storageAccounts@2023-01-01' = {\n}" to "bicep 95",
                "resource kv 'Microsoft.KeyVault/vaults@2023-07-01-preview' = {\n}" to "bicep 95",
                "targetScope = 'subscription'" to "bicep 90",
                "param name string\nparam size int = 2" to "bicep 70",
                "param name string\noutput id string = x.id" to "bicep 70",
                "param name string" to "",
                "param name text\nparam size number" to "",
            )
    }
}
