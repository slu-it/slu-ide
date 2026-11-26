package dev.sluide.previews.plantuml

/**
 * The colours a PlantUML file's preview shows its diagrams in (the toolbar's "Theme" dropdown): [DEFAULT] follows the
 * app's scheme, [LIGHT] and [DARK] pin them to that variant. [page] is the name `sluide.setDiagramTheme` takes.
 */
internal enum class DiagramTheme(
    val label: String,
    val page: String,
) {
    DEFAULT("Default", "default"),
    LIGHT("Light", "light"),
    DARK("Dark", "dark"),
    ;

    /** Whether the diagrams show the dark variant when the app's scheme is [appDark]. */
    fun dark(appDark: Boolean): Boolean =
        when (this) {
            DEFAULT -> appDark
            LIGHT -> false
            DARK -> true
        }
}
