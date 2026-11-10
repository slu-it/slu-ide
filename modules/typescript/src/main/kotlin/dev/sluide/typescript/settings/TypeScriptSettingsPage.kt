package dev.sluide.typescript.settings

import dev.sluide.servertools.ServerToolDialogs
import dev.sluide.servertools.ServerToolPageTexts
import dev.sluide.servertools.ServerToolSettingsPage
import dev.sluide.typescript.TypeScriptInstaller
import dev.sluide.typescript.TypeScriptSettings
import dev.sluide.typescript.TypeScriptUpdates
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

private val TEXTS =
    ServerToolPageTexts(
        language = "TypeScript",
        heading = "TypeScript 7, the language server for TypeScript and JavaScript",
        note =
            "Thoreau downloads TypeScript 7 from the npm registry (registry.npmjs.org) only on Install or Update, " +
                "and checks for updates only when asked. The server runs no program of the project and needs no " +
                "Node.js.",
    )

/** Languages → TypeScript: the TypeScript 7 language server of TypeScript and JavaScript code intelligence. */
@Component
class TypeScriptSettingsPage internal constructor(
    settings: TypeScriptSettings,
    installer: TypeScriptInstaller,
    updates: TypeScriptUpdates,
    dialogs: ServerToolDialogs?,
) : ServerToolSettingsPage(listOf("Languages", "TypeScript"), TEXTS, settings, installer, updates, dialogs) {
    @Autowired
    constructor(settings: TypeScriptSettings, installer: TypeScriptInstaller, updates: TypeScriptUpdates) :
        this(settings, installer, updates, null)
}
