package dev.sluide.settings

/** The look of the IDE: light, dark, or whatever the operating system uses. The terminal is dark in every theme. */
enum class Theme(
    val label: String,
) {
    LIGHT("Light"),
    DARK("Dark"),
    SYSTEM("System"),
    ;

    override fun toString() = label
}
