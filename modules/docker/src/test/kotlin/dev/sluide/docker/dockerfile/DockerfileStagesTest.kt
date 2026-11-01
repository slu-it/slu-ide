package dev.sluide.docker.dockerfile

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.Test
import kotlin.test.assertEquals

/** [parseDockerfile]: the stage names and ARGs of a Dockerfile, for the Target combo and the Build args table. */
class DockerfileStagesTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("stageCases")
    fun `finds the named stages`(
        @Suppress("UNUSED_PARAMETER") case: String,
        text: String,
        stages: List<String>,
    ) {
        assertEquals(stages, parseDockerfile(text).stages)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("argCases")
    fun `finds the ARGs with their defaults`(
        @Suppress("UNUSED_PARAMETER") case: String,
        text: String,
        // List<Any>: a public test method cannot take the internal BuildArg in its signature.
        args: List<Any>,
    ) {
        assertEquals(args, parseDockerfile(text).args)
    }

    @Test
    fun `an empty or broken file gives nothing and does not throw`() {
        for (text in listOf("", "\n\n", "FROM", "ARG", "ARG =x", "RUN <<EOF\nnever ends", "FROM a AS")) {
            assertEquals(DockerfileInfo(emptyList(), emptyList()), parseDockerfile(text), text)
        }
    }

    companion object {
        private fun arg(
            name: String,
            default: String? = null,
        ) = BuildArg(name, default)

        @JvmStatic
        fun stageCases(): List<Arguments> =
            listOf(
                Arguments.of("unnamed", "FROM alpine\nRUN true\n", emptyList<String>()),
                Arguments.of("named", "FROM node:20 AS deps\nFROM deps AS build\n", listOf("deps", "build")),
                Arguments.of(
                    "case-insensitive instruction and AS",
                    "from node:20 as deps\nFrOm deps As build",
                    listOf("deps", "build"),
                ),
                Arguments.of(
                    "platform flag",
                    "FROM --platform=\$BUILDPLATFORM golang:1.22 AS builder\nFROM scratch",
                    listOf("builder"),
                ),
                Arguments.of("continuation", "FROM \\\n  node:20 \\\n  AS deps\n", listOf("deps")),
                Arguments.of(
                    "backtick escape directive",
                    "# escape=`\nFROM mcr.microsoft.com/windows `\n  AS win\n",
                    listOf("win"),
                ),
                Arguments.of("comment", "# FROM a AS commented\nFROM b AS real  \n", listOf("real")),
                Arguments.of(
                    "comment inside a continuation",
                    "FROM node:20 \\\n# a note\n  AS deps\n",
                    listOf("deps"),
                ),
                Arguments.of(
                    "heredoc body is no instruction",
                    "FROM a AS one\nRUN <<EOF\nFROM b AS fake\nEOF\nFROM c AS two\n",
                    listOf("one", "two"),
                ),
                Arguments.of(
                    "quoted heredoc with stripped tabs, and two heredocs",
                    "FROM a AS one\nCOPY <<-\"X\" <<'Y' /dst/\n\tFROM b AS fake\n\tX\nFROM c AS fake2\nY\nFROM d AS two\n",
                    listOf("one", "two"),
                ),
                Arguments.of(
                    "a shift or a here-string is no heredoc",
                    "FROM a AS one\nRUN echo \$((1<<SHIFT))\nFROM b AS two\nRUN grep x <<<word\nFROM c AS three\n",
                    listOf("one", "two", "three"),
                ),
                Arguments.of("empty line in a continuation", "FROM node:20 \\\n\n  AS deps\n", listOf("deps")),
                Arguments.of("a name once", "FROM a AS app\nFROM b AS App\n", listOf("app")),
            )

        @JvmStatic
        fun argCases(): List<Arguments> =
            listOf(
                Arguments.of("plain", "ARG VERSION\n", listOf(arg("VERSION"))),
                Arguments.of("default", "arg VERSION=1.2\n", listOf(arg("VERSION", "1.2"))),
                Arguments.of("empty default", "ARG EMPTY=\n", listOf(arg("EMPTY", ""))),
                Arguments.of("several per line", "ARG a=1 b\n", listOf(arg("a", "1"), arg("b"))),
                Arguments.of(
                    "quoted defaults",
                    "ARG X=\"a b\" Y='c \$d'\n",
                    listOf(arg("X", "a b"), arg("Y", "c \$d")),
                ),
                Arguments.of("escaped space", "ARG X=a\\ b\n", listOf(arg("X", "a b"))),
                Arguments.of(
                    "global and in stages, in file order, the first of a name kept",
                    "ARG BASE=alpine\nFROM \$BASE AS one\nARG BASE\nARG TOKEN\nFROM one\nARG LEVEL=3 TOKEN=x\n",
                    listOf(arg("BASE", "alpine"), arg("TOKEN"), arg("LEVEL", "3")),
                ),
                Arguments.of(
                    "continuation",
                    "ARG A=1 \\\n    B=2\n",
                    listOf(arg("A", "1"), arg("B", "2")),
                ),
                Arguments.of(
                    "backtick continuation keeps backslashes",
                    "# escape=`\nARG DIR=C:\\tools `\n  NEXT\n",
                    listOf(arg("DIR", "C:\\tools"), arg("NEXT")),
                ),
                Arguments.of(
                    "heredoc body is no instruction",
                    "FROM a\nRUN <<EOF\nARG FAKE=1\nEOF\nARG REAL\n",
                    listOf(arg("REAL")),
                ),
                Arguments.of(
                    "after a shift and a here-string",
                    "FROM a\nRUN echo \$((1<<SHIFT))\nARG ONE\nRUN grep x <<<word\nARG TWO\n",
                    listOf(arg("ONE"), arg("TWO")),
                ),
                Arguments.of(
                    "empty line in a continuation",
                    "ARG A=1 \\\n\n    B=2\n",
                    listOf(arg("A", "1"), arg("B", "2")),
                ),
                Arguments.of("commented", "# ARG HIDDEN\n  # ARG ALSO\nARG SHOWN\n", listOf(arg("SHOWN"))),
            )
    }
}
