package dev.sluide.previews.preview

import javafx.scene.web.WebEngine
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a preview offers by default. */
class PreviewDefaultsTest {
    private val plain =
        object : Preview {
            override fun load(
                engine: WebEngine,
                file: Path,
                text: String,
                dark: Boolean,
                root: Path,
            ) {}
        }

    @Test
    fun `a preview shows no dropdown unless it says so`() {
        assertEquals(0, plain.choices.size)
    }
}
