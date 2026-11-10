package dev.sluide.python.settings

import dev.sluide.python.PythonSettings
import dev.sluide.python.TyInstaller
import dev.sluide.python.TyUpdates
import dev.sluide.servertools.ServerToolDialogs
import dev.sluide.servertools.ServerToolPageTexts
import dev.sluide.servertools.ServerToolSettingsPage
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

private val TEXTS =
    ServerToolPageTexts(
        language = "Python",
        heading = "ty, the language server for Python",
        note =
            "Thoreau downloads ty from GitHub only on Install or Update, and checks for updates only when asked. " +
                "ty runs no program of the project.",
    )

/** Languages → Python: ty, the language server of Python code intelligence (ADR 0144, ADR 0159). */
@Component
class PythonSettingsPage internal constructor(
    settings: PythonSettings,
    installer: TyInstaller,
    updates: TyUpdates,
    dialogs: ServerToolDialogs?,
) : ServerToolSettingsPage(listOf("Languages", "Python"), TEXTS, settings, installer, updates, dialogs) {
    @Autowired
    constructor(settings: PythonSettings, installer: TyInstaller, updates: TyUpdates) :
        this(settings, installer, updates, null)
}
