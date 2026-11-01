package dev.sluide.docker.compose

import kotlin.test.Test
import kotlin.test.assertEquals

class ServiceLinesTest {
    @Test
    fun `each service key gets its zero-based line, in file order`() {
        val text =
            """
            name: demo
            services:
              web:
                image: nginx
              db:
                image: postgres

              worker:
                image: busybox
            volumes:
              data:
            """.trimIndent()

        val lines = serviceLines(text)

        assertEquals(mapOf("web" to 2, "db" to 4, "worker" to 7), lines)
        assertEquals(listOf("web", "db", "worker"), lines.keys.toList())
    }

    @Test
    fun `anchors, merge keys and comments do not disturb it`() {
        val text =
            """
            x-common: &common
              restart: always
            services:
              # the web part
              web:
                <<: *common
                image: nginx
              "quoted":
                <<: *common
            """.trimIndent()

        assertEquals(mapOf("web" to 4, "quoted" to 7), serviceLines(text))
    }

    @Test
    fun `keys of other mappings are not services`() {
        val text = "networks:\n  front: {}\nservices:\n  api: {}\n"

        assertEquals(mapOf("api" to 3), serviceLines(text))
    }

    @Test
    fun `broken, empty or foreign YAML gives no services and never throws`() {
        assertEquals(emptyMap(), serviceLines("services:\n  web:\n    ports: [\n"))
        assertEquals(emptyMap(), serviceLines(""))
        assertEquals(emptyMap(), serviceLines("- a\n- b\n"))
        assertEquals(emptyMap(), serviceLines("services: nope\n"))
        assertEquals(emptyMap(), serviceLines("services:\n\t- tab"))
    }

    @Test
    fun `a merge key directly under services is no service`() {
        val text = "x-base: &base\n  a: {}\nservices:\n  <<: *base\n  b: {}\n"

        assertEquals(mapOf("b" to 4), serviceLines(text))
    }
}
