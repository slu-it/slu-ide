package dev.sluide.encryptedfiles.ui

import dev.sluide.encryptedfiles.EncryptedFiles
import dev.sluide.encryptedfiles.settings.EncryptedFilesSettings
import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.FileViewers
import dev.sluide.settings.SettingsStore
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.ObjectProvider
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The viewer as Spring builds it: the view it opens asks the viewer provider for the inner viewers. Needs a display,
 * because the view is a control; the rest of the viewer is in [EncryptedFileViewerTest].
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class EncryptedFileViewerUiTest {
    @TempDir
    lateinit var dir: Path

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(StackPane(), 100.0, 100.0)
        stage.show()
    }

    @Test
    fun `a viewer built from a provider opens an encrypted file view`(robot: FxRobot) {
        val fallback = DefaultTextFileViewer()
        val viewers = FileViewers(listOf(fallback), fallback)
        val provider =
            object : ObjectProvider<FileViewers> {
                override fun getObject(vararg args: Any?): FileViewers = viewers

                override fun getObject(): FileViewers = viewers

                override fun getIfAvailable(): FileViewers? = viewers

                override fun getIfUnique(): FileViewers? = viewers
            }
        val settings = EncryptedFilesSettings(SettingsStore(dir.resolve("settings.properties")))
        val viewer = EncryptedFileViewer(EncryptedFiles(), settings, provider)
        val file = Files.createFile(dir.resolve("notes.md.sluenc"))
        var view: Any? = null

        robot.interact { view = viewer.open(file) }

        assertTrue(view is EncryptedFileView)
    }
}
