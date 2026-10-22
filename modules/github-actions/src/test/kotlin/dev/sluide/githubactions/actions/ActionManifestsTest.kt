package dev.sluide.githubactions.actions

import dev.sluide.githubactions.model.Step
import dev.sluide.githubactions.model.StepAction
import dev.sluide.githubactions.model.UsesReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ActionManifestsTest {
    private fun parsed(text: String) = assertIs<ActionManifests.Result.Parsed>(ActionManifests.parse(text)).manifest

    private fun failed(text: String) = assertIs<ActionManifests.Result.Failed>(ActionManifests.parse(text)).reason

    @Test
    fun `a composite action has its name, inputs and steps with their lines`() {
        val manifest =
            parsed(
                """
                name: Set up
                inputs:
                  java:
                    description: Java version
                    required: true
                  cache:
                    required: false
                runs:
                  using: composite
                  steps:
                    - name: Install
                      run: |

                        ./install.sh --fast
                        echo done
                      shell: bash
                    - uses: ./.github/actions/other
                      if: runner.os == 'Linux'
                    - id: nothing
                    - uses: actions/cache@v4
                      run: echo both
                """.trimIndent(),
            )

        assertEquals("Set up", manifest.name)
        assertEquals(listOf(ActionInput("java", true), ActionInput("cache", false)), manifest.inputs)
        assertEquals(
            Runs.Composite(
                listOf(
                    Step(null, "Install", 11, StepAction.Run("./install.sh --fast"), null),
                    Step(
                        null,
                        null,
                        17,
                        StepAction.Uses(
                            "./.github/actions/other",
                            UsesReference.LocalAction("./.github/actions/other"),
                        ),
                        "runner.os == 'Linux'",
                    ),
                    Step(null, null, 20, StepAction.Run("echo both"), null),
                ),
            ),
            manifest.runs,
        )
    }

    @Test
    fun `JavaScript and Docker actions say how they run`() {
        assertEquals(Runs.JavaScript("node20"), parsed("runs:\n  using: node20\n  main: index.js").runs)
        assertEquals(Runs.JavaScript("node24"), parsed("runs:\n  using: 'node24'").runs)
        assertEquals(Runs.Docker("Dockerfile"), parsed("runs:\n  using: docker\n  image: Dockerfile").runs)
        assertEquals(Runs.Docker(null), parsed("runs:\n  using: docker").runs)
        assertEquals(Runs.Unknown("node"), parsed("runs:\n  using: node").runs)
        assertEquals(Runs.Unknown(null), parsed("name: x").runs)
        assertEquals(Runs.Unknown(null), parsed("runs: [composite]").runs)
    }

    @Test
    fun `YAML 1_1 booleans and numbers stay text`() {
        val manifest =
            parsed(
                """
                name: yes
                inputs:
                  on: {}
                  no:
                    required: yes
                  0x10:
                    required: TRUE
                runs:
                  using: composite
                  steps:
                    - run: echo
                      name: off
                      if: on
                """.trimIndent(),
            )

        assertEquals("yes", manifest.name)
        assertEquals(
            listOf(ActionInput("on", false), ActionInput("no", false), ActionInput("0x10", true)),
            manifest.inputs,
        )
        val step = assertIs<Runs.Composite>(manifest.runs).steps.single()
        assertEquals("off", step.name)
        assertEquals("on", step.condition)
    }

    @Test
    fun `an alias bomb is refused`() {
        val bomb =
            buildString {
                append("a: &a [x, x, x, x, x, x, x, x, x]\n")
                for (level in 1..9) {
                    append("${'a' + level}: &${'a' + level} [")
                    append((1..9).joinToString(", ") { "*${'a' + level - 1}" })
                    append("]\n")
                }
            }

        assertTrue("aliases" in failed(bomb).lowercase(), failed(bomb))
    }

    @Test
    fun `deep nesting is refused`() {
        val deep = "[".repeat(200) + "]".repeat(200)

        assertTrue("nesting" in failed("runs: $deep").lowercase(), failed("runs: $deep"))
    }

    @Test
    fun `broken YAML, custom tags and other documents fail with a short reason`() {
        assertTrue(failed("runs:\n  using: [composite\n").contains("(line "), failed("runs:\n  using: [composite\n"))
        assertTrue("Global tag is not allowed" in failed("runs: !!javax.script.ScriptEngineManager [x]"))
        assertEquals("not a YAML mapping", failed("- a\n- b"))
        assertEquals("not a YAML mapping", failed(""))
        assertTrue(failed("a: 1\n---\nb: 2").length <= 80)
    }

    @Test
    fun `texts over the code point limit are refused`() {
        val big = "name: " + "x".repeat(ActionManifests.MAX_CODE_POINTS + 10)

        assertEquals("The incoming YAML document exceeds the limit: 1048576 code points.", failed(big))
    }
}
