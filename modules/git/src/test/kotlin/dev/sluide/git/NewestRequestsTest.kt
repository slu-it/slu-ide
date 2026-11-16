package dev.sluide.git

import java.util.concurrent.RejectedExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NewestRequestsTest {
    private val queued = mutableListOf<Runnable>()
    private val dispatched = mutableListOf<Runnable>()
    private val requests = NewestRequests<String>({ queued += it }, { dispatched += it })
    private val log = mutableListOf<String>()

    private fun submit(
        key: String,
        name: String,
        followed: () -> Boolean = { true },
    ) = requests.submit(key, followed, {
        log += "work $name"
        name
    }, { log += "done $it" })

    private fun runAll() {
        while (queued.isNotEmpty()) queued.removeFirst().run()
        while (dispatched.isNotEmpty()) dispatched.removeFirst().run()
    }

    @Test
    fun `a request is worked on, then answered through dispatch`() {
        submit("k", "a")
        queued.single().run()

        assertEquals(listOf("work a"), log)
        dispatched.single().run()
        assertEquals(listOf("work a", "done a"), log)
    }

    @Test
    fun `an older request for a key does not start and is not answered`() {
        submit("k", "old")
        submit("k", "new")

        runAll()

        assertEquals(listOf("work new", "done new"), log)
    }

    @Test
    fun `an older request that already ran is not answered`() {
        submit("k", "old")
        queued.single().run()
        queued.clear()
        submit("k", "new")

        runAll()

        assertEquals(listOf("work old", "work new", "done new"), log)
    }

    @Test
    fun `the git of an older request is stopped`() {
        val stopped = mutableListOf<GitCancellation>()
        requests.submit("k", { true }, { stopped += it }, {})
        requests.submit("k", { true }, { stopped += it }, {})

        queued.removeLast().run()

        assertEquals(1, stopped.size)
        assertFalse(stopped.single().cancelled)
        requests.cancel("k")
        assertTrue(stopped.single().cancelled)
    }

    @Test
    fun `different keys do not replace each other`() {
        submit("a", "first")
        submit("b", "second")

        runAll()

        assertEquals(listOf("work first", "work second", "done first", "done second"), log)
    }

    @Test
    fun `a cancelled request does not start`() {
        submit("k", "a")

        requests.cancel("k")
        runAll()

        assertEquals(emptyList(), log)
    }

    @Test
    fun `an answered request is forgotten, so a later cancel stops nothing`() {
        var seen: GitCancellation? = null
        requests.submit("k", { true }, { seen = it }, {})
        runAll()

        requests.cancel("k")

        assertFalse(seen?.cancelled == true)
    }

    @Test
    fun `a result is dropped once the repository is no longer followed`() {
        var followed = true
        submit("k", "a") { followed }
        queued.single().run()

        followed = false
        dispatched.single().run()

        assertEquals(listOf("work a"), log)
    }

    @Test
    fun `a closed executor drops the request without failing`() {
        val closed = NewestRequests<String>({ throw RejectedExecutionException() }, { dispatched += it })

        closed.submit("k", { true }, { log += "work" }, { log += "done" })

        assertEquals(emptyList(), log)
    }
}
