package dev.sluide.typescript

import dev.sluide.servertools.ServerPathSetting
import dev.sluide.settings.SettingsStore
import org.springframework.stereotype.Component

private const val SERVER_PATH_KEY = "typescript.server.path"

/**
 * The TypeScript settings: the `tsc` executable the user chose (`typescript.server.path`, an absolute path; empty:
 * Thoreau's download), see [ServerPathSetting].
 */
@Component
class TypeScriptSettings(
    store: SettingsStore,
) : ServerPathSetting(store, SERVER_PATH_KEY)
