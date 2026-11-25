package dev.sluide.pdf.view

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.pdf.engine.PageRect
import javafx.geometry.Bounds
import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The PDF view in a stage of 800 by 600, on real worker threads. Subclasses declare `@Start` and call [startStage]
 * (ApplicationExtension finds `@Start` only on the test class itself).
 */
internal abstract class PdfViewUiFixture {
    protected lateinit var stage: Stage
    private val host = StackPane()
    protected val views = mutableListOf<PdfFileView>()

    protected fun startStage(stage: Stage) {
        this.stage = stage
        stage.scene = Scene(host, 800.0, 600.0)
        stage.show()
    }

    @AfterEach
    fun disposeViews() {
        fx { views.forEach(PdfFileView::dispose) }
    }

    protected fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx<T> { block() }.get(30, TimeUnit.SECONDS)

    protected fun await(condition: () -> Boolean) = WaitForAsyncUtils.waitFor(30, TimeUnit.SECONDS) { fx(condition) }

    /** Opens [file] in a view shown in the stage; [configure] runs before the view is shown. */
    protected fun open(
        file: Path,
        configure: (PdfFileView) -> Unit = {},
    ): PdfFileView =
        fx {
            PdfFileView(file, NamedThreads.single("test-pdf-render"), NamedThreads.single("test-pdf-text"))
                .also(configure)
                .also {
                    views += it
                    host.children.setAll(it.node)
                }
        }

    /** Waits until [page] shows an image rendered at the view's scale, or a failure. */
    protected fun awaitRendered(
        view: PdfFileView,
        page: Int,
    ) = await {
        val slot = view.pageColumn.pageSlots[page]
        slot != null && (slot.failure.isVisible || slot.image?.let { it.width >= slot.width - 1 } == true)
    }

    /** The scene as painted. */
    protected fun paint(): Image = fx { stage.scene.snapshot(null) }

    /** The colours of the pixels of [bounds] (scene coordinates) in [image], the edges left out. */
    protected fun colours(
        image: Image,
        bounds: Bounds,
    ): List<Color> {
        val reader = image.pixelReader
        return (ceil(bounds.minX).toInt() + 1 until floor(bounds.maxX).toInt() - 1).flatMap { x ->
            (ceil(bounds.minY).toInt() + 1 until floor(bounds.maxY).toInt() - 1).map { y -> reader.getColor(x, y) }
        }
    }

    protected fun Color.isWhite() = red > 0.99 && green > 0.99 && blue > 0.99

    protected fun Color.isDark() = red < 0.5 && green < 0.5 && blue < 0.5

    /** [rect] of [page] of [view] in scene coordinates. */
    protected fun sceneRect(
        view: PdfFileView,
        page: Int,
        rect: PageRect,
    ): Bounds = checkNotNull(fx { view.pageRectToScene(page, rect) })
}
