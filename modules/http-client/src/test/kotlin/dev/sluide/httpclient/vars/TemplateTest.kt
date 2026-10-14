package dev.sluide.httpclient.vars

import dev.sluide.httpclient.vars.Template.Part.Call
import dev.sluide.httpclient.vars.Template.Part.Literal
import dev.sluide.httpclient.vars.Template.Part.Ref
import kotlin.test.Test
import kotlin.test.assertEquals

class TemplateTest {
    private fun parts(text: String) = Template.parse(text).parts

    @Test
    fun `splits text into literals and references`() {
        assertEquals(
            listOf(
                Literal("https://"),
                Ref(listOf("host")),
                Literal("/users/"),
                Ref(listOf("user", "id"), "{{ user.id }}"),
            ),
            parts("https://{{host}}/users/{{ user.id }}"),
        )
    }

    @Test
    fun `reads calls with arguments`() {
        assertEquals(listOf(Call("\$random.integer", listOf("1", "10"))), parts("{{\$random.integer(1, 10)}}"))
        assertEquals(
            listOf(Call("\$auth.token", listOf("my auth"), "{{\$auth.token(\"my auth\")}}")),
            parts("{{\$auth.token(\"my auth\")}}"),
        )
        assertEquals(listOf(Call("\$random.float", emptyList())), parts("{{\$random.float()}}"))
        assertEquals(listOf(Call("f", listOf("a,b", "c"), "{{f('a,b', c)}}")), parts("{{f('a,b', c)}}"))
    }

    @Test
    fun `reads dynamic names as references`() {
        assertEquals(listOf(Ref(listOf("\$env", "HOME"))), parts("{{\$env.HOME}}"))
        assertEquals("\$random.uuid", (parts("{{\$random.uuid}}").single() as Ref).name)
    }

    @Test
    fun `keeps text that is no variable`() {
        assertEquals(listOf(Literal("{{ }} {{a b}} {{open")), parts("{{ }} {{a b}} {{open"))
        assertEquals(listOf(Literal("{\"a\": {\"b\": 1}}")), parts("{\"a\": {\"b\": 1}}"))
        assertEquals(emptyList(), parts(""))
    }

    @Test
    fun `gives the raw text of variables`() {
        assertEquals("{{a.b}}", Ref(listOf("a", "b")).raw)
        assertEquals("{{f(1, 2)}}", Call("f", listOf("1", "2")).raw)
    }
}
