package dev.sluide.ide

import javafx.application.HostServices
import org.springframework.stereotype.Component
import java.net.URI

/** Opens web pages in the system browser, through the [hostServices] that [SluIdeApplication] sets at startup. */
@Component
class SystemBrowser {
    /** The application's host services; until they are set, [open] does nothing. */
    var hostServices: HostServices? = null

    fun open(uri: URI) {
        hostServices?.showDocument(uri.toString())
    }
}
