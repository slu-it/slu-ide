package dev.sluide.pdf.view

import dev.sluide.pdf.TestPdfs
import dev.sluide.pdf.engine.PdfDocument
import dev.sluide.pdf.engine.RenderedPage
import dev.sluide.pdf.view.WorkPriority.NEAR
import dev.sluide.pdf.view.WorkPriority.THUMBNAIL
import dev.sluide.pdf.view.WorkPriority.VISIBLE
import javafx.scene.image.WritableImage
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The render scheduling on a manual worker; the renders are stand-ins that record what was asked. Pages are numbered
 * from 3 on, so that no page is the 0 a lost value would give.
 */
class PageImagesTest {
    private val executor = ManualExecutor()
    private val fx = ManualFx()
    private val worker = PdfWorker(executor, fx).also { it.reopen(TestPdfs.text, null, { _ -> }) {} }
    private val rendered = mutableListOf<Pair<Int, Double>>()
    private val changed = mutableListOf<Int>()

    /** What the stand-in render does, once, before it answers; it returns `null` once its cancel check says so. */
    private var during: ((page: Int, cancelled: () -> Boolean) -> Unit)? = null
    private var failure: Throwable? = null
    private var budget = PIXEL_BUDGET

    private val images by lazy {
        PageImages(
            worker,
            render = { _: PdfDocument, page: Int, scale: Double, cancelled: () -> Boolean ->
                rendered += page to scale
                during?.also { during = null }?.invoke(page, cancelled)
                failure?.let { throw it }
                if (cancelled()) null else RenderedPage(10, 10, IntArray(0), scale)
            },
            convert = { it },
            pixels = { it.width.toLong() * it.height },
            changed = { changed += it },
            budget = budget,
        )
    }

    @AfterTest
    fun dispose() = worker.dispose()

    private fun run() {
        executor.runAll()
        fx.runAll()
    }

    private fun update(
        vararg order: Pair<Int, WorkPriority>,
        scale: Double = 1.0,
        centre: Int = order.firstOrNull()?.first ?: 0,
    ) = images.update(order.toList(), scale, centre)

    @Test
    fun `renders the pages by priority at the scale and reports each`() {
        run()
        update(4 to NEAR, 3 to VISIBLE, scale = 1.5)
        assertEquals(emptyList(), rendered)
        run()
        assertEquals(listOf(3 to 1.5, 4 to 1.5), rendered)
        assertEquals(listOf(3, 4), changed)
        assertEquals(1.5, images.image(3)?.scale)
        assertNull(images.image(5))
    }

    @Test
    fun `a page it has at the scale is not rendered again`() {
        run()
        update(3 to VISIBLE)
        run()
        update(3 to VISIBLE)
        run()
        assertEquals(listOf(3 to 1.0), rendered)
    }

    @Test
    fun `a new scale keeps the old image until the new one arrives`() {
        run()
        update(3 to VISIBLE, scale = 1.0)
        run()
        update(3 to VISIBLE, scale = 2.0)
        assertEquals(1.0, images.image(3)?.scale)
        run()
        assertEquals(2.0, images.image(3)?.scale)
    }

    @Test
    fun `queued renders of pages no longer wanted never run, those still wanted are queued in the new order`() {
        run()
        update(3 to NEAR, 4 to NEAR, 5 to NEAR)
        update(5 to VISIBLE, 4 to NEAR)
        run()
        assertEquals(listOf(5 to 1.0, 4 to 1.0), rendered)
    }

    @Test
    fun `cancelling its renders leaves the other jobs of the worker queued`() {
        run()
        val ran = mutableListOf<String>()
        update(3 to NEAR)
        worker.submit(THUMBNAIL, { ran += "thumbnail" }, {}, tag = "thumbnail 3")
        update(4 to VISIBLE)
        run()
        assertEquals(listOf("thumbnail"), ran)
        assertEquals(listOf(4 to 1.0), rendered)
    }

    @Test
    fun `its renders are cancelled without those of another instance on the same worker`() {
        run()
        val other =
            PageImages(
                worker,
                render = { _: PdfDocument, page: Int, scale: Double, _: () -> Boolean ->
                    rendered += page to -scale
                    RenderedPage(10, 10, IntArray(0), scale)
                },
                convert = { it },
                pixels = { it.width.toLong() * it.height },
                changed = {},
            )
        other.update(listOf(5 to NEAR), 1.0, 5)
        update(3 to VISIBLE)
        update(4 to VISIBLE)
        run()
        assertEquals(listOf(4 to 1.0, 5 to -1.0), rendered)
    }

    @Test
    fun `pixels of an image are its width times its height`() {
        assertEquals(60_000L, pixelsOf(WritableImage(300, 200)))
    }

    @Test
    fun `an image made of a rendered page has its pixels`() {
        val pixels = intArrayOf(0xFF112233.toInt(), 0xFF445566.toInt(), 0xFF778899.toInt(), 0xFFAABBCC.toInt())
        val image = toImage(RenderedPage(2, 2, pixels, 1.0))
        assertEquals(2.0 to 2.0, image.width to image.height)
        assertEquals(0xFF112233.toInt(), image.pixelReader.getArgb(0, 0))
        assertEquals(0xFF445566.toInt(), image.pixelReader.getArgb(1, 0))
        assertEquals(0xFF778899.toInt(), image.pixelReader.getArgb(0, 1))
    }

    @Test
    fun `a render running for a page no longer wanted is cancelled and its result dropped`() {
        run()
        val checks = mutableListOf<Boolean>()
        during = { _, cancelled ->
            images.update(listOf(4 to VISIBLE), 1.0, 4)
            checks += cancelled()
        }
        update(3 to VISIBLE)
        run()
        assertEquals(listOf(true), checks)
        assertNull(images.image(3))
        assertFalse(images.failed(3))
        assertEquals(listOf(3 to 1.0, 4 to 1.0), rendered)
    }

    @Test
    fun `a render running at a scale no longer wanted is cancelled and its page asked again`() {
        run()
        val checks = mutableListOf<Boolean>()
        during = { _, cancelled ->
            images.update(listOf(3 to VISIBLE), 2.0, 3)
            checks += cancelled()
        }
        update(3 to VISIBLE, scale = 1.0)
        run()
        assertEquals(listOf(true), checks)
        assertEquals(listOf(3 to 1.0, 3 to 2.0), rendered)
        assertEquals(2.0, images.image(3)?.scale)
    }

    @Test
    fun `a render that fails after its page is no longer wanted marks no failure`() {
        run()
        during = { _, _ ->
            images.update(listOf(4 to VISIBLE), 1.0, 4)
            failure = IOException("broken")
        }
        update(3 to VISIBLE)
        run()
        assertFalse(images.failed(3))
        assertTrue(images.failed(4))
    }

    @Test
    fun `a render running for a page still wanted at its scale goes on and is not asked again`() {
        run()
        val checks = mutableListOf<Boolean>()
        during = { _, cancelled ->
            images.update(listOf(4 to VISIBLE, 3 to NEAR), 1.0, 4)
            checks += cancelled()
        }
        update(3 to VISIBLE)
        run()
        assertEquals(listOf(false), checks)
        assertEquals(1.0, images.image(3)?.scale)
        assertEquals(listOf(3 to 1.0, 4 to 1.0), rendered)
    }

    @Test
    fun `a result that arrives after its page is no longer wanted is dropped`() {
        run()
        update(3 to VISIBLE)
        executor.runAll()
        update(4 to VISIBLE)
        run()
        assertNull(images.image(3))
        assertEquals(1.0, images.image(4)?.scale)
    }

    @Test
    fun `a page whose render failed is marked and tried again only at another scale`() {
        run()
        update(5 to NEAR)
        run()
        failure = IOException("Could not read page 4")
        update(3 to VISIBLE)
        run()
        assertTrue(images.failed(3))
        assertEquals(listOf(5, 3), changed)
        assertEquals(1.0, images.image(5)?.scale, "an IOException drops no image")
        update(3 to VISIBLE)
        run()
        assertEquals(listOf(5 to 1.0, 3 to 1.0), rendered)
        failure = null
        update(3 to VISIBLE, scale = 2.0)
        run()
        assertFalse(images.failed(3))
        assertEquals(2.0, images.image(3)?.scale)
    }

    @Test
    fun `out of memory drops the images of the pages not wanted`() {
        run()
        update(3 to VISIBLE, 4 to NEAR, 5 to NEAR)
        run()
        failure = OutOfMemoryError("Java heap space")
        update(4 to VISIBLE, 5 to NEAR, scale = 2.0)
        run()
        assertNull(images.image(3))
        assertEquals(1.0, images.image(5)?.scale)
        assertTrue(images.failed(4))
    }

    @Test
    fun `images beyond the budget are dropped farthest from the centre first, and rendered again when wanted`() {
        budget = 300
        run()
        update(3 to VISIBLE, 4 to NEAR)
        run()
        update(8 to VISIBLE, 9 to NEAR, centre = 8)
        run()
        assertNull(images.image(3))
        assertEquals(1.0, images.image(4)?.scale)
        assertEquals(1.0, images.image(9)?.scale)
        assertEquals(listOf(3, 4, 8, 9, 3), changed)
        update(3 to VISIBLE)
        run()
        assertEquals(1.0, images.image(3)?.scale)
    }

    @Test
    fun `clear cancels the render running and forgets images, failures and requests`() {
        run()
        failure = IOException("broken")
        update(6 to VISIBLE)
        run()
        failure = null
        update(3 to VISIBLE)
        run()
        val checks = mutableListOf<Boolean>()
        during = { _, cancelled ->
            images.clear()
            checks += cancelled()
            // Asked again at once: the cleared request may not stand in the way.
            images.update(listOf(4 to VISIBLE), 1.0, 4)
        }
        update(4 to VISIBLE)
        run()
        assertEquals(listOf(true), checks)
        assertNull(images.image(3))
        assertFalse(images.failed(6))
        assertEquals(listOf(4 to 1.0, 4 to 1.0), rendered.takeLast(2))
        assertEquals(1.0, images.image(4)?.scale)
    }
}
