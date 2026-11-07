package dev.sluide.fileviewers.code

import javafx.beans.binding.Bindings
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ObservableValue
import javafx.scene.control.Button
import javafx.scene.layout.VBox
import java.nio.file.Path

/**
 * The banner of a code view saying that its file is a generated one, like `uv.lock` ([GeneratedFiles]), naming the file
 * to change instead. Shown in [banners] below the find bar ([findBarOpen]: whether it is the first of them) and above
 * the other banners, until dismissed for the view; [fileName] is the name shown. The Dismiss button gives the focus to
 * [refocus]. The file stays editable. FX thread.
 */
internal class GeneratedFileBanner(
    private val banners: VBox,
    fileName: ObservableValue<String>,
    private val findBarOpen: () -> Boolean,
    refocus: () -> Unit,
) {
    /** The tool that writes the file, if it is a generated one. */
    private val generated = SimpleObjectProperty<GeneratedFile?>()

    private val banner =
        banner(
            Bindings.createStringBinding({ generated.get()?.message(fileName.value).orEmpty() }, generated, fileName),
            Button("Dismiss").apply {
                setOnAction {
                    dismiss()
                    refocus()
                }
            },
        ).apply { styleClass += "generated-banner" }

    /** Hides the banner for this view, until the file is [shown][show] under the name of another tool's file. */
    private fun dismiss() {
        banners.children -= banner
    }

    /** Whether the banner is shown. */
    val shown: Boolean get() = banner in banners.children

    /**
     * Shows the banner if [file] is a generated one, below the find bar and above the other banners. Nothing changes
     * while the file stays one of the same tool (a moved folder, another case), even if dismissed.
     */
    fun show(file: Path) {
        val now = GeneratedFiles.of(file)
        if (now == generated.get()) return
        generated.set(now)
        banners.children -= banner
        if (now != null) banners.children.add(if (findBarOpen()) 1 else 0, banner)
    }
}
