package dev.sluide.fileviewers.library

import dev.sluide.fileviewers.CodeEditorExtension
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.fileviewers.code.banner
import dev.sluide.librarycode.LibraryCode
import dev.sluide.librarycode.LibraryCodeSettings
import dev.sluide.librarycode.LibraryKind
import dev.sluide.librarycode.LibraryLocation
import javafx.beans.property.SimpleStringProperty
import javafx.scene.Node
import javafx.scene.control.Button
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Opens library code read-only (ADR 0150): a code editor whose file lies in a virtual environment, in `site-packages`
 * and the like is locked ([ExtensibleCodeEditor.readOnlyLock]) with a banner offering Edit Anyway, while
 * [LibraryCodeSettings.readOnly] is on. The file is classified again when it moves and the lock follows the setting;
 * the rules are [LibraryLockPolicy]'s. FX thread.
 */
@Component
class LibraryCodeExtension(
    private val settings: LibraryCodeSettings,
) : CodeEditorExtension {
    override fun extend(editor: ExtensibleCodeEditor): AutoCloseable = Binding(editor, settings)

    /** What a banner shows: its state, the file's name and the description of its location. */
    private data class Shown(
        val state: LibraryLockState,
        val location: LibraryLocation,
        val file: Path,
    )

    /** One editor's lock and banner. */
    private class Binding(
        private val editor: ExtensibleCodeEditor,
        private val settings: LibraryCodeSettings,
    ) : AutoCloseable {
        private val policy = LibraryLockPolicy()
        private var lock: AutoCloseable? = null
        private var bannerHandle: AutoCloseable? = null
        private var shown: Shown? = null
        private var dismissed = false
        private val subscriptions =
            listOf(
                // Subscribing already calls update once, which takes the first state.
                editor.file.subscribe { _ -> update() },
                settings.readOnly.subscribe { _ -> update() },
            )

        private fun update() {
            val file = editor.file.value
            val location = file?.let { LibraryCode.locate(it, editor.host.projectFolder) }
            val state = policy.evaluate(location != null, settings.readOnly.get(), editor.dirty.value)
            if (state == LibraryLockState.NOT_LIBRARY) dismissed = false
            if (state == LibraryLockState.LOCKED) {
                if (lock == null) lock = editor.readOnlyLock()
            } else {
                lock?.close()
                lock = null
            }
            val bannerShown =
                state == LibraryLockState.LOCKED || (state == LibraryLockState.UNLOCKED_BY_USER && !dismissed)
            val wanted =
                if (location != null && bannerShown) {
                    Shown(state, location, file)
                } else {
                    null
                }
            if (wanted == shown) return
            bannerHandle?.close()
            bannerHandle = wanted?.let { editor.addBanner(bannerFor(it)) }
            shown = wanted
        }

        private fun bannerFor(shown: Shown): Node =
            if (shown.state == LibraryLockState.LOCKED) {
                banner(
                    SimpleStringProperty(bannerMessage(shown.location, shown.file, locked = true)),
                    Button("Edit Anyway").apply {
                        setOnAction {
                            policy.editAnyway()
                            update()
                            refocus()
                        }
                    },
                ).apply { styleClass += "generated-banner" }
            } else {
                banner(
                    SimpleStringProperty(bannerMessage(shown.location, shown.file, locked = false)),
                    Button("Dismiss").apply {
                        setOnAction {
                            dismissed = true
                            update()
                            refocus()
                        }
                    },
                )
            }

        private fun refocus() {
            editor.popupOwner?.requestFocus()
        }

        override fun close() {
            for (subscription in subscriptions) subscription.unsubscribe()
            lock?.close()
            lock = null
            bannerHandle?.close()
            bannerHandle = null
        }
    }
}

/** The parent folder and the name of [file] (`json/__init__.py`: `__init__.py` alone says nothing), else the name. */
internal fun displayName(file: Path): String {
    val name = file.fileName?.toString() ?: file.toString()
    val parent = file.parent?.fileName?.toString()
    return if (parent == null) name else "$parent/$name"
}

/**
 * The banner text of a library [file] at [location]: the locked one, or the one after Edit Anyway. A Java library class
 * is a copy from the language server and is named by its file name alone (its folder is a hash); the Python kinds say
 * their changes are lost on the next install.
 */
internal fun bannerMessage(
    location: LibraryLocation,
    file: Path,
    locked: Boolean,
): String =
    if (location.kind == LibraryKind.JAVA_CLASS) {
        val name = file.fileName?.toString() ?: file.toString()
        if (locked) {
            "$name is a ${location.description}: read-only, a copy from jdtls that changes don't reach."
        } else {
            "Editing a copy of a ${location.description}: changes don't reach the library."
        }
    } else if (locked) {
        "${displayName(file)} is library code (${location.description}): read-only, as changes are lost on the " +
            "next install."
    } else {
        "Editing library code (${location.description}): changes are lost on the next install."
    }
