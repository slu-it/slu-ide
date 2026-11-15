package dev.sluide.findinfiles

import dev.sluide.findinfiles.search.FileHits
import dev.sluide.findinfiles.search.LineHit
import dev.sluide.findinfiles.ui.FindAnchor
import dev.sluide.findinfiles.ui.FindView
import dev.sluide.textediting.SearchOptions
import java.nio.file.Path

internal class FakeFindView : FindView {
    val calls = mutableListOf<String>()
    override var isShowing = false

    /** Whether setting a field reports it as an edit, as a view that echoes would. */
    var echo = false
    override var query = ""
        set(value) {
            field = value
            if (echo) onChanged()
        }
    override var mask = ""
        set(value) {
            field = value
            if (echo) onChanged()
        }
    override var options = SearchOptions()
        set(value) {
            field = value
            if (echo) onChanged()
        }
    override var scopeLabel: String? = null
    override var severalRoots = false
    override var onChanged: () -> Unit = {}
    override var onWiden: () -> Unit = {}
    override var onOpen: (FileHits, LineHit) -> Unit = { _, _ -> }
    override var onHidden: () -> Unit = {}
    val files = mutableListOf<Path>()
    val hitRoots = mutableListOf<Path>()
    var status = ""
    var anchors = 0
    var lastAnchor: FindAnchor? = null

    override fun open(anchor: FindAnchor) {
        anchors++
        lastAnchor = anchor
        isShowing = true
        calls += "open"
    }

    override fun clear() {
        files.clear()
        hitRoots.clear()
        calls += "clear"
    }

    override fun add(files: List<FileHits>) {
        this.files += files.map { it.file }
        hitRoots += files.map { it.root }
    }

    override fun status(text: String) {
        status = text
    }

    override fun hide() {
        isShowing = false
        onHidden()
    }

    /** As the real popup: hiding it for an opening still hides it, so [onHidden] runs before the opener. */
    override fun hideForOpen() {
        calls += "hideForOpen"
        hide()
    }
}
