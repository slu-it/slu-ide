package dev.sluide.ide

import javafx.application.HostServices
import java.net.URI

/** Opens web pages in the system browser, through the [HostServices] that [SluIdeApplication] sets at startup. */
internal object SystemBrowser {
    var hostServices: HostServices? = null

    fun open(uri: URI) {
        hostServices?.showDocument(uri.toString())
    }
}
