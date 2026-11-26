package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test

class PlantUmlHighlightingTest {
    private fun plantuml(text: String) = Highlighted(text, RSyntaxHighlighter.plantuml)

    @ParameterizedTest
    @ValueSource(strings = ["@startuml", "@enduml", "@startmindmap", "@endmindmap", "@startgantt", "@startjson"])
    fun `start and end markers are keywords`(marker: String) {
        plantuml("$marker\n").assertExactly(TokenStyle.KEYWORD, marker)
    }

    @Test
    fun `the name after a start marker stays plain`() {
        val text = plantuml("@startmindmap my-name\n* root\n@endmindmap\n")
        text.assertExactly(TokenStyle.KEYWORD, "@startmindmap")
        text.assertPlain("my-name")
    }

    @ParameterizedTest
    @ValueSource(strings = ["' a comment -> with an arrow", "    ' an indented comment", "'"])
    fun `a quote starting a line comments the line`(line: String) {
        plantuml("@startuml\n$line\nA -> B\n").assertExactly(TokenStyle.COMMENT, line.trim())
    }

    @Test
    fun `a quote later in a line is text`() {
        val text = plantuml("A -> B : it's fine\nA -> B : 'quoted'\n")
        text.assertPlain("it's fine")
        text.assertPlain("'quoted'")
        text.assertExactly(TokenStyle.OPERATOR, "->")
    }

    @Test
    fun `block comments run over lines`() {
        val text = plantuml("@startuml\n/' first\nA -> B\n  last '/\nC -> D\n/' one '/ E -> F\n")
        text.assertExactly(TokenStyle.COMMENT, "/' first\nA -> B\n  last '/")
        text.assertExactly(TokenStyle.COMMENT, "/' one '/")
        text.assertExactly(TokenStyle.OPERATOR, "->", 1)
        text.assertExactly(TokenStyle.OPERATOR, "->", 2)
    }

    @Test
    fun `spans touching a block comment stay`() {
        val text = plantuml("/' a '/->/' b '/->")
        text.assertExactly(TokenStyle.COMMENT, "/' a '/")
        text.assertExactly(TokenStyle.OPERATOR, "->")
        text.assertExactly(TokenStyle.COMMENT, "/' b '/")
        text.assertExactly(TokenStyle.OPERATOR, "->", 1)
    }

    @Test
    fun `an unterminated block comment runs to the end`() {
        plantuml("a\n/' open\nclass X\n").assertExactly(TokenStyle.COMMENT, "/' open\nclass X\n")
    }

    @Test
    fun `a block comment opener after a line comment is no block`() {
        val text = plantuml("' see /'\nclass X\n")
        text.assertExactly(TokenStyle.COMMENT, "' see /'")
        text.assertExactly(TokenStyle.KEYWORD, "class")
        plantuml("  ' see /'").assertExactly(TokenStyle.COMMENT, "' see /'")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "!include other.puml", "!include <C4/C4_Container>", "!define NAME value", "!theme plain",
            "!pragma teoz true", "!if (a)", "!endif", "!procedure foo()",
        ],
    )
    fun `preprocessor lines are annotations`(line: String) {
        plantuml("@startuml\n$line\n").assertExactly(TokenStyle.ANNOTATION, line.substringBefore(' '))
    }

    @ParameterizedTest
    @CsvSource(
        "participant Alice, participant",
        "actor Bob, actor",
        "database DB, database",
        "abstract class A, abstract",
        "interface I, interface",
        "enum E, enum",
        "note left of A: hi, note",
        "end note, end note",
        "alt ok, alt",
        "else not ok, else",
        "end, end",
        "activate A, activate",
        "autonumber, autonumber",
        "title My title, title",
        "skinparam shadowing false, skinparam",
        "left to right direction, left to right direction",
        "start, start",
        "if (x) then (yes), if",
        "endif, endif",
        "end fork, end fork",
        "fork, fork",
        "detach, detach",
        "legend right, legend",
        "endlegend, endlegend",
        "'   package p {', package",
        "usecase U, usecase",
        "hide empty members, hide",
        "box Foo, box",
        "end box, end box",
        "ref over A, ref",
        "end ref, end ref",
        "artifact a, artifact",
        "card c, card",
        "agent g, agent",
        "storage s, storage",
        "file f, file",
        "person p, person",
        "label l, label",
        "switch (x), switch",
        "case (1), case",
        "endswitch, endswitch",
        "backward :x;, backward",
        "endnote, endnote",
        "endfork, endfork",
        "hnote over A, hnote",
        "rnote over A, rnote",
        "newpage, newpage",
    )
    fun `statements at the start of a line are keywords`(
        line: String,
        keyword: String,
    ) {
        plantuml("A -> B\n$line\n").assertExactly(TokenStyle.KEYWORD, keyword)
    }

    @Test
    fun `a longer name starting like a keyword is no keyword`() {
        val text = plantuml("classic -> B\nendpoint -> C\nstartup -> D\n")
        text.assertPlain("classic")
        text.assertPlain("endpoint")
        text.assertPlain("startup")
    }

    @Test
    fun `keywords count only at the start of a line`() {
        val text = plantuml("A -> B : class actor\nA : title\n")
        text.assertPlain("class")
        text.assertPlain("actor")
        text.assertPlain("title")
    }

    @Test
    fun `strings keep arrows and keywords inside`() {
        val text = plantuml("class \"A -> B ' x\" as AB\n")
        text.assertExactly(TokenStyle.STRING, "\"A -> B ' x\"")
        text.assertExactly(TokenStyle.KEYWORD, "class")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "->", "-->", "<-", "<--", "->>", "-\\\\", "..>", "<|--", "*--", "o--", "--|>", "-[#red]->", "-up->",
            "-left->", "<->", "<|..", "-[#blue,dashed]->", "--*", "--o", "-/", "/-", "-//", "\\\\-", "o->",
        ],
    )
    fun `arrows are operators`(arrow: String) {
        plantuml("A $arrow B : msg\n").assertExactly(TokenStyle.OPERATOR, arrow)
    }

    @Test
    fun `hyphens in names are no arrows`() {
        val text = plantuml("my-name : x\nA -> B : x-ray o-ring C++-style a/-b a-/b\n")
        text.assertPlain("my-name")
        text.assertPlain("x-ray")
        text.assertPlain("o-ring")
        text.assertPlain("C++-style")
        text.assertPlain("a/-b")
        text.assertPlain("a-/b")
    }

    @ParameterizedTest
    @ValueSource(strings = ["puml", "plantuml"])
    fun `code fences use it`(name: String) {
        val markdown = Highlighted("# D\n\n```$name\n@startuml\nA -> B\n```\n", RSyntaxHighlighter.markdown)
        markdown.assertExactly(TokenStyle.KEYWORD, "@startuml")
        markdown.assertExactly(TokenStyle.OPERATOR, "->")
    }

    @Test
    fun `spans are well-formed`() = plantuml("@startuml\n/' c '/\nA -> B : \"x\"\n@enduml\n").assertWellFormed()
}
