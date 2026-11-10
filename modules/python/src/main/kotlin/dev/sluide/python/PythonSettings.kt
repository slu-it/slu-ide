package dev.sluide.python

import dev.sluide.servertools.ServerPathSetting
import dev.sluide.settings.SettingsStore
import org.springframework.stereotype.Component

private const val TY_PATH_KEY = "python.ty.path"

/**
 * The Python settings: the ty executable the user chose (`python.ty.path`, an absolute path; empty: Thoreau's
 * download), see [ServerPathSetting].
 */
@Component
class PythonSettings(
    store: SettingsStore,
) : ServerPathSetting(store, TY_PATH_KEY)
