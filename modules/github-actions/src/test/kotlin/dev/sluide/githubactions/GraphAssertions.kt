package dev.sluide.githubactions

/** The `class … <style>` line's ids. */
internal fun WorkflowGraph.styled(style: String): List<String> =
    mermaid
        .lines()
        .singleOrNull { it.startsWith("    class ") && it.endsWith(" $style") }
        ?.removePrefix("    class ")
        ?.removeSuffix(" $style")
        ?.split(",") ?: emptyList()

/** The line drawing the box [id]. */
internal fun WorkflowGraph.box(id: String): String = mermaid.lines().single { it.trim().startsWith("$id[") }.trim()

/** Letters, digits, `_-./:@(),`, blanks, entity codes, line breaks, the bold and italic tags and the inputs block. */
internal val SAFE_LABEL =
    Regex("""([A-Za-z0-9 _\-./:@(),]|#\d+;|</?(br|b|i)/?>|<div class='sluide-inputs'>|</div>)*""")
