package dev.sluide.texttypes

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Texts that look like more than one type, with the built-in rules alone and with fake sources that give the scores
 * Converting's data detection gave for the same text (measured with `DataFamily.detect` on 2026-11-19; the comment
 * of each case names the score).
 */
class TextTypeConfusionTest {
    private val rulesOnly = TextTypeDetector()

    /** Converting's score for a text, as `ide` will plug it in. */
    private fun converting(
        type: TextType,
        score: Int,
    ) = TextTypeDetector(listOf(TextTypeSource { listOf(TypeScore(type, score)) }))

    @Test
    fun `the same class in Kotlin and in Java`() {
        val kotlin =
            """
            package com.example.shop

            import java.time.Instant

            class OrderService(private val repository: OrderRepository) {
                private val created: Instant = Instant.now()

                fun find(id: String): Order? = repository.byId(id)
            }
            """.trimIndent()
        val java =
            """
            package com.example.shop;

            import java.time.Instant;

            public class OrderService {
                private final OrderRepository repository;
                private final Instant created = Instant.now();

                public Order find(String id) {
                    return repository.byId(id);
                }
            }
            """.trimIndent()
        assertEquals(TextTypes.KOTLIN, rulesOnly.detect(kotlin))
        assertEquals(TextTypes.JAVA, rulesOnly.detect(java))
    }

    @Test
    fun `the same function in TypeScript and in JavaScript`() {
        val typescript =
            """
            export function total(items: Item[]): number {
              return items.reduce((sum, item) => sum + item.price, 0);
            }
            """.trimIndent()
        val javascript =
            """
            export function total(items) {
              return items.reduce((sum, item) => sum + item.price, 0);
            }
            """.trimIndent()
        assertEquals(TextTypes.TYPESCRIPT, rulesOnly.detect(typescript))
        assertEquals(TextTypes.JAVASCRIPT, rulesOnly.detect(javascript))
    }

    @Test
    fun `a shell script's comments do not make it Markdown`() {
        val script =
            """
            # Install the tools
            # - jq for the JSON
            brew install jq
            # Run it
            ./run.sh --verbose
            """.trimIndent()
        assertEquals(TextTypes.PLAIN_TEXT, rulesOnly.detect(script))
        assertEquals(TextTypes.SHELL_SCRIPT, rulesOnly.detect("#!/bin/bash\n$script"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "def f():\n    return 1\n",
            "def greet(name):\n    print(\"Hello\", name)\n",
            "class Config:\n    debug = False\n    port = 8080\n",
            "class Order:\n    def __init__(self, id):\n        self.id = id\n",
        ],
    )
    fun `a Python snippet that parses as a YAML mapping is Python`(snippet: String) {
        // Converting: yaml 80 for each.
        assertEquals(TextTypes.PYTHON, converting(TextTypes.YAML, 80).detect(snippet))
    }

    @Test
    fun `a YAML mapping stays YAML`() {
        // Converting: yaml 80.
        val yaml = "# Server settings\nserver:\n  port: 8080\n  hosts:\n    - a.example.com\n    - b.example.com\n"
        assertEquals(TextTypes.YAML, converting(TextTypes.YAML, 80).detect(yaml))
    }

    @Test
    fun `SQL in prose is Plain Text, SQL statements are SQL even where they parse as CSV`() {
        assertEquals(
            TextTypes.PLAIN_TEXT,
            rulesOnly.detect("Select the files you need from the list.\nThen update the settings and set a theme."),
        )
        // Converting: csv 70.
        val statements =
            "INSERT INTO customers (id, name) VALUES (1, 'Anna');\nUPDATE orders SET open = FALSE WHERE id = 42;"
        assertEquals(TextTypes.SQL, converting(TextTypes.CSV, 70).detect(statements))
    }

    @ParameterizedTest
    @MethodSource("strongSigns")
    fun `a strong sign beats a coincidental YAML 80`(
        text: String,
        expected: TextType,
    ) {
        assertEquals(expected, converting(TextTypes.YAML, 80).detect(text))
    }

    @Test
    fun `a README with a fenced Kotlin block is Markdown, a diff of a README is a diff`() {
        val readme = "# Hello\n\n```kotlin\nfun main() {\n    val name: String = readln()\n    println(name)\n}\n```\n"
        assertEquals(TextTypes.MARKDOWN, rulesOnly.detect(readme))
        val diff = "--- a/README.md\n+++ b/README.md\n@@ -1,2 +1,3 @@\n # Hello\n+- see [docs](docs.md)\n **bold**\n"
        assertEquals(TextTypes.DIFF, rulesOnly.detect(diff))
    }

    @Test
    fun `Markdown with front matter and enough Markdown beats YAML's multi-document 85`() {
        // Converting: yaml 85 (two documents).
        val post = "---\ntitle: Hello\n---\n# Hello\n\nSome **bold** text, a [link](https://x.org).\n\n- one\n- two\n"
        assertEquals(TextTypes.MARKDOWN, converting(TextTypes.YAML, 85).detect(post))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Select one option from the list below:\n* Pizza\n* Pasta",
            "Update website set up for launch\n* check links\n* fix typos",
            "Create table of contents for the report.\nBudget = 5000 EUR.",
            "We discussed public transport: any line is fine.",
            "Q: Is it done?\nA: Not yet, waiting for public review: unknown date.",
            "Reminder: never forget the keys.\nQuestion?: none",
            "Note: any feedback welcome.\nlet me know by Friday.",
            "Wow!! That was great.\nobject of the game: win",
            "Remember: never commit secrets!!\nfun fact (really): it works",
            "Todo:\n    call mom\n    buy milk\nwith the kids:\n    zoo",
            "class schedule:\n  Monday: math",
            "Delete from the drive anything where size > 1 GB.",
            "Select a seat from the plan where row = 5, please.",
            "Budget: any\nlet me know by Friday",
            "Seats left: none\nTickets: any\nconst reminder",
            "with open questions (see below):\n  budget\n  timeline",
        ],
    )
    fun `prose with the words or punctuation of code is Plain Text`(prose: String) {
        assertEquals(TextTypes.PLAIN_TEXT, rulesOnly.detect(prose))
    }

    @Test
    fun `a PlantUML class diagram with Kotlin members is PlantUML`() {
        val diagram =
            "@startuml\nclass OrderService {\n  private val repository: Repository\n" +
                "  fun find(id: String): Order\n}\n@enduml\n"

        assertEquals(TextTypes.PLANTUML, rulesOnly.detect(diagram))
    }

    @Test
    fun `the same function and the same typed value in Kotlin and in TypeScript`() {
        assertEquals(TextTypes.KOTLIN, rulesOnly.detect("fun f(x: String): Int {\n    return x.length\n}"))
        assertEquals(TextTypes.TYPESCRIPT, rulesOnly.detect("function f(x: string): number {\n  return x.length;\n}"))
        assertEquals(TextTypes.KOTLIN, rulesOnly.detect("val limit: Int = 10\nval name: String = \"x\""))
        assertEquals(TextTypes.TYPESCRIPT, rulesOnly.detect("const limit: number = 10;\nconst name: string = 'x';"))
    }

    @Test
    fun `the same method in Java and in JavaScript`() {
        val java =
            """
            public int total(List<Item> items) {
                int sum = 0;
                for (Item item : items) {
                    sum += item.price();
                }
                return sum;
            }
            """.trimIndent()
        val javascript =
            """
            function total(items) {
              let sum = 0;
              for (const item of items) {
                sum += item.price;
              }
              return sum;
            }
            """.trimIndent()
        assertEquals(TextTypes.JAVA, rulesOnly.detect(java))
        assertEquals(TextTypes.JAVASCRIPT, rulesOnly.detect(javascript))
    }

    companion object {
        @JvmStatic
        fun strongSigns(): List<Arguments> =
            listOf(
                Arguments.of("#!/usr/bin/env python3\nname: str = 'x'\n", TextTypes.PYTHON),
                Arguments.of("#!/bin/sh\nkey: value\n", TextTypes.SHELL_SCRIPT),
                Arguments.of("<?xml version=\"1.0\"?>\n<a>b</a>\n", TextTypes.XML),
                Arguments.of("<!DOCTYPE html>\n<p>Hi</p>\n", TextTypes.HTML),
                Arguments.of("diff --git a/x.yaml b/x.yaml\n--- a/x.yaml\n+++ b/x.yaml\n", TextTypes.DIFF),
            )
    }
}
